package io.cassyx.core.api.query;

import java.util.List;
import java.util.Map;

/**
 * Result of a row mutation. {@code cql} is the exact generated statement and is ALWAYS returned -
 * plan section 4/7: never execute generated CQL the user has not been shown.
 *
 * @param currentValues for a failed LWT, the values Cassandra returned alongside {@code [applied]=false}
 */
public record RowMutationOutcome(
    boolean executed,
    String cql,
    Boolean applied,
    Map<String, Object> currentValues,
    long elapsedMillis,
    List<String> warnings) {

  public RowMutationOutcome {
    warnings = warnings == null ? List.of() : List.copyOf(warnings);
  }
}
