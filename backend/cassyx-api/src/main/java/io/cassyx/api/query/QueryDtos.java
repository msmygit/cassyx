package io.cassyx.api.query;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Wire shapes for the {@code query} tag of {@code openapi/cassyx-api.yaml}.
 *
 * <p>These are deliberately separate from the {@code cassyx-core} records: the contract governs the
 * wire, and letting a core record leak into a response would mean a core refactor silently becomes
 * an API change. Field names and nullability here mirror the spec exactly.
 */
public final class QueryDtos {

  private QueryDtos() {}

  /** Contract: {@code QueryRequest}. */
  public record QueryRequest(
      @NotBlank String cql,
      List<Object> positionalValues,
      Map<String, Object> namedValues,
      String keyspace,
      String consistency,
      String serialConsistency,
      @Min(1) @Max(10000) Integer fetchSize,
      @Min(100) Integer timeoutMillis,
      Boolean tracing,
      Boolean idempotent,
      Boolean pageAllRows) {}

  /** Contract: {@code ColumnMetadata}. */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  public record ColumnMetadata(
      String name,
      String type,
      String keyspace,
      String table,
      boolean primaryKeyColumn,
      String kind,
      boolean collection,
      boolean vector,
      Integer vectorDimensions,
      boolean udt,
      boolean similarity) {}

  /** Contract: {@code QueryResult}. */
  public record QueryResult(
      String resultHandle,
      String queryId,
      List<ColumnMetadata> columns,
      List<Map<String, Object>> rows,
      int rowCount,
      int pageNumber,
      boolean hasMorePages,
      String nextPageToken,
      String previousPageToken,
      Boolean applied,
      boolean wasVoid,
      long elapsedMillis,
      List<String> warnings,
      String tracingId,
      List<String> similarityColumns,
      String coordinator,
      String consistency) {}

  /** Contract: {@code PageRequest}. */
  public record PageRequest(@NotBlank String pageToken, @Min(1) @Max(10000) Integer fetchSize) {}

  /** Contract: {@code SchemaIdentity}, in the subset a result set can populate. */
  public record SourceTable(String kind, String keyspace, String table, String qualifiedName) {}

  /** Contract: {@code ResultSetState}. */
  public record ResultSetState(
      String resultHandle,
      String cql,
      List<ColumnMetadata> columns,
      int pagesFetched,
      long rowsFetched,
      boolean hasMorePages,
      boolean editable,
      SourceTable sourceTable,
      Instant expiresAt) {}

  /** Contract: {@code QueryCancellationResult}. */
  public record QueryCancellationResult(
      String queryId, boolean cancelled, String state, String message) {}

  /** Contract: {@code QueryTraceEvent}. */
  public record QueryTraceEvent(
      String activity, String source, long sourceElapsedMicros, String threadName, Instant timestamp) {}

  /** Contract: {@code QueryTrace}. */
  public record QueryTrace(
      String tracingId,
      String requestType,
      String coordinator,
      long durationMicros,
      Instant startedAt,
      Map<String, String> parameters,
      List<QueryTraceEvent> events) {}

  /** Contract: {@code BatchStatement}. */
  public record BatchStatementDto(
      @NotBlank String cql, List<Object> positionalValues, Map<String, Object> namedValues) {}

  /** Contract: {@code BatchRequest}. */
  public record BatchRequest(
      @NotNull String type,
      @NotEmpty List<BatchStatementDto> statements,
      String keyspace,
      String consistency,
      String serialConsistency,
      Long timestamp,
      Boolean previewOnly) {}

  /** Contract: {@code BatchResult}. */
  public record BatchResult(
      String assembledCql,
      int statementCount,
      boolean spansMultiplePartitions,
      int distinctPartitions,
      List<String> warnings,
      boolean executed,
      Boolean applied,
      long elapsedMillis) {}

  /** Contract: {@code CqlScriptSplitRequest}. */
  public record CqlScriptSplitRequest(@NotNull String cql, Integer cursorOffset) {}

  /** Contract: {@code CqlStatementSlice}. */
  public record CqlStatementSlice(
      int index, String cql, int startOffset, int endOffset, int startLine, String kind, boolean underCursor) {}

  /** Contract: {@code CqlSyntaxProblem}. */
  public record CqlSyntaxProblem(String message, int offset, int line) {}

  /** Contract: {@code CqlScriptSplitResult}. */
  public record CqlScriptSplitResult(List<CqlStatementSlice> statements, List<CqlSyntaxProblem> errors) {}

  /** Contract: {@code CqlToken}. */
  public record CqlToken(String type, String text, int startOffset, int endOffset, int line, int column) {}

  /** Contract: {@code CqlLexResult}. */
  public record CqlLexResult(List<CqlToken> tokens, List<CqlSyntaxProblem> errors) {}

  /** Contract: {@code QueryHistoryEntry}. */
  public record QueryHistoryEntry(
      String id,
      String connectionId,
      String connectionName,
      String keyspace,
      String cql,
      Instant executedAt,
      long elapsedMillis,
      long rowCount,
      boolean success,
      String errorMessage,
      String consistency) {}

  /** Contract: {@code QueryHistoryPage}. */
  public record QueryHistoryPage(List<QueryHistoryEntry> items, int total, int limit, int offset) {}

  /** Contract: {@code SavedScriptRequest}. */
  public record SavedScriptRequest(
      @NotBlank String name,
      @NotNull String cql,
      String folder,
      Boolean favourite,
      String connectionId,
      String description) {}

  /** Contract: {@code SavedScript} (the request fields plus identity and timestamps). */
  public record SavedScript(
      String id,
      String name,
      String cql,
      String folder,
      boolean favourite,
      String connectionId,
      String description,
      Instant createdAt,
      Instant updatedAt) {}
}
