package io.cassyx.core.impl.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.ExecutionInfo;
import com.datastax.oss.driver.api.core.cql.ResultSet;
import com.datastax.oss.driver.api.core.cql.Statement;
import io.cassyx.core.api.schema.DdlExecuteRequest;
import io.cassyx.core.api.schema.DdlExecutionResult;
import io.cassyx.core.api.schema.DdlPreview;
import io.cassyx.core.api.schema.InvalidDefinitionException;
import io.cassyx.core.api.schema.SchemaIdentity;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SessionDdlExecutorTest {

  private final SessionDdlExecutor executor = new SessionDdlExecutor();
  private CqlSession session;

  @BeforeEach
  void setUp() {
    session = mock(CqlSession.class);
    ExecutionInfo executionInfo = mock(ExecutionInfo.class);
    lenient().when(executionInfo.getWarnings()).thenReturn(List.of("server said something"));
    ResultSet resultSet = mock(ResultSet.class);
    lenient().when(resultSet.getExecutionInfo()).thenReturn(executionInfo);
    lenient().when(session.execute(any(Statement.class))).thenReturn(resultSet);
    lenient().when(session.checkSchemaAgreement()).thenReturn(true);
  }

  @Test
  void splitsWithTheLexerNotOnSemicolons() {
    DdlExecutionResult result =
        executor.execute(
            session,
            new DdlExecuteRequest(
                "CREATE TABLE demo.t (k text PRIMARY KEY);\n"
                    + "ALTER TABLE demo.t WITH comment = 'has ; a semicolon';",
                true,
                true,
                null));

    assertThat(result.statementsExecuted()).isEqualTo(2);
    assertThat(result.executedCql().get(1)).contains("has ; a semicolon");
    assertThat(result.success()).isTrue();
    assertThat(result.schemaAgreement()).isTrue();
    assertThat(result.warnings()).contains("server said something");
    assertThat(result.elapsedMillis()).isGreaterThanOrEqualTo(0);
  }

  @Test
  void redactsRolePasswordsFromTheExecutedStatements() {
    DdlExecutionResult result =
        executor.execute(
            session,
            new DdlExecuteRequest("CREATE ROLE r WITH PASSWORD = 's3cret' AND LOGIN = true;", null, null, null));

    assertThat(result.executedCql()).allSatisfy(cql -> assertThat(cql).doesNotContain("s3cret"));
    assertThat(result.executedCql().get(0)).contains("PASSWORD = '***'");

    ArgumentCaptor<Statement<?>> captured = ArgumentCaptor.forClass(Statement.class);
    verify(session).execute(captured.capture());
    assertThat(captured.getValue().toString()).isNotNull();
  }

  @Test
  void executesAPreviewAndKeepsItsTargetIdentity() {
    DdlPreview preview =
        DdlPreview.of(
            SchemaIdentity.table("demo", "users"),
            List.of("TRUNCATE TABLE demo.users;"),
            List.of());

    DdlExecutionResult result = executor.execute(session, preview, false);

    assertThat(result.affectedIdentity().qualifiedName()).isEqualTo("demo.users");
    assertThat(result.schemaAgreement()).isFalse();
  }

  @Test
  void rejectsEmptyScripts() {
    assertThatThrownBy(() -> executor.execute(session, new DdlExecuteRequest(null, null, null, null)))
        .isInstanceOf(InvalidDefinitionException.class);
    assertThatThrownBy(() -> executor.execute(session, (DdlExecuteRequest) null))
        .isInstanceOf(InvalidDefinitionException.class);
    assertThatThrownBy(
            () -> executor.execute(session, new DdlExecuteRequest(";", null, null, null)))
        .isInstanceOf(InvalidDefinitionException.class);
  }

  @Test
  void stopOnErrorPropagatesAndContinueCollectsTheMessage() {
    when(session.execute(any(Statement.class))).thenThrow(new IllegalStateException("boom"));

    assertThatThrownBy(
            () -> executor.execute(session, new DdlExecuteRequest("DROP TABLE demo.t;", true, false, null)))
        .isInstanceOf(IllegalStateException.class);

    DdlExecutionResult result =
        executor.execute(
            session, new DdlExecuteRequest("DROP TABLE demo.t;", false, false, null));
    assertThat(result.success()).isFalse();
    assertThat(result.warnings()).contains("boom");
  }

  @Test
  void acceptsAnInjectedSplitter() {
    SessionDdlExecutor custom =
        new SessionDdlExecutor(new io.cassyx.core.impl.DefaultCqlStatementSplitter());
    assertThat(custom.execute(session, new DdlExecuteRequest("DROP TABLE demo.t;", null, null, null))
            .statementsExecuted())
        .isEqualTo(1);
  }
}
