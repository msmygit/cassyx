package io.cassyx.core.impl.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.datastax.oss.driver.api.core.CqlIdentifier;
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.ProtocolVersion;
import com.datastax.oss.driver.api.core.context.DriverContext;
import com.datastax.oss.driver.api.core.cql.AsyncResultSet;
import com.datastax.oss.driver.api.core.cql.ColumnDefinition;
import com.datastax.oss.driver.api.core.cql.ColumnDefinitions;
import com.datastax.oss.driver.api.core.cql.ExecutionInfo;
import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.api.core.cql.Statement;
import com.datastax.oss.driver.api.core.type.DataType;
import com.datastax.oss.driver.api.core.type.DataTypes;
import io.cassyx.core.api.CassyxCoreException;
import io.cassyx.core.api.query.BatchOutcome;
import io.cassyx.core.api.query.BatchSpec;
import io.cassyx.core.api.query.QueryCancellation;
import io.cassyx.core.api.query.QuerySpec;
import io.cassyx.core.api.query.ResultHandleExpiredException;
import io.cassyx.core.api.query.ResultPage;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class VirtualThreadQueryServiceTest {

  private final VirtualThreadQueryService service = new VirtualThreadQueryService();

  @AfterEach
  void tearDown() {
    service.close();
  }

  /* ------------------------------------------------------------------------------ execute */

  @Test
  void executesAndReturnsTheFirstPageWithAPagingHandle() {
    CqlSession session = session();
    stubExecute(session, page(List.of(Map.of("id", 1)), "state-1", null, false));

    ResultPage result = service.execute(session, QuerySpec.of("SELECT * FROM demo.users"));

    assertThat(result.resultHandle()).startsWith("rs_");
    assertThat(result.queryId()).isNotBlank();
    assertThat(result.pageNumber()).isEqualTo(1);
    assertThat(result.rowCount()).isEqualTo(1);
    assertThat(result.rows()).singleElement().isEqualTo(Map.of("id", 1));
    assertThat(result.hasMorePages()).isTrue();
    assertThat(result.nextPageToken()).isNotBlank();
    assertThat(result.previousPageToken()).isNull();
    assertThat(result.applied()).isNull();
    assertThat(result.wasVoid()).isFalse();
    assertThat(result.columns()).singleElement().satisfies(c -> {
      assertThat(c.name()).isEqualTo("id");
      assertThat(c.type()).isEqualTo("int");
    });
  }

  @Test
  void honoursTheCallerSuppliedQueryIdSoTheUiCanCancelBeforeTheResponseArrives() {
    CqlSession session = session();
    stubExecute(session, page(List.of(), null, null, false));

    ResultPage result =
        service.execute(
            session,
            new QuerySpec(
                "SELECT 1", null, null, null, "LOCAL_ONE", null, 500, Duration.ofSeconds(5),
                false, true, "my-query-id"));

    assertThat(result.queryId()).isEqualTo("my-query-id");
  }

  @Test
  @DisplayName("[applied] is surfaced as a distinct field, not just another column")
  void surfacesTheLwtAppliedFlag() {
    CqlSession session = session();
    stubExecute(session, page(List.of(Map.of("[applied]", false)), null, null, false, "[applied]"));

    ResultPage result = service.execute(session, QuerySpec.of("INSERT ... IF NOT EXISTS"));

    assertThat(result.applied()).isFalse();
  }

  @Test
  void aStatementWithNoColumnsIsReportedAsVoid() {
    CqlSession session = session();
    stubExecute(session, page(List.of(), null, null, true));

    ResultPage result = service.execute(session, QuerySpec.of("TRUNCATE demo.users"));

    assertThat(result.wasVoid()).isTrue();
    assertThat(result.hasMorePages()).isFalse();
    assertThat(result.nextPageToken()).isNull();
  }

  @Test
  void serverWarningsAreCarriedThrough() {
    CqlSession session = session();
    AsyncResultSet rs = page(List.of(), null, null, false);
    when(rs.getExecutionInfo().getWarnings()).thenReturn(List.of("Read 5000 live rows"));
    stubExecute(session, rs);

    assertThat(service.execute(session, QuerySpec.of("SELECT 1")).warnings())
        .contains("Read 5000 live rows");
  }

  @Test
  void driverFailuresPropagateUnwrapped() {
    CqlSession session = session();
    when(session.executeAsync(any(Statement.class)))
        .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("boom")));

    assertThatThrownBy(() -> service.execute(session, QuerySpec.of("SELECT 1")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("boom");
  }

  @Test
  void checkedDriverFailuresBecomeCoreExceptions() {
    CqlSession session = session();
    when(session.executeAsync(any(Statement.class)))
        .thenReturn(CompletableFuture.failedFuture(new java.io.IOException("network")));

    assertThatThrownBy(() -> service.execute(session, QuerySpec.of("SELECT 1")))
        .isInstanceOf(CassyxCoreException.class)
        .hasMessageContaining("network");
  }

  @Test
  void anUnknownConsistencyLevelIsRejectedBeforeExecution() {
    CqlSession session = session();

    assertThatThrownBy(
            () ->
                service.execute(
                    session,
                    new QuerySpec("SELECT 1", null, null, null, "NOPE", null, 500, null, false, false, null)))
        .isInstanceOf(CassyxCoreException.class)
        .hasMessageContaining("Unknown consistency level");
  }

  @Test
  void serialConsistencyMustBeASerialLevel() {
    CqlSession session = session();

    assertThatThrownBy(
            () ->
                service.execute(
                    session,
                    new QuerySpec(
                        "SELECT 1", null, null, null, null, "QUORUM", 500, null, false, false, null)))
        .isInstanceOf(CassyxCoreException.class)
        .hasMessageContaining("SERIAL or LOCAL_SERIAL");
  }

  /* ------------------------------------------------------------------- cancellation & trace */

  @Test
  void cancellingAnUnknownQueryReportsNotFound() {
    QueryCancellation result = service.cancel("nope");

    assertThat(result.cancelled()).isFalse();
    assertThat(result.state()).isEqualTo(QueryCancellation.State.NOT_FOUND);
  }

  @Test
  void cancellingACompletedQueryReportsAlreadyCompleted() {
    CqlSession session = session();
    stubExecute(session, page(List.of(), null, null, false));
    ResultPage result = service.execute(session, QuerySpec.of("SELECT 1"));

    assertThat(service.cancel(result.queryId()).state())
        .isEqualTo(QueryCancellation.State.ALREADY_COMPLETED);
  }

  @Test
  @DisplayName("A query still running can be cancelled from the UI")
  void cancelsAnInFlightQuery() throws Exception {
    CqlSession session = session();
    CompletableFuture<AsyncResultSet> never = new CompletableFuture<>();
    when(session.executeAsync(any(Statement.class))).thenReturn(never);

    CompletableFuture<Throwable> failure = new CompletableFuture<>();
    Thread caller =
        Thread.ofVirtual()
            .start(
                () -> {
                  try {
                    service.execute(
                        session,
                        new QuerySpec(
                            "SELECT 1", null, null, null, null, null, 500, null, false, false, "cancel-me"));
                    failure.complete(null);
                  } catch (RuntimeException e) {
                    failure.complete(e);
                  }
                });

    QueryCancellation cancellation = null;
    for (int i = 0; i < 200 && (cancellation == null || !cancellation.cancelled()); i++) {
      cancellation = service.cancel("cancel-me");
      if (!cancellation.cancelled()) {
        Thread.sleep(10);
      }
    }
    caller.join(java.time.Duration.ofSeconds(5));

    assertThat(cancellation).isNotNull();
    assertThat(cancellation.cancelled()).isTrue();
    assertThat(failure.get()).isInstanceOf(CassyxCoreException.class);
  }

  @Test
  void thereIsNoTraceForAQueryThatDidNotRequestOne() {
    CqlSession session = session();
    stubExecute(session, page(List.of(), null, null, false));
    ResultPage result = service.execute(session, QuerySpec.of("SELECT 1"));

    assertThat(service.trace(result.queryId())).isEmpty();
    assertThat(service.trace("unknown")).isEmpty();
  }

  /* --------------------------------------------------------------------------- result sets */

  @Test
  void resultHandlesAreReleasableAndThenGone() {
    CqlSession session = session();
    stubExecute(session, page(List.of(), null, null, false));
    ResultPage result = service.execute(session, QuerySpec.of("SELECT 1"));

    assertThat(service.resultSetInfo(result.resultHandle()).cql()).isEqualTo("SELECT 1");
    service.closeResultSet(result.resultHandle());

    assertThatThrownBy(() -> service.closeResultSet(result.resultHandle()))
        .isInstanceOf(ResultHandleExpiredException.class);
    assertThat(service.sweepExpiredResultSets()).isZero();
  }

  /* -------------------------------------------------------------------------------- batch */

  @Test
  void assemblesEachBatchTypeWithoutExecutingWhenPreviewOnly() {
    CqlSession session = session();

    BatchOutcome logged =
        service.executeBatch(
            session,
            new BatchSpec(
                BatchSpec.Kind.LOGGED,
                List.of(new BatchSpec.Statement("INSERT INTO demo.users (id) VALUES (1);", null, null)),
                null,
                null,
                null,
                1755424262000000L,
                true));

    assertThat(logged.executed()).isFalse();
    assertThat(logged.statementCount()).isEqualTo(1);
    assertThat(logged.assembledCql())
        .isEqualTo(
            "BEGIN BATCH USING TIMESTAMP 1755424262000000\n"
                + "  INSERT INTO demo.users (id) VALUES (1);\n"
                + "APPLY BATCH;");

    BatchOutcome unlogged =
        service.executeBatch(
            session,
            new BatchSpec(
                BatchSpec.Kind.UNLOGGED,
                List.of(new BatchSpec.Statement("INSERT INTO demo.users (id) VALUES (1)", null, null)),
                null, null, null, null, true));
    assertThat(unlogged.assembledCql()).startsWith("BEGIN UNLOGGED BATCH");

    BatchOutcome counter =
        service.executeBatch(
            session,
            new BatchSpec(
                BatchSpec.Kind.COUNTER,
                List.of(new BatchSpec.Statement("UPDATE c SET n = n + 1 WHERE k = 1", null, null)),
                null, null, null, null, true));
    assertThat(counter.assembledCql()).startsWith("BEGIN COUNTER BATCH");
  }

  @Test
  @DisplayName("Inline literals defeat partition analysis, and the warning says so rather than lying")
  void warnsWhenPartitionAnalysisIsNotPossible() {
    CqlSession session = session();

    BatchOutcome outcome =
        service.executeBatch(
            session,
            new BatchSpec(
                BatchSpec.Kind.LOGGED,
                List.of(
                    new BatchSpec.Statement("INSERT INTO demo.users (id) VALUES (1)", null, null),
                    new BatchSpec.Statement("INSERT INTO demo.users (id) VALUES (2)", null, null)),
                null, null, null, null, true));

    assertThat(outcome.spansMultiplePartitions()).isTrue();
    assertThat(outcome.distinctPartitions()).isEqualTo(2);
    assertThat(outcome.warnings()).anySatisfy(w -> assertThat(w).contains("Bind values with ?"));
  }

  @Test
  void anEmptyBatchIsRejected() {
    assertThatThrownBy(
            () ->
                service.executeBatch(
                    session(),
                    new BatchSpec(BatchSpec.Kind.LOGGED, List.of(), null, null, null, null, true)))
        .isInstanceOf(CassyxCoreException.class)
        .hasMessageContaining("at least one statement");
  }

  /* ------------------------------------------------------------------------------ helpers */

  private static CqlSession session() {
    CqlSession session = mock(CqlSession.class);
    DriverContext context = mock(DriverContext.class);
    lenient().when(context.getProtocolVersion()).thenReturn(ProtocolVersion.V4);
    lenient().when(session.getContext()).thenReturn(context);
    com.datastax.oss.driver.api.core.metadata.Metadata metadata =
        mock(com.datastax.oss.driver.api.core.metadata.Metadata.class);
    lenient().when(metadata.getKeyspace(any(CqlIdentifier.class))).thenReturn(java.util.Optional.empty());
    lenient().when(session.getMetadata()).thenReturn(metadata);
    return session;
  }

  private static void stubExecute(CqlSession session, AsyncResultSet result) {
    when(session.executeAsync(any(Statement.class)))
        .thenReturn(CompletableFuture.completedFuture(result));
  }

  private static AsyncResultSet page(
      List<Map<String, Object>> rows, String nextState, String tracingId, boolean voidResult, String... names) {

    List<String> columnNames =
        names.length > 0
            ? List.of(names)
            : voidResult ? List.of() : rows.isEmpty() ? List.of("id") : List.copyOf(rows.get(0).keySet());

    AsyncResultSet rs = mock(AsyncResultSet.class);
    ColumnDefinitions definitions = mock(ColumnDefinitions.class);
    List<ColumnDefinition> defs = new ArrayList<>();
    for (String name : columnNames) {
      defs.add(definition(name, DataTypes.INT));
    }
    lenient().when(definitions.iterator()).thenAnswer(i -> defs.iterator());
    lenient().when(definitions.size()).thenReturn(defs.size());
    when(rs.getColumnDefinitions()).thenReturn(definitions);

    List<Row> driverRows = new ArrayList<>();
    for (Map<String, Object> values : rows) {
      Row row = mock(Row.class);
      int index = 0;
      for (String column : columnNames) {
        lenient().when(row.getObject(index)).thenReturn(values.get(column));
        index++;
      }
      driverRows.add(row);
    }
    lenient().when(rs.currentPage()).thenReturn(driverRows);
    lenient().when(rs.wasApplied()).thenReturn(false);

    ExecutionInfo info = mock(ExecutionInfo.class);
    lenient()
        .when(info.getPagingState())
        .thenReturn(nextState == null ? null : ByteBuffer.wrap(nextState.getBytes(StandardCharsets.UTF_8)));
    lenient().when(info.getWarnings()).thenReturn(List.of());
    lenient()
        .when(info.getTracingId())
        .thenReturn(tracingId == null ? null : java.util.UUID.fromString(tracingId));
    when(rs.getExecutionInfo()).thenReturn(info);
    return rs;
  }

  private static ColumnDefinition definition(String name, DataType type) {
    ColumnDefinition definition = mock(ColumnDefinition.class);
    lenient().when(definition.getName()).thenReturn(CqlIdentifier.fromInternal(name));
    lenient().when(definition.getType()).thenReturn(type);
    lenient().when(definition.getKeyspace()).thenReturn(CqlIdentifier.fromInternal("demo"));
    lenient().when(definition.getTable()).thenReturn(CqlIdentifier.fromInternal("users"));
    return definition;
  }
}
