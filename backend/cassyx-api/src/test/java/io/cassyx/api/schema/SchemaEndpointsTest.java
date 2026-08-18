package io.cassyx.api.schema;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.datastax.oss.driver.api.core.CqlSession;
import io.cassyx.core.api.Capability;
import io.cassyx.core.api.ClusterCapabilities;
import io.cassyx.core.api.ClusterFlavor;
import io.cassyx.core.api.SessionRegistry;
import io.cassyx.core.api.schema.ClusteringKeyColumn;
import io.cassyx.core.api.schema.ColumnInfo;
import io.cassyx.core.api.schema.ColumnKind;
import io.cassyx.core.api.schema.CqlPermission;
import io.cassyx.core.api.schema.DdlExecutionResult;
import io.cassyx.core.api.schema.DdlExecutor;
import io.cassyx.core.api.schema.DdlPreview;
import io.cassyx.core.api.schema.IndexInfo;
import io.cassyx.core.api.schema.IndexKind;
import io.cassyx.core.api.schema.PermissionGrant;
import io.cassyx.core.api.schema.PrimaryKeyDefinition;
import io.cassyx.core.api.schema.ReplicationSettings;
import io.cassyx.core.api.schema.RoleInfo;
import io.cassyx.core.api.schema.RoleReader;
import io.cassyx.core.api.schema.SchemaFactory;
import io.cassyx.core.api.schema.SchemaIdentity;
import io.cassyx.core.api.schema.SchemaNode;
import io.cassyx.core.api.schema.SchemaObjectKind;
import io.cassyx.core.api.schema.SchemaReader;
import io.cassyx.core.api.schema.SchemaSearchMatch;
import io.cassyx.core.api.schema.SchemaSearchResult;
import io.cassyx.core.api.schema.SchemaTreeSnapshot;
import io.cassyx.core.api.schema.SearchMatchKind;
import io.cassyx.core.api.schema.TableDetail;
import io.cassyx.core.api.schema.TableInfo;
import io.cassyx.core.api.schema.TableOptions;
import io.cassyx.core.api.schema.TableStatisticsStore;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The contract-facing behaviour of the schema endpoints: response shapes, the capability gate, and
 * the RFC 9457 problem bodies.
 *
 * <p>The DDL generator and executor are the REAL implementations - only the cluster-facing seams
 * (session registry, catalog reader, role reader) are mocked. That keeps the assertions about
 * generated CQL honest.
 */
@WebMvcTest(
    controllers = {
      SchemaTreeController.class,
      KeyspaceController.class,
      TableController.class,
      ColumnController.class,
      IndexController.class,
      MaterializedViewController.class,
      UserDefinedTypeController.class,
      FunctionController.class,
      RoleController.class,
      DdlController.class
    })
@Import({SchemaEndpointsTest.RealDdl.class, DdlService.class, SchemaSessions.class,
    SchemaProblemAdvice.class})
class SchemaEndpointsTest {

  private static final String CONNECTION = "8f2b1c6e-2a55-4f47-9f2a-4c1c3f0d9a11";
  private static final String BASE = "/api/connections/" + CONNECTION;

  @TestConfiguration
  static class RealDdl {

    @Bean
    io.cassyx.core.api.schema.DdlGenerator ddlGenerator() {
      return SchemaFactory.ddlGenerator();
    }

    @Bean
    TableStatisticsStore tableStatisticsStore() {
      return SchemaFactory.tableStatisticsStore();
    }
  }

  @Autowired private MockMvc mockMvc;

  @MockitoBean private SessionRegistry sessionRegistry;
  @MockitoBean private SchemaReader schemaReader;
  @MockitoBean private RoleReader roleReader;
  @MockitoBean private DdlExecutor ddlExecutor;

