package io.cassyx.core.api.query;

import java.util.List;
import java.util.Map;

/**
 * One page of a result set plus the handles needed to page further. Mirrors the contract's
 * {@code QueryResult}.
 *
 * <p>{@code nextPageToken} / {@code previousPageToken} are <b>opaque</b>: they are server-side
 * handles onto the driver's {@code PagingState}, never something a client constructs or decodes.
 * Cassandra's paging state is forward-only, so "previous" is a replay of a token this server already
 * retained (plan section 5.1) - it is emphatically not a re-run with an offset, because CQL has no
 * offset.
 *
 * @param applied the LWT {@code [applied]} flag, surfaced distinctly from the row data; null when
 *     the statement was not conditional
 */
public record ResultPage(
    String resultHandle,
    String queryId,
    List<ColumnInfo> columns,
    List<Map<String, Object>> rows,
    int rowCount,
    int pageNumber,
    boolean hasMorePages,
    String nextPageToken,
    String previousPageToken,
    Boolean applied,
    boolean wasVoid,
    long elapsedMillis,
    List<String> warnings,
    String tracingId,
    List<String> similarityColumns,
    String coordinator,
    String consistency) {

  public ResultPage {
    columns = columns == null ? List.of() : List.copyOf(columns);
    rows = rows == null ? List.of() : List.copyOf(rows);
    warnings = warnings == null ? List.of() : List.copyOf(warnings);
    similarityColumns = similarityColumns == null ? List.of() : List.copyOf(similarityColumns);
  }
}
