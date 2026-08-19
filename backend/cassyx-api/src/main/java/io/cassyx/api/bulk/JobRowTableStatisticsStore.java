package io.cassyx.api.bulk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.cassyx.core.api.schema.TableStatistics;
import io.cassyx.core.api.schema.TableStatisticsStore;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The Statistics tab's snapshot store, backed by the job row that produced it (plan sections 4 and
 * 5.4).
 *
 * <p>This replaces the process-local map the schema workstream shipped as a placeholder. The
 * lifetimes did not match: a COUNT job is a persisted row in {@code cassyx_job} that survives a
 * restart, while the map did not, so after every restart {@code GET .../statistics} answered 404
 * for tables that had demonstrably been counted - and offered to re-run a full cluster-wide scan to
 * recover a number already sitting in the database.
 *
 * <p>So the job row is the source of truth and the map is only a read cache in front of it. That
 * also means the snapshot is reachable at all: nothing ever wrote to the placeholder store, which
 * is why the tab was permanently empty regardless of how many counts had run.
 */
@Component
public class JobRowTableStatisticsStore implements TableStatisticsStore {

  private static final Logger LOG = LoggerFactory.getLogger(JobRowTableStatisticsStore.class);

  private final DsbulkJobRepository jobs;
  private final ObjectMapper json;
  private final Map<String, TableStatistics> cache = new ConcurrentHashMap<>();

  public JobRowTableStatisticsStore(DsbulkJobRepository jobs, ObjectMapper json) {
    this.jobs = jobs;
    this.json = json;
  }

  @Override
  public Optional<TableStatistics> find(String connectionId, String keyspace, String table) {
    TableStatistics cached = cache.get(key(connectionId, keyspace, table));
    if (cached != null) {
      return Optional.of(cached);
    }
    return jobs.latestCount(connectionId, keyspace, table)
        .flatMap(this::readSnapshot)
        .map(statistics -> {
          cache.put(key(connectionId, keyspace, table), statistics);
          return statistics;
        });
  }

  @Override
  public void put(String connectionId, TableStatistics statistics) {
    cache.put(
        key(connectionId, statistics.identity().keyspace(), statistics.identity().table()),
        statistics);
  }

  /**
   * Pulls the snapshot out of the job's {@code settings_json} reproducibility document.
   *
   * <p>A row whose document is unreadable or predates the statistics field is treated as "no
   * statistics", not as an error: the caller's 404 already means "run a count", which is the right
   * instruction either way.
   */
  private Optional<TableStatistics> readSnapshot(Map<String, Object> row) {
    Object settings = column(row, "settings_json");
    if (settings == null) {
      return Optional.empty();
    }
    try {
      JsonNode node = json.readTree(String.valueOf(settings)).get("statistics");
      if (node == null || node.isNull()) {
        return Optional.empty();
      }
      return Optional.of(json.treeToValue(node, DsbulkDtos.TableStatistics.class).toCore());
    } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
      LOG.warn("Cannot read the statistics snapshot from job {}: {}", column(row, "id"), e.toString());
      return Optional.empty();
    }
  }

  /** {@code SELECT *} keys are upper case on H2 and lower case on PostgreSQL; match either. */
  private static Object column(Map<String, Object> row, String name) {
    for (Map.Entry<String, Object> entry : row.entrySet()) {
      if (entry.getKey().equalsIgnoreCase(name)) {
        return entry.getValue();
      }
    }
    return null;
  }

  private static String key(String connectionId, String keyspace, String table) {
    return connectionId + " " + keyspace + " " + table;
  }
}
