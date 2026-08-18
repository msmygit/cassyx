package io.cassyx.api.schema;

import static org.assertj.core.api.Assertions.assertThat;

import io.cassyx.core.api.schema.ClusteringKeyColumn;
import io.cassyx.core.api.schema.ColumnDefinition;
import io.cassyx.core.api.schema.ColumnInfo;
import io.cassyx.core.api.schema.ColumnKind;
import io.cassyx.core.api.schema.DdlExecutionResult;
import io.cassyx.core.api.schema.DdlExecutor;
import io.cassyx.core.api.schema.DdlGenerator;
import io.cassyx.core.api.schema.IndexDefinition;
import io.cassyx.core.api.schema.IndexInfo;
import io.cassyx.core.api.schema.IndexKind;
import io.cassyx.core.api.schema.PrimaryKeyDefinition;
import io.cassyx.core.api.schema.SchemaFactory;
import io.cassyx.core.api.schema.SchemaIdentity;
import io.cassyx.core.api.schema.SchemaReader;
import io.cassyx.core.api.schema.TableDefinition;
import io.cassyx.core.api.schema.TableInfo;
import io.cassyx.core.api.schema.TableOptions;
import io.cassyx.core.api.schema.UserDefinedTypeDefinition;
import io.cassyx.core.api.schema.UserDefinedTypeField;
import io.cassyx.core.testsupport.IntegrationTestBase;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The schema surface against the shared Cassandra 5.x container (plan section 11.2 - one singleton
 * for the whole suite, never a container per class).
 *
 * <p>This is where the generated CQL meets a real cluster: if the generator emits something the
 * server rejects, these tests fail rather than the user finding out.
 */
class SchemaCatalogIT extends IntegrationTestBase {

  private static final String KEYSPACE = "cassyx_schema_it";

  private static final SchemaReader READER = SchemaFactory.reader();
  private static final DdlGenerator GENERATOR = SchemaFactory.ddlGenerator();
  private static final DdlExecutor EXECUTOR = SchemaFactory.ddlExecutor();

  @BeforeAll
  static void createSchema() {
    if (!Boolean.getBoolean(IntegrationTestBase.ENABLED_PROPERTY)) {
      return;
    }
    ensureKeyspace(KEYSPACE);

    EXECUTOR.execute(
        session(),
        GENERATOR.createType(
            KEYSPACE,
            new UserDefinedTypeDefinition(
                "address",
                List.of(
                    new UserDefinedTypeField("street", "text"),
                    new UserDefinedTypeField("city", "text")),
                true)),
        true);

    EXECUTOR.execute(
        session(),
        GENERATOR.createTable(
            KEYSPACE,
            new TableDefinition(
                "users",
                List.of(
                    ColumnDefinition.of("user_id", "uuid"),
                    ColumnDefinition.of("created_at", "timestamp"),
                    ColumnDefinition.of("email", "text"),
                    new ColumnDefinition("tenant_name", "text", true, null),
                    ColumnDefinition.of("preferences", "map<text, text>"),
                    ColumnDefinition.of("profile", "frozen<address>"),
                    // The CASSJAVA-2 regression fixture (plan section 4).
                    ColumnDefinition.of("embedding", "vector<float, 1536>")),
                new PrimaryKeyDefinition(
                    List.of("user_id"),
                    List.of(
                        new ClusteringKeyColumn(
                            "created_at", io.cassyx.core.api.schema.ClusteringOrder.DESC))),
                TableOptions.comment("Application users"),
                true)),
        true);

    EXECUTOR.execute(
        session(),
        GENERATOR.createIndex(
            KEYSPACE,
            "users",
            new IndexDefinition(
                "users_email_sai", "email", IndexKind.SAI, null, Map.of(), true)),
        true);

    EXECUTOR.execute(
        session(),
        GENERATOR.createIndex(
            KEYSPACE,
            "users",
            new IndexDefinition(
                "users_embedding_ann",
                "embedding",
                IndexKind.SAI,
                null,
                Map.of("similarity_function", "cosine"),
                true)),
        true);
  }

  @Test
  void generatedDdlIsAcceptedByARealCluster() {
    assertThat(READER.keyspaces(session(), false))
        .extracting(io.cassyx.core.api.schema.KeyspaceInfo::name)
        .contains(KEYSPACE);
    assertThat(READER.table(session(), KEYSPACE, "users").identity().qualifiedName())
        .isEqualTo(KEYSPACE + ".users");
  }

