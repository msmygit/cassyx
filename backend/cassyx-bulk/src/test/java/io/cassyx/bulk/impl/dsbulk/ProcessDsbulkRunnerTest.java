package io.cassyx.bulk.impl.dsbulk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.cassyx.bulk.api.dsbulk.DsbulkDistribution;
import io.cassyx.bulk.api.dsbulk.DsbulkException;
import io.cassyx.bulk.api.dsbulk.DsbulkExitStatus;
import io.cassyx.bulk.api.dsbulk.DsbulkJobSpec;
import io.cassyx.bulk.api.dsbulk.DsbulkListener;
import io.cassyx.bulk.api.dsbulk.DsbulkLogLine;
import io.cassyx.bulk.api.dsbulk.DsbulkOperation;
import io.cassyx.bulk.api.dsbulk.DsbulkPlan;
import io.cassyx.bulk.api.dsbulk.DsbulkProbe;
import io.cassyx.bulk.api.dsbulk.DsbulkProgress;
import io.cassyx.bulk.api.dsbulk.DsbulkResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The out-of-process runner, exercised against REAL child processes.
 *
 * <p>The {@code ProcessStarter} seam swaps DSBulk's command line for a shell script, so these tests
 * drive genuine pipes, genuine exit codes and a genuine kill - none of which a mocked
 * {@link Process} would prove anything about. What is under test is precisely the plumbing that
 * mocks cannot check: that stdout lands in a file, that stderr feeds progress, that a non-zero exit
 * maps to the right status, and that cancellation actually terminates something.
 */
class ProcessDsbulkRunnerTest {

  @TempDir Path tmp;

  /** Runs {@code script} through {@code sh} instead of DSBulk, keeping the configured redirects. */
  private static ProcessDsbulkRunner.ProcessStarter shell(String script) {
    return builder -> {
      builder.command(List.of("sh", "-c", script));
      return builder.start();
    };
  }

  private DsbulkPlan plan(DsbulkOperation operation) {
    DsbulkJobSpec spec = operation == DsbulkOperation.LOAD
        ? DsbulkJobSpec.table(operation, "demo", "users", "csv", "/in.csv")
        : DsbulkJobSpec.table(operation, "demo", "users", "csv", "/out");
    return DsbulkPlanner.plan(spec, DsbulkProbe.UNKNOWN, tmp, "TEST_1");
  }

  private static final class RecordingListener implements DsbulkListener {
    final List<DsbulkProgress> progress = new CopyOnWriteArrayList<>();
    final List<DsbulkLogLine> logs = new CopyOnWriteArrayList<>();

    @Override
    public void onProgress(DsbulkProgress tick) {
      progress.add(tick);
    }

    @Override
    public void onLog(DsbulkLogLine line) {
      logs.add(line);
    }
  }

  @Test
  @DisplayName("writes the conf, streams progress from stderr and captures stdout to a file")
  void happyPath() throws IOException {
    String script = """
        echo 'total | failed | rows/s | p50ms' >&2
        echo '  100 |      0 |    250 | 1.00' >&2
        echo '2026-08-17 12:00:03 INFO  Operation TEST_1 completed successfully.' >&2
        echo 'this is stdout'
        exit 0
        """;
    ProcessDsbulkRunner runner =
        new ProcessDsbulkRunner(distribution(), "512m", Duration.ofSeconds(2), shell(script));
    RecordingListener listener = new RecordingListener();

    DsbulkResult result = runner.run(plan(DsbulkOperation.UNLOAD), tmp, Map.of(), listener);

    assertThat(result.exitCode()).isZero();
    assertThat(result.succeeded()).isTrue();
    assertThat(result.status()).isEqualTo(DsbulkExitStatus.OK);
    assertThat(result.rowsProcessed()).isEqualTo(100);
    assertThat(result.failureMessage()).isEmpty();
    assertThat(result.elapsed()).isPositive();
    assertThat(result.rowsPerSecond()).isPositive();

    assertThat(listener.progress).isNotEmpty();
    assertThat(listener.progress.get(listener.progress.size() - 1).rowsProcessed()).isEqualTo(100);
    assertThat(listener.logs).extracting(DsbulkLogLine::message)
        .anySatisfy(message -> assertThat(message).contains("completed successfully"));

    Path conf = tmp.resolve("dsbulk.conf");
    assertThat(conf).exists();
    assertThat(Files.readString(conf)).contains("dsbulk.schema.keyspace = \"demo\"");
    assertThat(tmp.resolve(ProcessDsbulkRunner.STDOUT_FILE_NAME)).exists();
    assertThat(Files.readString(tmp.resolve(ProcessDsbulkRunner.STDOUT_FILE_NAME))).contains("this is stdout");
    assertThat(result.artifacts()).contains(conf);
  }

