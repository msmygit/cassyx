package io.cassyx.core.api.schema;

import java.util.Optional;

/**
 * Cached statistics snapshots (plan section 5.4).
 *
 * <p>The schema workstream reads this store; workstream E's COUNT job writes to it. Until a count
 * has run, {@link #find} is empty and the STATISTICS tab gets the contract's 404.
 */
public interface TableStatisticsStore {

  Optional<TableStatistics> find(String connectionId, String keyspace, String table);

  void put(String connectionId, TableStatistics statistics);
}
