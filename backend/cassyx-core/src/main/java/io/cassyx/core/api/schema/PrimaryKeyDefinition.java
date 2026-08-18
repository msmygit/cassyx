package io.cassyx.core.api.schema;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** Partition key plus optional clustering key, in declaration order. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PrimaryKeyDefinition(
    List<String> partitionKey, List<ClusteringKeyColumn> clusteringKey) {

  public PrimaryKeyDefinition {
    partitionKey = partitionKey == null ? List.of() : List.copyOf(partitionKey);
    clusteringKey = clusteringKey == null ? List.of() : List.copyOf(clusteringKey);
  }

  /** Every primary-key column name, partition columns first. */
  public List<String> allColumns() {
    return java.util.stream.Stream.concat(
            partitionKey.stream(), clusteringKey.stream().map(ClusteringKeyColumn::column))
        .toList();
  }
}
