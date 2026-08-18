package io.cassyx.core.impl.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.cassyx.core.api.schema.CachingSettings;
import io.cassyx.core.api.schema.ClusteringKeyColumn;
import io.cassyx.core.api.schema.ClusteringOrder;
import io.cassyx.core.api.schema.ColumnAlteration;
import io.cassyx.core.api.schema.ColumnDefinition;
import io.cassyx.core.api.schema.CompactionSettings;
import io.cassyx.core.api.schema.CompressionSettings;
import io.cassyx.core.api.schema.CqlPermission;
import io.cassyx.core.api.schema.DdlPreview;
import io.cassyx.core.api.schema.FunctionArgument;
import io.cassyx.core.api.schema.IndexDefinition;
import io.cassyx.core.api.schema.IndexKind;
import io.cassyx.core.api.schema.InvalidDefinitionException;
import io.cassyx.core.api.schema.KeyspaceDefinition;
import io.cassyx.core.api.schema.MaterializedViewDefinition;
import io.cassyx.core.api.schema.PermissionChange;
import io.cassyx.core.api.schema.PrimaryKeyDefinition;
import io.cassyx.core.api.schema.ReplicationSettings;
import io.cassyx.core.api.schema.RoleDefinition;
import io.cassyx.core.api.schema.SchemaObjectKind;
import io.cassyx.core.api.schema.TableDefinition;
import io.cassyx.core.api.schema.TableOptions;
import io.cassyx.core.api.schema.UdfNullHandling;
import io.cassyx.core.api.schema.UserDefinedAggregateDefinition;
import io.cassyx.core.api.schema.UserDefinedFunctionDefinition;
import io.cassyx.core.api.schema.UserDefinedTypeAlteration;
import io.cassyx.core.api.schema.UserDefinedTypeDefinition;
import io.cassyx.core.api.schema.UserDefinedTypeField;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The generator is a pure function, which is why it gets the densest test file in the module: every
 * statement the UI can produce is asserted here rather than against a container.
 */
class CqlDdlGeneratorTest {

  private final CqlDdlGenerator generator = new CqlDdlGenerator();

  private static TableDefinition usersTable() {
    return new TableDefinition(
        "users",
        List.of(
            ColumnDefinition.of("user_id", "uuid"),
            ColumnDefinition.of("created_at", "timestamp"),
            ColumnDefinition.of("email", "text"),
            new ColumnDefinition("tenant_name", "text", true, null)),
        new PrimaryKeyDefinition(
            List.of("user_id"),
            List.of(new ClusteringKeyColumn("created_at", ClusteringOrder.DESC))),
        null,
        true);
  }

  @Nested
  class Keyspaces {

    @Test
    void createsNetworkTopologyKeyspace() {
      DdlPreview preview =
          generator.createKeyspace(
              new KeyspaceDefinition(
                  "demo",
                  ReplicationSettings.networkTopology(Map.of("dc1", 3, "dc2", 2)),
                  true,
                  true));

      assertThat(preview.statements())
          .containsExactly(
              "CREATE KEYSPACE IF NOT EXISTS demo WITH replication = "
                  + "{'class': 'NetworkTopologyStrategy', 'dc1': '3', 'dc2': '2'} "
                  + "AND durable_writes = true;");
      assertThat(preview.targetIdentity().kind()).isEqualTo(SchemaObjectKind.KEYSPACE);
      assertThat(preview.targetIdentity().keyspace()).isEqualTo("demo");
    }

    @Test
    void createsSimpleStrategyKeyspaceWithoutIfNotExists() {
      DdlPreview preview =
          generator.createKeyspace(
              new KeyspaceDefinition("demo", ReplicationSettings.simple(1), false, false));

      assertThat(preview.cql())
          .isEqualTo(
              "CREATE KEYSPACE demo WITH replication = "
                  + "{'class': 'SimpleStrategy', 'replication_factor': '1'} "
                  + "AND durable_writes = false;");
    }

    @Test
    void quotesCaseSensitiveKeyspaceNames() {
      DdlPreview preview =
          generator.createKeyspace(
              new KeyspaceDefinition("MyKeyspace", ReplicationSettings.simple(1), null, null));

      assertThat(preview.cql()).startsWith("CREATE KEYSPACE IF NOT EXISTS \"MyKeyspace\"");
    }

