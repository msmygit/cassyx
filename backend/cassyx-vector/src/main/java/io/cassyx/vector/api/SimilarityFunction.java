package io.cassyx.vector.api;

import java.util.Locale;

/**
 * SAI vector index similarity functions (plan section 6).
 *
 * <p>The scoring formulas mirror Cassandra's own {@code similarity_*} CQL functions, which delegate
 * to jvector's {@code VectorSimilarityFunction} and normalise every score into {@code [0, 1]} where
 * {@code 1} means "identical". Computing them here rather than in the browser means the inspector
 * panel never ships 1536 floats to the client and never disagrees with the cluster. Parity with a
 * live cluster is asserted in {@code AnnQueryIT.similarityScoresMatchTheCluster}.
 */
public enum SimilarityFunction {

  /** {@code (1 + cos(a, b)) / 2}. Cassandra's default. */
  COSINE,

  /** {@code (1 + a.b) / 2}. */
  DOT_PRODUCT,

  /** {@code 1 / (1 + |a - b|^2)}. */
  EUCLIDEAN;

  /** The value written into {@code WITH OPTIONS = {'similarity_function': '...'}}. */
  public String cqlValue() {
    return name().toLowerCase(Locale.ROOT);
  }

  /** Projection function that renders the score column in the grid. */
  public String projectionFunction() {
    return "similarity_" + cqlValue();
  }

  /** Alias used for the generated score column, e.g. {@code cosine_score}. */
  public String scoreColumnName() {
    return cqlValue() + "_score";
  }

  /**
   * Parses {@code cosine} / {@code dot_product} / {@code euclidean}, case-insensitively.
   *
   * @throws VectorException if the value is not a supported similarity function
   */
  public static SimilarityFunction fromCql(String value) {
    if (value == null || value.isBlank()) {
      throw new VectorException("similarity_function is required");
    }
    String normalised = value.trim().toUpperCase(Locale.ROOT);
    for (SimilarityFunction function : values()) {
      if (function.name().equals(normalised)) {
        return function;
      }
    }
    throw new VectorException(
        "Unsupported similarity function '"
            + value
            + "'; expected cosine, dot_product or euclidean");
  }

  /**
   * Scores two vectors exactly as the matching CQL {@code similarity_*} function would.
   *
   * @throws VectorException if the vectors have different dimensions
   */
  public double score(float[] left, float[] right) {
    requireSameDimensions(left, right);
    return switch (this) {
      case COSINE -> (1.0d + rawCosine(left, right)) / 2.0d;
      case DOT_PRODUCT -> (1.0d + dot(left, right)) / 2.0d;
      case EUCLIDEAN -> 1.0d / (1.0d + squaredDistance(left, right));
    };
  }

  private static void requireSameDimensions(float[] left, float[] right) {
    if (left == null || right == null) {
      throw new VectorException("Both vectors are required");
    }
    if (left.length != right.length) {
      throw new VectorException(
          "Dimension mismatch: left has " + left.length + ", right has " + right.length);
    }
    if (left.length == 0) {
      throw new VectorException("Vectors must have at least one dimension");
    }
  }

  private static double dot(float[] left, float[] right) {
    double sum = 0.0d;
    for (int i = 0; i < left.length; i++) {
      sum += (double) left[i] * (double) right[i];
    }
    return sum;
  }

  private static double squaredDistance(float[] left, float[] right) {
    double sum = 0.0d;
    for (int i = 0; i < left.length; i++) {
      double delta = (double) left[i] - (double) right[i];
      sum += delta * delta;
    }
    return sum;
  }

  private static double rawCosine(float[] left, float[] right) {
    double magnitudes = VectorEncoding.magnitude(left) * VectorEncoding.magnitude(right);
    // A zero vector has no direction; Cassandra rejects it at insert time, so this is defensive.
    return magnitudes == 0.0d ? 0.0d : dot(left, right) / magnitudes;
  }
}
