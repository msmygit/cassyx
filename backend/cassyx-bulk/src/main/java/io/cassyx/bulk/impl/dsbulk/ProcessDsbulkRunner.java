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
 *   <li><b>Real cancellation.</b> {@link #cancel(Path)} sends a signal. There is no cooperative
 *       interrupt to hope DSBulk honours.
 *   <li><b>Memory capping.</b> {@code -Xmx} per job, set independently of the server's heap.
 *   <li><b>Immunity to {@code System.exit()}.</b> DSBulk exits with a meaningful status; in-process
 *       that call would end the application. Here it is just an integer we read.
 * </ul>
 */
public final class ProcessDsbulkRunner implements DsbulkRunner {

  private static final Logger LOG = LoggerFactory.getLogger(ProcessDsbulkRunner.class);

  /** Main class of {@code dsbulk-runner}, used when the distribution's launcher is not executable. */
  public static final String MAIN_CLASS = "com.datastax.oss.dsbulk.runner.DataStaxBulkLoader";

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
  private final Map<Path, Process> running = new ConcurrentHashMap<>();

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
    ProcessBuilder builder = new ProcessBuilder(command);
    builder.directory(workDir.toFile());
    // STDOUT goes to a FILE, STDERR to the pipe we read. This is not arbitrary: DSBulk writes the
    // `count` workflow's report to stdout and everything else - logging AND the console progress
    // reporter - to stderr. Merging them would interleave a machine-readable report with log noise
    // and force us to tell them apart by shape. Redirecting stdout to a file also means a single
    // reader thread suffices: a second pipe nobody drains blocks the child the moment its buffer
    // fills.
    builder.redirectOutput(ProcessBuilder.Redirect.to(stdoutFile.toFile()));
    builder.environment().put("DSBULK_JAVA_OPTS", "-Xmx" + maxHeap);
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
    running.put(workDir, process);

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
      terminate(process);
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
    Process process = running.get(jobDirectory.toAbsolutePath().normalize());
    if (process == null || !process.isAlive()) {
      return false;
    }
    terminate(process);
    return true;
  }

  /** SIGTERM, then SIGKILL after the grace period: DSBulk flushes its error reports on SIGTERM. */
  private void terminate(Process process) {
    process.destroy();
    try {
      if (!process.waitFor(killGrace.toMillis(), TimeUnit.MILLISECONDS)) {
        process.destroyForcibly();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      process.destroyForcibly();
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