    @Test
    void rejectsSimpleStrategyWithoutReplicationFactor() {
      KeyspaceDefinition definition =
          new KeyspaceDefinition(
              "demo",
              new ReplicationSettings(
                  io.cassyx.core.api.schema.ReplicationStrategy.SimpleStrategy, null, Map.of()),
              null,
              null);

      assertThatThrownBy(() -> generator.createKeyspace(definition))
          .isInstanceOf(InvalidDefinitionException.class)
          .hasMessageContaining("replication factor");
    }

    @Test
    void rejectsNetworkTopologyWithoutDatacenters() {
      KeyspaceDefinition definition =
          new KeyspaceDefinition("demo", ReplicationSettings.networkTopology(Map.of()), null, null);

      assertThatThrownBy(() -> generator.createKeyspace(definition))
          .isInstanceOf(InvalidDefinitionException.class)
          .hasMessageContaining("datacenter");
    }

    @Test
    void rejectsMissingReplication() {
      KeyspaceDefinition definition = new KeyspaceDefinition("demo", null, null, null);
      assertThatThrownBy(() -> generator.createKeyspace(definition))
          .isInstanceOf(InvalidDefinitionException.class);
    }

    @Test
    void rejectsBlankName() {
      KeyspaceDefinition definition =
          new KeyspaceDefinition(" ", ReplicationSettings.simple(1), null, null);
      assertThatThrownBy(() -> generator.createKeyspace(definition))
          .isInstanceOf(InvalidDefinitionException.class);
    }

    @Test
    void altersOnlyDurableWrites() {
      DdlPreview preview =
          generator.alterKeyspace("demo", new KeyspaceDefinition("demo", null, false, null));

      assertThat(preview.cql()).isEqualTo("ALTER KEYSPACE demo WITH durable_writes = false;");
    }

    @Test
    void altersReplicationAndDurableWrites() {
      DdlPreview preview =
          generator.alterKeyspace(
              "demo",
              new KeyspaceDefinition("demo", ReplicationSettings.simple(3), true, null));

      assertThat(preview.cql())
          .isEqualTo(
              "ALTER KEYSPACE demo WITH replication = "
                  + "{'class': 'SimpleStrategy', 'replication_factor': '3'} AND durable_writes = true;");
    }

    @Test
    void rejectsEmptyKeyspaceAlteration() {
      KeyspaceDefinition definition = new KeyspaceDefinition("demo", null, null, null);
      assertThatThrownBy(() -> generator.alterKeyspace("demo", definition))
          .isInstanceOf(InvalidDefinitionException.class);
    }

    @Test
    void dropWarnsThatItIsIrreversible() {
      DdlPreview preview = generator.dropKeyspace("demo", true);

      assertThat(preview.cql()).isEqualTo("DROP KEYSPACE IF EXISTS demo;");
      assertThat(preview.warnings()).anySatisfy(w -> assertThat(w).contains("Irreversible"));
    }

    @Test
    void dropWithoutIfExists() {
      assertThat(generator.dropKeyspace("demo", false).cql()).isEqualTo("DROP KEYSPACE demo;");
    }
  }

  @Nested
  class Tables {

    @Test
    void createsTableWithStaticColumnAndClusteringOrder() {
      DdlPreview preview = generator.createTable("demo", usersTable());

      assertThat(preview.cql())
          .isEqualTo(
              """
              CREATE TABLE IF NOT EXISTS demo.users (
                  user_id uuid,
                  created_at timestamp,
                  email text,
                  tenant_name text STATIC,
                  PRIMARY KEY (user_id, created_at)
              )
                  WITH CLUSTERING ORDER BY (created_at DESC);""");
      assertThat(preview.targetIdentity().table()).isEqualTo("users");
      assertThat(preview.targetIdentity().qualifiedName()).isEqualTo("demo.users");
    }

    @Test
    void createsCompositePartitionKey() {
      TableDefinition definition =
          new TableDefinition(
              "events",
              List.of(
                  ColumnDefinition.of("tenant", "text"),
                  ColumnDefinition.of("day", "date"),
                  ColumnDefinition.of("id", "timeuuid")),
              new PrimaryKeyDefinition(
                  List.of("tenant", "day"), List.of(ClusteringKeyColumn.asc("id"))),
              null,
              false);

      assertThat(generator.createTable("demo", definition).cql())
          .contains("PRIMARY KEY ((tenant, day), id)")
          .startsWith("CREATE TABLE demo.events");
    }

