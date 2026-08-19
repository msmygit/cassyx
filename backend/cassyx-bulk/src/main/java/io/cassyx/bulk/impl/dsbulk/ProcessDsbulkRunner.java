package io.cassyx.bulk.impl.dsbulk;

import io.cassyx.bulk.api.dsbulk.DsbulkCountReport;
import io.cassyx.bulk.api.dsbulk.DsbulkDistribution;
import io.cassyx.bulk.api.dsbulk.DsbulkException;
import io.cassyx.bulk.api.dsbulk.DsbulkExitStatus;
import io.cassyx.bulk.api.dsbulk.DsbulkListener;
import io.cassyx.bulk.api.dsbulk.DsbulkOperation;
import io.cassyx.bulk.api.dsbulk.DsbulkPlan;
import io.cassyx.bulk.api.dsbulk.DsbulkProgress;
import io.cassyx.bulk.api.dsbulk.DsbulkResult;
import io.cassyx.bulk.api.dsbulk.DsbulkRunner;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs DSBulk in a SEPARATE JVM PROCESS (plan section 5.3).
 *
 * <p>This is not an optimisation, it is the design. DSBulk ships an {@code application.conf}, and
 * Typesafe Config merges every {@code application.conf} it finds on the classpath - so embedding
 * DSBulk beside Spring Boot means each side silently reads the other's configuration. Four more
 * things fall out of the separation, all of which we would otherwise have to build:
 *
 * <ul>
 *   <li><b>Isolation.</b> A job that exhausts its heap takes down its own JVM, not the API.
 *   <li><b>Real cancellation.</b> {@link #cancel(Path)} sends a signal to the child's whole PROCESS
 *       GROUP. There is no cooperative interrupt to hope DSBulk honours, and no descendant left
 *       behind - see {@link #inOwnProcessGroup} for why signalling only the direct child is not
 *       enough.
 *   <li><b>Memory capping.</b> {@code -Xmx} per job, set independently of the server's heap.
 *   <li><b>Immunity to {@code System.exit()}.</b> DSBulk exits with a meaningful status; in-process
 *       that call would end the application. Here it is just an integer we read.
 * </ul>
 */
public final class ProcessDsbulkRunner implements DsbulkRunner {

  private static final Logger LOG = LoggerFactory.getLogger(ProcessDsbulkRunner.class);

  /** Main class of {@code dsbulk-runner}, used when the distribution's launcher is not executable. */
  public static final String MAIN_CLASS = "com.datastax.oss.dsbulk.runner.DataStaxBulkLoader";

  /**
   * Pinned on the child JVM rather than inherited from the host. DSBulk formats the count report's
   * percentage column with {@code "%.2f"} and no explicit {@code Locale}, so on a comma-decimal host
   * it emits {@code "33,33"} - which the parser's grouping-separator handling then reads as the
   * integer {@code 3333}. Nothing about a row count should depend on the server's regional settings.
   */
  private static final List<String> LOCALE_ARGS = List.of("-Duser.language=en", "-Duser.country=US");

  /** Grace between SIGTERM and SIGKILL: enough for DSBulk to flush its error reports. */
  public static final Duration DEFAULT_KILL_GRACE = Duration.ofSeconds(10);

  /** Retained report lines. Bounded so a chatty job cannot exhaust the server's heap. */
  static final int MAX_RETAINED_LINES = 100_000;

  /** The child's stdout, captured to a file: for `count` this IS the statistics report. */
  public static final String STDOUT_FILE_NAME = "dsbulk-stdout.log";

  private final DsbulkDistribution distribution;
  private final String maxHeap;
  private final Duration killGrace;
  private final ProcessStarter starter;
  private final Map<Path, Child> running = new ConcurrentHashMap<>();

  /**
   * A started child plus the process group it was placed in, or {@link #NO_GROUP}.
   *
   * <p>The group is captured at start time, not at cancel time, and that is the whole point. A
   * {@code descendants()} snapshot taken just before signalling is a TOCTOU race - the child may not
   * have forked its JVM yet, and anything it forks afterwards is missed. A process group id is fixed
   * by {@code setsid(2)} at exec time and every later {@code fork()} inherits it, so signalling the
   * group reaches descendants that did not exist when cancellation was requested.
   */
  private record Child(Process process, long processGroup) {}

  public ProcessDsbulkRunner(DsbulkDistribution distribution, String maxHeap) {
    this(distribution, maxHeap, DEFAULT_KILL_GRACE, ProcessBuilder::start);
  }

  /** @param starter injection seam: the tests drive a fake child process instead of a real JVM */
  public ProcessDsbulkRunner(
      DsbulkDistribution distribution, String maxHeap, Duration killGrace, ProcessStarter starter) {
    this.distribution = distribution;
    this.maxHeap = maxHeap == null || maxHeap.isBlank() ? "2g" : maxHeap;
    this.killGrace = killGrace == null ? DEFAULT_KILL_GRACE : killGrace;
    this.starter = starter == null ? ProcessBuilder::start : starter;
  }

  /** Starts a configured {@link ProcessBuilder}. Exists so the runner is testable without DSBulk. */
  @FunctionalInterface
  public interface ProcessStarter {
    Process start(ProcessBuilder builder) throws IOException;
  }

  @Override
  public DsbulkResult run(
      DsbulkPlan plan, Path jobDirectory, Map<String, String> secrets, DsbulkListener listener) {
    DsbulkListener callbacks = listener == null ? DsbulkListener.noop() : listener;
    Path workDir = jobDirectory.toAbsolutePath().normalize();
    Path confFile = workDir.resolve(DsbulkCommandBuilder.CONF_FILE_NAME);
    Path logDir = workDir.resolve(DsbulkPlanner.LOG_DIR_NAME);

    try {
      Files.createDirectories(logDir);
      Files.writeString(confFile, DsbulkPlanner.realHocon(plan, secrets), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new DsbulkException("Cannot prepare the DSBulk job directory at " + workDir, e);
    }

    Path stdoutFile = workDir.resolve(STDOUT_FILE_NAME);
    List<String> command = command(plan, confFile);
    ProcessBuilder builder = new ProcessBuilder(inOwnProcessGroup(command));
    builder.directory(workDir.toFile());
    // STDOUT goes to a FILE, STDERR to the pipe we read. This is not arbitrary: DSBulk writes the
    // `count` workflow's report to stdout and everything else - logging AND the console progress
    // reporter - to stderr. Merging them would interleave a machine-readable report with log noise
    // and force us to tell them apart by shape. Redirecting stdout to a file also means a single
    // reader thread suffices: a second pipe nobody drains blocks the child the moment its buffer
    // fills.
    builder.redirectOutput(ProcessBuilder.Redirect.to(stdoutFile.toFile()));
    // The locale is pinned, not inherited. DSBulk formats the count report's percentage column with
    // "%.2f" and no explicit Locale, so on a host set to a comma-decimal locale it emits "33,33" -
    // which the parser's grouping-separator handling then reads as the integer 3333. Nothing about a
    // row count should depend on the server's regional settings.
    builder
        .environment()
        .put("DSBULK_JAVA_OPTS", "-Xmx" + maxHeap + " " + String.join(" ", LOCALE_ARGS));
    if (distribution != null && distribution.home() != null) {
      builder.environment().put(DsbulkDistribution.HOME_ENV, distribution.home().toString());
    }

    long startedAt = System.nanoTime();
    Process process;
    try {
      process = starter.start(builder);
    } catch (IOException e) {
      throw new DsbulkException("Cannot start the DSBulk process: " + String.join(" ", command), e);
    }
    Child child = new Child(process, processGroupOf(process));
    running.put(workDir, child);

    DsbulkProgressTracker tracker = new DsbulkProgressTracker();
    long rows = 0;
    long failures = 0;
    boolean interrupted = false;

    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        callbacks.onLog(DsbulkOutputParser.toLogLine(line));
        DsbulkProgress tick = tracker.accept(line);
        if (tick != null) {
          rows = Math.max(rows, tick.rowsProcessed());
          failures = Math.max(failures, tick.failures());
          callbacks.onProgress(tick);
        }
      }
      process.waitFor();
    } catch (IOException e) {
      throw new DsbulkException("Failed while reading the DSBulk process output", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      interrupted = true;
      terminate(child);
    } finally {
      running.remove(workDir);
    }

    int exitCode = interrupted ? DsbulkExitStatus.INTERRUPTED.code() : process.exitValue();
    DsbulkCountReport report =
        plan.operation() == DsbulkOperation.COUNT
            ? DsbulkCountParser.parse(readReport(stdoutFile))
            : DsbulkCountReport.EMPTY;
    if (report.totalRows() > rows) {
      rows = report.totalRows();
    }
    Path operationDirectory =
        tracker.operationDirectory() == null ? logDir : Path.of(tracker.operationDirectory());

    DsbulkExitStatus status = DsbulkExitStatus.of(exitCode);
    if (!status.isSuccess()) {
      LOG.warn("DSBulk {} exited {} ({}); logs retained at {}", plan.operation(), exitCode, status, logDir);
    }
    return new DsbulkResult(
        exitCode,
        rows,
        failures,
        Duration.ofNanos(System.nanoTime() - startedAt),
        operationDirectory,
        List.of(confFile, stdoutFile),
        report,
        status.isSuccess() ? "" : failureMessage(status, exitCode));
  }

  /** Reads the captured stdout, bounded. Missing or unreadable means an empty report, not a crash. */
  static List<String> readReport(Path stdoutFile) {
    if (!Files.isReadable(stdoutFile)) {
      return List.of();
    }
    try (var lines = Files.lines(stdoutFile, StandardCharsets.UTF_8)) {
      return lines.limit(MAX_RETAINED_LINES).toList();
    } catch (IOException e) {
      LOG.warn("Cannot read the DSBulk count report at {}: {}", stdoutFile, e.toString());
      return List.of();
    }
  }

  @Override
  public boolean cancel(Path jobDirectory) {
    Child child = running.get(jobDirectory.toAbsolutePath().normalize());
    if (child == null || !child.process().isAlive()) {
      return false;
    }
    terminate(child);
    return true;
  }

  /**
   * SIGTERM to the whole process group, then SIGKILL after the grace period.
   *
   * <p>SIGTERM first because DSBulk flushes its error reports and checkpoint on it. The group rather
   * than the process because {@code bin/dsbulk} is a shell wrapper around a JVM and killing the
   * wrapper alone leaves the JVM running against the user's cluster while the UI says CANCELLED.
   *
   * <p>The escalation to SIGKILL is driven by the GROUP, not by the direct child: an orphan that
   * outlives its parent still holds the inherited stderr pipe, and {@link #run} sits in
   * {@code readLine()} until that pipe closes. Waiting only on the direct child would return here
   * promptly and hang the worker thread forever.
   */
  private void terminate(Child child) {
    Process process = child.process();
    long group = child.processGroup();
    signalGroup(group, "TERM");
    process.destroy();
    try {
      boolean exited = process.waitFor(killGrace.toMillis(), TimeUnit.MILLISECONDS);
      if (!exited || groupIsAlive(group)) {
        signalGroup(group, "KILL");
        process.destroyForcibly();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      signalGroup(group, "KILL");
      process.destroyForcibly();
    }
  }

  /* ------------------------------------------------------------------------- process groups */

  /** Sentinel for "this child is not in a process group of its own, so only it may be signalled". */
  static final long NO_GROUP = -1;

  /**
   * {@code setsid(1)}, or {@code null} where the platform does not ship it (notably macOS).
   *
   * <p>Probed once. Without it the child stays in the server's own process group and there is
   * nothing safe to signal but the child itself - see {@link #processGroupOf}.
   */
  static final String SETSID = findSetsid();

  /** The server's own process group. Signalling it would kill the API along with the job. */
  private static final long OWN_GROUP = readProcessGroup(ProcessHandle.current().pid());

  private static String findSetsid() {
    for (String candidate : List.of("/usr/bin/setsid", "/bin/setsid")) {
      if (Files.isExecutable(Path.of(candidate))) {
        return candidate;
      }
    }
    return null;
  }

  /**
   * Prefixes {@code command} with {@code setsid} so the child leads its own session and group.
   *
   * <p>This is the fix for process-tree cancellation, and it has to happen at START time. The
   * alternative - snapshotting {@code Process.descendants()} at cancel time and destroying each -
   * does not work: it is a time-of-check/time-of-use race against a child that has not forked its
   * JVM yet, and any process forked after the snapshot survives, keeps the inherited stderr pipe
   * open and leaves {@link #run} blocked in {@code readLine()} forever.
   *
   * <p>{@code setsid} only forks when the caller is already a process group leader. A child of this
   * JVM never is, so it {@code exec}s in place: the pid Java holds stays the pid of the real
   * command, and {@code waitFor()}/{@code exitValue()} keep reporting DSBulk's own exit status.
   */
  static List<String> inOwnProcessGroup(List<String> command) {
    if (SETSID == null || command.isEmpty()) {
      return command;
    }
    List<String> wrapped = new ArrayList<>(command.size() + 1);
    wrapped.add(SETSID);
    wrapped.addAll(command);
    return List.copyOf(wrapped);
  }

  /**
   * The child's process group, or {@link #NO_GROUP} when signalling it would be unsafe.
   *
   * <p>Read from the OS rather than assumed to equal the pid: if {@code setsid} was unavailable, did
   * fork, or the platform behaves differently, the child sits in the SERVER's group and a group
   * signal would take down the API. Refusing in that case degrades to today's behaviour - the direct
   * child is still killed - instead of turning a cancel into an outage.
   */
  static long processGroupOf(Process process) {
    if (SETSID == null || !process.isAlive()) {
      return NO_GROUP;
    }
    long group = readProcessGroup(process.pid());
    if (group <= 0 || group == OWN_GROUP) {
      LOG.debug("Child {} is not in a process group of its own; cancelling it alone", process.pid());
      return NO_GROUP;
    }
    return group;
  }

  /** {@code ps -o pgid= -p <pid>}; {@link #NO_GROUP} when {@code ps} is absent or says nothing. */
  private static long readProcessGroup(long pid) {
    String out = exec(List.of("ps", "-o", "pgid=", "-p", Long.toString(pid)));
    if (out == null || out.isBlank()) {
      return NO_GROUP;
    }
    try {
      return Long.parseLong(out.trim());
    } catch (NumberFormatException e) {
      return NO_GROUP;
    }
  }

  /**
   * {@code true} when any member of {@code group} is still running.
   *
   * <p>A zombie does not count: it holds no file descriptor, so it cannot be what is keeping
   * {@link #run} blocked, and it disappears as soon as its reaper gets to it.
   *
   * <p>The whole table is listed and filtered here rather than asking {@code ps} to select the
   * group: {@code ps -g} means "session" on procps and "process group" on BSD, and a selector that
   * means two things on two platforms is a silent no-op on one of them.
   */
  static boolean groupIsAlive(long group) {
    if (group == NO_GROUP) {
      return false;
    }
    String out = exec(List.of("ps", "-eo", "pgid=,stat="));
    if (out == null) {
      return false;
    }
    String prefix = Long.toString(group);
    return out.lines()
        .map(String::trim)
        .map(line -> line.split("\\s+"))
        .filter(columns -> columns.length >= 2 && prefix.equals(columns[0]))
        .anyMatch(columns -> !columns[1].isEmpty() && columns[1].charAt(0) != 'Z');
  }

  /** Signals a whole process group. A negative pid means "the group" to {@code kill(2)}. */
  private static void signalGroup(long group, String signal) {
    if (group == NO_GROUP) {
      return;
    }
    exec(List.of("kill", "-" + signal, "--", "-" + group));
  }

  /** Runs a short command and returns its stdout, or {@code null} if it could not be run. */
  private static String exec(List<String> command) {
    try {
      Process process = new ProcessBuilder(command)
          .redirectErrorStream(false)
          .redirectError(ProcessBuilder.Redirect.DISCARD)
          .start();
      process.getOutputStream().close();
      String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      if (!process.waitFor(5, TimeUnit.SECONDS)) {
        process.destroyForcibly();
      }
      return out;
    } catch (IOException e) {
      LOG.debug("Cannot run {}: {}", command, e.toString());
      return null;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return null;
    }
  }

  /** {@code bin/dsbulk} when the distribution ships an executable one, else an explicit java call. */
  List<String> command(DsbulkPlan plan, Path confFile) {
    List<String> argv = new ArrayList<>(rewriteConfPath(plan.argv(), confFile));
    Path launcher = distribution == null ? null : distribution.launcher();
    if (launcher != null && Files.isExecutable(launcher)) {
      argv.add(0, launcher.toString());
      return List.copyOf(argv);
    }
    List<String> command = new ArrayList<>();
    command.add(javaExecutable());
    command.add("-Xmx" + maxHeap);
    // Same reason as DSBULK_JAVA_OPTS above: the count report is locale-formatted.
    command.addAll(LOCALE_ARGS);
    command.add("-cp");
    command.add(classpath());
    command.add(MAIN_CLASS);
    command.addAll(argv);
    return List.copyOf(command);
  }

  /**
   * Substitutes the real conf path into the planned argv.
   *
   * <p>A plan can be built for preview before a job directory exists, so its {@code -f} argument may
   * be a bare file name. The file the runner actually wrote is authoritative.
   */
  static List<String> rewriteConfPath(List<String> argv, Path confFile) {
    List<String> out = new ArrayList<>(argv);
    for (int i = 0; i < out.size() - 1; i++) {
      if ("-f".equals(out.get(i))) {
        out.set(i + 1, confFile.toString());
        return out;
      }
    }
    out.add("-f");
    out.add(confFile.toString());
    return out;
  }

  private String javaExecutable() {
    return Path.of(System.getProperty("java.home", "."), "bin", "java").toString();
  }

  private String classpath() {
    if (distribution instanceof LocalDsbulkDistribution local && local.libraryDirectory() != null) {
      return local.libraryDirectory().resolve("*").toString();
    }
    throw new DsbulkException(
        "No DSBulk library directory available; set " + DsbulkDistribution.HOME_ENV
            + " to the unpacked distribution.");
  }

  private static String failureMessage(DsbulkExitStatus status, int exitCode) {
    return switch (status) {
      case ABORTED_TOO_MANY_ERRORS ->
          "DSBulk aborted: too many rejected records. Raise log.maxErrors, or fix the mapping - "
              + "the rejected rows are in the job's *-errors.log.";
      case ABORTED_FATAL_ERROR -> "DSBulk aborted on a fatal error; see the job log for the cause.";
      case INTERRUPTED -> "The job was cancelled.";
      default -> "DSBulk exited with status " + exitCode + " (" + status + ").";
    };
  }
}
