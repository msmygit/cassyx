package io.cassyx.api.bulk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.cassyx.api.bulk.DsbulkDtos.SchemaIdentity;
import io.cassyx.api.bulk.JobDtos.Job;
import io.cassyx.api.bulk.JobDtos.JobArtifact;
import io.cassyx.api.bulk.JobDtos.JobProgressView;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Persistence and read model for <b>every</b> job, whichever engine ran it (plan section 5.5).
 *
 * <p>Reads span both engines on purpose. {@code DsbulkJobRepository} writes DSBulk rows and this one
 * writes native rows, but {@code GET /api/jobs} is a single list in the contract and in the UI: a
 * user does not think in terms of which engine happened to serve their export. Splitting the read
 * side per engine would produce a jobs panel that shows half the jobs.
 *
 * <p>No Flyway migration ships here. {@code cassyx_job} already exists in the Phase 0 baseline with
 * the columns this needs ({@code splits_total}/{@code splits_completed} are literally there for the
 * native engine), and a second workstream adding a {@code V3__} at the same time is a version
 * collision that breaks every developer's database at once. Anything without a column - the job's
 * display name, its artifact metadata - lives in the existing {@code settings_json} CLOB.
 */
@Repository
public class JobRepository {

  private static final Logger LOG = LoggerFactory.getLogger(JobRepository.class);

  /** Contract default and ceiling for {@code GET /api/jobs}. */
  static final int DEFAULT_LIMIT = 50;

  static final int MAX_LIMIT = 500;

  private final JdbcTemplate jdbc;
  private final ObjectMapper json;