    @Test
    void createsTableWithFullOptionSurface() {
      TableOptions options =
          new TableOptions(
              "Application users",
              new CompactionSettings(
                  "LeveledCompactionStrategy", Map.of("sstable_size_in_mb", "160")),
              new CompressionSettings("LZ4Compressor", 16, 1.0),
              new CachingSettings("ALL", "NONE"),
              0.01,
              864000,
              0,
              "BLOCKING",
              0.0,
              0.0,
              "99p",
              "99p",
              128,
              2048,
              0,
              1.0,
              false,
              Map.of("custom_option", "value"));
      TableDefinition definition =
          new TableDefinition(
              "t",
              List.of(ColumnDefinition.of("k", "text")),
              new PrimaryKeyDefinition(List.of("k"), List.of()),
              options,
              true);

      String cql = generator.createTable("demo", definition).cql();

      assertThat(cql)
          .contains("comment = 'Application users'")
          .contains("compaction = {'class': 'LeveledCompactionStrategy', 'sstable_size_in_mb': '160'}")
          .contains("compression = {'chunk_length_in_kb': '16'")
          .contains("caching = {'keys': 'ALL', 'rows_per_partition': 'NONE'}")
          .contains("bloom_filter_fp_chance = 0.01")
          .contains("gc_grace_seconds = 864000")
          .contains("default_time_to_live = 0")
          .contains("read_repair = 'BLOCKING'")
          .contains("read_repair_chance = 0.0")
          .contains("dclocal_read_repair_chance = 0.0")
          .contains("speculative_retry = '99p'")
          .contains("additional_write_policy = '99p'")
          .contains("min_index_interval = 128")
          .contains("max_index_interval = 2048")
          .contains("memtable_flush_period_in_ms = 0")
          .contains("crc_check_chance = 1.0")
          .contains("cdc = false")
          .contains("custom_option = 'value'");
    }

    @Test
    void disabledCompressionRendersEnabledFalse() {
      TableDefinition definition =
          new TableDefinition(
              "t",
              List.of(ColumnDefinition.of("k", "text")),
              new PrimaryKeyDefinition(List.of("k"), List.of()),
              new TableOptions(
                  null,
                  null,
                  new CompressionSettings("none", null, null),
                  null,
                  null,
                  null,
                  null,
                  null,
                  null,
                  null,
                  null,
                  null,
                  null,
                  null,
                  null,
                  null,
                  null,
                  Map.of()),
              true);

      assertThat(generator.createTable("demo", definition).cql())
          .contains("compression = {'enabled': 'false'}");
    }

    @Test
    void warnsAboutVectorColumns() {
      TableDefinition definition =
          new TableDefinition(
              "product_embeddings",
              List.of(
                  ColumnDefinition.of("product_id", "uuid"),
                  ColumnDefinition.of("embedding", "vector<float, 1536>")),
              new PrimaryKeyDefinition(List.of("product_id"), List.of()),
              null,
              true);

      DdlPreview preview = generator.createTable("demo", definition);

      assertThat(preview.cql()).contains("embedding vector<float, 1536>");
      assertThat(preview.warnings()).anySatisfy(w -> assertThat(w).contains("CASSJAVA-2"));
    }

    @Test
    void rejectsPrimaryKeyColumnThatIsNotDeclared() {
      TableDefinition definition =
          new TableDefinition(
              "t",
              List.of(ColumnDefinition.of("a", "text")),
              new PrimaryKeyDefinition(List.of("missing"), List.of()),
              null,
              true);

      assertThatThrownBy(() -> generator.createTable("demo", definition))
          .isInstanceOf(InvalidDefinitionException.class)
          .hasMessageContaining("missing");
    }

    @Test
    void rejectsTableWithoutColumns() {
      TableDefinition definition =
          new TableDefinition("t", List.of(), new PrimaryKeyDefinition(List.of("a"), null), null, true);

      assertThatThrownBy(() -> generator.createTable("demo", definition))
          .isInstanceOf(InvalidDefinitionException.class);
    }

    @Test
    void rejectsTableWithoutPartitionKey() {
      TableDefinition definition =
          new TableDefinition("t", List.of(ColumnDefinition.of("a", "text")), null, null, true);

      assertThatThrownBy(() -> generator.createTable("demo", definition))
          .isInstanceOf(InvalidDefinitionException.class)
          .hasMessageContaining("partition key");
    }

