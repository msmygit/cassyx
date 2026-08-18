package io.cassyx.vector.impl;

import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import io.cassyx.vector.api.AnnPredicate;
import io.cassyx.vector.api.AnnQuery;
import io.cassyx.vector.api.AnnQueryBuilder;
import io.cassyx.vector.api.AnnQueryPreview;
import io.cassyx.vector.api.SimilarityFunction;
import io.cassyx.vector.api.VectorColumn;
import io.cassyx.vector.api.VectorEncoding;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.StringJoiner;

/**
 * Reference {@link AnnQueryBuilder}.
 *
 * <p>The generated statement is always shown to the user in the "Preview CQL" pane and is editable
 * before execution (plan section 4/6). Nothing here executes anything.
 */
public final class DefaultAnnQueryBuilder implements AnnQueryBuilder {

  /** Placeholder the abbreviated rendering substitutes for a vector literal. */
  private static final String ELIDED = "[… %d floats …]";

  /** Above this many dimensions, projecting the raw vector is worth warning about. */
  private static final int LARGE_VECTOR = 256;

  @Override
  public String build(AnnQuery query) {
    return render(query, false);
  }

  @Override
  public AnnQueryPreview preview(AnnQuery query) {
    return preview(query, null);
  }

  @Override
  public AnnQueryPreview preview(AnnQuery query, Set<String> saiIndexedColumns) {
    return new AnnQueryPreview(
        render(query, false),
        render(query, true),
        query.column().dimensions(),
        query.similarityColumnNames(),
        warnings(query, saiIndexedColumns),
        query.column().index());
  }

  @Override
  public SimpleStatement statement(AnnQuery query) {
    List<Object> values = new ArrayList<>();
    String cql = renderBound(query, values);
    return SimpleStatement.builder(cql).addPositionalValues(values.toArray()).build();
  }

  /* ------------------------------------------------------------------ rendering */

  private String render(AnnQuery query, boolean abbreviated) {
    VectorColumn column = query.column();
    String vectorLiteral =
        abbreviated
            ? String.format(Locale.ROOT, ELIDED, column.dimensions())
            : VectorEncoding.toJsonArray(query.queryVector());

    StringBuilder cql = new StringBuilder("SELECT ");
    cql.append(projection(query, vectorLiteral));
    cql.append(" FROM ").append(CqlLiterals.qualified(column.keyspace(), column.table()));

    if (!query.predicates().isEmpty()) {
      StringJoiner where = new StringJoiner(" AND ");
      for (AnnPredicate predicate : query.predicates()) {
        where.add(renderPredicate(predicate));
      }
      cql.append(" WHERE ").append(where);
    }

    cql.append(" ORDER BY ")
        .append(CqlLiterals.identifier(column.column()))
        .append(" ANN OF ")
        .append(vectorLiteral)
        .append(" LIMIT ")
        .append(query.limit());
    return cql.toString();
  }

  /** Same statement as {@link #render}, but with bind markers; collects the values in order. */
  private String renderBound(AnnQuery query, List<Object> values) {
    VectorColumn column = query.column();
    Object vector = VectorEncoding.toCqlVector(query.queryVector());

    List<String> selection = new ArrayList<>(selectedColumns(query));
    for (SimilarityFunction function : query.similarityProjections()) {
      selection.add(
          function.projectionFunction()
              + "("
              + CqlLiterals.identifier(column.column())
              + ", ?) AS "
              + function.scoreColumnName());
      values.add(vector);
    }

    StringBuilder cql =
        new StringBuilder("SELECT ")
            .append(String.join(", ", selection))
            .append(" FROM ")
            .append(CqlLiterals.qualified(column.keyspace(), column.table()));

    if (!query.predicates().isEmpty()) {
      StringJoiner where = new StringJoiner(" AND ");
      for (AnnPredicate predicate : query.predicates()) {
        where.add(boundPredicate(predicate, values));
      }
      cql.append(" WHERE ").append(where);
    }

    cql.append(" ORDER BY ")
        .append(CqlLiterals.identifier(column.column()))
        .append(" ANN OF ? LIMIT ")
        .append(query.limit());
    values.add(vector);
    return cql.toString();
  }

