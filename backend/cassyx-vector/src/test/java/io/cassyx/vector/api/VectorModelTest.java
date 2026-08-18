package io.cassyx.vector.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.cassyx.core.api.Capability;
import io.cassyx.core.api.ClusterCapabilities;
import io.cassyx.core.api.ClusterFlavor;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** The immutable value objects that cross the module boundary (plan section 2.1). */
class VectorModelTest {

  @Nested
  class VectorColumns {

    @Test
    void rendersTheCqlTypeAndIdentity() {
      VectorColumn column = new VectorColumn("demo", "doc_embeddings", "embedding", 1536);

      assertThat(column.cqlType()).isEqualTo("vector<float, 1536>");
      assertThat(column.qualifiedName()).isEqualTo("demo.doc_embeddings.embedding");
      assertThat(column.elementType()).isEqualTo("float");
      assertThat(column.annCapable()).isFalse();
      assertThat(column.similarityFunction()).isNull();
    }

    @Test
    void attachesAnIndexWithoutMutating() {
      VectorColumn column = new VectorColumn("demo", "docs", "embedding", 3);
      SaiIndexDescriptor index =
          new SaiIndexDescriptor(
              "demo",
              "docs",
              "docs_embedding_idx",
              "embedding",
              true,
              SimilarityFunction.EUCLIDEAN,
              null,
              Map.of("similarity_function", "euclidean"),
              "StorageAttachedIndex");

      VectorColumn indexed = column.withIndex(index);

      assertThat(indexed.annCapable()).isTrue();
      assertThat(indexed.similarityFunction()).isEqualTo(SimilarityFunction.EUCLIDEAN);
      assertThat(index.qualifiedName()).isEqualTo("demo.docs_embedding_idx");
      assertThat(column.annCapable()).as("original is untouched").isFalse();
    }

    @Test
    void rejectsNonPositiveDimensions() {
      assertThatThrownBy(() -> new VectorColumn("demo", "docs", "embedding", 0))
          .isInstanceOf(VectorException.class)
          .hasMessageContaining("positive");
    }

    @Test
    void blankElementTypeDefaultsToFloat() {
      assertThat(new VectorColumn("demo", "docs", "e", 2, "  ", null).elementType())
          .isEqualTo("float");
    }
  }

  @Nested
  class ColumnDefinitions {

    @Test
    void derivesTheIndexNameAndDefinition() {
      VectorColumnDefinition definition =
          new VectorColumnDefinition(
              "embedding", 1536, null, true, null, SimilarityFunction.COSINE, "openai-v3-large");

      assertThat(definition.cqlType()).isEqualTo("vector<float, 1536>");
      assertThat(definition.resolveIndexName("products")).isEqualTo("products_embedding_idx");

      SaiIndexDefinition index = definition.toIndexDefinition("products");
      assertThat(index.name()).isEqualTo("products_embedding_idx");
      assertThat(index.withOptions())
          .containsExactly(
              Map.entry("similarity_function", "cosine"),
              Map.entry("source_model", "openai-v3-large"));
    }

    @Test
    void honoursAnExplicitIndexNameAndDefaultsSimilarityToCosine() {
      VectorColumnDefinition definition =
          new VectorColumnDefinition("v", 4, "float", true, "my_idx", null, null);

      assertThat(definition.resolveIndexName("t")).isEqualTo("my_idx");
      assertThat(definition.toIndexDefinition("t").similarityFunction())
          .isEqualTo(SimilarityFunction.COSINE);
    }

    @Test
    void rejectsBadDimensionsNamesAndElementTypes() {
      assertThatThrownBy(() -> VectorColumnDefinition.of("embedding", 0))
          .isInstanceOf(VectorException.class);
      assertThatThrownBy(() -> VectorColumnDefinition.of("embedding", 8193))
          .isInstanceOf(VectorException.class)
          .hasMessageContaining("8192");
      assertThatThrownBy(() -> VectorColumnDefinition.of(" ", 3))
          .isInstanceOf(VectorException.class);
      assertThatThrownBy(
              () -> new VectorColumnDefinition("v", 3, "double", false, null, null, null))
          .isInstanceOf(VectorException.class)
          .hasMessageContaining("vector<float, N>");
    }

    @Test
    void ofBuildsAPlainUnindexedColumn() {
      VectorColumnDefinition definition = VectorColumnDefinition.of("embedding", 1536);
      assertThat(definition.createIndex()).isFalse();
      assertThat(definition.sourceModel()).isNull();
    }
  }

  @Nested
  class IndexDefinitions {