    @Test
    void rejectsColumnWithoutType() {
      TableDefinition definition =
          new TableDefinition(
              "t",
              List.of(new ColumnDefinition("a", null, false, null)),
              new PrimaryKeyDefinition(List.of("a"), null),
              null,
              true);

      assertThatThrownBy(() -> generator.createTable("demo", definition))
          .isInstanceOf(InvalidDefinitionException.class);
    }

    @Test
    void altersOptions() {
      DdlPreview preview = generator.alterTable("demo", "users", TableOptions.comment("hello"));
      assertThat(preview.cql()).isEqualTo("ALTER TABLE demo.users WITH comment = 'hello';");
    }

    @Test
    void escapesQuotesInComments() {
      DdlPreview preview =
          generator.alterTable("demo", "users", TableOptions.comment("it's fine"));
      assertThat(preview.cql()).isEqualTo("ALTER TABLE demo.users WITH comment = 'it''s fine';");
    }

    @Test
    void rejectsEmptyOptionChange() {
      assertThatThrownBy(() -> generator.alterTable("demo", "users", TableOptions.empty()))
          .isInstanceOf(InvalidDefinitionException.class);
    }

    @Test
    void dropsAndTruncates() {
      assertThat(generator.dropTable("demo", "users", true).cql())
          .isEqualTo("DROP TABLE IF EXISTS demo.users;");
      assertThat(generator.dropTable("demo", "users", false).cql())
          .isEqualTo("DROP TABLE demo.users;");
      DdlPreview truncate = generator.truncateTable("demo", "users");
      assertThat(truncate.cql()).isEqualTo("TRUNCATE TABLE demo.users;");
      assertThat(truncate.warnings()).isNotEmpty();
    }
  }

  @Nested
  class Columns {

    @Test
    void addsStaticAndVectorColumns() {
      assertThat(generator.addColumn("demo", "users", ColumnDefinition.of("nickname", "text")).cql())
          .isEqualTo("ALTER TABLE demo.users ADD nickname text;");
      assertThat(
              generator
                  .addColumn("demo", "users", new ColumnDefinition("tenant", "text", true, null))
                  .cql())
          .isEqualTo("ALTER TABLE demo.users ADD tenant text STATIC;");

      DdlPreview vector =
          generator.addColumn(
              "demo", "users", ColumnDefinition.of("embedding", "vector<float, 1536>"));
      assertThat(vector.warnings()).anySatisfy(w -> assertThat(w).contains("CASSJAVA-2"));
    }

    @Test
    void renamesAColumn() {
      DdlPreview preview =
          generator.alterColumn("demo", "users", "email", new ColumnAlteration("user_email", null));

      assertThat(preview.cql()).isEqualTo("ALTER TABLE demo.users RENAME email TO user_email;");
      assertThat(preview.targetIdentity().column()).isEqualTo("user_email");
    }

    @Test
    void retypesAColumn() {
      DdlPreview preview =
          generator.alterColumn("demo", "users", "count", new ColumnAlteration(null, "bigint"));

      assertThat(preview.cql()).isEqualTo("ALTER TABLE demo.users ALTER count TYPE bigint;");
    }

    @Test
    void rejectsAmbiguousOrEmptyAlteration() {
      assertThatThrownBy(
              () ->
                  generator.alterColumn(
                      "demo", "users", "email", new ColumnAlteration("a", "text")))
          .isInstanceOf(InvalidDefinitionException.class);
      assertThatThrownBy(
              () -> generator.alterColumn("demo", "users", "email", new ColumnAlteration(null, null)))
          .isInstanceOf(InvalidDefinitionException.class);
      assertThatThrownBy(() -> generator.alterColumn("demo", "users", "email", null))
          .isInstanceOf(InvalidDefinitionException.class);
    }

    @Test
    void dropsAColumnWithAWarning() {
      DdlPreview preview = generator.dropColumn("demo", "users", "email");
      assertThat(preview.cql()).isEqualTo("ALTER TABLE demo.users DROP email;");
      assertThat(preview.warnings()).isNotEmpty();
    }
  }

  @Nested
  class Indexes {

