package io.cassyx.bulk.api;

import java.time.Duration;
import java.util.List;

/**
 * Outcome of an unload.
 *
 * @param splitsCompleted must equal the split count that was submitted - the completeness
 *     assertion of plan section 11.2 (union of splits = full row count, no dupes, no gaps)
 */
public record UnloadResult(
    long rowsWritten,
    int splitsCompleted,
    Duration elapsed,
    List<String> artifacts,
    List<String> warnings) {

  public UnloadResult {
    artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
    warnings = warnings == null ? List.of() : List.copyOf(warnings);
  }

  public double rowsPerSecond() {
    double seconds = elapsed == null ? 0 : elapsed.toNanos() / 1_000_000_000.0;
    return seconds <= 0 ? 0 : rowsWritten / seconds;
  }
}
