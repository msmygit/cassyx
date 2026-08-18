package io.cassyx.vector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import com.datastax.oss.driver.api.core.servererrors.InvalidConfigurationInQueryException;
import io.cassyx.core.api.Capability;
import io.cassyx.core.testsupport.IntegrationTestBase;
import io.cassyx.vector.api.SaiIndexDefinition;
import io.cassyx.vector.api.SaiIndexDescriptor;
import io.cassyx.vector.api.SaiIndexManager;
import io.cassyx.vector.api.SaiIndexState;
import io.cassyx.vector.api.SaiIndexStatus;
import io.cassyx.vector.api.SimilarityFunction;
import io.cassyx.vector.api.VectorCapabilities;
import io.cassyx.vector.api.VectorColumn;
import io.cassyx.vector.api.VectorColumnDefinition;
import io.cassyx.vector.api.VectorException;
import io.cassyx.vector.api.VectorFactory;
import io.cassyx.vector.api.VectorService;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Full SAI lifecycle against Cassandra 5.x: create / list / check / alter / drop, on a vector
 * column and on a scalar column (plan section 6).
 *
 * <p>Every statement asserted here is one this module generated, executed verbatim - which is the
 * only way to know the generator emits CQL the cluster actually accepts.
 */
class SaiIndexLifecycleIT extends IntegrationTestBase {

  private static final String KEYSPACE = "cassyx_vector_sai_it";
  private static final String TABLE = "docs";

  private final SaiIndexManager manager = VectorFactory.saiIndexManager();
  private final VectorService vectors = VectorFactory.vectorService();