    @Test
    void createsSaiIndexWithDefaultClass() {
      DdlPreview preview =
          generator.createIndex(
              "demo",
              "users",
              new IndexDefinition("users_email_idx", "email", IndexKind.SAI, null, Map.of(), true));

      assertThat(preview.cql())
          .isEqualTo(
              "CREATE CUSTOM INDEX IF NOT EXISTS users_email_idx ON demo.users (email) "
                  + "USING 'org.apache.cassandra.index.sai.StorageAttachedIndex';");
      assertThat(preview.targetIdentity().table()).isEqualTo("users");
      assertThat(preview.targetIdentity().index()).isEqualTo("users_email_idx");
    }

    @Test
    void createsVectorSaiIndexWithSimilarityFunction() {
      DdlPreview preview =
          generator.createIndex(
              "demo",
              "product_embeddings",
              new IndexDefinition(
                  "embedding_ann_idx",
                  "embedding",
                  IndexKind.SAI,
                  null,
                  Map.of("similarity_function", "cosine"),
                  true));

      assertThat(preview.cql()).endsWith("WITH OPTIONS = {'similarity_function': 'cosine'};");
    }

    @Test
    void createsLegacySecondaryIndexWithAWarning() {
      DdlPreview preview =
          generator.createIndex(
              "demo",
              "users",
              new IndexDefinition(
                  "users_email_idx", "email", IndexKind.COMPOSITES, null, Map.of(), false));

      assertThat(preview.cql())
          .isEqualTo("CREATE INDEX users_email_idx ON demo.users (email);");
      assertThat(preview.warnings()).anySatisfy(w -> assertThat(w).contains("SAI"));
    }

    @Test
    void createsCollectionIndexTargets() {
      DdlPreview preview =
          generator.createIndex(
              "demo",
              "users",
              new IndexDefinition(
                  "prefs_idx", "keys(preferences)", IndexKind.KEYS, null, Map.of(), true));

      assertThat(preview.cql()).contains("(keys(preferences))");
    }

    @Test
    void createsDseSearchIndex() {
      DdlPreview preview =
          generator.createIndex(
              "demo",
              "users",
              new IndexDefinition("solr_idx", "email", IndexKind.DSE_SEARCH, null, Map.of(), true));

      assertThat(preview.cql()).contains("Cql3SolrSecondaryIndex");
      assertThat(preview.warnings()).anySatisfy(w -> assertThat(w).contains("DataStax Enterprise"));
    }

    @Test
    void createsCustomIndexAndRequiresAClassName() {
      assertThat(
              generator
                  .createIndex(
                      "demo",
                      "users",
                      new IndexDefinition(
                          "custom_idx", "email", IndexKind.CUSTOM, "com.example.Index", Map.of(), true))
                  .cql())
          .contains("USING 'com.example.Index'");

      IndexDefinition invalid =
          new IndexDefinition("custom_idx", "email", IndexKind.CUSTOM, null, Map.of(), true);
      assertThatThrownBy(() -> generator.createIndex("demo", "users", invalid))
          .isInstanceOf(InvalidDefinitionException.class);
    }

    @Test
    void defaultsToLegacyWhenNoKindGiven() {
      assertThat(
              generator
                  .createIndex(
                      "demo", "users", new IndexDefinition("i", "email", null, null, Map.of(), true))
                  .cql())
          .startsWith("CREATE INDEX IF NOT EXISTS i ON demo.users");
    }

    @Test
    void rejectsMissingNameOrTarget() {
      IndexDefinition noName =
          new IndexDefinition(null, "email", IndexKind.SAI, null, Map.of(), true);
      IndexDefinition noTarget =
          new IndexDefinition("i", " ", IndexKind.SAI, null, Map.of(), true);
      assertThatThrownBy(() -> generator.createIndex("demo", "users", noName))
          .isInstanceOf(InvalidDefinitionException.class);
      assertThatThrownBy(() -> generator.createIndex("demo", "users", noTarget))
          .isInstanceOf(InvalidDefinitionException.class);
    }

    @Test
    void dropsAnIndexQualifiedByKeyspaceButIdentifiedByTable() {
      DdlPreview preview = generator.dropIndex("demo", "users", "users_email_idx", true);

      assertThat(preview.cql()).isEqualTo("DROP INDEX IF EXISTS demo.users_email_idx;");
      assertThat(preview.targetIdentity().table()).isEqualTo("users");
    }
  }

