package io.cassyx.core.impl.schema;

import io.cassyx.core.api.schema.TableStatistics;
import io.cassyx.core.api.schema.TableStatisticsStore;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-local statistics cache.
 *
 * <p>Empty until workstream E's COUNT job writes into it, which is exactly the contract's
 * "no statistics computed yet" 404 for the STATISTICS tab (plan sections 4 and 5.4).
 */
public final class InMemoryTableStatisticsStore implements TableStatisticsStore {

  private final Map<String, TableStatistics> snapshots = new ConcurrentHashMap<>();

  @Override
  public Optional<TableStatistics> find(String connectionId, String keyspace, String table) {
    return Optional.ofNullable(snapshots.get(key(connectionId, keyspace, table)));
  }

  @Override
  public void put(String connectionId, TableStatistics statistics) {
    snapshots.put(
        key(connectionId, statistics.identity().keyspace(), statistics.identity().table()),
        statistics);
  }

  private static String key(String connectionId, String keyspace, String table) {
    return connectionId + " " + keyspace + " " + table;
  }
}
