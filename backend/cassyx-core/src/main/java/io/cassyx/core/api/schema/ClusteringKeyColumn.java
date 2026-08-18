package io.cassyx.core.api.schema;

import com.fasterxml.jackson.annotation.JsonInclude;

/** A clustering column plus its order. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ClusteringKeyColumn(String column, ClusteringOrder order) {

  public ClusteringKeyColumn {
    order = order == null ? ClusteringOrder.ASC : order;
  }

  public static ClusteringKeyColumn asc(String column) {
    return new ClusteringKeyColumn(column, ClusteringOrder.ASC);
  }
}
