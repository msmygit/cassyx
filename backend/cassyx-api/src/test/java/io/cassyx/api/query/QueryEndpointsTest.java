package io.cassyx.api.query;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.datastax.oss.driver.api.core.CqlSession;
import io.cassyx.api.data.DataController;
import io.cassyx.core.api.ConnectionNotOpenException;
import io.cassyx.core.api.SessionRegistry;
import io.cassyx.core.api.query.BatchOutcome;
import io.cassyx.core.api.query.CqlLexer;
import io.cassyx.core.api.query.CqlScriptSplitter;
import io.cassyx.core.api.query.EditabilityVerdict;
import io.cassyx.core.api.query.GeneratedStatements;
import io.cassyx.core.api.query.IncompletePrimaryKeyException;
import io.cassyx.core.api.query.PageTokenMismatchException;
import io.cassyx.core.api.query.QueryCancellation;
import io.cassyx.core.api.query.QueryFactory;
import io.cassyx.core.api.query.QueryService;
import io.cassyx.core.api.query.ResultHandleExpiredException;
import io.cassyx.core.api.query.ResultPage;
import io.cassyx.core.api.query.ResultSetInfo;
import io.cassyx.core.api.query.RowMutationOutcome;
import io.cassyx.core.api.query.RowMutationService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * The {@code query} and {@code data} adapters against the contract's wire shapes.
 *
 * <p>Standalone MockMvc rather than a full context: these are thin adapters, and what is worth
 * asserting is the JSON shape and the status-code mapping, not Spring's ability to start.
 */
class QueryEndpointsTest {

