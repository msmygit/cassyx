package io.cassyx.core.api.query;

import com.datastax.oss.driver.api.core.type.DataType;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The primary-key shape of one table, plus every column's declared type.
 *
 * <p>This is the input to the plan section 7 hard rule: a result set that does not project the
 * COMPLETE primary key cannot be edited, and the refusal must name the missing columns.
 */
public record TableKeyInfo(
    String keyspace,
    String table,
    List<String> partitionKey,
    List<String> clusteringColumns,
    Map<String, DataType> columnTypes) {

  public TableKeyInfo {
    partitionKey = partitionKey == null ? List.of() : List.copyOf(partitionKey);
    clusteringColumns = clusteringColumns == null ? List.of() : List.copyOf(clusteringColumns);
    columnTypes =
        columnTypes == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(columnTypes));
  }

  /** Partition key followed by clustering columns, in CQL order - the full {@code WHERE} shape. */
  public List<String> primaryKey() {
    return java.util.stream.Stream.concat(partitionKey.stream(), clusteringColumns.stream()).toList();
  }

  public boolean isPrimaryKeyColumn(String column) {
    return primaryKey().stream().anyMatch(c -> c.equalsIgnoreCase(column));
  }
}