  @Nested
  class MaterializedViews {

    @Test
    void generatesWhereClauseFromThePrimaryKeyWhenOmitted() {
      DdlPreview preview =
          generator.createMaterializedView(
              "demo",
              new MaterializedViewDefinition(
                  "users_by_email",
                  "users",
                  List.of("user_id", "email", "created_at"),
                  new PrimaryKeyDefinition(
                      List.of("email"), List.of(ClusteringKeyColumn.asc("user_id"))),
                  null,
                  null,
                  true));

      assertThat(preview.cql())
          .isEqualTo(
              """
              CREATE MATERIALIZED VIEW IF NOT EXISTS demo.users_by_email AS
                  SELECT user_id, email, created_at
                  FROM demo.users
                  WHERE email IS NOT NULL AND user_id IS NOT NULL
                  PRIMARY KEY (email, user_id)
                  WITH CLUSTERING ORDER BY (user_id ASC);""");
      assertThat(preview.targetIdentity().view()).isEqualTo("users_by_email");
    }

    @Test
    void selectsAllColumnsWhenNoProjectionGiven() {
      DdlPreview preview =
          generator.createMaterializedView(
              "demo",
              new MaterializedViewDefinition(
                  "v",
                  "users",
                  List.of(),
                  new PrimaryKeyDefinition(List.of("email"), List.of()),
                  "email IS NOT NULL",
                  null,
                  false));

      assertThat(preview.cql()).contains("SELECT *").contains("WHERE email IS NOT NULL");
    }

    @Test
    void rejectsMissingBaseTableOrPrimaryKey() {
      MaterializedViewDefinition noBase =
          new MaterializedViewDefinition(
              "v", null, List.of(), new PrimaryKeyDefinition(List.of("a"), null), null, null, true);
      MaterializedViewDefinition noKey =
          new MaterializedViewDefinition("v", "users", List.of(), null, null, null, true);

      assertThatThrownBy(() -> generator.createMaterializedView("demo", noBase))
          .isInstanceOf(InvalidDefinitionException.class);
      assertThatThrownBy(() -> generator.createMaterializedView("demo", noKey))
          .isInstanceOf(InvalidDefinitionException.class);
    }

    @Test
    void altersAndDrops() {
      assertThat(
              generator.alterMaterializedView("demo", "v", TableOptions.comment("hi")).cql())
          .isEqualTo("ALTER MATERIALIZED VIEW demo.v WITH comment = 'hi';");
      assertThat(generator.dropMaterializedView("demo", "v", true).cql())
          .isEqualTo("DROP MATERIALIZED VIEW IF EXISTS demo.v;");
      assertThatThrownBy(
              () -> generator.alterMaterializedView("demo", "v", TableOptions.empty()))
          .isInstanceOf(InvalidDefinitionException.class);
    }
  }

  @Nested
  class UserDefinedTypes {

    @Test
    void createsAType() {
      DdlPreview preview =
          generator.createType(
              "demo",
              new UserDefinedTypeDefinition(
                  "address",
                  List.of(
                      new UserDefinedTypeField("street", "text"),
                      new UserDefinedTypeField("postcode", "text")),
                  true));

      assertThat(preview.cql())
          .isEqualTo(
              """
              CREATE TYPE IF NOT EXISTS demo.address (
                  street text,
                  postcode text
              );""");
    }

    @Test
    void rejectsATypeWithoutFields() {
      UserDefinedTypeDefinition definition =
          new UserDefinedTypeDefinition("address", List.of(), true);
      assertThatThrownBy(() -> generator.createType("demo", definition))
          .isInstanceOf(InvalidDefinitionException.class);
    }

    @Test
    void addsAndRenamesFields() {
      DdlPreview preview =
          generator.alterType(
              "demo",
              "address",
              new UserDefinedTypeAlteration(
                  List.of(new UserDefinedTypeField("country", "text")),
                  Map.of("postcode", "postal_code")));

      assertThat(preview.statements())
          .containsExactly(
              "ALTER TYPE demo.address ADD country text;",
              "ALTER TYPE demo.address RENAME postcode TO postal_code;");
    }

    @Test
    void rejectsAnEmptyAlteration() {
      assertThatThrownBy(() -> generator.alterType("demo", "address", null))
          .isInstanceOf(InvalidDefinitionException.class);
      UserDefinedTypeAlteration empty = new UserDefinedTypeAlteration(List.of(), Map.of());
      assertThatThrownBy(() -> generator.alterType("demo", "address", empty))
          .isInstanceOf(InvalidDefinitionException.class);
    }