  @BeforeAll
  static void createSchema() {
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
            + " embedding vector<float, 8>, PRIMARY KEY ((doc_id), chunk_no))");
  }

  /**
   * Schema changes need far longer than the driver's 2s default request timeout, especially the
   * first ones against a freshly started container.
   */
  private static void ddl(String cql) {
    session()
        .execute(
            SimpleStatement.builder(cql).setTimeout(java.time.Duration.ofSeconds(60)).build());
  }


  @Test
  @DisplayName("Cassandra 5.x reports both SAI and vector/ANN (plan §7.1)")
  void capabilityProbeAllowsVectorAndSai() {
    VectorCapabilities capabilities = vectors.capabilities(session());

    assertThat(capabilities.vectorAnn()).isTrue();
    assertThat(capabilities.sai()).isTrue();
    assertThat(capabilities.supports(Capability.VECTOR_ANN)).isTrue();
    assertThat(capabilities.version()).startsWith("5.");
  }

  @Test
  @DisplayName("Generated ALTER TABLE ... ADD vector<float, N> is accepted verbatim")
  void addsAVectorColumnAndItsIndexInOneOperation() {
    List<String> statements =
        vectors.addColumnCql(
            KEYSPACE,
            TABLE,
            new VectorColumnDefinition(
                "summary_vec", 4, "float", true, null, SimilarityFunction.DOT_PRODUCT, null));

    assertThat(statements).hasSize(2);
    statements.forEach(SaiIndexLifecycleIT::ddl);

    VectorColumn added = vectors.vectorColumn(session(), KEYSPACE, TABLE, "summary_vec");
    assertThat(added).isNotNull();
    assertThat(added.dimensions()).isEqualTo(4);
    assertThat(added.cqlType()).isEqualTo("vector<float, 4>");
    assertThat(added.annCapable()).isTrue();
    assertThat(added.similarityFunction()).isEqualTo(SimilarityFunction.DOT_PRODUCT);
  }

  @Test
  void createsListsChecksAltersAndDropsAVectorIndex() {
    String indexName = "docs_embedding_idx";

    ddl(
        manager.createIndexCql(
                KEYSPACE,
                TABLE,
                SaiIndexDefinition.builder(indexName, "embedding")
                    .similarityFunction(SimilarityFunction.COSINE)
                    .build()));

    SaiIndexDescriptor index =
        manager.list(session(), KEYSPACE, TABLE).stream()
            .filter(i -> i.name().equals(indexName))
            .findFirst()
            .orElseThrow();

    assertThat(index.vectorIndex()).isTrue();
    assertThat(index.target()).isEqualTo("embedding");
    assertThat(index.similarityFunction()).isEqualTo(SimilarityFunction.COSINE);
    assertThat(index.className()).endsWith("StorageAttachedIndex");
    assertThat(index.options()).containsEntry("similarity_function", "cosine");

    SaiIndexStatus status = manager.status(session(), KEYSPACE, TABLE, indexName);
    assertThat(status.perNode()).isNotEmpty();
    assertThat(status.state()).isIn(SaiIndexState.QUERYABLE, SaiIndexState.BUILDING);
    assertThat(status.buildProgressPercent()).isNotNull();
    assertThat(status.definition()).isNotNull();

    // ALTER is a drop + recreate pair; both statements have to be accepted, in order.
    manager
        .alterIndexCql(
            KEYSPACE,
            TABLE,
            SaiIndexDefinition.builder(indexName, "embedding")
                .similarityFunction(SimilarityFunction.EUCLIDEAN)
                .build())
        .forEach(SaiIndexLifecycleIT::ddl);

    assertThat(
            manager.list(session(), KEYSPACE, TABLE).stream()
                .filter(i -> i.name().equals(indexName))
                .findFirst()
                .orElseThrow()
                .similarityFunction())
        .isEqualTo(SimilarityFunction.EUCLIDEAN);

    ddl(manager.dropIndexCql(KEYSPACE, indexName));

    assertThat(manager.list(session(), KEYSPACE, TABLE))
        .extracting(SaiIndexDescriptor::name)
        .doesNotContain(indexName);
    assertThat(manager.status(session(), KEYSPACE, TABLE, indexName).state())
        .isEqualTo(SaiIndexState.UNKNOWN);
  }

  @Test
  @DisplayName("SAI on a scalar column too, with the analyzer options surface")
  void createsAScalarSaiIndex() {
    String indexName = "docs_category_sai";

    ddl(
        manager.createIndexCql(
                KEYSPACE,
                TABLE,
                SaiIndexDefinition.builder(indexName, "category")
                    .caseSensitive(false)
                    .normalize(true)
                    .asciiOnly(true)
                    .build()));

    SaiIndexDescriptor index =
        manager.list(session(), KEYSPACE, TABLE).stream()
            .filter(i -> i.name().equals(indexName))
            .findFirst()
            .orElseThrow();

    assertThat(index.vectorIndex()).isFalse();
    assertThat(index.similarityFunction()).isNull();
    assertThat(index.options()).containsEntry("case_sensitive", "false");

    ddl(manager.dropIndexCql(KEYSPACE, indexName));
  }

  @Test
  @DisplayName("source_model is an Astra/DSE option; vanilla Cassandra 5.0 rejects it")
  void sourceModelIsNotUnderstoodByApacheCassandra5() {
    // Discovered against the real cluster, not assumed. The API contract exposes `sourceModel`
    // because Astra and DSE accept it, but emitting it against Apache Cassandra 5.0 fails with
    // "Properties specified [source_model] are not understood by StorageAttachedIndex". The UI
    // must therefore offer the field only where the cluster supports it - this test is the
    // executable record of that constraint, so nobody "fixes" the generator to always emit it.
    assertThatThrownBy(
            () ->
                ddl(
                    manager.createIndexCql(
                        KEYSPACE,
                        TABLE,
                        SaiIndexDefinition.builder("docs_srcmodel_idx", "embedding")
                            .similarityFunction(SimilarityFunction.COSINE)
                            .sourceModel("openai-v3-large")
                            .build())))
        .isInstanceOf(InvalidConfigurationInQueryException.class)
        .hasMessageContaining("source_model");
  }

  @Test
  void unknownTablesFailWithOurOwnException() {
    assertThatThrownBy(() -> manager.list(session(), KEYSPACE, "no_such_table"))
        .isInstanceOf(VectorException.class)
        .hasMessageContaining("No table");
  }
}
