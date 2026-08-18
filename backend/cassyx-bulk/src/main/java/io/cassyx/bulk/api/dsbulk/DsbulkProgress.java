package io.cassyx.bulk.api.dsbulk;

/**
 * A progress tick scraped from the DSBulk child process (plan section 5.3: "parse the runner's exit
 * status and tail its log directory for progress").
 *
 * @param rowsProcessed rows read or written so far
 * @param rowsPerSecond instantaneous throughput as reported by DSBulk's own reporter
 * @param failures records DSBulk rejected so far
 * @param phase free-text phase label, surfaced as {@code JobProgress.currentPhase}
 */
public record DsbulkProgress(long rowsProcessed, long rowsPerSecond, long failures, String phase) {

  public static final DsbulkProgress NONE = new DsbulkProgress(0, 0, 0, "");

  public DsbulkProgress {
    phase = phase == null ? "" : phase;
  }
}