  @Test
  void columnsCarryKindsIncludingStaticAndVector() {
    List<ColumnInfo> columns = READER.columns(session(), KEYSPACE, "users");

    assertThat(columns)
        .filteredOn(column -> column.name().equals("tenant_name"))
        .singleElement()
        .satisfies(column -> assertThat(column.kind()).isEqualTo(ColumnKind.STATIC));
    assertThat(columns)
        .filteredOn(column -> column.name().equals("embedding"))
        .singleElement()
        .satisfies(
            column -> {
              assertThat(column.vector()).isTrue();
              assertThat(column.vectorDimensions()).isEqualTo(1536);
            });
    assertThat(columns)
        .filteredOn(column -> column.name().equals("profile"))
        .singleElement()
        .satisfies(column -> assertThat(column.frozen()).isTrue());
  }

  @Test
  void indexesTabIsPopulatedIncludingTheVectorSaiIndex() {
    List<IndexInfo> indexes = READER.indexes(session(), KEYSPACE, "users");

    assertThat(indexes).extracting(IndexInfo::name).contains("users_email_sai", "users_embedding_ann");
    assertThat(indexes)
        .allSatisfy(index -> assertThat(index.identity().table()).isEqualTo("users"));
    assertThat(indexes)
        .filteredOn(index -> index.name().equals("users_embedding_ann"))
        .singleElement()
        .satisfies(
            index -> {
              assertThat(index.kind()).isEqualTo(IndexKind.SAI);
              assertThat(index.options()).containsEntry("similarity_function", "cosine");
            });
  }

  /**
   * CASSJAVA-2 regression.
   *
   * <p>Driver 4.x patches before 4.19.0 emitted invalid CQL for {@code vector} columns - the
   * describe output could not be fed back to the cluster. Assert both halves: the rendering is
   * right, AND the cluster accepts a table created from it.
   */
  @Test
  void vectorColumnsRoundTripThroughDescribe() {
    String describe =
        READER.describe(session(), SchemaIdentity.table(KEYSPACE, "users"), false, true);

    assertThat(describe).contains("vector<float, 1536>");
    assertThat(describe).doesNotContain("org.apache.cassandra.db.marshal.VectorType");

    String roundTripped =
        describe.replace(KEYSPACE + ".users", KEYSPACE + ".users_roundtrip");
    EXECUTOR.execute(
        session(),
        new io.cassyx.core.api.schema.DdlExecuteRequest(roundTripped, true, true, null));

    List<ColumnInfo> columns = READER.columns(session(), KEYSPACE, "users_roundtrip");
    assertThat(columns)
        .filteredOn(column -> column.name().equals("embedding"))
        .singleElement()
        .satisfies(
            column -> {
              assertThat(column.vector()).isTrue();
              assertThat(column.vectorDimensions()).isEqualTo(1536);
              assertThat(column.type()).isEqualTo("vector<float, 1536>");
            });
  }

  @Test
  void tableInfoPanelIsFullyPopulated() {
    TableInfo info = READER.tableInfo(session(), KEYSPACE, "users", false);

    assertThat(info.fields()).isNotEmpty();
    assertThat(info.indexes()).isNotEmpty();
    assertThat(info.comment()).isEqualTo("Application users");
    assertThat(info.definition()).contains("CREATE TABLE");
    assertThat(info.statisticsAvailable()).isFalse();
  }

  @Test
  void typesReportTheColumnsUsingThem() {
    assertThat(READER.type(session(), KEYSPACE, "address").usedBy())
        .extracting(SchemaIdentity::column)
        .contains("profile");
  }

  @Test
  void columnLifecycleIsDrivenEntirelyByGeneratedDdl() {
    DdlExecutionResult added =
        EXECUTOR.execute(
            session(),
            GENERATOR.addColumn(KEYSPACE, "users", ColumnDefinition.of("nickname", "text")),
            true);
    assertThat(added.success()).isTrue();
    assertThat(READER.columns(session(), KEYSPACE, "users"))
        .extracting(ColumnInfo::name)
        .contains("nickname");

    EXECUTOR.execute(session(), GENERATOR.dropColumn(KEYSPACE, "users", "nickname"), true);
    assertThat(READER.columns(session(), KEYSPACE, "users"))
        .extracting(ColumnInfo::name)
        .doesNotContain("nickname");
  }

  @Test
  void searchFindsObjectsAcrossKinds() {
    assertThat(READER.search(session(), "users", null, false, 100).matches())
        .anySatisfy(
            match ->
                assertThat(match.identity().qualifiedName()).isEqualTo(KEYSPACE + ".users"));
    assertThat(READER.search(session(), "embedding", null, false, 100).matches()).isNotEmpty();
  }
}
