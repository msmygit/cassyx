package io.cassyx.api.query;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Query history with timings, in the H2 {@code cassyx_query_history} table of the Phase 0 baseline.
 *
 * <p>{@code connection_id} carries a foreign key to {@code cassyx_connection}, so a history row for
 * a connection that has not been saved yet is written with a NULL connection rather than failing the
 * query the user actually ran. Losing an audit row is bad; failing a working query to protect one is
 * worse.
 */
@Repository
public class QueryHistoryRepository {

  private final JdbcTemplate jdbc;

  public QueryHistoryRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  private static final RowMapper<QueryDtos.QueryHistoryEntry> MAPPER =
      (rs, rowNum) ->
          new QueryDtos.QueryHistoryEntry(
              rs.getString("id"),
              rs.getString("connection_id"),
              null,
              rs.getString("keyspace_name"),
              rs.getString("statement"),
              rs.getTimestamp("executed_at").toInstant(),
              rs.getLong("duration_ms"),
              rs.getLong("row_count"),
              rs.getBoolean("succeeded"),
              rs.getString("error_message"),
              rs.getString("consistency"));

  /** @return the new entry id */
  public String record(
      String connectionId,
      String keyspace,
      String cql,
      String consistency,
      long durationMillis,
      long rowCount,
      boolean succeeded,
      String errorMessage,
      String tracingId) {

    String id = UUID.randomUUID().toString();
    jdbc.update(
        "INSERT INTO cassyx_query_history (id, connection_id, keyspace_name, statement, consistency,"
            + " executed_at, duration_ms, row_count, succeeded, error_message, tracing_id)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?,?)",
        id,
        knownConnection(connectionId),
        keyspace,
        cql,
        consistency,
        Timestamp.from(Instant.now()),
        durationMillis,
        rowCount,
        succeeded,
        truncate(errorMessage, 2000),
        tracingId);
    return id;
  }

  public QueryDtos.QueryHistoryPage list(String connectionId, String search, int limit, int offset) {
    StringBuilder where = new StringBuilder(" WHERE 1 = 1");
    List<Object> args = new ArrayList<>();
    if (connectionId != null && !connectionId.isBlank()) {
      where.append(" AND connection_id = ?");
      args.add(connectionId);
    }
    if (search != null && !search.isBlank()) {
      where.append(" AND LOWER(statement) LIKE ?");
      args.add("%" + search.toLowerCase(java.util.Locale.ROOT) + "%");
    }

    Integer total =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM cassyx_query_history" + where, Integer.class, args.toArray());

    List<Object> pagedArgs = new ArrayList<>(args);
    pagedArgs.add(limit);
    pagedArgs.add(offset);
    List<QueryDtos.QueryHistoryEntry> items =
        jdbc.query(
            "SELECT * FROM cassyx_query_history"
                + where
                + " ORDER BY executed_at DESC LIMIT ? OFFSET ?",
            MAPPER,
            pagedArgs.toArray());

    return new QueryDtos.QueryHistoryPage(items, total == null ? 0 : total, limit, offset);
  }

  public void clear(String connectionId) {
    if (connectionId == null || connectionId.isBlank()) {
      jdbc.update("DELETE FROM cassyx_query_history");
    } else {
      jdbc.update("DELETE FROM cassyx_query_history WHERE connection_id = ?", connectionId);
    }
  }

  private String knownConnection(String connectionId) {
    if (connectionId == null || connectionId.isBlank()) {
      return null;
    }
    Integer count =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM cassyx_connection WHERE id = ?", Integer.class, connectionId);
    return count != null && count > 0 ? connectionId : null;
  }

  private static String truncate(String text, int max) {
    if (text == null) {
      return null;
    }
    return text.length() <= max ? text : text.substring(0, max);
  }
}
