package io.cassyx.vector.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import com.datastax.oss.driver.api.core.data.CqlVector;
import io.cassyx.vector.api.AnnPredicate;
import io.cassyx.vector.api.AnnQuery;
import io.cassyx.vector.api.AnnQueryBuilder;
import io.cassyx.vector.api.AnnQueryPreview;
import io.cassyx.vector.api.SaiIndexDescriptor;
import io.cassyx.vector.api.SimilarityFunction;
import io.cassyx.vector.api.VectorColumn;
import io.cassyx.vector.api.VectorFactory;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DefaultAnnQueryBuilderTest {

  private static final SaiIndexDescriptor COSINE_INDEX =
      new SaiIndexDescriptor(
          "demo",
          "docs",
          "docs_embedding_idx",
          "embedding",
          true,
          SimilarityFunction.COSINE,
          "openai-v3-large",
          Map.of("similarity_function", "cosine"),
          "StorageAttachedIndex");

  private static final VectorColumn UNINDEXED = new VectorColumn("demo", "docs", "embedding", 3);
  private static final VectorColumn INDEXED = UNINDEXED.withIndex(COSINE_INDEX);
  private static final List<Float> VECTOR = List.of(0.1f, 0.2f, 0.3f);

  private final AnnQueryBuilder builder = VectorFactory.annQueryBuilder();

  @Test
  void buildsTheMinimalAnnStatement() {
    String cql = builder.build(AnnQuery.builder(INDEXED, VECTOR).limit(5).build());

    assertThat(cql)
        .isEqualTo("SELECT * FROM demo.docs ORDER BY embedding ANN OF [0.1, 0.2, 0.3] LIMIT 5");
  }

  @Test
  @DisplayName("Hybrid query: SAI predicates + ANN + similarity projections in one statement")
  void buildsHybridStatement() {
    AnnQuery query =
        AnnQuery.builder(INDEXED, VECTOR)
            .limit(10)
            .select(List.of("doc_id", "title", "category"))
            .where(AnnPredicate.equalTo("category", "electronics"))
            .where(new AnnPredicate("lang", "IN", List.of("en", "de")))
            .score(SimilarityFunction.COSINE)
            .build();

    assertThat(builder.build(query))
        .isEqualTo(
            "SELECT doc_id, title, category, "
                + "similarity_cosine(embedding, [0.1, 0.2, 0.3]) AS cosine_score "
                + "FROM demo.docs "
                + "WHERE category = 'electronics' AND lang IN ('en', 'de') "
                + "ORDER BY embedding ANN OF [0.1, 0.2, 0.3] LIMIT 10");
  }

  @Test
  void projectsTheVectorColumnOnlyOnRequestAndNeverTwice() {
    AnnQuery query =
        AnnQuery.builder(INDEXED, VECTOR)
            .select(List.of("doc_id", "embedding"))
            .includeVectorColumn(true)
            .build();

    assertThat(builder.build(query)).startsWith("SELECT doc_id, embedding FROM");

    AnnQuery without = AnnQuery.builder(INDEXED, VECTOR).select(List.of("doc_id")).build();
    assertThat(builder.build(without)).startsWith("SELECT doc_id FROM");
  }

  @Test
  void quotesCaseSensitiveIdentifiers() {
    VectorColumn column = new VectorColumn("demo", "Doc Chunks", "Embedding", 3);
    String cql = builder.build(AnnQuery.builder(column, VECTOR).select(List.of("Doc Id")).build());

    assertThat(cql)
        .isEqualTo(
            "SELECT \"Doc Id\" FROM demo.\"Doc Chunks\" "
                + "ORDER BY \"Embedding\" ANN OF [0.1, 0.2, 0.3] LIMIT 10");
  }

  @Test
  void supportsTheAnalyzerMatchOperator() {
    AnnQuery query =
        AnnQuery.builder(INDEXED, VECTOR).where(new AnnPredicate("body", ":", "cassandra")).build();

    assertThat(builder.build(query)).contains("WHERE body : 'cassandra'");
  }

  /* ------------------------------------------------------------------------ preview */

  @Test
  void previewElidesTheVectorAndReportsScoreColumns() {
    VectorColumn wide =
        new VectorColumn("demo", "doc_embeddings", "embedding", 1536).withIndex(COSINE_INDEX);
    List<Float> vector = Collections.nCopies(1536, 0.01f);

    AnnQueryPreview preview =
        builder.preview(
            AnnQuery.builder(wide, vector)
                .limit(3)
                .select(List.of("doc_id", "title"))
                .score(SimilarityFunction.COSINE)
                .build());

    assertThat(preview.dimensions()).isEqualTo(1536);
    assertThat(preview.similarityColumns()).containsExactly("cosine_score");
    assertThat(preview.indexUsed()).isEqualTo(COSINE_INDEX);
    assertThat(preview.warnings()).isEmpty();
    assertThat(preview.abbreviatedCql())
        .isEqualTo(
            "SELECT doc_id, title, similarity_cosine(embedding, [… 1536 floats …]) AS cosine_score "
                + "FROM demo.doc_embeddings "
                + "ORDER BY embedding ANN OF [… 1536 floats …] LIMIT 3");
    assertThat(preview.cql()).doesNotContain("…").contains("0.01");
  }

  @Test
  @DisplayName("An unindexed vector column is warned about, not silently generated against")
  void warnsWhenTheVectorColumnHasNoSaiIndex() {
    AnnQueryPreview preview = builder.preview(AnnQuery.builder(UNINDEXED, VECTOR).build());

    assertThat(preview.indexUsed()).isNull();
    assertThat(preview.warnings())
        .singleElement()
        .asString()
        .contains("has no SAI index")
        .contains("Create an SAI index");
  }

  @Test
  void warnsWhenTheScoreFunctionDisagreesWithTheIndex() {
    AnnQueryPreview preview =
        builder.preview(
            AnnQuery.builder(INDEXED, VECTOR).score(SimilarityFunction.EUCLIDEAN).build());

    assertThat(preview.warnings())
        .singleElement()
        .asString()
        .contains("euclidean")
        .contains("cosine")
        .contains("disagree");
  }

  @Test
  void warnsAboutPredicatesOnUnindexedColumnsWhenTheIndexSetIsKnown() {
    AnnQuery query =
        AnnQuery.builder(INDEXED, VECTOR)
            .where(AnnPredicate.equalTo("category", "x"))
            .where(AnnPredicate.equalTo("lang", "en"))
            .build();

    assertThat(builder.preview(query, Set.of("category")).warnings())
        .singleElement()
        .asString()
        .contains("lang");

    assertThat(builder.preview(query, null).warnings())
        .as("unknown index set means no guesswork")
        .isEmpty();
  }

  @Test
  void warnsAboutTheCostOfProjectingALargeVector() {
    VectorColumn wide =
        new VectorColumn("demo", "doc_embeddings", "embedding", 1536).withIndex(COSINE_INDEX);

    AnnQueryPreview preview =
        builder.preview(
            AnnQuery.builder(wide, Collections.nCopies(1536, 0.5f))
                .limit(100)
                .select(List.of("doc_id"))
                .includeVectorColumn(true)
                .build());

    assertThat(preview.warnings()).singleElement().asString().contains("153600 floats");
  }

  /* ---------------------------------------------------------------------- execution */

  @Test
  @DisplayName("The executed statement binds the vector; it does not inline 1536 floats")
  void statementBindsValues() {
    AnnQuery query =
        AnnQuery.builder(INDEXED, VECTOR)
            .limit(7)
            .select(List.of("doc_id"))
            .where(AnnPredicate.equalTo("category", "electronics"))
            .where(new AnnPredicate("lang", "IN", List.of("en", "de")))
            .score(SimilarityFunction.COSINE)
            .build();

    SimpleStatement statement = builder.statement(query);

    assertThat(statement.getQuery())
        .isEqualTo(
            "SELECT doc_id, similarity_cosine(embedding, ?) AS cosine_score "
                + "FROM demo.docs "
                + "WHERE category = ? AND lang IN (?, ?) "
                + "ORDER BY embedding ANN OF ? LIMIT 7");

    List<Object> values = statement.getPositionalValues();
    assertThat(values).hasSize(5);
    assertThat(values.get(0)).isEqualTo(CqlVector.newInstance(VECTOR));
    assertThat(values.get(1)).isEqualTo("electronics");
    assertThat(values.get(2)).isEqualTo("en");
    assertThat(values.get(3)).isEqualTo("de");
    assertThat(values.get(4)).isEqualTo(CqlVector.newInstance(VECTOR));
  }

  @Test
  void statementFallsBackToSelectStar() {
    SimpleStatement statement = builder.statement(AnnQuery.builder(INDEXED, VECTOR).build());
    assertThat(statement.getQuery())
        .isEqualTo("SELECT * FROM demo.docs ORDER BY embedding ANN OF ? LIMIT 10");
    assertThat(statement.getPositionalValues()).hasSize(1);
  }
}