  private String projection(AnnQuery query, String vectorLiteral) {
    List<String> selection = new ArrayList<>(selectedColumns(query));
    for (SimilarityFunction function : query.similarityProjections()) {
      selection.add(
          function.projectionFunction()
              + "("
              + CqlLiterals.identifier(query.column().column())
              + ", "
              + vectorLiteral
              + ") AS "
              + function.scoreColumnName());
    }
    return String.join(", ", selection);
  }

  /**
   * {@code SELECT *} when no projection is given. With an explicit projection, the raw vector is
   * included only on request - it is large, and the grid renders a sparkline from the metadata
   * rather than the values.
   */
  private List<String> selectedColumns(AnnQuery query) {
    if (query.selectColumns().isEmpty()) {
      return List.of("*");
    }
    Set<String> columns = new LinkedHashSet<>();
    query.selectColumns().forEach(name -> columns.add(CqlLiterals.identifier(name)));
    if (query.includeVectorColumn()) {
      columns.add(CqlLiterals.identifier(query.column().column()));
    }
    return List.copyOf(columns);
  }

  private String renderPredicate(AnnPredicate predicate) {
    String column = CqlLiterals.identifier(predicate.column());
    if ("IN".equals(predicate.operator())) {
      return column + " IN " + CqlLiterals.inList((List<?>) predicate.value());
    }
    return column + " " + predicate.operator() + " " + CqlLiterals.literal(predicate.value());
  }

  private String boundPredicate(AnnPredicate predicate, List<Object> values) {
    String column = CqlLiterals.identifier(predicate.column());
    if ("IN".equals(predicate.operator())) {
      List<?> in = (List<?>) predicate.value();
      StringJoiner markers = new StringJoiner(", ", "(", ")");
      in.forEach(
          value -> {
            markers.add("?");
            values.add(value);
          });
      return column + " IN " + markers;
    }
    values.add(predicate.value());
    return column + " " + predicate.operator() + " ?";
  }

  /* ------------------------------------------------------------------- warnings */

  private List<String> warnings(AnnQuery query, Set<String> saiIndexedColumns) {
    List<String> warnings = new ArrayList<>();
    VectorColumn column = query.column();

    if (!column.annCapable()) {
      warnings.add(
          "Column "
              + column.column()
              + " has no SAI index, so ORDER BY ... ANN OF will be rejected by the cluster. "
              + "Create an SAI index on it first.");
    } else {
      SimilarityFunction indexFunction = column.similarityFunction();
      for (SimilarityFunction projection : query.similarityProjections()) {
        if (indexFunction != null && projection != indexFunction) {
          warnings.add(
              "Score column "
                  + projection.scoreColumnName()
                  + " uses "
                  + projection.cqlValue()
                  + ", but the index on "
                  + column.column()
                  + " is built with "
                  + indexFunction.cqlValue()
                  + ", so the ranking and the score will disagree.");
        }
      }
    }

    if (saiIndexedColumns != null) {
      for (AnnPredicate predicate : query.predicates()) {
        if (!saiIndexedColumns.contains(predicate.column())) {
          warnings.add(
              "Predicate column "
                  + predicate.column()
                  + " has no SAI index; a hybrid query needs one on every filtered column.");
        }
      }
    }

    if (query.includeVectorColumn() && column.dimensions() > LARGE_VECTOR) {
      warnings.add(
          "Projecting the raw "
              + column.dimensions()
              + "-dimension vector returns "
              + ((long) column.dimensions() * query.limit())
              + " floats; the grid renders a sparkline without it.");
    }
    return List.copyOf(warnings);
  }
}
