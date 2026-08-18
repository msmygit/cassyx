package io.cassyx.vector.impl;

import io.cassyx.vector.api.AnnQuery;
import io.cassyx.vector.api.AnnQueryBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

/** Reference {@link AnnQueryBuilder}. Always shown to the user before execution ("Preview CQL"). */
public final class DefaultAnnQueryBuilder implements AnnQueryBuilder {

  @Override
  public String build(AnnQuery query) {
    List<String> selection = new ArrayList<>();
    selection.add("*");
    selection.addAll(query.projections());

    StringBuilder cql = new StringBuilder("SELECT ");
    cql.append(String.join(", ", selection));
    cql.append(" FROM ")
        .append(query.column().keyspace())
        .append('.')
        .append(query.column().table());

    if (!query.predicates().isEmpty()) {
      StringJoiner where = new StringJoiner(" AND ");
      query.predicates().forEach((column, predicate) -> where.add(column + " " + predicate));
      cql.append(" WHERE ").append(where);
    }

    cql.append(" ORDER BY ")
        .append(query.column().column())
        .append(" ANN OF ")
        .append(formatVector(query.queryVector()))
        .append(" LIMIT ")
        .append(query.limit());
    return cql.toString();
  }

  /** Visible for testing: vectors are CQL list literals of floats. */
  public static String formatVector(List<Float> vector) {
    StringJoiner joiner = new StringJoiner(", ", "[", "]");
    vector.forEach(v -> joiner.add(String.valueOf(v)));
    return joiner.toString();
  }
}
