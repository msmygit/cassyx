package io.cassyx.core.impl.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.datastax.oss.driver.api.core.CqlIdentifier;
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.DefaultConsistencyLevel;
import com.datastax.oss.driver.api.core.ProtocolVersion;
import com.datastax.oss.driver.api.core.context.DriverContext;
import com.datastax.oss.driver.api.core.cql.BoundStatement;
import com.datastax.oss.driver.api.core.cql.BoundStatementBuilder;
import com.datastax.oss.driver.api.core.cql.ColumnDefinition;
import com.datastax.oss.driver.api.core.cql.ColumnDefinitions;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import com.datastax.oss.driver.api.core.cql.Statement;
import com.datastax.oss.driver.api.core.type.DataType;
import com.datastax.oss.driver.api.core.type.DataTypes;
import io.cassyx.core.api.CassyxCoreException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StatementFactoryTest {

  private final StatementFactory factory = new StatementFactory(new DefaultCqlValueCodec());

  private static final String UUID_TEXT = "1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d";

  @Test
  void aStatementWithoutBindValuesNeedsNoPreparation() {
    CqlSession session = mock(CqlSession.class);

    Statement<?> statement = factory.build(session, "SELECT 1", List.of(), Map.of());

    assertThat(statement).isInstanceOf(SimpleStatement.class);
    assertThat(((SimpleStatement) statement).getQuery()).isEqualTo("SELECT 1");
  }

  @Test
  @DisplayName("Bind values are decoded against the PREPARED statement's declared types")
  void bindsPositionalValuesUsingTheDeclaredType() {
    Fixture fixture = fixture(definition("user_id", DataTypes.UUID));

    factory.build(fixture.session, "SELECT * FROM t WHERE user_id = ?", List.of(UUID_TEXT), null);

    // The JSON value is a string; guessing would bind it as text and the query would silently
    // return nothing.
    verify(fixture.builder).set(0, UUID.fromString(UUID_TEXT), UUID.class);
  }

  @Test
  void bindsNamedValues() {
    Fixture fixture = fixture(definition("email", DataTypes.TEXT));

    factory.build(fixture.session, "SELECT * FROM t WHERE email = :email", null,
        Map.of("email", "ops@example.com"));

    verify(fixture.builder).set(0, "ops@example.com", String.class);
  }

  @Test
  @DisplayName("null binds a tombstone; unset leaves the column unwritten")
  void nullAndUnsetBindDifferently() {
    Fixture nullFixture = fixture(definition("email", DataTypes.TEXT));
    List<Object> withNull = new ArrayList<>();
    withNull.add(null);
    factory.build(nullFixture.session, "UPDATE t SET email = ? WHERE id = 1", withNull, null);
    verify(nullFixture.builder).setToNull(0);

    Fixture unsetFixture = fixture(definition("email", DataTypes.TEXT));
    factory.build(unsetFixture.session, "UPDATE t SET email = ? WHERE id = 1", List.of("$unset"), null);
    verify(unsetFixture.builder).unset(0);
  }

  @Test
  void rejectsTooManyValuesAndUnknownNames() {
    Fixture fixture = fixture(definition("email", DataTypes.TEXT));

    assertThatThrownBy(
            () -> factory.build(fixture.session, "SELECT 1 WHERE a = ?", List.of("a", "b"), null))
        .isInstanceOf(CassyxCoreException.class)
        .hasMessageContaining("1 bind marker(s) but 2 value(s)");

    Fixture unknown = fixture(definition("email", DataTypes.TEXT));
    when(unknown.variables.contains(any(CqlIdentifier.class))).thenReturn(false);
    assertThatThrownBy(
            () -> factory.build(unknown.session, "SELECT 1 WHERE a = :nope", null, Map.of("nope", "x")))
        .isInstanceOf(CassyxCoreException.class)
        .hasMessageContaining("No bind marker named ':nope'");
  }

  /* ---------------------------------------------------------------------------- options */

  @Test
  void appliesEveryStatementLevelControl() {
    CqlSession session = sessionWithProtocol(ProtocolVersion.V5);
    List<String> warnings = new ArrayList<>();

    Statement<?> statement =
        StatementFactory.applyOptions(
            session,
            SimpleStatement.newInstance("SELECT 1"),
            "demo",
            "LOCAL_QUORUM",
            "LOCAL_SERIAL",
            250,
            Duration.ofSeconds(5),
            true,
            true,
            warnings);

    assertThat(statement.getPageSize()).isEqualTo(250);
    assertThat(statement.getConsistencyLevel()).isEqualTo(DefaultConsistencyLevel.LOCAL_QUORUM);
    assertThat(statement.getSerialConsistencyLevel()).isEqualTo(DefaultConsistencyLevel.LOCAL_SERIAL);
    assertThat(statement.getTimeout()).isEqualTo(Duration.ofSeconds(5));
    assertThat(statement.isTracing()).isTrue();
    assertThat(statement.isIdempotent()).isTrue();
    assertThat(statement.getKeyspace()).isEqualTo(CqlIdentifier.fromInternal("demo"));
    assertThat(warnings).isEmpty();
  }

  @Test
  @DisplayName("A per-request keyspace on protocol v4 warns instead of failing at execution time")
  void degradesGracefullyWhenTheProtocolCannotCarryAKeyspace() {
    CqlSession session = sessionWithProtocol(ProtocolVersion.V4);
    List<String> warnings = new ArrayList<>();

    Statement<?> statement =
        StatementFactory.applyOptions(
            session, SimpleStatement.newInstance("SELECT 1"), "demo", null, null, 500, null,
            false, false, warnings);

    assertThat(statement.getKeyspace()).isNull();
    assertThat(warnings).singleElement().asString().contains("protocol v5");
  }

  @Test
  void leavesTheKeyspaceAloneWhenNoneIsRequested() {
    CqlSession session = sessionWithProtocol(ProtocolVersion.V5);

    Statement<?> statement =
        StatementFactory.applyOptions(
            session, SimpleStatement.newInstance("SELECT 1"), "  ", null, null, 500, null,
            false, false, null);

    assertThat(statement.getKeyspace()).isNull();
  }

  @Test
  void rejectsUnknownAndNonSerialConsistencyLevels() {
    assertThatThrownBy(() -> StatementFactory.parseConsistency("NOPE"))
        .isInstanceOf(CassyxCoreException.class)
        .hasMessageContaining("Unknown consistency level 'NOPE'");
    assertThatThrownBy(() -> StatementFactory.parseSerialConsistency("QUORUM"))
        .isInstanceOf(CassyxCoreException.class)
        .hasMessageContaining("SERIAL or LOCAL_SERIAL");
    assertThat(StatementFactory.parseSerialConsistency("serial"))
        .isEqualTo(DefaultConsistencyLevel.SERIAL);
  }

  /* ---------------------------------------------------------------------------- helpers */

  private record Fixture(CqlSession session, BoundStatementBuilder builder, ColumnDefinitions variables) {}

  private static Fixture fixture(ColumnDefinition... variables) {
    ColumnDefinitions definitions = mock(ColumnDefinitions.class);
    List<ColumnDefinition> list = List.of(variables);
    lenient().when(definitions.size()).thenReturn(list.size());
    for (int i = 0; i < list.size(); i++) {
      lenient().when(definitions.get(i)).thenReturn(list.get(i));
    }
    lenient().when(definitions.contains(any(CqlIdentifier.class))).thenReturn(true);
    lenient().when(definitions.get(any(CqlIdentifier.class))).thenReturn(list.get(0));

    BoundStatementBuilder builder = mock(BoundStatementBuilder.class, RETURNS_SELF);
    lenient().when(builder.build()).thenReturn(mock(BoundStatement.class));
    lenient().when(builder.setToNull(anyInt())).thenReturn(builder);
    lenient().when(builder.unset(anyInt())).thenReturn(builder);

    PreparedStatement prepared = mock(PreparedStatement.class);
    lenient().when(prepared.getVariableDefinitions()).thenReturn(definitions);
    lenient().when(prepared.boundStatementBuilder()).thenReturn(builder);

    CqlSession session = mock(CqlSession.class);
    lenient().when(session.prepare(any(String.class))).thenReturn(prepared);
    return new Fixture(session, builder, definitions);
  }

  private static ColumnDefinition definition(String name, DataType type) {
    ColumnDefinition definition = mock(ColumnDefinition.class);
    lenient().when(definition.getName()).thenReturn(CqlIdentifier.fromInternal(name));
    lenient().when(definition.getType()).thenReturn(type);
    return definition;
  }

  private static CqlSession sessionWithProtocol(ProtocolVersion version) {
    CqlSession session = mock(CqlSession.class);
    DriverContext context = mock(DriverContext.class);
    lenient().when(context.getProtocolVersion()).thenReturn(version);
    lenient().when(session.getContext()).thenReturn(context);
    return session;
  }
}
