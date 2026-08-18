package io.cassyx.vector.api;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * An ANN query (plan section 6).
 *
 * @param predicates optional SAI predicates for a hybrid query, as raw CQL fragments
 * @param projections extra projections, e.g. a similarity score column
 */
public record AnnQuery(
    VectorColumn column,
    List<Float> queryVector,
    int limit,
    List<String> projections,
    Map<String, String> predicates) {

  public AnnQuery {
    Objects.requireNonNull(column, "column");
    queryVector = queryVector == null ? List.of() : List.copyOf(queryVector);
    projections = projections == null ? List.of() : List.copyOf(projections);
    predicates = predicates == null ? Map.of() : Map.copyOf(predicates);
    if (limit <= 0) {
      limit = 10;
    }
    if (queryVector.size() != column.dimensions()) {
      throw new IllegalArgumentException(
          "Query vector has "
              + queryVector.size()
              + " dimensions but column "
              + column.column()
              + " expects "
              + column.dimensions());
    }
  }
}
