package io.cassyx.api.query;

import io.cassyx.core.api.query.BatchOutcome;
import io.cassyx.core.api.query.BatchSpec;
import io.cassyx.core.api.query.ColumnInfo;
import io.cassyx.core.api.query.CqlLexer;
import io.cassyx.core.api.query.CqlScriptSplitter;
import io.cassyx.core.api.query.QueryCancellation;
import io.cassyx.core.api.query.QuerySpec;
import io.cassyx.core.api.query.ResultPage;
import io.cassyx.core.api.query.ResultSetInfo;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

/** Pure translation between the contract's wire shapes and the {@code cassyx-core} value objects. */
final class QueryMapper {

  private QueryMapper() {}

  static QuerySpec toSpec(QueryDtos.QueryRequest request, String queryId) {
    return new QuerySpec(
        request.cql(),
        request.positionalValues(),
        request.namedValues(),
        request.keyspace(),
        request.consistency(),
        request.serialConsistency(),
        request.fetchSize() == null ? QuerySpec.DEFAULT_FETCH_SIZE : request.fetchSize(),
        request.timeoutMillis() == null ? null : Duration.ofMillis(request.timeoutMillis()),
        Boolean.TRUE.equals(request.tracing()),
        Boolean.TRUE.equals(request.idempotent()),
        queryId);
  }

  static BatchSpec toSpec(QueryDtos.BatchRequest request) {
    List<BatchSpec.Statement> statements =
        request.statements().stream()
            .map(s -> new BatchSpec.Statement(s.cql(), s.positionalValues(), s.namedValues()))
            .toList();
    return new BatchSpec(
        parseBatchKind(request.type()),
        statements,
        request.keyspace(),
        request.consistency(),
        request.serialConsistency(),
        request.timestamp(),
        Boolean.TRUE.equals(request.previewOnly()));
  }

  static BatchSpec.Kind parseBatchKind(String type) {
    if (type == null || type.isBlank()) {
      return BatchSpec.Kind.LOGGED;
    }
    try {
      return BatchSpec.Kind.valueOf(type.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Batch type must be LOGGED, UNLOGGED or COUNTER, got '" + type + "'");
    }
  }

  static QueryDtos.QueryResult toDto(ResultPage page) {
    return new QueryDtos.QueryResult(
        page.resultHandle(),
        page.queryId(),
        page.columns().stream().map(QueryMapper::toDto).toList(),
        page.rows(),
        page.rowCount(),
        page.pageNumber(),
        page.hasMorePages(),
        page.nextPageToken(),
        page.previousPageToken(),
        page.applied(),
        page.wasVoid(),
        page.elapsedMillis(),
        page.warnings(),
        page.tracingId(),
        page.similarityColumns(),
        page.coordinator(),
        page.consistency());
  }

  static QueryDtos.ColumnMetadata toDto(ColumnInfo column) {
    return new QueryDtos.ColumnMetadata(
        column.name(),
        column.type(),
        column.keyspace(),
        column.table(),
        column.primaryKeyColumn(),
        column.kind(),
        column.collection(),
        column.vector(),
        column.vectorDimensions(),
        column.udt(),
        column.similarity());
  }

  static QueryDtos.ResultSetState toDto(ResultSetInfo info) {
    QueryDtos.SourceTable source =
        info.keyspace() == null || info.table() == null
            ? null
            : new QueryDtos.SourceTable(
                "TABLE", info.keyspace(), info.table(), info.keyspace() + "." + info.table());
    return new QueryDtos.ResultSetState(
        info.resultHandle(),
        info.cql(),
        info.columns().stream().map(QueryMapper::toDto).toList(),
        info.pagesFetched(),
        info.rowsFetched(),
        info.hasMorePages(),
        info.editable(),
        source,
        info.expiresAt());
  }

  static QueryDtos.BatchResult toDto(BatchOutcome outcome) {
    return new QueryDtos.BatchResult(
        outcome.assembledCql(),
        outcome.statementCount(),
        outcome.spansMultiplePartitions(),
        outcome.distinctPartitions(),
        outcome.warnings(),
        outcome.executed(),
        outcome.applied(),
        outcome.elapsedMillis());
  }

  static QueryDtos.QueryCancellationResult toDto(QueryCancellation cancellation) {
    return new QueryDtos.QueryCancellationResult(
        cancellation.queryId(),
        cancellation.cancelled(),
        cancellation.state().name(),
        cancellation.message());
  }

  static QueryDtos.QueryTrace toDto(io.cassyx.core.api.query.QueryTrace trace) {
    return new QueryDtos.QueryTrace(
        trace.tracingId(),
        trace.requestType(),
        trace.coordinator(),
        trace.durationMicros(),
        trace.startedAt(),
        trace.parameters(),
        trace.events().stream()
            .map(
                e ->
                    new QueryDtos.QueryTraceEvent(
                        e.activity(), e.source(), e.sourceElapsedMicros(), e.threadName(), e.timestamp()))
            .toList());
  }

  static QueryDtos.CqlScriptSplitResult toDto(CqlScriptSplitter.Result result) {
    return new QueryDtos.CqlScriptSplitResult(
        result.statements().stream()
            .map(
                s ->
                    new QueryDtos.CqlStatementSlice(
                        s.index(),
                        s.cql(),
                        s.startOffset(),
                        s.endOffset(),
                        s.startLine(),
                        s.kind().name(),
                        s.underCursor()))
            .toList(),
        result.errors().stream().map(QueryMapper::toDto).toList());
  }

  static QueryDtos.CqlLexResult toDto(CqlLexer.Result result) {
    return new QueryDtos.CqlLexResult(
        result.tokens().stream()
            .map(
                t ->
                    new QueryDtos.CqlToken(
                        t.type().name(), t.text(), t.startOffset(), t.endOffset(), t.line(), t.column()))
            .toList(),
        result.errors().stream().map(QueryMapper::toDto).toList());
  }

  static QueryDtos.CqlSyntaxProblem toDto(CqlLexer.Problem problem) {
    return new QueryDtos.CqlSyntaxProblem(problem.message(), problem.offset(), problem.line());
  }
}
