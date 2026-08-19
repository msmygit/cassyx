package io.cassyx.core.impl.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.datastax.oss.driver.api.core.type.DataTypes;
import io.cassyx.core.api.Capability;
import io.cassyx.core.api.schema.CqlNames;
import io.cassyx.core.api.schema.SchemaIdentity;
import io.cassyx.core.api.schema.SchemaObjectKind;
import io.cassyx.core.api.schema.SchemaReader;
import io.cassyx.core.api.schema.TableStatistics;
import io.cassyx.core.api.schema.TableStatisticsStore;
import io.cassyx.core.api.schema.UdfNullHandling;
import io.cassyx.core.api.schema.UnsupportedCapabilityException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The small, load-bearing helpers: quoting, identity, redaction, the statistics cache. */
class SchemaSupportTest {

  @Test
  void quotesOnlyWhereCqlRequiresIt() {
    assertThat(CqlNames.quote("users")).isEqualTo("users");
    assertThat(CqlNames.quote("UserEvents")).isEqualTo("\"UserEvents\"");
    assertThat(CqlNames.quote("select")).isEqualTo("\"select\"");
    assertThat(CqlNames.qualify("demo", "users")).isEqualTo("demo.users");
    assertThat(CqlNames.qualify("demo", "UserEvents")).isEqualTo("demo.\"UserEvents\"");
    assertThat(CqlNames.qualify(null, "users")).isEqualTo("users");
    assertThat(CqlNames.qualify("demo", null)).isEqualTo("demo");
    assertThat(CqlNames.literal("it's")).isEqualTo("'it''s'");
    assertThat(CqlNames.literal(null)).isEqualTo("''");
    assertThatThrownBy(() -> CqlNames.quote("")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void identityFactoriesRenderQualifiedNames() {
    assertThat(SchemaIdentity.keyspace("demo").qualifiedName()).isEqualTo("demo");
    assertThat(SchemaIdentity.table("demo", "users").tableOrView()).isEqualTo("users");
    assertThat(SchemaIdentity.view("demo", "v").tableOrView()).isEqualTo("v");
    assertThat(SchemaIdentity.column("demo", "users", "email").qualifiedName())
        .isEqualTo("demo.users.email");
    assertThat(SchemaIdentity.index("demo", "users", "i").qualifiedName()).isEqualTo("demo.i");
    assertThat(SchemaIdentity.index("demo", "users", "i").table()).isEqualTo("users");
    assertThat(SchemaIdentity.type("demo", "address").kind()).isEqualTo(SchemaObjectKind.TYPE);
    assertThat(SchemaIdentity.function("demo", "f", "(int)").qualifiedName()).isEqualTo("demo.f(int)");
    assertThat(SchemaIdentity.function("demo", "f", null).qualifiedName()).isEqualTo("demo.f");
    assertThat(SchemaIdentity.aggregate("demo", "a", "(int)").qualifiedName()).isEqualTo("demo.a(int)");
    assertThat(SchemaIdentity.aggregate("demo", "a", null).qualifiedName()).isEqualTo("demo.a");
    assertThat(SchemaIdentity.role("app_reader").keyspace()).isEqualTo("system_auth");
  }

  @Test
  void systemKeyspacesDriveTheShowSystemToggle() {
    assertThat(SchemaReader.isSystemKeyspace("system_auth")).isTrue();
    assertThat(SchemaReader.isSystemKeyspace("SYSTEM_SCHEMA")).isTrue();
    assertThat(SchemaReader.isSystemKeyspace("dse_perf")).isTrue();
    assertThat(SchemaReader.isSystemKeyspace("solr_admin")).isTrue();
    assertThat(SchemaReader.isSystemKeyspace("demo")).isFalse();
    assertThat(SchemaReader.isSystemKeyspace(null)).isFalse();
  }

  @Test
  void nullHandlingRendersItsCqlClause() {
    assertThat(UdfNullHandling.CALLED_ON_NULL_INPUT.toCql()).isEqualTo("CALLED ON NULL INPUT");
    assertThat(UdfNullHandling.RETURNS_NULL_ON_NULL_INPUT.toCql())
        .isEqualTo("RETURNS NULL ON NULL INPUT");
  }

  @Test
  void passwordsAreRedactedFromExecutedCql() {
    assertThat(DdlSecrets.redact("CREATE ROLE r WITH PASSWORD = 'hunter2' AND LOGIN = true;"))
        .isEqualTo("CREATE ROLE r WITH PASSWORD = '***' AND LOGIN = true;");
    assertThat(DdlSecrets.redact("ALTER ROLE r WITH password='it''s' AND LOGIN = true;"))
        .doesNotContain("it''s");
    assertThat(DdlSecrets.redact("SELECT * FROM demo.users;"))
        .isEqualTo("SELECT * FROM demo.users;");
    assertThat(DdlSecrets.redact(null)).isNull();
  }

  @Test
  void statisticsAreAbsentUntilACountJobHasRun() {
    TableStatisticsStore store = new InMemoryTableStatisticsStore();
    assertThat(store.find("conn-1", "demo", "users")).isEmpty();

    TableStatistics statistics =
        TableStatistics.untruncated(
            SchemaIdentity.table("demo", "users"),
            10_000_000L,
            250_000L,
            Instant.parse("2026-08-17T11:02:33Z"),
            "6c8f2a10-b4f9-4a1e-9a12-5f0a7e2d3b44",
            21_400L,
            List.of(),
            List.of(),
            List.of());
    store.put("conn-1", statistics);

    assertThat(store.find("conn-1", "demo", "users")).contains(statistics);
    assertThat(store.find("conn-2", "demo", "users")).isEmpty();
    assertThat(store.find("conn-1", "system_auth", "users")).isEmpty();
  }

  @Test
  void capabilityFailuresCarryTheCapability() {
    UnsupportedCapabilityException error =
        new UnsupportedCapabilityException(Capability.SAI, "SAI requires Cassandra 5.x");
    assertThat(error.capability()).isEqualTo(Capability.SAI);
    assertThat(error.capability().wireName()).isEqualTo("sai");
  }

  @Test
  void typeIntrospectionAnswersTheInfoPanelQuestions() {
    assertThat(CqlTypes.isCollection(DataTypes.listOf(DataTypes.TEXT))).isTrue();
    assertThat(CqlTypes.isCollection(DataTypes.setOf(DataTypes.TEXT))).isTrue();
    assertThat(CqlTypes.isCollection(DataTypes.mapOf(DataTypes.TEXT, DataTypes.TEXT))).isTrue();
    assertThat(CqlTypes.isCollection(DataTypes.TEXT)).isFalse();
    assertThat(CqlTypes.isCounter(DataTypes.COUNTER)).isTrue();
    assertThat(CqlTypes.isFrozen(DataTypes.listOf(DataTypes.TEXT, true))).isTrue();
    assertThat(CqlTypes.isFrozen(DataTypes.setOf(DataTypes.TEXT, true))).isTrue();
    assertThat(CqlTypes.isFrozen(DataTypes.mapOf(DataTypes.TEXT, DataTypes.TEXT, true))).isTrue();
    assertThat(CqlTypes.isFrozen(DataTypes.tupleOf(DataTypes.TEXT))).isTrue();
    assertThat(CqlTypes.isFrozen(DataTypes.TEXT)).isFalse();
    assertThat(CqlTypes.references(DataTypes.listOf(DataTypes.TEXT), "address")).isFalse();
    assertThat(CqlTypes.references(DataTypes.mapOf(DataTypes.TEXT, DataTypes.TEXT), "address"))
        .isFalse();
    assertThat(CqlTypes.references(DataTypes.setOf(DataTypes.TEXT), "address")).isFalse();
    assertThat(CqlTypes.references(DataTypes.tupleOf(DataTypes.TEXT), "address")).isFalse();
    assertThat(CqlTypes.references(DataTypes.vectorOf(DataTypes.FLOAT, 3), "address")).isFalse();
    assertThat(CqlTypes.references(DataTypes.TEXT, "address")).isFalse();
  }

  @Test
  void permissionResourcesBecomeStructuredIdentities() {
    assertThat(SystemAuthRoleReader.resourceIdentity("<keyspace demo>").keyspace()).isEqualTo("demo");
    assertThat(SystemAuthRoleReader.resourceIdentity("<table demo.users>").qualifiedName())
        .isEqualTo("demo.users");
    assertThat(SystemAuthRoleReader.resourceIdentity("table demo.users").table()).isEqualTo("users");
    assertThat(SystemAuthRoleReader.resourceIdentity("<role app_reader>").name())
        .isEqualTo("app_reader");
    assertThat(SystemAuthRoleReader.resourceIdentity("<all keyspaces>")).isNull();
    assertThat(SystemAuthRoleReader.resourceIdentity("<table broken>")).isNull();
    assertThat(SystemAuthRoleReader.resourceIdentity(null)).isNull();
  }
}
