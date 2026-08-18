package io.cassyx.core.impl.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.ColumnDefinition;
import com.datastax.oss.driver.api.core.cql.ColumnDefinitions;
import com.datastax.oss.driver.api.core.cql.ExecutionInfo;
import com.datastax.oss.driver.api.core.cql.ResultSet;
import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.api.core.cql.Statement;
import com.datastax.oss.driver.api.core.type.DataTypes;
import io.cassyx.core.api.CassyxCoreException;
import io.cassyx.core.api.query.EditabilityVerdict;
import io.cassyx.core.api.query.GeneratedStatements;
import io.cassyx.core.api.query.IncompletePrimaryKeyException;
import io.cassyx.core.api.query.RowDeleteSpec;
import io.cassyx.core.api.query.RowInsertSpec;
import io.cassyx.core.api.query.RowMutationOutcome;
import io.cassyx.core.api.query.RowUpdateSpec;
import io.cassyx.core.api.query.StatementGenerationSpec;
import io.cassyx.core.api.query.TableKeyInfo;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DefaultRowMutationServiceTest {

  private final DefaultRowMutationService service = new DefaultRowMutationService();

  private static CqlSession usersSession() {
    return FakeSchema.table("demo", "users")
        .partitionKey("user_id", DataTypes.UUID)
        .clustering("created_at", DataTypes.TIMESTAMP)
        .regular("email", DataTypes.TEXT)
        .regular("logins", DataTypes.BIGINT)
        .staticColumn("tenant", DataTypes.TEXT)
        .session();
  }

  private static final String USER_ID = "1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d";
  private static final String CREATED_AT = "2026-08-17T10:31:02Z";

  private static Map<String, Object> fullKey() {
    Map<String, Object> key = new LinkedHashMap<>();
    key.put("user_id", USER_ID);
    key.put("created_at", CREATED_AT);
    return key;
  }

  /* --------------------------------------------------------------------------- table key */

  @Test
  void readsPrimaryKeyShapeFromDriverMetadata() {
    TableKeyInfo key = service.tableKey(usersSession(), "demo", "users");

    assertThat(key.partitionKey()).containsExactly("user_id");
    assertThat(key.clusteringColumns()).containsExactly("created_at");
    assertThat(key.primaryKey()).containsExactly("user_id", "created_at");
    assertThat(key.isPrimaryKeyColumn("USER_ID")).isTrue();
    assertThat(key.isPrimaryKeyColumn("email")).isFalse();
    assertThat(key.columnTypes()).containsKey("email");
  }

  @Test
  void unknownTablesFailWithTheirName() {
    assertThatThrownBy(() -> service.tableKey(usersSession(), "demo", "userz"))
        .isInstanceOf(CassyxCoreException.class)
        .hasMessageContaining("demo.userz");
  }

  /* ------------------------------------------------------------------------- editability */

  @Test
  @DisplayName("A result set missing a key column is read-only, and says exactly which column")
  void refusesToEditWithoutTheCompletePrimaryKey() {
    EditabilityVerdict verdict =
        service.editability(usersSession(), "demo", "users", List.of("user_id", "email"));

    assertThat(verdict.editable()).isFalse();
    assertThat(verdict.requiredKeyColumns()).containsExactly("user_id", "created_at");
    assertThat(verdict.missingKeyColumns()).containsExactly("created_at");
    assertThat(verdict.reason())
        .contains("created_at")
        .contains("demo.users")
        .contains("Add it to the SELECT clause");
    assertThat(verdict.suggestedCql()).isEqualTo("SELECT user_id, created_at, email FROM demo.users");
  }

  @Test
  void allowsEditingWhenTheKeyIsComplete() {
    EditabilityVerdict verdict =
        service.editability(usersSession(), "demo", "users", List.of("user_id", "created_at", "email"));

    assertThat(verdict.editable()).isTrue();
    assertThat(verdict.missingKeyColumns()).isEmpty();
    assertThat(verdict.suggestedCql()).isNull();
  }

  @Test
  void selectStarProjectsEverything() {
    assertThat(service.editability(usersSession(), "demo", "users", List.of("*")).editable()).isTrue();
  }

  @Test
  void multipleMissingColumnsArePluralised() {
    EditabilityVerdict verdict = service.editability(usersSession(), "demo", "users", List.of("email"));

    assertThat(verdict.missingKeyColumns()).containsExactly("user_id", "created_at");
    assertThat(verdict.reason()).contains("Add them to the SELECT clause");
  }

  /* ------------------------------------------------------------------------------ insert */

  @Test
  void generatesInsertWithTtlTimestampAndLwt() {
    Map<String, Object> values = fullKey();
    values.put("email", "ops@example.com");

    RowMutationOutcome outcome =
        service.insert(
            usersSession(),
            "demo",
            "users",
            new RowInsertSpec(values, 86400, 1755424262000000L, true, null, null, true));

    assertThat(outcome.executed()).isFalse();
    assertThat(outcome.cql())
        .isEqualTo(
            "INSERT INTO demo.users (user_id, created_at, email) VALUES ("
                + USER_ID
                + ", '"
                + CREATED_AT
                + "', 'ops@example.com') IF NOT EXISTS USING TTL 86400 AND TIMESTAMP 1755424262000000");
  }

  @Test
  @DisplayName("An unset column is omitted from the INSERT; an explicit null writes a tombstone")
  void unsetIsOmittedButNullIsWritten() {
    Map<String, Object> values = fullKey();
    values.put("email", null);
    values.put("logins", "$unset");

    RowMutationOutcome outcome =
        service.insert(
            usersSession(), "demo", "users", new RowInsertSpec(values, null, null, false, null, null, true));

    assertThat(outcome.cql()).contains("email").contains("null").doesNotContain("logins");
  }

  @Test
  void insertRequiresTheCompletePrimaryKey() {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("user_id", USER_ID);
    values.put("email", "ops@example.com");

    assertThatThrownBy(
            () ->
                service.insert(
                    usersSession(),
                    "demo",
                    "users",
                    new RowInsertSpec(values, null, null, false, null, null, true)))
        .isInstanceOf(IncompletePrimaryKeyException.class)
        .satisfies(
            e ->
                assertThat(((IncompletePrimaryKeyException) e).missingKeyColumns())
                    .containsExactly("created_at"));
  }

  @Test
  void insertNeedsAtLeastOneValue() {
    assertThatThrownBy(
            () ->
                service.insert(
                    usersSession(),
                    "demo",
                    "users",
                    new RowInsertSpec(Map.of("email", "$unset"), null, null, false, null, null, true)))
        .isInstanceOf(CassyxCoreException.class)
        .hasMessageContaining("at least one value");
  }

  /* ------------------------------------------------------------------------------ update */

  @Test
  void generatesUpdateWithFullKeyInTheWhereClause() {
    RowMutationOutcome outcome =
        service.update(
            usersSession(),
            "demo",
            "users",
            new RowUpdateSpec(
                fullKey(),
                Map.of("email", "new@example.com"),
                86400,
                null,
                null,
                false,
                null,
                null,
                true));

    assertThat(outcome.cql())
        .isEqualTo(
            "UPDATE demo.users USING TTL 86400 SET email = 'new@example.com' WHERE user_id = "
                + USER_ID
                + " AND created_at = '"
                + CREATED_AT
                + "'");
  }

  @Test
  void updateSupportsLwtConditions() {
    RowMutationOutcome withCondition =
        service.update(
            usersSession(),
            "demo",
            "users",
            new RowUpdateSpec(
                fullKey(), Map.of("email", "a@b.c"), null, null, "IF email = 'old@example.com'",
                false, null, null, true));
    assertThat(withCondition.cql()).endsWith(" IF email = 'old@example.com'");

    RowMutationOutcome ifExists =
        service.update(
            usersSession(),
            "demo",
            "users",
            new RowUpdateSpec(fullKey(), Map.of("email", "a@b.c"), null, null, null, true, null, null, true));
    assertThat(ifExists.cql()).endsWith(" IF EXISTS");
  }

  @Test
  void updateRefusesPartialKeysAndKeyColumnAssignments() {
    assertThatThrownBy(
            () ->
                service.update(
                    usersSession(),
                    "demo",
                    "users",
                    new RowUpdateSpec(
                        Map.of("user_id", USER_ID), Map.of("email", "a@b.c"), null, null, null,
                        false, null, null, true)))
        .isInstanceOf(IncompletePrimaryKeyException.class)
        .hasMessageContaining("created_at");

    assertThatThrownBy(
            () ->
                service.update(
                    usersSession(),
                    "demo",
                    "users",
                    new RowUpdateSpec(
                        fullKey(), Map.of("user_id", USER_ID), null, null, null, false, null, null, true)))
        .isInstanceOf(CassyxCoreException.class)
        .hasMessageContaining("cannot be updated");

    assertThatThrownBy(
            () ->
                service.update(
                    usersSession(),
                    "demo",
                    "users",
                    new RowUpdateSpec(fullKey(), Map.of(), null, null, null, false, null, null, true)))
        .isInstanceOf(CassyxCoreException.class)
        .hasMessageContaining("at least one column");
  }

  /* ------------------------------------------------------------------------------ delete */

  @Test
  void generatesRowAndColumnDeletes() {
    assertThat(
            service
                .delete(
                    usersSession(),
                    "demo",
                    "users",
                    new RowDeleteSpec(fullKey(), List.of(), 1755424262000000L, true, null, null, true))
                .cql())
        .isEqualTo(
            "DELETE FROM demo.users USING TIMESTAMP 1755424262000000 WHERE user_id = "
                + USER_ID
                + " AND created_at = '"
                + CREATED_AT
                + "' IF EXISTS");

    assertThat(
            service
                .delete(
                    usersSession(),
                    "demo",
                    "users",
                    new RowDeleteSpec(fullKey(), List.of("email"), null, false, null, null, true))
                .cql())
        .startsWith("DELETE email FROM demo.users WHERE");
  }

  @Test
  void deleteRefusesToDropKeyColumnsIndividually() {
    assertThatThrownBy(
            () ->
                service.delete(
                    usersSession(),
                    "demo",
                    "users",
                    new RowDeleteSpec(fullKey(), List.of("user_id"), null, false, null, null, true)))
        .isInstanceOf(CassyxCoreException.class)
        .hasMessageContaining("cannot be deleted individually");
  }

  /* -------------------------------------------------------------------------- generation */

  @Test
  void generatesInsertsForSelectedRows() {
    Map<String, Object> row = fullKey();
    row.put("email", "ops@example.com");

    GeneratedStatements generated =
        service.generate(
            usersSession(),
            "demo",
            "users",
            new StatementGenerationSpec(
                StatementGenerationSpec.Kind.INSERT, List.of(row), List.of(), null, null, true, false, true));

    assertThat(generated.rowCount()).isEqualTo(1);
    assertThat(generated.statements()).singleElement().asString().endsWith("IF NOT EXISTS;");
    assertThat(generated.cql()).isEqualTo(generated.statements().get(0));
  }

  @Test
  void generatesUpdatesAndDeletesFromWholeRows() {
    Map<String, Object> row = fullKey();
    row.put("email", "ops@example.com");
    CqlSession session = usersSession();

    GeneratedStatements updates =
        service.generate(
            session,
            "demo",
            "users",
            new StatementGenerationSpec(
                StatementGenerationSpec.Kind.UPDATE, List.of(row), List.of(), null, null, false, false, true));
    assertThat(updates.statements()).singleElement().asString()
        .startsWith("UPDATE demo.users SET email = 'ops@example.com' WHERE user_id =");

    GeneratedStatements deletes =
        service.generate(
            session,
            "demo",
            "users",
            new StatementGenerationSpec(
                StatementGenerationSpec.Kind.DELETE, List.of(row), List.of(), null, 5L, true, false, true));
    assertThat(deletes.statements()).singleElement().asString()
        .startsWith("DELETE FROM demo.users USING TIMESTAMP 5 WHERE").endsWith("IF EXISTS;");
  }

  @Test
  void wrapsGeneratedStatementsInABatchWithTheMultiPartitionWarning() {
    Map<String, Object> first = fullKey();
    first.put("email", "a@b.c");
    Map<String, Object> second = new LinkedHashMap<>(first);
    second.put("user_id", "2a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d");

    GeneratedStatements generated =
        service.generate(
            usersSession(),
            "demo",
            "users",
            new StatementGenerationSpec(
                StatementGenerationSpec.Kind.INSERT,
                List.of(first, second),
                List.of(),
                null,
                null,
                false,
                true,
                true));

    assertThat(generated.cql()).startsWith("BEGIN BATCH").endsWith("APPLY BATCH;");
    assertThat(generated.warnings()).singleElement().asString().contains("multiple partitions");
  }

  @Test
  void generationRestrictsToTheRequestedColumns() {
    Map<String, Object> row = fullKey();
    row.put("email", "ops@example.com");
    row.put("logins", "3");

    GeneratedStatements generated =
        service.generate(
            usersSession(),
            "demo",
            "users",
            new StatementGenerationSpec(
                StatementGenerationSpec.Kind.UPDATE,
                List.of(row),
                List.of("email"),
                null,
                null,
                false,
                false,
                true));

    assertThat(generated.statements().get(0)).contains("email").doesNotContain("logins");
  }

  @Test
  void generationRefusesRowsWithoutTheCompleteKey() {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("user_id", USER_ID);
    row.put("email", "a@b.c");

    assertThatThrownBy(
            () ->
                service.generate(
                    usersSession(),
                    "demo",
                    "users",
                    new StatementGenerationSpec(
                        StatementGenerationSpec.Kind.UPDATE, List.of(row), List.of(), null, null,
                        false, false, true)))
        .isInstanceOf(IncompletePrimaryKeyException.class);
  }

  @Test
  void generationRejectsRowsWithNothingToSet() {
    assertThatThrownBy(
            () ->
                service.generate(
                    usersSession(),
                    "demo",
                    "users",
                    new StatementGenerationSpec(
                        StatementGenerationSpec.Kind.UPDATE, List.of(fullKey()), List.of(), null, null,
                        false, false, true)))
        .isInstanceOf(CassyxCoreException.class)
        .hasMessageContaining("non-primary-key column");
  }

  @Test
  void unknownColumnsAreRejectedRatherThanGuessed() {
    Map<String, Object> values = fullKey();
    values.put("nope", "x");

    assertThatThrownBy(
            () ->
                service.insert(
                    usersSession(),
                    "demo",
                    "users",
                    new RowInsertSpec(values, null, null, false, null, null, true)))
        .isInstanceOf(CassyxCoreException.class)
        .hasMessageContaining("No column nope");
  }

  /* ------------------------------------------------------------------------- execution */

  @Test
  void executesAndReportsFailedLwtCurrentValues() {
    CqlSession session = usersSession();
    // Built BEFORE the stubbing call: creating mocks inside a thenReturn() argument leaves Mockito
    // mid-stubbing and it rejects the whole thing.
    ResultSet failedLwt = lwtResultSet(false, Map.of("email", "old@example.com"));
    when(session.execute(any(Statement.class))).thenReturn(failedLwt);

    RowMutationOutcome outcome =
        service.update(
            session,
            "demo",
            "users",
            new RowUpdateSpec(
                fullKey(), Map.of("email", "a@b.c"), null, null, null, true, "LOCAL_QUORUM",
                "LOCAL_SERIAL", false));

    assertThat(outcome.executed()).isTrue();
    assertThat(outcome.applied()).isFalse();
    assertThat(outcome.currentValues()).containsEntry("email", "old@example.com");
  }

  @Test
  void executesNonConditionalStatementsWithoutAnAppliedFlag() {
    CqlSession session = usersSession();
    ResultSet rs = voidResultSet();
    when(session.execute(any(Statement.class))).thenReturn(rs);

    RowMutationOutcome outcome =
        service.delete(
            session,
            "demo",
            "users",
            new RowDeleteSpec(fullKey(), List.of(), null, false, null, "ONE", false));

    assertThat(outcome.executed()).isTrue();
    assertThat(outcome.applied()).isNull();
    assertThat(outcome.currentValues()).isNull();
  }

  private static ResultSet voidResultSet() {
    ResultSet rs = mock(ResultSet.class);
    ColumnDefinitions definitions = mock(ColumnDefinitions.class);
    when(definitions.contains("[applied]")).thenReturn(false);
    when(rs.getColumnDefinitions()).thenReturn(definitions);
    ExecutionInfo info = mock(ExecutionInfo.class);
    when(info.getWarnings()).thenReturn(List.of());
    when(rs.getExecutionInfo()).thenReturn(info);
    return rs;
  }

  private static ResultSet lwtResultSet(boolean applied, Map<String, Object> current) {
    ColumnDefinitions rowDefinitions = mock(ColumnDefinitions.class);
    Row row = mock(Row.class);
    int index = 0;
    for (Map.Entry<String, Object> entry : current.entrySet()) {
      ColumnDefinition definition = mock(ColumnDefinition.class);
      // doReturn, not when(...): a nested when() inside an outer when() argument leaves Mockito
      // with an unfinished stubbing.
      org.mockito.Mockito.doReturn(
              com.datastax.oss.driver.api.core.CqlIdentifier.fromInternal(entry.getKey()))
          .when(definition)
          .getName();
      org.mockito.Mockito.doReturn(definition).when(rowDefinitions).get(index);
      org.mockito.Mockito.doReturn(entry.getValue()).when(row).getObject(index);
      index++;
    }
    org.mockito.Mockito.doReturn(current.size()).when(rowDefinitions).size();
    org.mockito.Mockito.doReturn(rowDefinitions).when(row).getColumnDefinitions();

    ResultSet rs = mock(ResultSet.class);
    ColumnDefinitions definitions = mock(ColumnDefinitions.class);
    org.mockito.Mockito.doReturn(true).when(definitions).contains("[applied]");
    org.mockito.Mockito.doReturn(definitions).when(rs).getColumnDefinitions();
    org.mockito.Mockito.doReturn(applied).when(rs).wasApplied();
    ExecutionInfo info = mock(ExecutionInfo.class);
    org.mockito.Mockito.doReturn(List.of()).when(info).getWarnings();
    org.mockito.Mockito.doReturn(info).when(rs).getExecutionInfo();
    org.mockito.Mockito.doReturn(row).when(rs).one();
    return rs;
  }
}
