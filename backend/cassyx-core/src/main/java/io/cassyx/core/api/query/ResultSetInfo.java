package io.cassyx.core.api.query;

import java.time.Instant;
import java.util.List;

/** Metadata about a cached result set, without transferring rows. Contract: {@code ResultSetState}. */
public record ResultSetInfo(
    String resultHandle,
    String cql,
    List<ColumnInfo> columns,
    int pagesFetched,
    long rowsFetched,
    boolean hasMorePages,
    boolean editable,
    String keyspace,
    String table,
    Instant expiresAt) {

  public ResultSetInfo {
    columns = columns == null ? List.of() : List.copyOf(columns);
  }
}
