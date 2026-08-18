package io.cassyx.core.api.query;

import java.util.List;

/**
 * Result of assembling (and possibly executing) a batch. Contract: {@code BatchResult}.
 *
 * @param spansMultiplePartitions drives the multi-partition batch anti-pattern warning: such a batch
 *     costs the coordinator far more than the same writes issued in parallel
 */
public record BatchOutcome(
    String assembledCql,
    int statementCount,
    boolean spansMultiplePartitions,
    int distinctPartitions,
    List<String> warnings,
    boolean executed,
    Boolean applied,
    long elapsedMillis) {

  public BatchOutcome {
    warnings = warnings == null ? List.of() : List.copyOf(warnings);
  }
}