  public JobRepository(JdbcTemplate jdbc, ObjectMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  /* ------------------------------------------------------------------------------ writes */

  /** Inserts the {@code QUEUED} row for a native-engine job. */
  public void insert(
      String id,
      String type,
      String connectionId,
      String keyspace,
      String table,
      String name,
      Instant createdAt,
      String settingsJson) {
    jdbc.update(
        "INSERT INTO cassyx_job "
            + "(id, type, status, connection_id, keyspace_name, table_name, engine, "
            + " settings_json, created_at) "
            + "VALUES (?, ?, 'QUEUED', ?, ?, ?, 'NATIVE', ?, ?)",
        id,
        type,
        connectionId,
        keyspace,
        table,
        settingsDocument(name, settingsJson),
        Timestamp.from(createdAt));
  }

  public void markRunning(String id, Instant startedAt) {
    jdbc.update(
        "UPDATE cassyx_job SET status = 'RUNNING', started_at = ? WHERE id = ?",
        Timestamp.from(startedAt),
        id);
  }

  /**
   * Progress heartbeat.
   *
   * <p>Written at the same throttled cadence as the SSE {@code progress} event rather than per row:
   * this is the polling fallback's data source, and a row-per-row UPDATE would make the database the
   * unload's bottleneck.
   */
  public void updateProgress(String id, long rowsProcessed, int splitsCompleted, int splitsTotal) {
    int percent = splitsTotal > 0 ? (int) Math.min(99, (100L * splitsCompleted) / splitsTotal) : 0;
    jdbc.update(
        "UPDATE cassyx_job SET rows_processed = ?, splits_completed = ?, splits_total = ?, "
            + "progress_percent = ? WHERE id = ?",
        rowsProcessed,
        splitsCompleted,
        splitsTotal,
        percent,
        id);
  }

  /** Terminal transition. {@code settingsJson} is merged in when present, preserved when not. */
  public void markFinished(
      String id,
      String status,
      Instant finishedAt,
      long rowsProcessed,
      String error,
      String artifactPath,
      String settingsJson) {
    jdbc.update(
        "UPDATE cassyx_job SET status = ?, finished_at = ?, rows_processed = ?, "
            + "progress_percent = ?, error_message = ?, "
            + "artifact_path = COALESCE(?, artifact_path), "
            + "settings_json = COALESCE(?, settings_json) WHERE id = ?",
        status,
        Timestamp.from(finishedAt),
        rowsProcessed,
        "SUCCEEDED".equals(status) ? 100 : 0,
        truncate(error),
        artifactPath,
        settingsJson,
        id);
  }

  public void delete(String id) {
    jdbc.update("DELETE FROM cassyx_job WHERE id = ?", id);
  }

  /* ------------------------------------------------------------------------------- reads */

  public Optional<Job> find(String id) {
    return jdbc.queryForList("SELECT * FROM cassyx_job WHERE id = ?", id).stream()
        .findFirst()
        .map(this::toJob);
  }

  /** The raw row, for callers that need columns the {@code Job} view does not carry. */
  public Optional<Map<String, Object>> findRow(String id) {
    return jdbc.queryForList("SELECT * FROM cassyx_job WHERE id = ?", id).stream().findFirst();
  }

  /** Total matching the same filters as {@link #list}, for {@code JobPage.total}. */
  public int count(List<String> statuses, List<String> types, String connectionId) {
    StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM cassyx_job WHERE 1 = 1");
    List<Object> args = new ArrayList<>();
    appendFilters(sql, args, statuses, types, connectionId);
    Integer total = jdbc.queryForObject(sql.toString(), Integer.class, args.toArray());
    return total == null ? 0 : total;
  }

  /** Jobs newest first, filtered and paged exactly as {@code listJobs} declares. */
  public List<Job> list(
      List<String> statuses, List<String> types, String connectionId, int limit, int offset) {
    StringBuilder sql = new StringBuilder("SELECT * FROM cassyx_job WHERE 1 = 1");
    List<Object> args = new ArrayList<>();
    appendFilters(sql, args, statuses, types, connectionId);
    sql.append(" ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?");
    args.add(clampLimit(limit));
    args.add(Math.max(0, offset));
    return jdbc.queryForList(sql.toString(), args.toArray()).stream().map(this::toJob).toList();
  }

  /**
   * Filters are built as bound parameters, never string-concatenated.
   *
   * <p>{@code status} and {@code type} arrive from the query string; interpolating them would be a
   * SQL injection in a filter nobody looks at twice.
   */
  private static void appendFilters(
      StringBuilder sql, List<Object> args, List<String> statuses, List<String> types,
      String connectionId) {
    if (statuses != null && !statuses.isEmpty()) {
      sql.append(" AND status IN (").append(placeholders(statuses.size())).append(')');
      args.addAll(statuses);
    }
    if (types != null && !types.isEmpty()) {
      sql.append(" AND type IN (").append(placeholders(types.size())).append(')');
      args.addAll(types);
    }
    if (connectionId != null && !connectionId.isBlank()) {
      sql.append(" AND connection_id = ?");
      args.add(connectionId);
    }
  }

  private static String placeholders(int count) {
    return String.join(", ", java.util.Collections.nCopies(count, "?"));
  }

  static int clampLimit(int limit) {
    if (limit <= 0) {
      return DEFAULT_LIMIT;
    }
    return Math.min(limit, MAX_LIMIT);
  }

  /* -------------------------------------------------------------------------- row mapping */

  /**
   * One row to the contract's {@code Job}.
   *
   * <p>Column names are read case-insensitively: H2 upper-cases unquoted identifiers and PostgreSQL
   * lower-cases them, so keying the map directly is a bug that only shows up on the other database.
   */
  Job toJob(Map<String, Object> row) {
    String id = string(row, "id");
    JsonNode settings = parse(string(row, "settings_json"));
    String status = string(row, "status");
    Instant createdAt = instant(row, "created_at");
    Instant startedAt = instant(row, "started_at");
    Instant finishedAt = instant(row, "finished_at");

    return new Job(
        id,
        text(settings, "name"),
        string(row, "type"),
        status,
        string(row, "engine"),
        string(row, "connection_id"),
        null,
        identity(string(row, "keyspace_name"), string(row, "table_name")),
        iso(createdAt),
        iso(startedAt),
        iso(finishedAt),
        startedAt == null || finishedAt == null
            ? null
            : finishedAt.toEpochMilli() - startedAt.toEpochMilli(),
        progress(row, status, startedAt, finishedAt),
        artifacts(id, row, status),
        "/api/jobs/" + id + "/events",
        "/api/jobs/" + id + "/logs",
        integer(settings, "exitCode"),
        error(row, status));
  }

  private JobProgressView progress(
      Map<String, Object> row, String status, Instant startedAt, Instant finishedAt) {
    long rows = number(row, "rows_processed");
    int splitsCompleted = (int) number(row, "splits_completed");
    int splitsTotal = (int) number(row, "splits_total");
    long percent = number(row, "progress_percent");
    long elapsed =
        startedAt == null
            ? 0
            : (finishedAt == null ? Instant.now() : finishedAt).toEpochMilli()
                - startedAt.toEpochMilli();
    return new JobProgressView(
        rows,
        null,
        percent <= 0 ? null : (double) percent,
        elapsed > 0 ? rows * 1000 / Math.max(1, elapsed) : 0,
        0,
        Math.max(0, elapsed),
        null,
        splitsCompleted,
        splitsTotal,
        0,
        "SUCCEEDED".equals(status) ? "COMPLETED" : status);
  }

  /**
   * The artifact list, derived from {@code artifact_path}.
   *
   * <p>Only ever populated for a {@code SUCCEEDED} job: the contract's {@code downloadJobArtifact}
   * 409s before then, and advertising a download link for a half-written file is how a user ends up
   * with a truncated export they believe is complete.
   */
  private List<JobArtifact> artifacts(String id, Map<String, Object> row, String status) {
    String path = string(row, "artifact_path");
    if (path == null || path.isBlank() || !"SUCCEEDED".equals(status)) {
      return List.of();
    }
    java.nio.file.Path file = java.nio.file.Path.of(path);
    long size = 0;
    try {
      size = java.nio.file.Files.isRegularFile(file) ? java.nio.file.Files.size(file) : 0;
    } catch (java.io.IOException e) {
      LOG.debug("Cannot size the artifact of job {}: {}", id, e.toString());
    }
    String fileName = file.getFileName() == null ? "artifact" : file.getFileName().toString();
    return List.of(
        new JobArtifact(
            "a1",
            fileName,
            size,
            contentTypeFor(fileName),
            "/api/jobs/" + id + "/artifact",
            null,
            "DATA",
            null));
  }

  /** Extension to media type; anything unrecognised streams as opaque bytes. */
  static String contentTypeFor(String fileName) {
    String lower = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
    if (lower.endsWith(".csv")) {
      return "text/csv";
    }
    if (lower.endsWith(".json") || lower.endsWith(".jsonl")) {
      return "application/json";
    }
    if (lower.endsWith(".xml")) {
      return "application/xml";
    }
    if (lower.endsWith(".xlsx")) {
      return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    }
    if (lower.endsWith(".parquet")) {
      return "application/vnd.apache.parquet";
    }
    if (lower.endsWith(".zip")) {
      return "application/zip";
    }
    return "application/octet-stream";
  }

  /** RFC 9457 body for a failed job. Never the stack trace, never a credential. */
  private Object error(Map<String, Object> row, String status) {
    String message = string(row, "error_message");
    if (!"FAILED".equals(status) || message == null || message.isBlank()) {
      return null;
    }
    return Map.of(
        "type", "https://cassyx.dev/problems/job-failed",
        "title", "Job failed",
        "status", 500,
        "detail", message);
  }

  private static SchemaIdentity identity(String keyspace, String table) {
    if (keyspace == null || keyspace.isBlank()) {
      return null;
    }
    String qualified = table == null || table.isBlank() ? keyspace : keyspace + "." + table;
    return new SchemaIdentity(
        table == null || table.isBlank() ? "KEYSPACE" : "TABLE", keyspace, table, qualified);
  }

  /* ------------------------------------------------------------------------------ helpers */

  /** Merges the display name into the settings document without losing what is already there. */
  private String settingsDocument(String name, String settingsJson) {
    try {
      com.fasterxml.jackson.databind.node.ObjectNode node =
          settingsJson == null || settingsJson.isBlank()
              ? json.createObjectNode()
              : (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(settingsJson);
      if (name != null && !name.isBlank()) {
        node.put("name", name);
      }
      return json.writeValueAsString(node);
    } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
      LOG.warn("Cannot build the job settings document: {}", e.toString());
      return null;
    }
  }

  private JsonNode parse(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return json.readTree(value);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      return null;
    }
  }

