package io.cassyx.vector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import com.datastax.oss.driver.api.core.data.CqlVector;
import io.cassyx.core.testsupport.IntegrationTestBase;
import io.cassyx.vector.api.AnnPredicate;
import io.cassyx.vector.api.AnnQuery;
import io.cassyx.vector.api.AnnQueryBuilder;
import io.cassyx.vector.api.AnnQueryPreview;
import io.cassyx.vector.api.SimilarityFunction;
import io.cassyx.vector.api.SimilarityScores;
import io.cassyx.vector.api.VectorColumn;
import io.cassyx.vector.api.VectorEncoding;
import io.cassyx.vector.api.VectorException;
import io.cassyx.vector.api.VectorFactory;
import io.cassyx.vector.api.VectorService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The ANN path end to end: generate, execute, rank.
 *
 * <p>The dimension here is deliberately small (16) so the fixture is fast; the 1536-dimension case
 * is covered by {@link VectorRoundTripIT} and by the seeded {@code demo.doc_embeddings} table.
 */
class AnnQueryIT extends IntegrationTestBase {

  private static final String KEYSPACE = "cassyx_vector_ann_it";
  private static final String TABLE = "doc_embeddings";
  private static final int DIMENSIONS = 16;
  private static final int ROWS = 120;

  private static final UUID TARGET_ID = UUID.randomUUID();
  private static final UUID LEFT_ID = UUID.randomUUID();

  /** The vector stored on {@link #LEFT_ID}, used for the similarity-parity check. */
  private static final List<Float> PARITY_LEFT = randomVector(new Random(99L));

  private final AnnQueryBuilder builder = VectorFactory.annQueryBuilder();
  private final VectorService vectors = VectorFactory.vectorService();

  @BeforeAll
  static void seed() {
    // NOT IntegrationTestBase.ensureKeyspace: it uses the driver's 2s default request timeout,
    // which the first schema change against a cold container regularly exceeds.
    ddl(
        "CREATE KEYSPACE IF NOT EXISTS "
            + KEYSPACE
            + " WITH replication = {'class':'SimpleStrategy','replication_factor':1}");
    ddl(
        "CREATE TABLE IF NOT EXISTS "
                + KEYSPACE
                + "."
                + TABLE
                + " (doc_id uuid, chunk_no int, title text, category text,"
                + " embedding vector<float, "
                + DIMENSIONS
                + ">, PRIMARY KEY ((doc_id), chunk_no))");
    ddl(
        "CREATE CUSTOM INDEX IF NOT EXISTS ann_it_embedding ON "
                + KEYSPACE
                + "."
                + TABLE
                + " (embedding) USING 'StorageAttachedIndex'"
                + " WITH OPTIONS = {'similarity_function': 'cosine'}");
    ddl(
        "CREATE CUSTOM INDEX IF NOT EXISTS ann_it_category ON "
                + KEYSPACE
                + "."
                + TABLE
                + " (category) USING 'StorageAttachedIndex'");

    SimpleStatement insert =
        SimpleStatement.builder(
                "INSERT INTO "
                + KEYSPACE
                + "."
                + TABLE
                    + " (doc_id, chunk_no, title, category, embedding) VALUES (?, ?, ?, ?, ?)")
            .setTimeout(Duration.ofSeconds(30))
            .build();

    Random random = new Random(42L);
    for (int i = 0; i < ROWS; i++) {
      session()
          .execute(
              insert.setPositionalValues(
                  List.of(
                      UUID.randomUUID(),
                      0,
                      "doc-" + i,
                      i % 2 == 0 ? "release-notes" : "runbook",
                      CqlVector.newInstance(randomVector(random)))));
    }

    // A row whose vector we also hold in Java, for the similarity-parity check.
    session()
        .execute(
            insert.setPositionalValues(
                List.of(LEFT_ID, 0, "parity-left", "runbook", CqlVector.newInstance(PARITY_LEFT))));

    // One row we can find again by exact match: the query vector IS this row's vector.
    session()
        .execute(
            insert.setPositionalValues(
                List.of(
                    TARGET_ID,
                    0,
                    "the-target",
                    "release-notes",
                    CqlVector.newInstance(targetVector()))));
  }

