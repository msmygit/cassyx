package io.cassyx.vector.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import io.cassyx.vector.api.SimilarityFunction;
import io.cassyx.vector.api.SimilarityScores;
import io.cassyx.vector.api.VectorColumnDefinition;
import io.cassyx.vector.api.VectorException;
import io.cassyx.vector.api.VectorFactory;
import io.cassyx.vector.api.VectorService;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The parts of {@link VectorService} that need no cluster. */
class DefaultVectorServiceTest {

  private final VectorService service = VectorFactory.vectorService();

  @Test
  void generatesAlterTableAddForAVectorColumn() {
    assertThat(service.addColumnCql("demo", "docs", VectorColumnDefinition.of("embedding", 1536)))
        .containsExactly("ALTER TABLE demo.docs ADD embedding vector<float, 1536>");
  }

  @Test
  void addsTheIndexInTheSameOperationWhenRequested() {
    List<String> statements =
        service.addColumnCql(
            "demo",
            "docs",
            new VectorColumnDefinition(
                "embedding", 3, "float", true, null, SimilarityFunction.COSINE, "bert"));

    assertThat(statements)
        .containsExactly(
            "ALTER TABLE demo.docs ADD embedding vector<float, 3>",
            "CREATE CUSTOM INDEX IF NOT EXISTS docs_embedding_idx ON demo.docs (embedding) "
                + "USING 'StorageAttachedIndex' "
                + "WITH OPTIONS = {'similarity_function': 'cosine', 'source_model': 'bert'}");
  }

  @Test
  void quotesTheTableAndColumnWhenItHasTo() {
    assertThat(service.addColumnCql("demo", "Docs", VectorColumnDefinition.of("Embedding", 2)))
        .containsExactly("ALTER TABLE demo.\"Docs\" ADD \"Embedding\" vector<float, 2>");
  }

  @Test
  void comparesTwoVectorsUnderEveryRequestedFunction() {
    SimilarityScores scores =
        service.compare(
            List.of(1f, 0f, 0f),
            List.of(0f, 1f, 0f),
            List.of(SimilarityFunction.COSINE, SimilarityFunction.EUCLIDEAN));

    assertThat(scores.dimensions()).isEqualTo(3);
    assertThat(scores.leftMagnitude()).isCloseTo(1.0d, within(1e-9));
    assertThat(scores.rightMagnitude()).isCloseTo(1.0d, within(1e-9));
    assertThat(scores.scoresByCqlName()).containsOnlyKeys("cosine", "euclidean");
    assertThat(scores.scoresByCqlName().get("cosine")).isCloseTo(0.5d, within(1e-9));
    assertThat(scores.scoresByCqlName().get("euclidean")).isCloseTo(1.0d / 3.0d, within(1e-9));
  }

  @Test
  void defaultsToCosineWhenNoFunctionIsRequested() {
    assertThat(service.compare(List.of(1f, 1f), List.of(1f, 1f), null).scores())
        .containsOnlyKeys(SimilarityFunction.COSINE);
    assertThat(service.compare(List.of(1f, 1f), List.of(1f, 1f), List.of()).scores())
        .containsOnlyKeys(SimilarityFunction.COSINE);
  }

  @Test
  void computesMagnitude() {
    assertThat(service.magnitude(List.of(3f, 4f))).isCloseTo(5.0d, within(1e-9));
    assertThatThrownBy(() -> service.magnitude(null)).isInstanceOf(VectorException.class);
  }

  @Test
  void rejectsMismatchedComparisons() {
    assertThatThrownBy(() -> service.compare(List.of(1f), List.of(1f, 2f), null))
        .isInstanceOf(VectorException.class)
        .hasMessageContaining("Dimension mismatch");
  }
}