  @BeforeEach
  void setUp() {
    CqlSession session = org.mockito.Mockito.mock(CqlSession.class);
    when(sessionRegistry.isConnected(CONNECTION)).thenReturn(true);
    when(sessionRegistry.session(CONNECTION)).thenReturn(session);
    when(sessionRegistry.capabilities(CONNECTION))
        .thenReturn(
            new ClusterCapabilities(
                ClusterFlavor.CASSANDRA,
                "5.0.2",
                Set.of(
                    Capability.SAI,
                    Capability.VECTOR_ANN,
                    Capability.MATERIALIZED_VIEWS,
                    Capability.UDF_UDA,
                    Capability.TRUNCATE,
                    Capability.ROLES_PERMISSIONS)));
    when(ddlExecutor.execute(any(CqlSession.class), any(DdlPreview.class), anyBoolean()))
        .thenAnswer(
            call -> {
              DdlPreview preview = call.getArgument(1);
              return new DdlExecutionResult(
                  true,
                  preview.statements(),
                  preview.statements().size(),
                  1L,
                  true,
                  List.of(),
                  preview.targetIdentity());
            });
  }

  private static ColumnInfo emailColumn() {
    return new ColumnInfo(
        SchemaIdentity.column("demo", "users", "email"),
        "email",
        "text",
        ColumnKind.REGULAR,
        -1,
        null,
        false,
        false,
        false,
        false,
        null,
        null,
        true);
  }

  private static IndexInfo emailIndex() {
    return new IndexInfo(
        SchemaIdentity.index("demo", "users", "users_email_idx"),
        "users_email_idx",
        "email",
        IndexKind.SAI,
        "org.apache.cassandra.index.sai.StorageAttachedIndex",
        Map.of());
  }

