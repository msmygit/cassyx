package io.cassyx.bulk.api;

import com.datastax.oss.driver.api.core.CqlSession;
import java.util.List;
import java.util.Map;

/**
 * Row counts and partition statistics (plan section 5.4): powers the table Statistics tab and
 * pre-flight estimates for export jobs.
 */
public interface CountEngine {

  CountResult count(CqlSession session, String keyspace, String table);

  /**
   * @param perRange rows per token range
   * @param largestPartitions top-N partitions by row count, key rendered as text
   */
  record CountResult(
      long totalRows, Map<String, Long> perRange, List<PartitionStat> largestPartitions) {

    public CountResult {
      perRange = perRange == null ? Map.of() : Map.copyOf(perRange);
      largestPartitions =
          largestPartitions == null ? List.of() : List.copyOf(largestPartitions);
    }
  }

  record PartitionStat(String partitionKey, long rows) {}
}