  @Test
  @DisplayName("a count job's statistics are parsed out of the captured stdout")
  void countReportIsParsedFromStdout() {
    String script = """
        echo 'Operation directory: %s/logs/COUNT_TEST' >&2
        echo 'Total rows:'
        echo '4200'
        echo 'Total rows per node:'
        echo '/127.0.0.1:9042 4200 100.00'
        exit 0
        """.formatted(tmp.toString());
    ProcessDsbulkRunner runner =
        new ProcessDsbulkRunner(distribution(), "512m", Duration.ofSeconds(2), shell(script));

    DsbulkResult result = runner.run(plan(DsbulkOperation.COUNT), tmp, Map.of(), DsbulkListener.noop());

    assertThat(result.countReport().totalRows()).isEqualTo(4200);
    assertThat(result.countReport().perReplica()).singleElement()
        .satisfies(replica -> assertThat(replica.endpoint()).isEqualTo("127.0.0.1:9042"));
    // The row count falls back to the count report when no reporter line was seen.
    assertThat(result.rowsProcessed()).isEqualTo(4200);
    // The operation directory DSBulk announced wins over the configured parent.
    assertThat(result.logDirectory().toString()).endsWith("logs/COUNT_TEST");
  }

  @Test
  @DisplayName("exit statuses are mapped, and completed-with-errors is NOT a failure")
  void exitStatusMapping() {
    assertThat(runExit(1).succeeded()).isTrue();
    assertThat(runExit(1).status()).isEqualTo(DsbulkExitStatus.COMPLETED_WITH_ERRORS);

    DsbulkResult tooManyErrors = runExit(2);
    assertThat(tooManyErrors.succeeded()).isFalse();
    assertThat(tooManyErrors.status()).isEqualTo(DsbulkExitStatus.ABORTED_TOO_MANY_ERRORS);
    assertThat(tooManyErrors.failureMessage()).contains("maxErrors").contains("errors.log");

    assertThat(runExit(3).status()).isEqualTo(DsbulkExitStatus.ABORTED_FATAL_ERROR);
    assertThat(runExit(3).failureMessage()).contains("fatal");
    assertThat(runExit(5).status()).isEqualTo(DsbulkExitStatus.CRASHED);
    // An exit code nobody has seen before is not a success.
    assertThat(DsbulkExitStatus.of(99)).isEqualTo(DsbulkExitStatus.CRASHED);
    assertThat(DsbulkExitStatus.INTERRUPTED.isInterrupted()).isTrue();
    assertThat(DsbulkExitStatus.OK.code()).isZero();
  }

  private DsbulkResult runExit(int code) {
    ProcessDsbulkRunner runner = new ProcessDsbulkRunner(
        distribution(), "512m", Duration.ofSeconds(2), shell("exit " + code));
    return runner.run(plan(DsbulkOperation.LOAD), tmp, Map.of(), DsbulkListener.noop());
  }