    @Test
    void mergesTypedOptionsBeforeRawOnes() {
      SaiIndexDefinition definition =
          SaiIndexDefinition.builder("idx", "body")
              .caseSensitive(false)
              .normalize(true)
              .asciiOnly(false)
              .analyzer("{\"tokenizer\":{\"name\":\"standard\"}}")
              .options(Map.of("normalize", "false"))
              .ifNotExists(false)
              .build();

      Map<String, String> options = definition.withOptions();
      assertThat(options).containsEntry("case_sensitive", "false");
      assertThat(options).containsEntry("ascii", "false");
      assertThat(options).containsEntry("index_analyzer", "{\"tokenizer\":{\"name\":\"standard\"}}");
      assertThat(options)
          .as("raw options are merged last so a caller can always override")
          .containsEntry("normalize", "false");
      assertThat(definition.vectorIndex()).isFalse();
      assertThat(definition.ifNotExists()).isFalse();
    }

    @Test
    void vectorIndexIsDetectedFromEitherVectorOption() {
      assertThat(
              SaiIndexDefinition.builder("i", "v")
                  .similarityFunction(SimilarityFunction.COSINE)
                  .build()
                  .vectorIndex())
          .isTrue();
      assertThat(SaiIndexDefinition.builder("i", "v").sourceModel("bert").build().vectorIndex())
          .isTrue();
      assertThat(SaiIndexDefinition.builder("i", "v").build().vectorIndex()).isFalse();
    }

    @Test
    void blankSourceModelAndAnalyzerAreDropped() {
      Map<String, String> options =
          SaiIndexDefinition.builder("i", "v").sourceModel(" ").analyzer(" ").build().withOptions();
      assertThat(options).isEmpty();
    }

    @Test
    void rejectsMissingNameOrTarget() {
      assertThatThrownBy(() -> SaiIndexDefinition.builder(" ", "v").build())
          .isInstanceOf(VectorException.class);
      assertThatThrownBy(() -> SaiIndexDefinition.builder("i", null).build())
          .isInstanceOf(VectorException.class);
    }
  }

  @Nested
  class Predicates {

    @Test
    void normalisesTheOperator() {
      assertThat(new AnnPredicate("category", " in ", List.of("a")).operator()).isEqualTo("IN");
      assertThat(AnnPredicate.equalTo("category", "electronics").operator()).isEqualTo("=");
    }

    @Test
    void rejectsUnsupportedOperatorsAndBadInValues() {
      assertThatThrownBy(() -> new AnnPredicate("c", "LIKE", "x"))
          .isInstanceOf(VectorException.class)
          .hasMessageContaining("CONTAINS KEY");
      assertThatThrownBy(() -> new AnnPredicate("c", "IN", "not-a-list"))
          .isInstanceOf(VectorException.class)
          .hasMessageContaining("list of values");
      assertThatThrownBy(() -> new AnnPredicate(" ", "=", "x"))
          .isInstanceOf(VectorException.class);
    }
  }

  @Nested
  class Queries {

    private final VectorColumn column = new VectorColumn("demo", "docs", "embedding", 3);

    @Test
    void buildsWithDefaults() {
      AnnQuery query = AnnQuery.builder(column, List.of(0.1f, 0.2f, 0.3f)).build();

      assertThat(query.limit()).isEqualTo(AnnQuery.DEFAULT_LIMIT);
      assertThat(query.selectColumns()).isEmpty();
      assertThat(query.includeVectorColumn()).isFalse();
      assertThat(query.queryVectorArray()).containsExactly(0.1f, 0.2f, 0.3f);
      assertThat(query.similarityColumnNames()).isEmpty();
    }

    @Test
    void builderAccumulatesEveryOptionalPart() {
      AnnQuery query =
          AnnQuery.builder(column, List.of(1f, 2f, 3f))
              .limit(5)
              .select(List.of("doc_id"))
              .select(null)
              .where(AnnPredicate.equalTo("category", "x"))
              .where(List.of(AnnPredicate.equalTo("lang", "en")))
              .where((List<AnnPredicate>) null)
              .score(SimilarityFunction.COSINE)
              .score(List.of(SimilarityFunction.EUCLIDEAN))
              .score((List<SimilarityFunction>) null)
              .includeVectorColumn(true)
              .build();

      assertThat(query.predicates()).hasSize(2);
      assertThat(query.similarityColumnNames())
          .containsExactly("cosine_score", "euclidean_score");
      assertThat(query.includeVectorColumn()).isTrue();
    }

    @Test
    void nonPositiveLimitFallsBackToTheDefault() {
      assertThat(AnnQuery.builder(column, List.of(1f, 2f, 3f)).limit(-4).build().limit())
          .isEqualTo(AnnQuery.DEFAULT_LIMIT);
    }

