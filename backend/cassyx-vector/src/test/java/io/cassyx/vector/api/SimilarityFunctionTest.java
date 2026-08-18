package io.cassyx.vector.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SimilarityFunctionTest {

  @ParameterizedTest
  @CsvSource({
    "COSINE,cosine,similarity_cosine,cosine_score",
    "DOT_PRODUCT,dot_product,similarity_dot_product,dot_product_score",
    "EUCLIDEAN,euclidean,similarity_euclidean,euclidean_score"
  })
  void rendersCqlNames(SimilarityFunction function, String cql, String projection, String score) {
    assertThat(function.cqlValue()).isEqualTo(cql);
    assertThat(function.projectionFunction()).isEqualTo(projection);
    assertThat(function.scoreColumnName()).isEqualTo(score);
    assertThat(SimilarityFunction.fromCql(cql)).isEqualTo(function);
    assertThat(SimilarityFunction.fromCql("  " + cql.toUpperCase(java.util.Locale.ROOT) + " "))
        .isEqualTo(function);
  }

  @Test
  void rejectsUnknownSimilarityFunctions() {
    assertThatThrownBy(() -> SimilarityFunction.fromCql("manhattan"))
        .isInstanceOf(VectorException.class)
        .hasMessageContaining("dot_product");
    assertThatThrownBy(() -> SimilarityFunction.fromCql(null))
        .isInstanceOf(VectorException.class);
    assertThatThrownBy(() -> SimilarityFunction.fromCql("  "))
        .isInstanceOf(VectorException.class);
  }

  @Test
  void identicalUnitVectorsScoreOne() {
    // Cassandra normalises every similarity_* function into [0, 1] with 1 meaning identical - but
    // dot_product only reaches 1 for UNIT vectors, which is exactly why the source_model /
    // normalisation options exist. Using an unnormalised vector here would look like a bug.
    // 0.6f/0.8f are not exactly representable, so a unit vector is only unit to ~1e-7. The
    // tolerance is float precision, not slack in the formula - SimilarityParityIT pins the
    // formulas against the cluster itself.
    float[] unit = {0.6f, -0.8f};
    for (SimilarityFunction function : SimilarityFunction.values()) {
      assertThat(function.score(unit, unit)).as(function.name()).isCloseTo(1.0d, within(1e-6));
    }

    float[] unnormalised = {3f, 4f};
    assertThat(SimilarityFunction.COSINE.score(unnormalised, unnormalised))
        .isCloseTo(1.0d, within(1e-9));
    assertThat(SimilarityFunction.EUCLIDEAN.score(unnormalised, unnormalised))
        .isCloseTo(1.0d, within(1e-9));
    assertThat(SimilarityFunction.DOT_PRODUCT.score(unnormalised, unnormalised))
        .as("dot_product is unbounded above for non-unit vectors")
        .isCloseTo(13.0d, within(1e-9));
  }

  @Test
  void opposedUnitVectorsScoreZeroUnderCosine() {
    assertThat(SimilarityFunction.COSINE.score(new float[] {1f, 0f}, new float[] {-1f, 0f}))
        .isCloseTo(0.0d, within(1e-9));
    // Orthogonal is the midpoint, not zero: (1 + 0) / 2.
    assertThat(SimilarityFunction.COSINE.score(new float[] {1f, 0f}, new float[] {0f, 1f}))
        .isCloseTo(0.5d, within(1e-9));
  }

  @Test
  void dotProductAndEuclideanUseCassandrasNormalisation() {
    // (1 + (3*1 + 4*0)) / 2
    assertThat(SimilarityFunction.DOT_PRODUCT.score(new float[] {3f, 4f}, new float[] {1f, 0f}))
        .isCloseTo(2.0d, within(1e-9));
    // 1 / (1 + |(0,0) - (3,4)|²) = 1 / 26
    assertThat(SimilarityFunction.EUCLIDEAN.score(new float[] {0f, 0f}, new float[] {3f, 4f}))
        .isCloseTo(1.0d / 26.0d, within(1e-9));
  }

  @Test
  void zeroVectorsDoNotDivideByZero() {
    assertThat(SimilarityFunction.COSINE.score(new float[] {0f, 0f}, new float[] {1f, 1f}))
        .isCloseTo(0.5d, within(1e-9));
  }

  @Test
  void rejectsMismatchedOrEmptyVectors() {
    assertThatThrownBy(
            () -> SimilarityFunction.COSINE.score(new float[] {1f}, new float[] {1f, 2f}))
        .isInstanceOf(VectorException.class)
        .hasMessageContaining("Dimension mismatch");
    assertThatThrownBy(() -> SimilarityFunction.COSINE.score(new float[] {}, new float[] {}))
        .isInstanceOf(VectorException.class)
        .hasMessageContaining("at least one dimension");
    assertThatThrownBy(() -> SimilarityFunction.COSINE.score(null, new float[] {1f}))
        .isInstanceOf(VectorException.class);
  }
}