  @Test
  @DisplayName("cancel kills the child process - real cancellation, not a cooperative interrupt")
  void cancelKillsTheChild() throws Exception {
    CountDownLatch started = new CountDownLatch(1);
    ProcessDsbulkRunner runner = new ProcessDsbulkRunner(
        distribution(), "512m", Duration.ofMillis(500),
        builder -> {
          // `exec` matters. Without it, dash (this image's /bin/sh) forks `sleep` as a CHILD
          // and does not replace itself - verified: `ps --ppid <sh>` shows `sleep 60`, and after
          // SIGTERM to sh the sleep SURVIVES. That orphan keeps the inherited stderr pipe open,
          // so run()'s `reader.readLine()` never sees EOF and never returns.
          //
          // Worse, it made the test nondeterministic: cancel() polls immediately, so whether an
          // orphan exists depends on whether dash had reached the fork yet. It passed locally in
          // ~1s (cancel won the race) and failed in CI at the 20s join (fork won). `exec` removes
          // the race entirely - sh becomes the sleep, so there is exactly one process to kill.
          //
          // The orphan scenario is a REAL product concern for `bin/dsbulk`, which is a shell
          // script wrapping a JVM. It is tracked separately in docs/integration-todo.md
          // ("Process-tree cancellation") because fixing it needs process-group handling and its
          // own test - not a fixture tweak smuggled into a CI fix.
          builder.command(List.of("sh", "-c", "echo started >&2; exec sleep 60"));
          Process process = builder.start();
          started.countDown();
          return process;
        });

    List<DsbulkResult> results = new ArrayList<>();
    Thread worker = new Thread(() ->
        results.add(runner.run(plan(DsbulkOperation.UNLOAD), tmp, Map.of(), DsbulkListener.noop())));
    worker.start();

    assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
    // Cancelling before the child is registered would be a flaky no-op; poll for it instead.
    boolean cancelled = false;
    for (int i = 0; i < 100 && !cancelled; i++) {
      cancelled = runner.cancel(tmp);
      if (!cancelled) {
        Thread.sleep(50);
      }
    }
    assertThat(cancelled).as("cancel() found and signalled the child").isTrue();

    worker.join(TimeUnit.SECONDS.toMillis(20));
    assertThat(worker.isAlive()).isFalse();
    assertThat(results).singleElement()
        .satisfies(result -> assertThat(result.succeeded()).isFalse());
    // Cancelling a job that is not running is false, not an exception.
    assertThat(runner.cancel(tmp)).isFalse();
  }

  @Test
  @DisplayName("secrets reach the file on disk and nothing else")
  void secretsOnlyReachTheFile() throws IOException {
    DsbulkJobSpec spec = new DsbulkJobSpec(DsbulkOperation.UNLOAD, "demo", "users", null, "csv",
        "s3://bucket/out", null, false, null, 10, Map.of("s3.secretAccessKey", "***"));
    DsbulkPlan plan = DsbulkPlanner.plan(spec, DsbulkProbe.UNKNOWN, tmp, "TEST_2");
    ProcessDsbulkRunner runner =
        new ProcessDsbulkRunner(distribution(), "512m", Duration.ofSeconds(2), shell("exit 0"));

    runner.run(plan, tmp, Map.of("s3.secretAccessKey", "REAL-SECRET"), DsbulkListener.noop());

    assertThat(Files.readString(tmp.resolve("dsbulk.conf"))).contains("REAL-SECRET");
    assertThat(plan.hocon()).doesNotContain("REAL-SECRET");
    assertThat(plan.command()).doesNotContain("REAL-SECRET");
  }

  @Test
  @DisplayName("a process that cannot be started fails with an actionable message")
  void unstartableProcess() {
    ProcessDsbulkRunner runner = new ProcessDsbulkRunner(distribution(), "512m", Duration.ofSeconds(1),
        builder -> {
          throw new IOException("no such file");
        });
    assertThatThrownBy(() -> runner.run(plan(DsbulkOperation.UNLOAD), tmp, Map.of(), null))
        .isInstanceOf(DsbulkException.class)
        .hasMessageContaining("Cannot start the DSBulk process");
  }

