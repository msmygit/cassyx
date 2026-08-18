package io.cassyx.vector.api;

/** SAI vector index similarity functions (plan section 6). */
public enum SimilarityFunction {
  COSINE,
  DOT_PRODUCT,
  EUCLIDEAN;

  public String cqlValue() {
    return name().toLowerCase(java.util.Locale.ROOT);
  }

  /** Projection function that renders the score column in the grid. */
  public String projectionFunction() {
    return "similarity_" + cqlValue();
  }
}
