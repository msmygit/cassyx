package io.cassyx.core.api;

import java.util.List;

/** Immutable table descriptor. */
public record TableSummary(
    String keyspace,
    String name,
    List<String> partitionKey,
    List<String> clusteringColumns,
    int columnCount) {

  public TableSummary {
    partitionKey = partitionKey == null ? List.of() : List.copyOf(partitionKey);
    clusteringColumns = clusteringColumns == null ? List.of() : List.copyOf(clusteringColumns);
  }

  public String qualifiedName() {
    return keyspace + "." + name;
  }
}