  @Test
  @DisplayName("the command uses bin/dsbulk when it is executable, and java -cp lib/* otherwise")
  void commandConstruction() throws IOException {
    ProcessDsbulkRunner viaJava =
        new ProcessDsbulkRunner(distribution(), "3g", Duration.ofSeconds(1), shell("exit 0"));
    List<String> javaCommand = viaJava.command(plan(DsbulkOperation.UNLOAD), tmp.resolve("dsbulk.conf"));
    assertThat(javaCommand.get(0)).endsWith("java");
    assertThat(javaCommand).contains("-Xmx3g", "-cp", ProcessDsbulkRunner.MAIN_CLASS);
    assertThat(javaCommand).anySatisfy(arg -> assertThat(arg).endsWith("lib/*"));
    assertThat(javaCommand).containsSequence("-f", tmp.resolve("dsbulk.conf").toString());

    Path home = Files.createDirectories(tmp.resolve("dist-with-launcher"));
    Files.createDirectories(home.resolve("lib"));
    Path launcher = Files.writeString(Files.createDirectories(home.resolve("bin")).resolve("dsbulk"), "#!/bin/sh\n");
    assertThat(launcher.toFile().setExecutable(true)).isTrue();
    ProcessDsbulkRunner viaLauncher = new ProcessDsbulkRunner(
        new LocalDsbulkDistribution(home), "1g", Duration.ofSeconds(1), shell("exit 0"));
    assertThat(viaLauncher.command(plan(DsbulkOperation.COUNT), tmp.resolve("dsbulk.conf")).get(0))
        .isEqualTo(launcher.toString());
  }

  @Test
  @DisplayName("a preview plan's placeholder conf path is replaced by the real one")
  void confPathIsRewritten() {
    assertThat(ProcessDsbulkRunner.rewriteConfPath(List.of("unload", "-f", "dsbulk.conf"), Path.of("/real.conf")))
        .containsExactly("unload", "-f", "/real.conf");
    // A plan with no -f at all still gets one.
    assertThat(ProcessDsbulkRunner.rewriteConfPath(List.of("unload"), Path.of("/real.conf")))
        .containsExactly("unload", "-f", "/real.conf");
  }

  @Test
  @DisplayName("an unreadable stdout capture yields an empty report, not a crash")
  void unreadableReport() {
    assertThat(ProcessDsbulkRunner.readReport(tmp.resolve("does-not-exist"))).isEmpty();
  }

  @Test
  @DisplayName("running without a distribution says so instead of building a nonsense classpath")
  void noDistribution() {
    ProcessDsbulkRunner runner =
        new ProcessDsbulkRunner(new NoLibraryDistribution(), null, null, null);
    assertThatThrownBy(() -> runner.command(plan(DsbulkOperation.UNLOAD), tmp.resolve("c.conf")))
        .isInstanceOf(DsbulkException.class)
        .hasMessageContaining(DsbulkDistribution.HOME_ENV);
  }

  private LocalDsbulkDistribution distribution() {
    try {
      Path home = tmp.resolve("dist");
      Files.createDirectories(home.resolve("lib"));
      return new LocalDsbulkDistribution(home);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  /** A distribution that is not a {@link LocalDsbulkDistribution}, so no {@code lib/} is derivable. */
  private static final class NoLibraryDistribution implements DsbulkDistribution {
    @Override
    public Path home() {
      return null;
    }

    @Override
    public Path launcher() {
      return null;
    }

    @Override
    public List<String> jars() {
      return List.of();
    }

    @Override
    public boolean isComplete() {
      return false;
    }

    @Override
    public List<String> workflows() {
      return List.of();
    }

    @Override
    public void verify() {
      // nothing to verify
    }
  }
}