  /** Schema changes need far longer than the driver's 2s default request timeout. */
  private static void ddl(String cql) {
    session().execute(SimpleStatement.builder(cql).setTimeout(Duration.ofSeconds(60)).build());
  }

  private static List<Float> randomVector(Random random) {
    List<Float> values = new ArrayList<>(DIMENSIONS);
    for (int i = 0; i < DIMENSIONS; i++) {
      values.add(random.nextFloat() * 2f - 1f);
    }
    return values;
  }

  private static List<Float> targetVector() {
    List<Float> values = new ArrayList<>(DIMENSIONS);
    for (int i = 0; i < DIMENSIONS; i++) {
      values.add(i == 0 ? 1.0f : 0.0f);
    }
    return values;
  }

  private VectorColumn column() {
    VectorColumn column = vectors.vectorColumn(session(), KEYSPACE, TABLE, "embedding");
    assertThat(column).isNotNull();
    return column;
  }

  @Test
  void discoversTheVectorColumnAndItsIndex() {
    List<VectorColumn> columns = vectors.vectorColumns(session(), KEYSPACE, TABLE);

    assertThat(columns).singleElement().satisfies(c -> {
      assertThat(c.column()).isEqualTo("embedding");
      assertThat(c.dimensions()).isEqualTo(DIMENSIONS);
      assertThat(c.annCapable()).isTrue();
      assertThat(c.similarityFunction()).isEqualTo(SimilarityFunction.COSINE);
    });
    assertThat(vectors.vectorColumn(session(), KEYSPACE, TABLE, "title")).isNull();
  }

  @Test
  @DisplayName("Generated ANN statement executes and ranks the nearest neighbour first")
  void executesAnnAndRanksCorrectly() {
    AnnQuery query =
        AnnQuery.builder(column(), targetVector())
            .limit(3)
            .select(List.of("doc_id", "title"))
            .score(SimilarityFunction.COSINE)
            .build();

    // The preview is what the user sees; the bound statement is what runs. Assert both.
    AnnQueryPreview preview = builder.preview(query);
    assertThat(preview.cql()).contains("ORDER BY embedding ANN OF [").contains("LIMIT 3");
    assertThat(preview.warnings()).isEmpty();
    assertThat(preview.similarityColumns()).containsExactly("cosine_score");

    List<Row> rows = session().execute(builder.statement(query)).all();

    assertThat(rows).hasSize(3);
    assertThat(rows.get(0).getString("title")).isEqualTo("the-target");
    assertThat(rows.get(0).getUuid("doc_id")).isEqualTo(TARGET_ID);
    assertThat(rows.get(0).getFloat("cosine_score")).isCloseTo(1.0f, within(1e-5f));

    // Scores must be monotonically non-increasing: that IS the ranking.
    float previous = Float.MAX_VALUE;
    for (Row row : rows) {
      float score = row.getFloat("cosine_score");
      assertThat(score).isLessThanOrEqualTo(previous);
      previous = score;
    }
  }

  @Test
  @DisplayName("Hybrid query: SAI predicate and ANN in one statement")
  void executesHybridQuery() {
    AnnQuery query =
        AnnQuery.builder(column(), targetVector())
            .limit(5)
            .select(List.of("doc_id", "title", "category"))
            .where(AnnPredicate.equalTo("category", "runbook"))
            .score(SimilarityFunction.COSINE)
            .build();

    List<Row> rows = session().execute(builder.statement(query)).all();

    assertThat(rows).isNotEmpty();
    assertThat(rows).allSatisfy(row -> assertThat(row.getString("category")).isEqualTo("runbook"));
    assertThat(rows)
        .as("the exact-match row is in the other category, so it must be filtered out")
        .noneSatisfy(row -> assertThat(row.getString("title")).isEqualTo("the-target"));
  }

