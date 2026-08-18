package io.cassyx.core.impl.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.metadata.schema.ColumnMetadata;
import com.datastax.oss.driver.api.core.metadata.schema.IndexMetadata;
import com.datastax.oss.driver.api.core.metadata.schema.KeyspaceMetadata;
import com.datastax.oss.driver.api.core.metadata.schema.TableMetadata;
import com.datastax.oss.driver.api.core.metadata.schema.ViewMetadata;
import com.datastax.oss.driver.api.core.type.DataTypes;
import io.cassyx.core.api.schema.ColumnInfo;
import io.cassyx.core.api.schema.ColumnKind;
import io.cassyx.core.api.schema.IndexInfo;
import io.cassyx.core.api.schema.IndexKind;
import io.cassyx.core.api.schema.KeyspaceInfo;
import io.cassyx.core.api.schema.ReplicationStrategy;
import io.cassyx.core.api.schema.SchemaIdentity;
import io.cassyx.core.api.schema.SchemaNode;
import io.cassyx.core.api.schema.SchemaNotFoundException;
import io.cassyx.core.api.schema.SchemaObjectKind;
import io.cassyx.core.api.schema.SchemaSearchResult;
import io.cassyx.core.api.schema.SchemaTreeSnapshot;
import io.cassyx.core.api.schema.SearchMatchKind;
import io.cassyx.core.api.schema.TableDetail;
import io.cassyx.core.api.schema.TableInfo;
import io.cassyx.core.api.schema.UserDefinedTypeInfo;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MetadataSchemaReaderTest {

  private final MetadataSchemaReader reader = new MetadataSchemaReader();

  private static CqlSession cluster() {
    ColumnMetadata userId = FakeSchema.column("user_id", DataTypes.UUID, false);
    ColumnMetadata createdAt = FakeSchema.column("created_at", DataTypes.TIMESTAMP, false);
    ColumnMetadata email = FakeSchema.column("email", DataTypes.TEXT, false);
    ColumnMetadata tenant = FakeSchema.column("tenant_name", DataTypes.TEXT, true);
    ColumnMetadata hits = FakeSchema.column("hits", DataTypes.COUNTER, false);
    ColumnMetadata prefs =
        FakeSchema.column("preferences", DataTypes.mapOf(DataTypes.TEXT, DataTypes.TEXT), false);
    ColumnMetadata embedding =
        FakeSchema.column("embedding", DataTypes.vectorOf(DataTypes.FLOAT, 1536), false);

    Map<ColumnMetadata, com.datastax.oss.driver.api.core.metadata.schema.ClusteringOrder> clustering =
        new LinkedHashMap<>();
    clustering.put(
        createdAt, com.datastax.oss.driver.api.core.metadata.schema.ClusteringOrder.DESC);

    IndexMetadata sai =
        FakeSchema.index(
            "users_email_idx",
            "email",
            com.datastax.oss.driver.api.core.metadata.schema.IndexKind.CUSTOM,
            "org.apache.cassandra.index.sai.StorageAttachedIndex");
    IndexMetadata legacy =
        FakeSchema.index(
            "users_prefs_idx",
            "keys(preferences)",
            com.datastax.oss.driver.api.core.metadata.schema.IndexKind.KEYS,
            null);

    Map<com.datastax.oss.driver.api.core.CqlIdentifier, Object> options = new LinkedHashMap<>();
    options.put(
        com.datastax.oss.driver.api.core.CqlIdentifier.fromInternal("comment"), "Application users");
    options.put(
        com.datastax.oss.driver.api.core.CqlIdentifier.fromInternal("gc_grace_seconds"), 864000);
    options.put(
        com.datastax.oss.driver.api.core.CqlIdentifier.fromInternal("bloom_filter_fp_chance"), 0.01);
    options.put(
        com.datastax.oss.driver.api.core.CqlIdentifier.fromInternal("compaction"),
        Map.of("class", "org.apache.cassandra.db.compaction.SizeTieredCompactionStrategy",
            "min_threshold", "4"));
    options.put(
        com.datastax.oss.driver.api.core.CqlIdentifier.fromInternal("compression"),
        Map.of("class", "org.apache.cassandra.io.compress.LZ4Compressor", "chunk_length_in_kb", "16"));
    options.put(
        com.datastax.oss.driver.api.core.CqlIdentifier.fromInternal("caching"),
        Map.of("keys", "ALL", "rows_per_partition", "NONE"));
    options.put(com.datastax.oss.driver.api.core.CqlIdentifier.fromInternal("cdc"), Boolean.FALSE);

    TableMetadata users =
        FakeSchema.table(
            "users",
            List.of(userId),
            clustering,
            List.of(email, tenant, prefs, hits, embedding),
            List.of(sai, legacy),
            options);

    ViewMetadata usersByEmail =
        FakeSchema.view(
            "users_by_email", "users", List.of(email), List.of(userId), "email IS NOT NULL");

    KeyspaceMetadata demo =
        FakeSchema.keyspace(
            "demo",
            Map.of("class", "org.apache.cassandra.locator.NetworkTopologyStrategy", "dc1", "3"),
            List.of(users),
            List.of(usersByEmail),
            List.of(FakeSchema.udt("address", Map.of("street", DataTypes.TEXT))),
            List.of(FakeSchema.function("avg_state", DataTypes.DOUBLE, DataTypes.INT)),
            List.of(FakeSchema.aggregate("average", "avg_state", DataTypes.DOUBLE)));

    // Same table name, different keyspace: the prior-art keyspace-resolution bug in fixture form.
    KeyspaceMetadata systemAuth =
        FakeSchema.keyspace(
            "system_auth",
            Map.of("class", "org.apache.cassandra.locator.SimpleStrategy", "replication_factor", "1"),
            List.of(
                FakeSchema.table(
                    "users",
                    List.of(FakeSchema.column("name", DataTypes.TEXT, false)),
                    Map.of(),
                    List.of(),
                    List.of(),
                    Map.of())),
            List.of(),
            List.of(),
            List.of(),
            List.of());

    return FakeSchema.session(demo, systemAuth);
  }

  @Test
  void treeHidesSystemKeyspacesUnlessAsked() {
    SchemaTreeSnapshot hidden = reader.tree(cluster(), "conn-1", false);
    assertThat(hidden.keyspaces()).extracting(SchemaNode::label).containsExactly("demo");
    assertThat(hidden.connectionId()).isEqualTo("conn-1");
    assertThat(hidden.schemaVersion()).isNotBlank();

    SchemaTreeSnapshot shown = reader.tree(cluster(), "conn-1", true);
    assertThat(shown.keyspaces()).extracting(SchemaNode::label).containsExactly("demo", "system_auth");
    assertThat(shown.keyspaces().get(1).system()).isTrue();
  }

  @Test
  void everyTreeNodeCarriesItsOwnFullyQualifiedIdentity() {
    SchemaTreeSnapshot tree = reader.tree(cluster(), "conn-1", true);

    SchemaNode systemAuthUsers =
        tree.keyspaces().get(1).children().stream()
            .filter(node -> node.kind() == SchemaObjectKind.TABLE)
            .findFirst()
            .orElseThrow();
    SchemaNode demoUsers =
        tree.keyspaces().get(0).children().stream()
            .filter(node -> node.kind() == SchemaObjectKind.TABLE)
            .findFirst()
            .orElseThrow();

    assertThat(demoUsers.label()).isEqualTo(systemAuthUsers.label()).isEqualTo("users");
    assertThat(demoUsers.identity().qualifiedName()).isEqualTo("demo.users");
    assertThat(systemAuthUsers.identity().qualifiedName()).isEqualTo("system_auth.users");
    assertThat(demoUsers.children())
        .allSatisfy(child -> assertThat(child.identity().keyspace()).isEqualTo("demo"));
  }

  @Test
  void treeExposesEveryObjectKind() {
    SchemaTreeSnapshot tree = reader.tree(cluster(), "conn-1", false);
    List<SchemaNode> children = tree.keyspaces().get(0).children();

    assertThat(children)
        .extracting(SchemaNode::kind)
        .contains(
            SchemaObjectKind.TABLE,
            SchemaObjectKind.VIEW,
            SchemaObjectKind.TYPE,
            SchemaObjectKind.FUNCTION,
            SchemaObjectKind.AGGREGATE);
  }

  @Test
  void tableNodeChildrenIncludeColumnsAndIndexes() {
    SchemaNode table =
        reader.tree(cluster(), "conn-1", false).keyspaces().get(0).children().get(0);

    assertThat(table.children())
        .extracting(SchemaNode::kind)
        .contains(SchemaObjectKind.COLUMN, SchemaObjectKind.INDEX);
    assertThat(table.children())
        .filteredOn(node -> node.kind() == SchemaObjectKind.COLUMN && "tenant_name".equals(node.label()))
        .singleElement()
        .satisfies(node -> assertThat(node.detail()).isEqualTo("text | STATIC"));
  }

  @Test
  void keyspaceReplicationIsParsed() {
    List<KeyspaceInfo> keyspaces = reader.keyspaces(cluster(), true);

    assertThat(keyspaces).extracting(KeyspaceInfo::name).containsExactly("demo", "system_auth");
    assertThat(keyspaces.get(0).replication().strategy())
        .isEqualTo(ReplicationStrategy.NetworkTopologyStrategy);
    assertThat(keyspaces.get(0).replication().datacenters()).containsEntry("dc1", 3);
    assertThat(keyspaces.get(1).replication().replicationFactor()).isEqualTo(1);
    assertThat(keyspaces.get(0).tableCount()).isEqualTo(1);
    assertThat(keyspaces.get(0).typeCount()).isEqualTo(1);
    assertThat(keyspaces.get(0).functionCount()).isEqualTo(1);
    assertThat(keyspaces.get(0).aggregateCount()).isEqualTo(1);
    assertThat(reader.keyspaces(cluster(), false)).hasSize(1);
    assertThat(reader.keyspace(cluster(), "demo").durableWrites()).isTrue();
  }

  @Test
  void unknownReplicationStrategyFallsBackWithoutThrowing() {
    assertThat(MetadataSchemaReader.toReplication(Map.of("class", "com.acme.Weird")).strategy())
        .isEqualTo(ReplicationStrategy.SimpleStrategy);
    assertThat(MetadataSchemaReader.toReplication(Map.of("dc1", "not-a-number")).datacenters())
        .isEmpty();
  }

  @Test
  void tableCarriesColumnsKeysIndexesAndFlags() {
    TableDetail table = reader.table(cluster(), "demo", "users");

    assertThat(table.identity().qualifiedName()).isEqualTo("demo.users");
    assertThat(table.primaryKey().partitionKey()).containsExactly("user_id");
    assertThat(table.primaryKey().clusteringKey())
        .singleElement()
        .satisfies(
            key -> {
              assertThat(key.column()).isEqualTo("created_at");
              assertThat(key.order()).isEqualTo(io.cassyx.core.api.schema.ClusteringOrder.DESC);
            });
    assertThat(table.hasCounters()).isTrue();
    assertThat(table.hasVectorColumns()).isTrue();
    assertThat(table.viewNames()).containsExactly("users_by_email");
    assertThat(table.indexes()).hasSize(2);
    assertThat(table.options().comment()).isEqualTo("Application users");
    assertThat(table.options().gcGraceSeconds()).isEqualTo(864000);
    assertThat(table.options().bloomFilterFpChance()).isEqualTo(0.01);
    assertThat(table.options().compaction().strategyClass()).isEqualTo("SizeTieredCompactionStrategy");
    assertThat(table.options().compression().compressionClass()).isEqualTo("LZ4Compressor");
    assertThat(table.options().caching().keys()).isEqualTo("ALL");
    assertThat(table.options().cdc()).isFalse();
    assertThat(reader.tables(cluster(), "demo")).hasSize(1);
  }

  @Test
  void columnsCarryKindPositionAndVectorDimensions() {
    List<ColumnInfo> columns = reader.columns(cluster(), "demo", "users");

    assertThat(columns)
        .filteredOn(column -> column.name().equals("user_id"))
        .singleElement()
        .satisfies(
            column -> {
              assertThat(column.kind()).isEqualTo(ColumnKind.PARTITION_KEY);
              assertThat(column.position()).isZero();
            });
    assertThat(columns)
        .filteredOn(column -> column.name().equals("created_at"))
        .singleElement()
        .satisfies(
            column -> {
              assertThat(column.kind()).isEqualTo(ColumnKind.CLUSTERING);
              assertThat(column.clusteringOrder())
                  .isEqualTo(io.cassyx.core.api.schema.ClusteringOrder.DESC);
            });
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
              assertThat(column.type()).contains("1536");
            });
    assertThat(columns)
        .filteredOn(column -> column.name().equals("hits"))
        .singleElement()
        .satisfies(column -> assertThat(column.counter()).isTrue());
    assertThat(columns)
        .filteredOn(column -> column.name().equals("preferences"))
        .singleElement()
        .satisfies(column -> assertThat(column.collection()).isTrue());
    assertThat(columns)
        .filteredOn(column -> column.name().equals("email"))
        .singleElement()
        .satisfies(column -> assertThat(column.indexed()).isTrue());
  }

  @Test
  void indexesTabIsActuallyPopulated() {
    List<IndexInfo> indexes = reader.indexes(cluster(), "demo", "users");

    assertThat(indexes).extracting(IndexInfo::name).containsExactly("users_email_idx", "users_prefs_idx");
    assertThat(indexes.get(0).kind()).isEqualTo(IndexKind.SAI);
    assertThat(indexes.get(0).className()).contains("StorageAttachedIndex");
    assertThat(indexes.get(0).identity().table()).isEqualTo("users");
    assertThat(indexes.get(1).kind()).isEqualTo(IndexKind.KEYS);
    assertThat(indexes.get(1).target()).isEqualTo("keys(preferences)");
  }

  @Test
  void tableInfoBacksTheFourPanelTabs() {
    TableInfo info = reader.tableInfo(cluster(), "demo", "users", false);

    assertThat(info.fields()).isNotEmpty();
    assertThat(info.indexes()).hasSize(2);
    assertThat(info.comment()).isEqualTo("Application users");
    assertThat(info.definition()).contains("CREATE TABLE");
    assertThat(info.views()).extracting(SchemaIdentity::view).containsExactly("users_by_email");
    assertThat(info.statisticsAvailable()).isFalse();
  }

  @Test
  void viewsCarryTheirBaseTableIdentity() {
    assertThat(reader.views(cluster(), "demo")).hasSize(1);
    assertThat(reader.view(cluster(), "demo", "users_by_email"))
        .satisfies(
            view -> {
              assertThat(view.baseTable().qualifiedName()).isEqualTo("demo.users");
              assertThat(view.whereClause()).isEqualTo("email IS NOT NULL");
              assertThat(view.includesAllColumns()).isFalse();
              assertThat(view.primaryKey().partitionKey()).containsExactly("email");
            });
  }

  @Test
  void typesReportTheColumnsThatUseThem() {
    List<UserDefinedTypeInfo> types = reader.types(cluster(), "demo");
    assertThat(types).singleElement().satisfies(type -> assertThat(type.name()).isEqualTo("address"));
    assertThat(reader.type(cluster(), "demo", "address").fields())
        .singleElement()
        .satisfies(field -> assertThat(field.name()).isEqualTo("street"));
  }

  @Test
  void functionsAndAggregatesAreAddressableBySignature() {
    assertThat(reader.functions(cluster(), "demo"))
        .singleElement()
        .satisfies(
            function -> {
              assertThat(function.signature()).isEqualTo("avg_state(double,int)");
              assertThat(function.arguments()).hasSize(2);
              assertThat(function.language()).isEqualTo("java");
              assertThat(function.nullHandling())
                  .isEqualTo(io.cassyx.core.api.schema.UdfNullHandling.CALLED_ON_NULL_INPUT);
            });
    assertThat(reader.function(cluster(), "demo", "avg_state(double,int)").name())
        .isEqualTo("avg_state");
    assertThat(reader.function(cluster(), "demo", "avg_state( double , int )").name())
        .isEqualTo("avg_state");
    assertThat(reader.aggregates(cluster(), "demo")).hasSize(1);
    assertThat(reader.aggregate(cluster(), "demo", "average(double)").stateFunction())
        .isEqualTo("avg_state");
  }

  @Test
  void describeDelegatesToTheDriver() {
    CqlSession session = cluster();
    assertThat(reader.describe(session, SchemaIdentity.keyspace("demo"), false, true))
        .isEqualTo("CREATE KEYSPACE demo;");
    assertThat(reader.describe(session, SchemaIdentity.table("demo", "users"), true, true))
        .contains("CREATE INDEX");
    assertThat(reader.describe(session, SchemaIdentity.table("demo", "users"), false, true))
        .isEqualTo("CREATE TABLE users;");
    assertThat(reader.describe(session, SchemaIdentity.view("demo", "users_by_email"), false, true))
        .contains("MATERIALIZED VIEW");
    assertThat(reader.describe(session, SchemaIdentity.type("demo", "address"), false, true))
        .contains("CREATE TYPE");
    assertThat(
            reader.describe(
                session, SchemaIdentity.function("demo", "avg_state", "(double,int)"), false, true))
        .contains("CREATE FUNCTION");
    assertThat(
            reader.describe(
                session, SchemaIdentity.aggregate("demo", "average", "(double)"), false, true))
        .contains("CREATE AGGREGATE");
    assertThat(
            reader.describe(
                session, SchemaIdentity.column("demo", "users", "email"), false, true))
        .contains("CREATE TABLE");
  }

  @Test
  void describeRejectsUnknownObjects() {
    CqlSession session = cluster();
    SchemaIdentity role = SchemaIdentity.role("app_reader");
    SchemaIdentity missingView = SchemaIdentity.view("demo", "nope");
    assertThatThrownBy(() -> reader.describe(session, role, false, true))
        .isInstanceOf(SchemaNotFoundException.class);
    assertThatThrownBy(() -> reader.describe(session, missingView, false, true))
        .isInstanceOf(SchemaNotFoundException.class);
  }

  @Test
  void missingObjectsRaiseSchemaNotFoundWithTheIdentity() {
    CqlSession session = cluster();
    assertThatThrownBy(() -> reader.keyspace(session, "nope"))
        .isInstanceOf(SchemaNotFoundException.class)
        .satisfies(
            error ->
                assertThat(((SchemaNotFoundException) error).identity().keyspace()).isEqualTo("nope"));
    assertThatThrownBy(() -> reader.table(session, "demo", "nope"))
        .isInstanceOf(SchemaNotFoundException.class);
    assertThatThrownBy(() -> reader.view(session, "demo", "nope"))
        .isInstanceOf(SchemaNotFoundException.class);
    assertThatThrownBy(() -> reader.type(session, "demo", "nope"))
        .isInstanceOf(SchemaNotFoundException.class);
    assertThatThrownBy(() -> reader.function(session, "demo", "nope(int)"))
        .isInstanceOf(SchemaNotFoundException.class);
    assertThatThrownBy(() -> reader.aggregate(session, "demo", "nope(int)"))
        .isInstanceOf(SchemaNotFoundException.class);
    assertThatThrownBy(() -> reader.keyspace(session, " "))
        .isInstanceOf(SchemaNotFoundException.class);
    assertThatThrownBy(() -> reader.table(session, "demo", " "))
        .isInstanceOf(SchemaNotFoundException.class);
  }

  @Test
  void searchMatchesNamesTypesTargetsAndComments() {
    CqlSession session = cluster();

    SchemaSearchResult byName = reader.search(session, "users", null, false, 100);
    assertThat(byName.matches())
        .anySatisfy(
            match -> {
              assertThat(match.kind()).isEqualTo(SchemaObjectKind.TABLE);
              assertThat(match.identity().qualifiedName()).isEqualTo("demo.users");
            });
    assertThat(byName.truncated()).isFalse();

    assertThat(reader.search(session, "vector", null, false, 100).matches())
        .anySatisfy(match -> assertThat(match.matchedOn()).isEqualTo(SearchMatchKind.TYPE));
    assertThat(reader.search(session, "Application", null, false, 100).matches())
        .anySatisfy(match -> assertThat(match.matchedOn()).isEqualTo(SearchMatchKind.COMMENT));
    assertThat(reader.search(session, "preferences", Set.of(SchemaObjectKind.INDEX), false, 100).matches())
        .anySatisfy(match -> assertThat(match.matchedOn()).isEqualTo(SearchMatchKind.TARGET));
  }

  @Test
  void searchHonoursKindFiltersSystemToggleLimitAndEmptyQuery() {
    CqlSession session = cluster();

    assertThat(reader.search(session, "users", Set.of(SchemaObjectKind.VIEW), false, 100).matches())
        .allSatisfy(match -> assertThat(match.kind()).isEqualTo(SchemaObjectKind.VIEW));
    assertThat(reader.search(session, "users", Set.of(SchemaObjectKind.TABLE), true, 100).matches())
        .extracting(match -> match.identity().qualifiedName())
        .contains("demo.users", "system_auth.users");
    assertThat(reader.search(session, "users", Set.of(SchemaObjectKind.TABLE), false, 100).matches())
        .extracting(match -> match.identity().qualifiedName())
        .doesNotContain("system_auth.users");

    SchemaSearchResult truncated = reader.search(session, "e", null, true, 1);
    assertThat(truncated.matches()).hasSize(1);
    assertThat(truncated.truncated()).isTrue();

    assertThat(reader.search(session, "  ", null, false, 100).matches()).isEmpty();
    assertThat(reader.search(session, "demo", null, false, 0).matches()).isNotEmpty();
    assertThat(reader.search(session, "address", null, false, 100).matches())
        .anySatisfy(match -> assertThat(match.kind()).isEqualTo(SchemaObjectKind.TYPE));
    assertThat(reader.search(session, "avg_state", null, false, 100).matches())
        .anySatisfy(match -> assertThat(match.kind()).isEqualTo(SchemaObjectKind.FUNCTION));
    assertThat(reader.search(session, "average", null, false, 100).matches())
        .anySatisfy(match -> assertThat(match.kind()).isEqualTo(SchemaObjectKind.AGGREGATE));
  }

  @Test
  void collectionIndexTargetsResolveToTheirBaseColumn() {
    assertThat(MetadataSchemaReader.baseColumnOfTarget("keys(preferences)")).isEqualTo("preferences");
    assertThat(MetadataSchemaReader.baseColumnOfTarget("values(\"Tags\")")).isEqualTo("Tags");
    assertThat(MetadataSchemaReader.baseColumnOfTarget("email")).isEqualTo("email");
    assertThat(MetadataSchemaReader.baseColumnOfTarget(null)).isEmpty();
    assertThat(MetadataSchemaReader.baseColumnOfTarget("full(")).isEqualTo("full(");
  }

  @Test
  void signatureMatchingIgnoresWhitespaceAndCase() {
    assertThat(MetadataSchemaReader.matchesSignature("f", "f(int,text)", "F( INT , TEXT )")).isTrue();
    assertThat(MetadataSchemaReader.matchesSignature("f", "f(int)", "f")).isTrue();
    assertThat(MetadataSchemaReader.matchesSignature("f", "f(int)", "g")).isFalse();
    assertThat(MetadataSchemaReader.matchesSignature("f", "f(int)", null)).isFalse();
  }

  @Test
  void indexKindDetectsSaiDseSearchAndCustom() {
    IndexMetadata custom =
        FakeSchema.index(
            "i", "c", com.datastax.oss.driver.api.core.metadata.schema.IndexKind.CUSTOM, "com.acme.X");
    IndexMetadata solr =
        FakeSchema.index(
            "i",
            "c",
            com.datastax.oss.driver.api.core.metadata.schema.IndexKind.CUSTOM,
            "com.datastax.bdp.search.solr.Cql3SolrSecondaryIndex");
    IndexMetadata composites =
        FakeSchema.index(
            "i", "c", com.datastax.oss.driver.api.core.metadata.schema.IndexKind.COMPOSITES, null);

    assertThat(MetadataSchemaReader.indexKind(custom, "com.acme.X")).isEqualTo(IndexKind.CUSTOM);
    assertThat(
            MetadataSchemaReader.indexKind(
                solr, "com.datastax.bdp.search.solr.Cql3SolrSecondaryIndex"))
        .isEqualTo(IndexKind.DSE_SEARCH);
    assertThat(MetadataSchemaReader.indexKind(composites, null)).isEqualTo(IndexKind.COMPOSITES);
  }
}