    @Test
    void dropsATypeWithAWarningAboutReferences() {
      DdlPreview preview = generator.dropType("demo", "address", true);
      assertThat(preview.cql()).isEqualTo("DROP TYPE IF EXISTS demo.address;");
      assertThat(preview.warnings()).isNotEmpty();
    }
  }

  @Nested
  class FunctionsAndAggregates {

    @Test
    void createsAFunctionWithNullHandling() {
      DdlPreview preview =
          generator.createFunction(
              "demo",
              new UserDefinedFunctionDefinition(
                  "avg_state",
                  List.of(new FunctionArgument("state", "tuple<double, int>"),
                      new FunctionArgument("val", "double")),
                  "tuple<double, int>",
                  "java",
                  " return state; ",
                  UdfNullHandling.RETURNS_NULL_ON_NULL_INPUT,
                  false,
                  true));

      assertThat(preview.cql())
          .isEqualTo(
              """
              CREATE FUNCTION IF NOT EXISTS demo.avg_state (state tuple<double, int>, val double)
                  RETURNS NULL ON NULL INPUT
                  RETURNS tuple<double, int>
                  LANGUAGE java
                  AS $$ return state; $$;""");
      assertThat(preview.targetIdentity().signature()).isEqualTo("(tuple<double, int>,double)");
    }

    @Test
    void orReplaceSuppressesIfNotExists() {
      DdlPreview preview =
          generator.createFunction(
              "demo",
              new UserDefinedFunctionDefinition(
                  "f", List.of(), "int", "java", "return 1;", null, true, true));

      assertThat(preview.cql()).startsWith("CREATE OR REPLACE FUNCTION demo.f ()");
      assertThat(preview.cql()).contains("CALLED ON NULL INPUT");
    }

    @Test
    void rejectsIncompleteFunctions() {
      UserDefinedFunctionDefinition noBody =
          new UserDefinedFunctionDefinition("f", List.of(), "int", "java", null, null, false, true);
      assertThatThrownBy(() -> generator.createFunction("demo", noBody))
          .isInstanceOf(InvalidDefinitionException.class);
    }

    @Test
    void dropsAFunctionBySignature() {
      assertThat(generator.dropFunction("demo", "avg_state(double,int)", true).cql())
          .isEqualTo("DROP FUNCTION IF EXISTS demo.avg_state(double, int);");
      assertThat(generator.dropFunction("demo", "noargs", false).cql())
          .isEqualTo("DROP FUNCTION demo.noargs();");
      assertThat(generator.dropFunction("demo", "noargs()", false).cql())
          .isEqualTo("DROP FUNCTION demo.noargs();");
    }

    @Test
    void createsAnAggregate() {
      DdlPreview preview =
          generator.createAggregate(
              "demo",
              new UserDefinedAggregateDefinition(
                  "average",
                  List.of("double"),
                  "avg_state",
                  "tuple<double, int>",
                  "avg_final",
                  "(0.0, 0)",
                  false,
                  true));

      assertThat(preview.cql())
          .isEqualTo(
              """
              CREATE AGGREGATE IF NOT EXISTS demo.average (double)
                  SFUNC avg_state
                  STYPE tuple<double, int>
                  FINALFUNC avg_final
                  INITCOND (0.0, 0);""");
      assertThat(preview.targetIdentity().signature()).isEqualTo("(double)");
    }

    @Test
    void createsAMinimalAggregate() {
      DdlPreview preview =
          generator.createAggregate(
              "demo",
              new UserDefinedAggregateDefinition(
                  "total", List.of("int"), "sum_state", "int", null, null, true, false));

      assertThat(preview.cql())
          .isEqualTo(
              """
              CREATE OR REPLACE AGGREGATE demo.total (int)
                  SFUNC sum_state
                  STYPE int;""");
    }

    @Test
    void rejectsIncompleteAggregates() {
      UserDefinedAggregateDefinition noState =
          new UserDefinedAggregateDefinition("a", List.of("int"), null, "int", null, null, false, true);
      assertThatThrownBy(() -> generator.createAggregate("demo", noState))
          .isInstanceOf(InvalidDefinitionException.class);
    }

