package io.cassyx.bulk.api.dsbulk;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Outcome of one DSBulk child process.
 *
 * <p>DSBulk calls {@link System#exit(int)} with a meaningful status, which is exactly why running it
 * out of process is worth it: in-process that call would take the whole Spring application down
 * (plan section 5.3). Here it is just an integer.
 *
 * @param exitCode the child's exit status; see {@link DsbulkExitStatus}
 * @param rowsProcessed rows read or written, scraped from the operation log
 * @param logDirectory the per-job DSBulk log directory, retained for download
 * @param artifacts files produced by the job, including the reproducible HOCON
 * @param countReport populated for {@link DsbulkOperation#COUNT}, otherwise {@link
 *     DsbulkCountReport#EMPTY}
 */
public record DsbulkResult(
    int exitCode,
    long rowsProcessed,
    long failures,
    Duration elapsed,
    Path logDirectory,
    List<Path> artifacts,
    DsbulkCountReport countReport,
    String failureMessage) {

  public DsbulkResult {
    elapsed = elapsed == null ? Duration.ZERO : elapsed;
    artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
    countReport = countReport == null ? DsbulkCountReport.EMPTY : countReport;
    failureMessage = failureMessage == null ? "" : failureMessage;
  }

  public boolean succeeded() {
    return DsbulkExitStatus.of(exitCode).isSuccess();
  }

  public DsbulkExitStatus status() {
    return DsbulkExitStatus.of(exitCode);
  }

  public double rowsPerSecond() {
    double seconds = elapsed.toNanos() / 1_000_000_000.0;
    return seconds <= 0 ? 0 : rowsProcessed / seconds;
  }
}