  @Test
  @DisplayName("The inlined preview statement is executable verbatim, not just displayable")
  void inlinedPreviewCqlIsValidCql() {
    AnnQuery query =
        AnnQuery.builder(column(), targetVector())
            .limit(2)
            .select(List.of("title"))
            .where(AnnPredicate.equalTo("category", "release-notes"))
            .score(SimilarityFunction.EUCLIDEAN)
            .build();

    List<Row> rows = session().execute(builder.preview(query).cql()).all();

    assertThat(rows).hasSize(2);
    assertThat(rows.get(0).getString("title")).isEqualTo("the-target");
  }

  @Test
  @DisplayName("Reference-a-row: the query vector is read from an existing row's primary key")
  void readsAQueryVectorFromAnExistingRow() {
    List<Float> vector =
        vectors.readVector(
            session(), KEYSPACE, TABLE, "embedding", Map.of("doc_id", TARGET_ID, "chunk_no", 0));

    assertThat(vector).isEqualTo(targetVector());

    assertThatThrownBy(
            () ->
                vectors.readVector(
                    session(), KEYSPACE, TABLE, "embedding", Map.of("doc_id", TARGET_ID)))
        .isInstanceOf(VectorException.class)
        .hasMessageContaining("chunk_no");

    assertThatThrownBy(
            () ->
                vectors.readVector(
                    session(),
                    KEYSPACE,
                    TABLE,
                    "embedding",
                    Map.of("doc_id", UUID.randomUUID(), "chunk_no", 0)))
        .isInstanceOf(VectorException.class)
        .hasMessageContaining("No row");
  }

  @Test
  @DisplayName("Our similarity maths matches the cluster's similarity_* functions exactly")
  void similarityScoresMatchTheCluster() {
    // This is the assertion that makes the inspector panel trustworthy: if these ever diverge, the
    // UI would show a number the user cannot reproduce in cqlsh.
    // `left` is the vector stored on the LEFT_ID row, so the cluster reads it from the column
    // while we compute over the same values in Java.
    Random random = new Random(7L);
    List<Float> left = PARITY_LEFT;
    List<Float> right = randomVector(random);

    // Both arguments have to be literals here: Cassandra cannot infer the element type of a
    // vector bind marker when NEITHER argument is a column ("use type casts to disambiguate").
    // The generated ANN projections always pass the column first, so they bind fine - see
    // executesAnnAndRanksCorrectly.
    String rightLiteral = VectorEncoding.toJsonArray(right);

    Row row =
        session()
            .execute(
                SimpleStatement.builder(
                        "SELECT similarity_cosine(embedding, "
                            + rightLiteral
                            + ") AS c,"
                            + " similarity_dot_product(embedding, "
                            + rightLiteral
                            + ") AS d,"
                            + " similarity_euclidean(embedding, "
                            + rightLiteral
                            + ") AS e"
                            + " FROM "
                            + KEYSPACE
                            + "."
                            + TABLE
                            + " WHERE doc_id = ? AND chunk_no = 0")
                    .addPositionalValue(LEFT_ID)
                    .build())
            .one();

    assertThat(row).isNotNull();

    SimilarityScores scores =
        vectors.compare(
            left,
            right,
            List.of(
                SimilarityFunction.COSINE,
                SimilarityFunction.DOT_PRODUCT,
                SimilarityFunction.EUCLIDEAN));

    assertThat(scores.scoresByCqlName().get("cosine"))
        .as("similarity_cosine")
        .isCloseTo(row.getFloat("c"), within(1e-5d));
    assertThat(scores.scoresByCqlName().get("dot_product"))
        .as("similarity_dot_product")
        .isCloseTo(row.getFloat("d"), within(1e-5d));
    assertThat(scores.scoresByCqlName().get("euclidean"))
        .as("similarity_euclidean")
        .isCloseTo(row.getFloat("e"), within(1e-5d));
    assertThat(scores.dimensions()).isEqualTo(DIMENSIONS);
    assertThat(scores.leftMagnitude()).isEqualTo(vectors.magnitude(left));
  }
}
