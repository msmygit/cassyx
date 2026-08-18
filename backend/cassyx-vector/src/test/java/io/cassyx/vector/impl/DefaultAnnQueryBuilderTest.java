package io.cassyx.vector.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.cassyx.vector.api.AnnQuery;
import io.cassyx.vector.api.SimilarityFunction;
import io.cassyx.vector.api.VectorColumn;
import io.cassyx.vector.api.VectorFactory;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DefaultAnnQueryBuilderTest {

  private static final VectorColumn COLUMN = new VectorColumn("demo", "docs", "embedding", 3);

  @Test
  void buildsAnnStatement() {
    String cql =
        VectorFactory.annQueryBuilder()
            .build(new AnnQuery(COLUMN, List.of(0.1f, 0.2f, 0.3f), 5, List.of(), Map.of()));

    assertThat(cql)
        .isEqualTo(
            "SELECT * FROM demo.docs ORDER BY embedding ANN OF [0.1, 0.2, 0.3] LIMIT 5");
  }

  @Test
  void buildsHybridStatementWithScoreProjection() {
    String cql =
        VectorFactory.annQueryBuilder()
            .build(
                new AnnQuery(
                    COLUMN,
                    List.of(0.1f, 0.2f, 0.3f),
                    2,
                    List.of("similarity_cosine(embedding, [0.1, 0.2, 0.3]) AS score"),
                    Map.of("lang", "= 'en'")));

    assertThat(cql).contains("WHERE lang = 'en'").contains("AS score").endsWith("LIMIT 2");
  }

  @Test
  void rejectsDimensionMismatch() {
    assertThatThrownBy(() -> new AnnQuery(COLUMN, List.of(0.1f), 5, List.of(), Map.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("dimensions");
    assertThatThrownBy(() -> new VectorColumn("demo", "docs", "embedding", 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rendersVectorTypeAndSaiDdl() {
    assertThat(COLUMN.cqlType()).isEqualTo("vector<float, 3>");
    assertThat(
            VectorFactory.saiIndexManager()
                .createIndexCql(
                    "demo", "docs", "embedding", "docs_embedding_idx",
                    SimilarityFunction.COSINE, Map.of()))
        .contains("USING 'StorageAttachedIndex'")
        .contains("'similarity_function': 'cosine'");
    assertThat(VectorFactory.saiIndexManager().dropIndexCql("demo", "docs_embedding_idx"))
        .isEqualTo("DROP INDEX IF EXISTS demo.docs_embedding_idx");
    assertThat(SimilarityFunction.DOT_PRODUCT.projectionFunction())
        .isEqualTo("similarity_dot_product");
  }
}