  private static String text(JsonNode node, String field) {
    if (node == null || !node.hasNonNull(field)) {
      return null;
    }
    return node.get(field).asText();
  }

  private static Integer integer(JsonNode node, String field) {
    if (node == null || !node.hasNonNull(field) || !node.get(field).isNumber()) {
      return null;
    }
    return node.get(field).asInt();
  }

  /** Case-insensitive column lookup - see {@link #toJob}. */
  static Object cell(Map<String, Object> row, String column) {
    Object value = row.get(column);
    if (value != null || row.containsKey(column)) {
      return value;
    }
    for (Map.Entry<String, Object> entry : row.entrySet()) {
      if (entry.getKey().equalsIgnoreCase(column)) {
        return entry.getValue();
      }
    }
    return null;
  }

  static String string(Map<String, Object> row, String column) {
    Object value = cell(row, column);
    return value == null ? null : value.toString();
  }

  static long number(Map<String, Object> row, String column) {
    Object value = cell(row, column);
    return value instanceof Number n ? n.longValue() : 0;
  }

  static Instant instant(Map<String, Object> row, String column) {
    Object value = cell(row, column);
    if (value instanceof Timestamp timestamp) {
      return timestamp.toInstant();
    }
    if (value instanceof java.time.LocalDateTime local) {
      return local.toInstant(java.time.ZoneOffset.UTC);
    }
    if (value instanceof Instant instant) {
      return instant;
    }
    return null;
  }

  static String iso(Instant instant) {
    return instant == null ? null : instant.toString();
  }

  /** {@code error_message} is VARCHAR(4000); an over-long failure must not fail the UPDATE. */
  static String truncate(String error) {
    if (error == null) {
      return null;
    }
    return error.length() <= 4000 ? error : error.substring(0, 3997) + "...";
  }
}
