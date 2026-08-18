package io.cassyx.api.bulk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.cassyx.api.bulk.DsbulkDtos.JobTemplate;
import io.cassyx.api.bulk.DsbulkDtos.JobTemplateRequest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Reusable job templates: persisted DSBulk overrides (plan section 5.3, "persist overrides as
 * reusable job templates").
 *
 * <p>The point is that tuning is expensive knowledge. Somebody works out that this cluster wants
 * 64 concurrent files and 16C splits; a template means the next person does not have to rediscover
 * it, and the derived defaults still fill in everything they did not have an opinion about.
 */
@Repository
public class DsbulkTemplateRepository {

  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  private final Clock clock;

  public DsbulkTemplateRepository(JdbcTemplate jdbc, ObjectMapper json, Clock clock) {
    this.jdbc = jdbc;
    this.json = json;
    this.clock = clock;
  }

  public List<JobTemplate> list(String operation) {
    String sql = "SELECT * FROM cassyx_job_template"
        + (operation == null || operation.isBlank() ? "" : " WHERE type = ?")
        + " ORDER BY name";
    List<Map<String, Object>> rows = operation == null || operation.isBlank()
        ? jdbc.queryForList(sql)
        : jdbc.queryForList(sql, operation);
    return rows.stream().map(this::toTemplate).toList();
  }

  public Optional<JobTemplate> find(String id) {
    return jdbc.queryForList("SELECT * FROM cassyx_job_template WHERE id = ?", id).stream()
        .findFirst()
        .map(this::toTemplate);
  }

  public JobTemplate create(JobTemplateRequest request) {
    String id = UUID.randomUUID().toString();
    Instant now = clock.instant();
    jdbc.update(
        "INSERT INTO cassyx_job_template (id, name, type, engine, settings_json, created_at, updated_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?)",
        id, request.name(), request.operation(), request.engine() == null ? "DSBULK" : request.engine(),
        document(request), Timestamp.from(now), Timestamp.from(now));
    return find(id).orElseThrow();
  }

  public Optional<JobTemplate> update(String id, JobTemplateRequest request) {
    int updated = jdbc.update(
        "UPDATE cassyx_job_template SET name = ?, type = ?, engine = ?, settings_json = ?, updated_at = ? "
            + "WHERE id = ?",
        request.name(), request.operation(), request.engine() == null ? "DSBULK" : request.engine(),
        document(request), Timestamp.from(clock.instant()), id);
    return updated == 0 ? Optional.empty() : find(id);
  }

  public boolean delete(String id) {
    return jdbc.update("DELETE FROM cassyx_job_template WHERE id = ?", id) > 0;
  }

  /**
   * Template settings first, the caller's own overrides second.
   *
   * <p>That order is the whole contract of a template: it is a starting point a user can still
   * disagree with, never a ceiling. A template that could not be overridden would just be a worse
   * version of the derived defaults.
   */
  public Map<String, String> merge(String templateId, Map<String, String> overrides) {
    Map<String, String> merged = new LinkedHashMap<>();
    if (templateId != null && !templateId.isBlank()) {
      find(templateId).ifPresent(template ->
          merged.putAll(DsbulkSettingsFlattener.flatten(template.dsbulkSettings())));
    }
    merged.putAll(overrides);
    return merged;
  }

  private String document(JobTemplateRequest request) {
    Map<String, Object> document = new LinkedHashMap<>();
    document.put("description", request.description());
    document.put("format", request.format());
    document.put("dsbulkSettings", request.dsbulkSettings());
    try {
      return json.writeValueAsString(document);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalArgumentException("Template settings are not serialisable JSON", e);
    }
  }

  private JobTemplate toTemplate(Map<String, Object> row) {
    JsonNode document = readDocument(row.get("SETTINGS_JSON") == null
        ? row.get("settings_json") : row.get("SETTINGS_JSON"));
    return new JobTemplate(
        string(row, "ID"),
        string(row, "NAME"),
        document.path("description").isMissingNode() ? null : document.path("description").asText(null),
        string(row, "TYPE"),
        document.path("format").isMissingNode() ? null : document.path("format").asText(null),
        string(row, "ENGINE"),
        document.get("dsbulkSettings"),
        instant(row, "CREATED_AT"),
        instant(row, "UPDATED_AT"));
  }

  private JsonNode readDocument(Object value) {
    try {
      return value == null ? json.createObjectNode() : json.readTree(String.valueOf(value));
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      return json.createObjectNode();
    }
  }

  /** H2 upper-cases unquoted column labels; accept either spelling so the code survives a move. */
  private static String string(Map<String, Object> row, String column) {
    Object value = row.containsKey(column) ? row.get(column) : row.get(column.toLowerCase(java.util.Locale.ROOT));
    return value == null ? null : String.valueOf(value);
  }

  private static String instant(Map<String, Object> row, String column) {
    Object value = row.containsKey(column) ? row.get(column) : row.get(column.toLowerCase(java.util.Locale.ROOT));
    if (value instanceof Timestamp timestamp) {
      return timestamp.toInstant().toString();
    }
    return value == null ? null : String.valueOf(value);
  }
}
