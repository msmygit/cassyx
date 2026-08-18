package io.cassyx.api.query;

import com.datastax.oss.driver.api.core.CqlSession;
import io.cassyx.core.api.SessionRegistry;
import io.cassyx.core.api.query.BatchSpec;
import io.cassyx.core.api.query.CqlLexer;
import io.cassyx.core.api.query.CqlScriptSplitter;
import io.cassyx.core.api.query.QueryService;
import io.cassyx.core.api.query.QuerySpec;
import io.cassyx.core.api.query.ResultPage;
import jakarta.validation.Valid;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The {@code query} tag of the contract (plan section 5.1).
 *
 * <p>Requests are served on virtual threads ({@code spring.threads.virtual.enabled=true}), so a
 * blocking wait for a slow query costs a stack, not a platform thread.
 *
 * <p><b>{@code X-Cassyx-Query-Id}.</b> Cancellation needs an id the client already knows: if the id
 * only appeared in the response, a client could never cancel the query it is still waiting for -
 * which is exactly the query anyone wants to cancel. The header is optional and additive; when it is
 * absent the server allocates the id and cancellation is only possible after the fact.
 */
@RestController
public class QueryController {

  private final SessionRegistry sessions;
  private final QueryService queries;
  private final CqlScriptSplitter splitter;
  private final CqlLexer lexer;
  private final QueryHistoryRepository history;

  public QueryController(
      SessionRegistry sessions,
      QueryService queries,
      CqlScriptSplitter splitter,
      CqlLexer lexer,
      QueryHistoryRepository history) {
    this.sessions = sessions;
    this.queries = queries;
    this.splitter = splitter;
    this.lexer = lexer;
    this.history = history;
  }

  @PostMapping("/api/connections/{connectionId}/query")
  public QueryDtos.QueryResult executeQuery(
      @PathVariable String connectionId,
      @RequestHeader(value = "X-Cassyx-Query-Id", required = false) String suppliedQueryId,
      @Valid @RequestBody QueryDtos.QueryRequest request) {

    CqlSession session = sessions.session(connectionId);
    String queryId =
        suppliedQueryId == null || suppliedQueryId.isBlank()
            ? UUID.randomUUID().toString()
            : suppliedQueryId;
    QuerySpec spec = QueryMapper.toSpec(request, queryId);

    long started = System.nanoTime();
    try {
      ResultPage page = CqlExecution.run(request.cql(), () -> queries.execute(session, spec));
      history.record(
          connectionId,
          request.keyspace(),
          request.cql(),
          page.consistency() == null ? request.consistency() : page.consistency(),
          page.elapsedMillis(),
          page.rowCount(),
          true,
          null,
          page.tracingId());
      return QueryMapper.toDto(page);
    } catch (RuntimeException e) {
      history.record(
          connectionId,
          request.keyspace(),
          request.cql(),
          request.consistency(),
          TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
          0,
          false,
          e.getMessage(),
          null);
      throw e;
    }
  }

  @PostMapping("/api/connections/{connectionId}/query/batch")
  public QueryDtos.BatchResult executeBatch(
      @PathVariable String connectionId, @Valid @RequestBody QueryDtos.BatchRequest request) {
    CqlSession session = sessions.session(connectionId);
    BatchSpec spec = QueryMapper.toSpec(request);
    String firstCql = request.statements().isEmpty() ? null : request.statements().get(0).cql();
    return QueryMapper.toDto(CqlExecution.run(firstCql, () -> queries.executeBatch(session, spec)));
  }

  @GetMapping("/api/query/results/{resultHandle}")
  public QueryDtos.ResultSetState getResultSetState(@PathVariable String resultHandle) {
    return QueryMapper.toDto(queries.resultSetInfo(resultHandle));
  }

  @DeleteMapping("/api/query/results/{resultHandle}")
  public ResponseEntity<Void> closeResultSet(@PathVariable String resultHandle) {
    queries.closeResultSet(resultHandle);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/api/query/results/{resultHandle}/next-page")
  public QueryDtos.QueryResult fetchNextPage(
      @PathVariable String resultHandle, @Valid @RequestBody QueryDtos.PageRequest request) {
    return QueryMapper.toDto(queries.nextPage(resultHandle, request.pageToken(), request.fetchSize()));
  }

  @PostMapping("/api/query/results/{resultHandle}/previous-page")
  public QueryDtos.QueryResult fetchPreviousPage(
      @PathVariable String resultHandle, @Valid @RequestBody QueryDtos.PageRequest request) {
    return QueryMapper.toDto(
        queries.previousPage(resultHandle, request.pageToken(), request.fetchSize()));
  }

  @PostMapping("/api/query/executions/{queryId}/cancel")
  public QueryDtos.QueryCancellationResult cancelQuery(@PathVariable String queryId) {
    return QueryMapper.toDto(queries.cancel(queryId));
  }

  @GetMapping("/api/query/executions/{queryId}/trace")
  public QueryDtos.QueryTrace getQueryTrace(@PathVariable String queryId) {
    return queries
        .trace(queryId)
        .map(QueryMapper::toDto)
        .orElseThrow(
            () ->
                new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "No trace for this query. It either ran without tracing, or the trace has not "
                        + "landed in system_traces yet."));
  }

  @PostMapping("/api/query/script/split")
  public QueryDtos.CqlScriptSplitResult splitCqlScript(
      @Valid @RequestBody QueryDtos.CqlScriptSplitRequest request) {
    return QueryMapper.toDto(splitter.split(request.cql(), request.cursorOffset()));
  }

  @PostMapping("/api/query/script/lex")
  public QueryDtos.CqlLexResult lexCqlScript(@Valid @RequestBody QueryDtos.CqlScriptSplitRequest request) {
    return QueryMapper.toDto(lexer.lex(request.cql()));
  }
}
