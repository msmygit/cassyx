package io.cassyx.api.bulk;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Persistence for DSBulk jobs, over the {@code cassyx_job} table of the Phase 0 baseline schema.
 *
 * <p>No Flyway migration ships with this workstream, deliberately: {@code cassyx_job} and
 * {@code cassyx_job_template} already exist, and a second agent adding a {@code V2__} migration at
 * the same time as this one is a version collision that breaks every developer's database at once.
 * The reproducibility document (resolved settings, generated command, count statistics) therefore
 * lives in the existing {@code settings_json} CLOB.
 */
@Repository
public class DsbulkJobRepository {

  private final JdbcTemplate jdbc;

  public DsbulkJobRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Inserts the {@code QUEUED} row. {@code engine} is always {@code DSBULK} here by definition. */
  public void insert(
      String id, String type, String connectionId, String keyspace, String table, String name, Instant createdAt) {
    jdbc.update(
        "INSERT INTO cassyx_job "
            + "(id, type, status, connection_id, keyspace_name, table_name, engine, created_at) "
            + "VALUES (?, ?, 'QUEUED', ?, ?, ?, 'DSBULK', ?)",
        id, type, connectionId, keyspace, table, Timestamp.from(createdAt));
    if (name != null && !name.isBlank()) {
      // The baseline table has no name column; the label lives in the settings document instead.
      jdbc.update("UPDATE cassyx_job SET settings_json = ? WHERE id = ?", "{\"name\":" + quote(name) + "}", id);
    }
  }

  public void markRunning(String id, Instant startedAt) {
    jdbc.update("UPDATE cassyx_job SET status = 'RUNNING', started_at = ? WHERE id = ?",
        Timestamp.from(startedAt), id);
  }

  public void markFinished(
      String id, String status, Instant finishedAt, long rowsProcessed, String error, String settingsJson) {
    jdbc.update(
        "UPDATE cassyx_job SET status = ?, finished_at = ?, rows_processed = ?, "
            + "progress_percent = ?, error_message = ?, "
            + "settings_json = COALESCE(?, settings_json) WHERE id = ?",
        status,
        Timestamp.from(finishedAt),
        rowsProcessed,
        "SUCCEEDED".equals(status) ? 100 : 0,
        truncate(error),
        settingsJson,
        id);
  }

  /** The raw job row, for the polling fallback and for {@code getTableStatistics}. */
  public Optional<Map<String, Object>> find(String id) {
    return jdbc.queryForList("SELECT * FROM cassyx_job WHERE id = ?", id).stream().findFirst();
  }

  /** The most recent successful COUNT job for a table - the source of the Statistics tab. */
  public Optional<Map<String, Object>> latestCount(String connectionId, String keyspace, String table) {
    return jdbc
        .queryForList(
            "SELECT * FROM cassyx_job WHERE type = 'COUNT' AND status = 'SUCCEEDED' "
                + "AND connection_id = ? AND keyspace_name = ? AND table_name = ? "
                + "ORDER BY finished_at DESC",
            connectionId, keyspace, table)
        .stream()
        .findFirst();
  }

  public void delete(String id) {
    jdbc.update("DELETE FROM cassyx_job WHERE id = ?", id);
  }

  /** {@code error_message} is VARCHAR(4000); an over-long DSBulk error must not fail the UPDATE. */
  static String truncate(String error) {
    if (error == null) {
      return null;
    }
    return error.length() <= 4000 ? error : error.substring(0, 3997) + "...";
  }

  private static String quote(String value) {
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }
}