    @Test
    void rejectsDimensionMismatchNullElementsAndOversizedLimits() {
      assertThatThrownBy(() -> AnnQuery.builder(column, List.of(0.1f)).build())
          .isInstanceOf(VectorException.class)
          .hasMessageContaining("dimensions");

      List<Float> withNull = new java.util.ArrayList<>(List.of(1f, 2f));
      withNull.add(null);
      assertThatThrownBy(() -> AnnQuery.builder(column, withNull).build())
          .isInstanceOf(VectorException.class)
          .hasMessageContaining("null element");

      assertThatThrownBy(
              () -> AnnQuery.builder(column, List.of(1f, 2f, 3f)).limit(10_001).build())
          .isInstanceOf(VectorException.class)
          .hasMessageContaining("10000");
    }

    @Test
    void previewIsImmutable() {
      AnnQueryPreview preview = new AnnQueryPreview("cql", "abbrev", 3, null, null, null);
      assertThat(preview.similarityColumns()).isEmpty();
      assertThat(preview.warnings()).isEmpty();
    }
  }

  @Nested
  class Capabilities {

    @Test
    void mirrorsTheCoreProbe() {
      VectorCapabilities capabilities =
          VectorCapabilities.from(
              new ClusterCapabilities(
                  ClusterFlavor.CASSANDRA, "5.0.2", Set.of(Capability.SAI, Capability.VECTOR_ANN)));

      assertThat(capabilities.vectorAnn()).isTrue();
      assertThat(capabilities.sai()).isTrue();
      assertThat(capabilities.supports(Capability.VECTOR_ANN)).isTrue();
      assertThat(capabilities.supports(Capability.SAI)).isTrue();
      assertThat(capabilities.supports(Capability.MATERIALIZED_VIEWS)).isFalse();
    }

    @Test
    void dseHasSaiButNoVectorAnn() {
      VectorCapabilities capabilities =
          VectorCapabilities.from(
              new ClusterCapabilities(ClusterFlavor.DSE, "6.8.30", Set.of(Capability.SAI)));

      assertThat(capabilities.sai()).isTrue();
      assertThat(capabilities.vectorAnn()).isFalse();
      assertThat(capabilities.explain(Capability.VECTOR_ANN))
          .contains("Cassandra 5.x or Astra")
          .contains("DSE 6.8.30");
    }

    @Test
    void keyspacesAndScyllaGetNeither() {
      for (ClusterFlavor flavor : List.of(ClusterFlavor.AMAZON_KEYSPACES, ClusterFlavor.SCYLLA)) {
        VectorCapabilities capabilities =
            VectorCapabilities.from(new ClusterCapabilities(flavor, "n/a", Set.of()));
        assertThat(capabilities.vectorAnn()).as("%s vector/ANN", flavor).isFalse();
        assertThat(capabilities.sai()).as("%s SAI", flavor).isFalse();
        assertThat(capabilities.explain(Capability.SAI)).contains("DSE 6.8+");
      }
    }

    @Test
    void anAbsentProbeIsTreatedAsUnsupported() {
      VectorCapabilities capabilities = VectorCapabilities.from(null);
      assertThat(capabilities.vectorAnn()).isFalse();
      assertThat(capabilities.flavor()).isEqualTo("UNKNOWN");
      assertThat(capabilities.version()).isEqualTo("unknown");
      assertThat(VectorCapabilities.permissive().vectorAnn()).isTrue();
      assertThat(new VectorCapabilities(true, true, null, null).flavor()).isEqualTo("UNKNOWN");
    }
  }

  @Nested
  class Scores {

    @Test
    void keysScoresByTheirCqlName() {
      SimilarityScores scores =
          new SimilarityScores(
              3,
              Map.of(SimilarityFunction.EUCLIDEAN, 0.5d, SimilarityFunction.COSINE, 0.9d),
              1.0d,
              1.0d);

      assertThat(scores.scoresByCqlName())
          .containsExactly(Map.entry("cosine", 0.9d), Map.entry("euclidean", 0.5d));
      assertThat(new SimilarityScores(1, null, 0, 0).scores()).isEmpty();
    }
  }

  @Nested
  class Statuses {

    @Test
    void unknownStatusHasNoDefinition() {
      SaiIndexStatus status = SaiIndexStatus.unknown("demo", "docs", "idx");
      assertThat(status.state()).isEqualTo(SaiIndexState.UNKNOWN);
      assertThat(status.queryable()).isFalse();
      assertThat(status.perNode()).isEmpty();
      assertThat(status.definition()).isNull();
      assertThat(new SaiIndexNodeStatus("127.0.0.1:9042", SaiIndexState.QUERYABLE).endpoint())
          .isEqualTo("127.0.0.1:9042");
    }
  }
}