    @Test
    void dropsAnAggregateBySignature() {
      assertThat(generator.dropAggregate("demo", "average(double)", true).cql())
          .isEqualTo("DROP AGGREGATE IF EXISTS demo.average(double);");
    }

    @Test
    void rejectsABlankSignature() {
      assertThatThrownBy(() -> generator.dropFunction("demo", " ", true))
          .isInstanceOf(InvalidDefinitionException.class);
    }
  }

  @Nested
  class RolesAndPermissions {

    @Test
    void createsARoleWithMemberships() {
      DdlPreview preview =
          generator.createRole(
              new RoleDefinition(
                  "app_reader", "s3cret", false, true, List.of("readers"), Map.of(), true));

      assertThat(preview.statements())
          .containsExactly(
              "CREATE ROLE IF NOT EXISTS app_reader WITH PASSWORD = 's3cret' "
                  + "AND LOGIN = true AND SUPERUSER = false;",
              "GRANT readers TO app_reader;");
      assertThat(preview.warnings()).anySatisfy(w -> assertThat(w).contains("redacted"));
    }

    @Test
    void appliesRoleDefaultsAndOptions() {
      DdlPreview preview =
          generator.createRole(
              new RoleDefinition("r", null, null, null, List.of(), Map.of("k", "v"), false));

      assertThat(preview.cql())
          .isEqualTo("CREATE ROLE r WITH LOGIN = true AND SUPERUSER = false AND OPTIONS = {'k': 'v'};");
      assertThat(preview.warnings()).isEmpty();
    }

    @Test
    void altersOnlyWhatChanged() {
      DdlPreview preview =
          generator.alterRole(
              "app_reader", new RoleDefinition("app_reader", null, true, null, null, null, null));

      assertThat(preview.cql()).isEqualTo("ALTER ROLE app_reader WITH SUPERUSER = true;");
    }

    @Test
    void rejectsAnEmptyRoleAlteration() {
      RoleDefinition empty = new RoleDefinition("r", null, null, null, null, null, null);
      assertThatThrownBy(() -> generator.alterRole("r", empty))
          .isInstanceOf(InvalidDefinitionException.class);
    }

    @Test
    void dropsARole() {
      assertThat(generator.dropRole("app_reader", true).cql())
          .isEqualTo("DROP ROLE IF EXISTS app_reader;");
    }

    @Test
    void grantsOneStatementPerPermission() {
      DdlPreview preview =
          generator.grant(
              new PermissionChange(
                  "app_reader", "table demo.users", List.of(CqlPermission.SELECT, CqlPermission.MODIFY)));

      assertThat(preview.statements())
          .containsExactly(
              "GRANT SELECT ON table demo.users TO app_reader;",
              "GRANT MODIFY ON table demo.users TO app_reader;");
    }

    @Test
    void revokesAndExpandsAll() {
      DdlPreview preview =
          generator.revoke(
              new PermissionChange("app_reader", "keyspace demo", List.of(CqlPermission.ALL)));

      assertThat(preview.statements())
          .containsExactly("REVOKE ALL PERMISSIONS ON keyspace demo FROM app_reader;");
    }

    @Test
    void rejectsIncompletePermissionChanges() {
      PermissionChange noPermissions = new PermissionChange("r", "keyspace demo", List.of());
      PermissionChange noRole = new PermissionChange(null, "keyspace demo", List.of(CqlPermission.SELECT));
      assertThatThrownBy(() -> generator.grant(noPermissions))
          .isInstanceOf(InvalidDefinitionException.class);
      assertThatThrownBy(() -> generator.grant(noRole))
          .isInstanceOf(InvalidDefinitionException.class);
    }
  }

  @Nested
  @DisplayName("identity never depends on tree position (plan 1, 4, 7.3)")
  class IdentityRegression {

    @Test
    void aTableNamedUsersInDemoIsNeverSystemAuthUsers() {
      DdlPreview demo = generator.dropTable("demo", "users", true);
      DdlPreview systemAuth = generator.dropTable("system_auth", "users", true);

      assertThat(demo.targetIdentity().keyspace()).isEqualTo("demo");
      assertThat(demo.cql()).contains("demo.users").doesNotContain("system_auth");
      assertThat(systemAuth.targetIdentity().keyspace()).isEqualTo("system_auth");
    }
  }
}