  private final SessionRegistry sessions = mock(SessionRegistry.class);
  private final QueryService queries = mock(QueryService.class);
  private final RowMutationService rows = mock(RowMutationService.class);
  private final QueryHistoryRepository history = mock(QueryHistoryRepository.class);
  private final CqlScriptSplitter splitter = QueryFactory.scriptSplitter();
  private final CqlLexer lexer = QueryFactory.lexer();

  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    when(sessions.session(anyString())).thenReturn(mock(CqlSession.class));
    mvc =
        MockMvcBuilders.standaloneSetup(
                new QueryController(sessions, queries, splitter, lexer, history),
                new DataController(sessions, rows))
            .setControllerAdvice(new QueryProblemAdvice())
            .build();
  }

  private static ResultPage page() {
    return new ResultPage(
        "rs_1",
        "q-1",
        List.of(
            new io.cassyx.core.api.query.ColumnInfo(
                "logins", "bigint", "demo", "users", false, "REGULAR", false, false, null, false, false)),
        List.of(Map.of("logins", "9007199254740993")),
        1,
        1,
        true,
        "tok-next",
        null,
        null,
        false,
        7L,
        List.of(),
        null,
        List.of(),
        "127.0.0.1:9042",
        "LOCAL_ONE");
  }

  @Test
  @DisplayName("executeQuery returns the contract's QueryResult, with bigints as strings")
  void executesAQuery() throws Exception {
    when(queries.execute(any(), any())).thenReturn(page());

    mvc.perform(
            post("/api/connections/c1/query")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Cassyx-Query-Id", "q-1")
                .content("{\"cql\":\"SELECT logins FROM demo.users\",\"fetchSize\":500}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.resultHandle").value("rs_1"))
        .andExpect(jsonPath("$.queryId").value("q-1"))
        .andExpect(jsonPath("$.rows[0].logins").value("9007199254740993"))
        .andExpect(jsonPath("$.nextPageToken").value("tok-next"))
        .andExpect(jsonPath("$.previousPageToken").doesNotExist())
        .andExpect(jsonPath("$.columns[0].type").value("bigint"));

    verify(history)
        .record(eq("c1"), any(), eq("SELECT logins FROM demo.users"), any(), anyLong(), anyLong(),
            anyBoolean(), any(), any());
  }

  @Test
  void aFailedQueryIsStillRecordedInHistory() throws Exception {
    when(queries.execute(any(), any())).thenThrow(new IllegalArgumentException("bad cql"));

    mvc.perform(
            post("/api/connections/c1/query")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"cql\":\"SELECT 1\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(jsonPath("$.title").value("Bad request"));

    verify(history)
        .record(eq("c1"), any(), eq("SELECT 1"), any(), anyLong(), anyLong(), eq(false), any(), any());
  }

  @Test
  void notConnectedIsA409() throws Exception {
    when(sessions.session("c1")).thenThrow(new ConnectionNotOpenException("c1"));

    mvc.perform(
            post("/api/connections/c1/query")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"cql\":\"SELECT 1\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.type").value("https://cassyx.dev/problems/not-connected"));
  }

  @Test
  void anExpiredResultHandleIsA404TellingTheClientToReRun() throws Exception {
    when(queries.resultSetInfo("rs_gone"))
        .thenThrow(new ResultHandleExpiredException("rs_gone", "expired after 10 minutes idle"));

    mvc.perform(get("/api/query/results/rs_gone"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.type").value("https://cassyx.dev/problems/result-handle-expired"))
        .andExpect(jsonPath("$.detail").value("expired after 10 minutes idle"));
  }

  @Test
  void aTokenFromAnotherResultSetIsA409() throws Exception {
    when(queries.nextPage(anyString(), anyString(), any()))
        .thenThrow(new PageTokenMismatchException("belongs to a different result set"));

    mvc.perform(
            post("/api/query/results/rs_1/next-page")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"pageToken\":\"tok\"}"))
        .andExpect(status().isConflict());
  }

  @Test
  void pagesForwardAndBackwards() throws Exception {
    when(queries.nextPage("rs_1", "tok", 250)).thenReturn(page());
    when(queries.previousPage("rs_1", "tok", null)).thenReturn(page());

    mvc.perform(
            post("/api/query/results/rs_1/next-page")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"pageToken\":\"tok\",\"fetchSize\":250}"))
        .andExpect(status().isOk());
    mvc.perform(
            post("/api/query/results/rs_1/previous-page")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"pageToken\":\"tok\"}"))
        .andExpect(status().isOk());
  }

  @Test
  void reportsResultSetStateAndReleasesIt() throws Exception {
    when(queries.resultSetInfo("rs_1"))
        .thenReturn(
            new ResultSetInfo(
                "rs_1", "SELECT 1", List.of(), 3, 1500, true, true, "demo", "users",
                Instant.parse("2026-08-17T10:45:00Z")));

    mvc.perform(get("/api/query/results/rs_1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rowsFetched").value(1500))
        .andExpect(jsonPath("$.editable").value(true))
        .andExpect(jsonPath("$.sourceTable.qualifiedName").value("demo.users"));

    mvc.perform(delete("/api/query/results/rs_1")).andExpect(status().isNoContent());
    verify(queries).closeResultSet("rs_1");
  }

  @Test
  void cancelsAQuery() throws Exception {
    when(queries.cancel("q-1"))
        .thenReturn(new QueryCancellation("q-1", true, QueryCancellation.State.CANCELLED, "gone"));

    mvc.perform(post("/api/query/executions/q-1/cancel"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.cancelled").value(true))
        .andExpect(jsonPath("$.state").value("CANCELLED"));
  }

  @Test
  void aQueryWithoutATraceIs404() throws Exception {
    when(queries.trace("q-1")).thenReturn(java.util.Optional.empty());

    mvc.perform(get("/api/query/executions/q-1/trace")).andExpect(status().isNotFound());
  }

  @Test
  void returnsTheTraceTimeline() throws Exception {
    when(queries.trace("q-1"))
        .thenReturn(
            java.util.Optional.of(
                new io.cassyx.core.api.query.QueryTrace(
                    "7f4c2b91-1d5e-11f0-9c3d-0242ac120002",
                    "Execute CQL3 query",
                    "127.0.0.1",
                    41230,
                    Instant.parse("2026-08-17T10:31:02.115Z"),
                    Map.of("page_size", "500"),
                    List.of(
                        new io.cassyx.core.api.query.QueryTrace.Event(
                            "Parsing", "127.0.0.1", 132, "Native-Transport-Requests-1",
                            Instant.parse("2026-08-17T10:31:02.115Z"))))));

    mvc.perform(get("/api/query/executions/q-1/trace"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.durationMicros").value(41230))
        .andExpect(jsonPath("$.events[0].activity").value("Parsing"))
        .andExpect(jsonPath("$.events[0].sourceElapsedMicros").value(132));
  }

  @Test
  @DisplayName("The split endpoint uses the real lexer: a semicolon in a literal is not a separator")
  void splitsAndLexesScripts() throws Exception {
    mvc.perform(
            post("/api/query/script/split")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"cql\":\"SELECT * FROM t WHERE a = 'x;y'; SELECT 1;\",\"cursorOffset\":5}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.statements.length()").value(2))
        .andExpect(jsonPath("$.statements[0].cql").value("SELECT * FROM t WHERE a = 'x;y'"))
        .andExpect(jsonPath("$.statements[0].kind").value("SELECT"))
        .andExpect(jsonPath("$.statements[0].underCursor").value(true));

    mvc.perform(
            post("/api/query/script/lex")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"cql\":\"SELECT 1\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tokens[0].type").value("KEYWORD"));
  }

  @Test
  void assemblesABatchWithItsPartitionVerdict() throws Exception {
    when(queries.executeBatch(any(), any()))
        .thenReturn(
            new BatchOutcome(
                "BEGIN BATCH\n  INSERT ...;\nAPPLY BATCH;", 1, false, 1, List.of(), false, null, 0L));

    mvc.perform(
            post("/api/connections/c1/query/batch")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"type\":\"LOGGED\",\"previewOnly\":true,"
                        + "\"statements\":[{\"cql\":\"INSERT INTO demo.users (id) VALUES (1)\"}]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.spansMultiplePartitions").value(false))
        .andExpect(jsonPath("$.executed").value(false));
  }

  @Test
  void rejectsAnUnknownBatchType() throws Exception {
    mvc.perform(
            post("/api/connections/c1/query/batch")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"NOPE\",\"statements\":[{\"cql\":\"INSERT ...\"}]}"))
        .andExpect(status().isBadRequest());
  }

  /* -------------------------------------------------------------------------- data tag */

  @Test
  void insertsUpdatesAndDeletesRows() throws Exception {
    when(rows.insert(any(), eq("demo"), eq("users"), any()))
        .thenReturn(new RowMutationOutcome(false, "INSERT INTO demo.users ...", null, null, 0L, List.of()));
    when(rows.update(any(), eq("demo"), eq("users"), any()))
        .thenReturn(new RowMutationOutcome(true, "UPDATE demo.users ...", Boolean.TRUE, null, 9L, List.of()));
    when(rows.delete(any(), eq("demo"), eq("users"), any()))
        .thenReturn(new RowMutationOutcome(true, "DELETE FROM demo.users ...", null, null, 3L, List.of()));

    mvc.perform(
            post("/api/connections/c1/keyspaces/demo/tables/users/rows")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"values\":{\"user_id\":\"1\"},\"previewOnly\":true}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.executed").value(false))
        .andExpect(jsonPath("$.cql").value("INSERT INTO demo.users ..."));

    mvc.perform(
            patch("/api/connections/c1/keyspaces/demo/tables/users/rows")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"primaryKey\":{\"user_id\":\"1\"},\"values\":{\"email\":\"a@b.c\"}}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.applied").value(true));

    mvc.perform(
            delete("/api/connections/c1/keyspaces/demo/tables/users/rows")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"primaryKey\":{\"user_id\":\"1\"}}"))
        .andExpect(status().isOk());
  }

  @Test
  @DisplayName("An incomplete primary key is 422 and NAMES the missing columns")
  void incompletePrimaryKeyIs422WithTheMissingColumns() throws Exception {
    when(rows.update(any(), anyString(), anyString(), any()))
        .thenThrow(new IncompletePrimaryKeyException("missing created_at", List.of("created_at")));

    mvc.perform(
            patch("/api/connections/c1/keyspaces/demo/tables/users/rows")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"primaryKey\":{\"user_id\":\"1\"},\"values\":{\"email\":\"a@b.c\"}}"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.type").value("https://cassyx.dev/problems/incomplete-primary-key"))
        .andExpect(jsonPath("$.missingKeyColumns[0]").value("created_at"));
  }

  @Test
  void generatesStatementsAndReportsEditability() throws Exception {
    when(rows.generate(any(), anyString(), anyString(), any()))
        .thenReturn(new GeneratedStatements(List.of("INSERT ...;"), "INSERT ...;", 1, List.of()));
    when(rows.editability(any(), anyString(), anyString(), any()))
        .thenReturn(
            new EditabilityVerdict(
                false, List.of("user_id", "created_at"), List.of("created_at"),
                "This result set does not project created_at", "SELECT user_id, created_at FROM demo.users"));

    mvc.perform(
            post("/api/connections/c1/keyspaces/demo/tables/users/rows/statements")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"statementKind\":\"INSERT\",\"rows\":[{\"user_id\":\"1\"}]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rowCount").value(1));

    mvc.perform(
            post("/api/connections/c1/keyspaces/demo/tables/users/rows/editability")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"projectedColumns\":[\"user_id\"]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.editable").value(false))
        .andExpect(jsonPath("$.missingKeyColumns[0]").value("created_at"))
        .andExpect(jsonPath("$.reason").value("This result set does not project created_at"));
  }

  @Test
  void rejectsAnUnknownStatementKind() throws Exception {
    mvc.perform(
            post("/api/connections/c1/keyspaces/demo/tables/users/rows/statements")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"statementKind\":\"MERGE\",\"rows\":[{}]}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value(
            "statementKind must be INSERT, UPDATE or DELETE, got 'MERGE'"));
  }
}
