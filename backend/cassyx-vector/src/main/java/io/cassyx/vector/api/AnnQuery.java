package io.cassyx.vector.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * An ANN query (plan section 6):
 *
 * <pre>{@code SELECT ... ORDER BY <col> ANN OF [...] LIMIT k}</pre>
 *
 * @param selectColumns projection; empty means {@code SELECT *}
 * @param predicates SAI predicates ANDed into the {@code WHERE} clause for a hybrid query
 * @param similarityProjections extra {@code similarity_*} score columns, sortable in the grid
 * @param includeVectorColumn whether the raw vector is projected; off by default because it is
 *     large and the grid renders it as a sparkline anyway
 */
public record AnnQuery(
    VectorColumn column,
    List<Float> queryVector,
    int limit,
    List<String> selectColumns,
    List<AnnPredicate> predicates,
    List<SimilarityFunction> similarityProjections,
    boolean includeVectorColumn) {

  /** ANN queries always require a limit; this is the fallback when none is supplied. */
  public static final int DEFAULT_LIMIT = 10;

  /** Contract ceiling on {@code k}. */
  public static final int MAX_LIMIT = 10_000;

  public AnnQuery {
    Objects.requireNonNull(column, "column");
    // Checked BEFORE List.copyOf, which throws a bare NullPointerException on a null element.
    if (queryVector != null && queryVector.stream().anyMatch(Objects::isNull)) {
      throw new VectorException("The query vector contains a null element");
    }
    queryVector = queryVector == null ? List.of() : List.copyOf(queryVector);
    selectColumns = selectColumns == null ? List.of() : List.copyOf(selectColumns);
    predicates = predicates == null ? List.of() : List.copyOf(predicates);
    similarityProjections =
        similarityProjections == null ? List.of() : List.copyOf(similarityProjections);
    if (limit <= 0) {
      limit = DEFAULT_LIMIT;
    }
    if (limit > MAX_LIMIT) {
      throw new VectorException("LIMIT must be at most " + MAX_LIMIT + ", got " + limit);
    }
    if (queryVector.size() != column.dimensions()) {
      throw new VectorException(
          "Query vector has "
              + queryVector.size()
              + " dimensions but column "
              + column.column()
              + " expects "
              + column.dimensions());
    }
  }

  public static Builder builder(VectorColumn column, List<Float> queryVector) {
    return new Builder(column, queryVector);
  }

  /** The query vector as a primitive array, for the similarity maths. */
  public float[] queryVectorArray() {
    float[] values = new float[queryVector.size()];
    for (int i = 0; i < values.length; i++) {
      values[i] = queryVector.get(i);
    }
    return values;
  }

  /** Names of the generated score columns, in projection order. */
  public List<String> similarityColumnNames() {
    return similarityProjections.stream().map(SimilarityFunction::scoreColumnName).toList();
  }

  /** Fluent builder - the record has seven components and most calls set three of them. */
  public static final class Builder {

    private final VectorColumn column;
    private final List<Float> queryVector;
    private int limit = DEFAULT_LIMIT;
    private final List<String> selectColumns = new ArrayList<>();
    private final List<AnnPredicate> predicates = new ArrayList<>();
    private final List<SimilarityFunction> similarityProjections = new ArrayList<>();
    private boolean includeVectorColumn;

    private Builder(VectorColumn column, List<Float> queryVector) {
      this.column = column;
      this.queryVector = queryVector;
    }

    public Builder limit(int value) {
      this.limit = value;
      return this;
    }

    public Builder select(List<String> columns) {
      if (columns != null) {
        this.selectColumns.addAll(columns);
      }
      return this;
    }

    public Builder where(AnnPredicate predicate) {
      this.predicates.add(predicate);
      return this;
    }

    public Builder where(List<AnnPredicate> values) {
      if (values != null) {
        this.predicates.addAll(values);
      }
      return this;
    }

    public Builder score(SimilarityFunction function) {
      this.similarityProjections.add(function);
      return this;
    }

    public Builder score(List<SimilarityFunction> functions) {
      if (functions != null) {
        this.similarityProjections.addAll(functions);
      }
      return this;
    }

    public Builder includeVectorColumn(boolean value) {
      this.includeVectorColumn = value;
      return this;
    }

    public AnnQuery build() {
      return new AnnQuery(
          column,
          queryVector,
          limit,
          selectColumns,
          predicates,
          similarityProjections,
          includeVectorColumn);
    }
  }
}
