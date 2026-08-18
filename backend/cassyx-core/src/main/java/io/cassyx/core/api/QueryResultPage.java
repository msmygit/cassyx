package io.cassyx.core.api;

import java.util.List;
import java.util.Map;

/**
 * One page of results.
 *
 * @param columns column names in projection order
 * @param rows row values keyed by column name
 * @param nextPagingState continuation token, or null when the result set is exhausted
 * @param applied the {@code [applied]} flag of an LWT, or null when not an LWT
 * @param tracingSessionId trace id when tracing was requested
 */
public record QueryResultPage(
    List<String> columns,
    List<Map<String, Object>> rows,
    String nextPagingState,
    Boolean applied,
    String tracingSessionId) {

  public QueryResultPage {
    columns = columns == null ? List.of() : List.copyOf(columns);
    rows = rows == null ? List.of() : List.copyOf(rows);
  }

  public boolean hasMorePages() {
    return nextPagingState != null;
  }
}
