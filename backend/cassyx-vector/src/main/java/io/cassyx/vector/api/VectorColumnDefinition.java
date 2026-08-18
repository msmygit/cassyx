package io.cassyx.vector.api;

/**
 * Request to add a {@code vector<float, N>} column, optionally indexing it in the same operation.
 *
 * @param createIndex also emit a {@code CREATE CUSTOM INDEX} for the new column
 * @param sourceModel SAI {@code source_model} hint (e.g. {@code openai-v3-large}) which tunes the
 *     index's internal parameters for a known embedding model
 */
public record VectorColumnDefinition(
    String name,
    int dimensions,
    String elementType,
    boolean createIndex,
    String indexName,
    SimilarityFunction similarityFunction,
    String sourceModel) {

  /**
   * Cassandra's own ceiling for a vector column. Rejecting here rather than at the cluster keeps the
   * error legible.
   */
  public static final int MAX_DIMENSIONS = 8192;

  public VectorColumnDefinition {
    if (name == null || name.isBlank()) {
      throw new VectorException("Column name is required");
    }
    if (dimensions <= 0 || dimensions > MAX_DIMENSIONS) {
      throw new VectorException(
          "Vector dimensions must be between 1 and " + MAX_DIMENSIONS + ", got " + dimensions);
    }
    elementType =
        elementType == null || elementType.isBlank() ? VectorColumn.FLOAT : elementType.trim();
    if (!VectorColumn.FLOAT.equals(elementType)) {
      throw new VectorException(
          "Cassandra only supports vector<float, N>; got element type '" + elementType + "'");
    }
  }

  public static VectorColumnDefinition of(String name, int dimensions) {
    return new VectorColumnDefinition(name, dimensions, VectorColumn.FLOAT, false, null, null, null);
  }

  public String cqlType() {
    return "vector<" + elementType + ", " + dimensions + ">";
  }

  /** Index name to use, defaulting to {@code <table>_<column>_idx}. */
  public String resolveIndexName(String table) {
    return indexName == null || indexName.isBlank() ? table + "_" + name + "_idx" : indexName;
  }

  /** The SAI definition implied by {@link #createIndex()}. */
  public SaiIndexDefinition toIndexDefinition(String table) {
    return SaiIndexDefinition.builder(resolveIndexName(table), name)
        .similarityFunction(similarityFunction == null ? SimilarityFunction.COSINE : similarityFunction)
        .sourceModel(sourceModel)
        .build();
  }
}
