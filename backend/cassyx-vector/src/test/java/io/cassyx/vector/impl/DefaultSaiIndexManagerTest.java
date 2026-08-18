package io.cassyx.vector.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.cassyx.vector.api.SaiIndexDefinition;
import io.cassyx.vector.api.SaiIndexManager;
import io.cassyx.vector.api.SimilarityFunction;
import io.cassyx.vector.api.VectorException;
import io.cassyx.vector.api.VectorFactory;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** DDL generation. Nothing here talks to a cluster - see {@code SaiIndexLifecycleIT} for that. */
class DefaultSaiIndexManagerTest {

  private final SaiIndexManager manager = VectorFactory.saiIndexManager();

  @Test
  void createsAVectorIndexWithSimilarityAndSourceModel() {
    String cql =
        manager.createIndexCql(
            "demo",
            "doc_embeddings",
            SaiIndexDefinition.builder("doc_embeddings_ann", "embedding")
                .similarityFunction(SimilarityFunction.COSINE)
                .sourceModel("openai-v3-large")
                .build());

    assertThat(cql)
        .isEqualTo(
            "CREATE CUSTOM INDEX IF NOT EXISTS doc_embeddings_ann ON demo.doc_embeddings "
                + "(embedding) USING 'StorageAttachedIndex' "
                + "WITH OPTIONS = {'similarity_function': 'cosine', 'source_model': 'openai-v3-large'}");
  }

  @Test
  @DisplayName("SAI on a scalar column: analyzer / normalization options, no similarity function")
  void createsAScalarIndex() {
    String cql =
        manager.createIndexCql(
            "demo",
            "docs",
            SaiIndexDefinition.builder("docs_body_sai", "body")
                .caseSensitive(false)
                .normalize(true)
                .ifNotExists(false)
                .build());

    assertThat(cql)
        .isEqualTo(
            "CREATE CUSTOM INDEX docs_body_sai ON demo.docs (body) USING 'StorageAttachedIndex' "
                + "WITH OPTIONS = {'case_sensitive': 'false', 'normalize': 'true'}");
  }

  @Test
  void createsAnIndexWithNoOptionsAtAll() {
    assertThat(
            manager.createIndexCql(
                "demo", "docs", SaiIndexDefinition.builder("docs_category_sai", "category").build()))
        .isEqualTo(
            "CREATE CUSTOM INDEX IF NOT EXISTS docs_category_sai ON demo.docs (category) "
                + "USING 'StorageAttachedIndex'")
        .doesNotContain("WITH OPTIONS");
  }

  @Test
  void indexesCollectionSelectors() {
    assertThat(
            manager.createIndexCql(
                "demo", "docs", SaiIndexDefinition.builder("docs_meta_sai", "values(meta)").build()))
        .contains("(values(meta))");
  }

  @Test
  void theConvenienceOverloadStillWorks() {
    Map<String, String> extra = new LinkedHashMap<>();
    extra.put("source_model", "bert");

    assertThat(
            manager.createIndexCql(
                "demo", "docs", "embedding", "docs_embedding_idx", SimilarityFunction.DOT_PRODUCT, extra))
        .isEqualTo(
            "CREATE CUSTOM INDEX IF NOT EXISTS docs_embedding_idx ON demo.docs (embedding) "
                + "USING 'StorageAttachedIndex' "
                + "WITH OPTIONS = {'similarity_function': 'dot_product', 'source_model': 'bert'}");
  }

  @Test
  @DisplayName("ALTER is a drop-and-recreate pair, returned together for preview")
  void alterReturnsBothStatementsInOrder() {
    var statements =
        manager.alterIndexCql(
            "demo",
            "docs",
            SaiIndexDefinition.builder("docs_embedding_idx", "embedding")
                .similarityFunction(SimilarityFunction.EUCLIDEAN)
                .build());

    assertThat(statements)
        .containsExactly(
            "DROP INDEX IF EXISTS demo.docs_embedding_idx",
            "CREATE CUSTOM INDEX IF NOT EXISTS docs_embedding_idx ON demo.docs (embedding) "
                + "USING 'StorageAttachedIndex' WITH OPTIONS = {'similarity_function': 'euclidean'}");
  }

  @Test
  void drops() {
    assertThat(manager.dropIndexCql("demo", "docs_embedding_idx"))
        .isEqualTo("DROP INDEX IF EXISTS demo.docs_embedding_idx");
    assertThat(manager.dropIndexCql("demo", "docs_embedding_idx", false))
        .isEqualTo("DROP INDEX demo.docs_embedding_idx");
    assertThat(manager.dropIndexCql("demo", "Weird Index", true))
        .isEqualTo("DROP INDEX IF EXISTS demo.\"Weird Index\"");
  }

  @Test
  void escapesOptionKeysAndValues() {
    String cql =
        manager.createIndexCql(
            "demo",
            "docs",
            SaiIndexDefinition.builder("i", "body")
                .options(Map.of("comment", "it's fine"))
                .build());

    assertThat(cql).contains("'comment': 'it''s fine'");
  }

  @Test
  void refusesUnquotableIdentifiers() {
    assertThatThrownBy(
            () ->
                manager.createIndexCql(
                    "demo", "docs", SaiIndexDefinition.builder("bad\"idx", "body").build()))
        .isInstanceOf(VectorException.class);
  }

  @Test
  void quotedHelperEscapesEmbeddedQuotes() {
    assertThat(DefaultSaiIndexManager.quoted("Weird\"Name")).isEqualTo("\"Weird\"\"Name\"");
    assertThatThrownBy(() -> DefaultSaiIndexManager.quoted(" "))
        .isInstanceOf(VectorException.class);
  }
}