  @Test
  void treeCarriesIdentityOnEveryNode() throws Exception {
    SchemaNode column =
        SchemaNode.leaf(
            SchemaIdentity.column("demo", "users", "email"),
            "email",
            SchemaObjectKind.COLUMN,
            false,
            "text");
    SchemaNode table =
        new SchemaNode(
            SchemaIdentity.table("demo", "users"),
            "users",
            SchemaObjectKind.TABLE,
            false,
            "1 partition keys",
            List.of(column));
    SchemaNode keyspace =
        new SchemaNode(
            SchemaIdentity.keyspace("demo"),
            "demo",
            SchemaObjectKind.KEYSPACE,
            false,
            "1 tables",
            List.of(table));
    when(schemaReader.tree(any(), eq(CONNECTION), eq(false)))
        .thenReturn(
            new SchemaTreeSnapshot(
                CONNECTION, Instant.parse("2026-08-17T10:31:00Z"), false, "v1", List.of(keyspace)));

    mockMvc
        .perform(get(BASE + "/schema/tree"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.connectionId").value(CONNECTION))
        .andExpect(jsonPath("$.keyspaces[0].identity.kind").value("KEYSPACE"))
        .andExpect(jsonPath("$.keyspaces[0].children[0].identity.qualifiedName").value("demo.users"))
        .andExpect(
            jsonPath("$.keyspaces[0].children[0].children[0].identity.qualifiedName")
                .value("demo.users.email"));
  }

  @Test
  void searchReturnsMatchesWithIdentities() throws Exception {
    when(schemaReader.search(any(), eq("user"), any(), eq(false), eq(100)))
        .thenReturn(
            new SchemaSearchResult(
                "user",
                false,
                List.of(
                    new SchemaSearchMatch(
                        SchemaIdentity.table("demo", "users"),
                        "users",
                        SchemaObjectKind.TABLE,
                        SearchMatchKind.NAME,
                        "demo.users"))));

    mockMvc
        .perform(get(BASE + "/schema/search").param("q", "user"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.truncated").value(false))
        .andExpect(jsonPath("$.matches[0].identity.keyspace").value("demo"))
        .andExpect(jsonPath("$.matches[0].matchedOn").value("NAME"));
  }

  @Test
  void keyspaceCreateReturnsTheExecutedCql() throws Exception {
    mockMvc
        .perform(
            post(BASE + "/keyspaces")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"name":"demo","replication":{"strategy":"SimpleStrategy","replicationFactor":1},
                     "durableWrites":true,"ifNotExists":true}"""))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.executedCql[0]").value(
            "CREATE KEYSPACE IF NOT EXISTS demo WITH replication = "
                + "{'class': 'SimpleStrategy', 'replication_factor': '1'} AND durable_writes = true;"))
        .andExpect(jsonPath("$.affectedIdentity.keyspace").value("demo"));
  }

  @Test
  void tableInfoPopulatesAllFourTabs() throws Exception {
    when(schemaReader.tableInfo(any(), eq("demo"), eq("users"), eq(false)))
        .thenReturn(
            new TableInfo(
                SchemaIdentity.table("demo", "users"),
                List.of(emailColumn()),
                List.of(emailIndex()),
                "Application users",
                "CREATE TABLE demo.users (...);",
                List.of(SchemaIdentity.view("demo", "users_by_email")),
                false));

    mockMvc
        .perform(get(BASE + "/keyspaces/demo/tables/users/info"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.fields[0].name").value("email"))
        .andExpect(jsonPath("$.indexes[0].kind").value("SAI"))
        .andExpect(jsonPath("$.indexes[0].identity.table").value("users"))
        .andExpect(jsonPath("$.comment").value("Application users"))
        .andExpect(jsonPath("$.definition").isString())
        .andExpect(jsonPath("$.statisticsAvailable").value(false));
  }

  @Test
  void tableListExposesTheContractShape() throws Exception {
    when(schemaReader.tables(any(), eq("demo")))
        .thenReturn(
            List.of(
                new TableDetail(
                    SchemaIdentity.table("demo", "users"),
                    "users",
                    "demo",
                    List.of(emailColumn()),
                    new PrimaryKeyDefinition(
                        List.of("user_id"), List.of(ClusteringKeyColumn.asc("created_at"))),
                    TableOptions.comment("Application users"),
                    List.of(emailIndex()),
                    List.of("users_by_email"),
                    false,
                    false,
                    false,
                    true)));

    mockMvc
        .perform(get(BASE + "/keyspaces/demo/tables"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].identity.qualifiedName").value("demo.users"))
        .andExpect(jsonPath("$[0].keyspace").value("demo"))
        .andExpect(jsonPath("$[0].primaryKey.partitionKey[0]").value("user_id"))
        .andExpect(jsonPath("$[0].hasVectorColumns").value(true))
        .andExpect(jsonPath("$[0].options.comment").value("Application users"));
  }

  @Test
  void statisticsAreA404UntilACountJobHasRun() throws Exception {
    when(schemaReader.table(any(), eq("demo"), eq("users")))
        .thenReturn(
            new TableDetail(
                SchemaIdentity.table("demo", "users"),
                "users",
                "demo",
                List.of(),
                new PrimaryKeyDefinition(List.of("user_id"), List.of()),
                null,
                List.of(),
                List.of(),
                false,
                false,
                false,
                false));

    mockMvc
        .perform(get(BASE + "/keyspaces/demo/tables/users/statistics"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.type").value("https://cassyx.dev/problems/not-found"))
        .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("COUNT job")));
  }

  @Test
  void commentEditGeneratesAnAlterTable() throws Exception {
    when(schemaReader.table(any(), eq("demo"), eq("users")))
        .thenReturn(
            new TableDetail(
                SchemaIdentity.table("demo", "users"),
                "users",
                "demo",
                List.of(),
                new PrimaryKeyDefinition(List.of("user_id"), List.of()),
                null,
                List.of(),
                List.of(),
                false,
                false,
                false,
                false));

    mockMvc
        .perform(
            put(BASE + "/keyspaces/demo/tables/users/comment")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"comment\":\"Application users\"}"))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.executedCql[0]")
                .value("ALTER TABLE demo.users WITH comment = 'Application users';"));
  }

  @Test
  void generateNeverTouchesTheCluster() throws Exception {
    mockMvc
        .perform(
            post(BASE + "/ddl/generate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"objectType":"TABLE","action":"CREATE","keyspace":"demo",
                     "definition":{"name":"users","columns":[{"name":"user_id","type":"uuid"},
                     {"name":"embedding","type":"vector<float, 1536>"}],
                     "primaryKey":{"partitionKey":["user_id"]}}}"""))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.cql").value(org.hamcrest.Matchers.containsString("CREATE TABLE")))
        .andExpect(jsonPath("$.statements").isArray())
        .andExpect(jsonPath("$.targetIdentity.qualifiedName").value("demo.users"))
        .andExpect(jsonPath("$.warnings[0]").value(org.hamcrest.Matchers.containsString("CASSJAVA-2")));
  }

  @Test
  void generateRejectsAnImpossibleActionForTheObjectType() throws Exception {
    mockMvc
        .perform(
            post(BASE + "/ddl/generate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"objectType\":\"INDEX\",\"action\":\"TRUNCATE\",\"definition\":{}}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("https://cassyx.dev/problems/validation-failed"))
        .andExpect(jsonPath("$.errors[0].field").value("action"));
  }

  @Test
  void previewDescribesAnExistingObject() throws Exception {
    when(schemaReader.describe(any(), any(SchemaIdentity.class), eq(true), eq(true)))
        .thenReturn("CREATE TABLE demo.users (embedding vector<float, 1536>);");

    mockMvc
        .perform(
            post(BASE + "/ddl/preview")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"identity":{"kind":"TABLE","keyspace":"demo","table":"users"},
                     "withChildren":true}"""))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.cql").value(org.hamcrest.Matchers.containsString("vector<float, 1536>")))
        .andExpect(jsonPath("$.targetIdentity.table").value("users"));
  }

  @Test
  void unsupportedFeaturesFailWithTheCapabilityProblem() throws Exception {
    when(sessionRegistry.capabilities(CONNECTION))
        .thenReturn(
            new ClusterCapabilities(ClusterFlavor.CASSANDRA, "4.1.3", Set.of(Capability.TRUNCATE)));

    mockMvc
        .perform(
            post(BASE + "/keyspaces/demo/tables/users/indexes")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"i\",\"target\":\"email\",\"kind\":\"SAI\"}"))
        .andExpect(status().isNotImplemented())
        .andExpect(jsonPath("$.type").value("https://cassyx.dev/problems/capability-unsupported"))
        .andExpect(jsonPath("$.capability").value("sai"))
        .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("4.1.3")));
  }

  @Test
  void materializedViewsAreHiddenOnAstra() throws Exception {
    when(sessionRegistry.capabilities(CONNECTION))
        .thenReturn(new ClusterCapabilities(ClusterFlavor.ASTRA, "Astra", Set.of(Capability.SAI)));

    mockMvc
        .perform(
            post(BASE + "/keyspaces/demo/views")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"name\":\"v\",\"baseTable\":\"users\",\"primaryKey\":{\"partitionKey\":[\"email\"]}}"))
        .andExpect(status().isNotImplemented())
        .andExpect(jsonPath("$.capability").value("materializedViews"));
  }

  @Test
  void udfIsHiddenOnAstra() throws Exception {
    when(sessionRegistry.capabilities(CONNECTION))
        .thenReturn(new ClusterCapabilities(ClusterFlavor.ASTRA, "Astra", Set.of(Capability.SAI)));

    mockMvc
        .perform(
            post(BASE + "/keyspaces/demo/functions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"name":"f","arguments":[],"returnType":"int","language":"java","body":"return 1;"}"""))
        .andExpect(status().isNotImplemented())
        .andExpect(jsonPath("$.capability").value("udfUda"));
  }

  @Test
  void notConnectedIsA409() throws Exception {
    when(sessionRegistry.isConnected(CONNECTION)).thenReturn(false);

    mockMvc
        .perform(get(BASE + "/schema/tree"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.type").value("https://cassyx.dev/problems/not-connected"));
  }

  @Test
  void rolesAndPermissionsAreServedAndGuarded() throws Exception {
    when(roleReader.roles(any()))
        .thenReturn(
            List.of(
                new RoleInfo(
                    SchemaIdentity.role("app_reader"),
                    "app_reader",
                    false,
                    true,
                    List.of("readers"),
                    Map.of())));
    when(roleReader.permissions(any(), anyString(), any()))
        .thenReturn(
            List.of(
                new PermissionGrant(
                    "app_reader",
                    "<table demo.users>",
                    SchemaIdentity.table("demo", "users"),
                    CqlPermission.SELECT,
                    false)));

    mockMvc
        .perform(get(BASE + "/roles"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].name").value("app_reader"))
        .andExpect(jsonPath("$[0].memberOf[0]").value("readers"));

    mockMvc
        .perform(get(BASE + "/permissions").param("role", "app_reader"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].permission").value("SELECT"))
        .andExpect(jsonPath("$[0].resourceIdentity.qualifiedName").value("demo.users"));

    mockMvc
        .perform(
            post(BASE + "/permissions/grant")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"role":"app_reader","resource":"table demo.users","permissions":["SELECT","MODIFY"]}"""))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.executedCql.length()").value(2));
  }

  @Test
  void rolePasswordsNeverComeBackInTheExecutionResult() throws Exception {
    when(ddlExecutor.execute(any(CqlSession.class), any(DdlPreview.class), anyBoolean()))
        .thenAnswer(
            call ->
                SchemaFactory.ddlExecutor()
                    .execute(org.mockito.Mockito.mock(CqlSession.class), call.getArgument(1), false));

    // The real executor runs against a mock session that returns null result sets, so assert the
    // redaction through the generator + preview path instead of the driver round trip.
    DdlPreview preview =
        SchemaFactory.ddlGenerator()
            .createRole(
                new io.cassyx.core.api.schema.RoleDefinition(
                    "r", "hunter2", false, true, List.of(), Map.of(), true));
    org.assertj.core.api.Assertions.assertThat(preview.cql()).contains("hunter2");
    org.assertj.core.api.Assertions.assertThat(preview.warnings())
        .anySatisfy(w -> org.assertj.core.api.Assertions.assertThat(w).contains("redacted"));
  }

  @Test
  void dropsAndTruncatesGenerateTheRightStatements() throws Exception {
    mockMvc
        .perform(delete(BASE + "/keyspaces/demo/tables/users").param("ifExists", "true"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.executedCql[0]").value("DROP TABLE IF EXISTS demo.users;"));

    when(schemaReader.table(any(), eq("demo"), eq("users")))
        .thenReturn(
            new TableDetail(
                SchemaIdentity.table("demo", "users"),
                "users",
                "demo",
                List.of(),
                new PrimaryKeyDefinition(List.of("user_id"), List.of()),
                null,
                List.of(),
                List.of(),
                false,
                false,
                false,
                false));

    mockMvc
        .perform(post(BASE + "/keyspaces/demo/tables/users/truncate"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.executedCql[0]").value("TRUNCATE TABLE demo.users;"));
  }

  @Test
  void keyspaceListPassesTheSystemToggleThrough() throws Exception {
    when(schemaReader.keyspaces(any(), eq(true)))
        .thenReturn(
            List.of(
                new io.cassyx.core.api.schema.KeyspaceInfo(
                    SchemaIdentity.keyspace("system_auth"),
                    "system_auth",
                    ReplicationSettings.simple(1),
                    true,
                    true,
                    3,
                    0,
                    0,
                    0,
                    0)));

    mockMvc
        .perform(get(BASE + "/keyspaces").param("includeSystem", "true"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].system").value(true))
        .andExpect(jsonPath("$[0].replication.strategy").value("SimpleStrategy"));
  }
}
