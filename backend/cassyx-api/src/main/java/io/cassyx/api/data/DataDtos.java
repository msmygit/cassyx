package io.cassyx.api.data;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;

/**
 * Wire shapes for the {@code data} tag of {@code openapi/cassyx-api.yaml} (plan section 7).
 *
 * <p>Every value map is a {@code CqlValue} map: a key mapped to the string {@code "$unset"}, or
 * simply absent, means <b>unset</b>; an explicit {@code null} writes a tombstone. That distinction
 * survives all the way to the generated CQL.
 */
public final class DataDtos {

  private DataDtos() {}

  /** Contract: {@code RowInsertRequest}. */
  public record RowInsertRequest(
      @NotNull Map<String, Object> values,
      @Min(0) Integer ttlSeconds,
      Long timestampMicros,
      Boolean ifNotExists,
      String consistency,
      String serialConsistency,
      Boolean previewOnly) {}

  /** Contract: {@code RowUpdateRequest}. */
  public record RowUpdateRequest(
      @NotNull Map<String, Object> primaryKey,
      @NotNull Map<String, Object> values,
      @Min(0) Integer ttlSeconds,
      Long timestampMicros,
      String condition,
      Boolean ifExists,
      String consistency,
      String serialConsistency,
      Boolean previewOnly) {}

  /** Contract: {@code RowDeleteRequest}. */
  public record RowDeleteRequest(
      @NotNull Map<String, Object> primaryKey,
      List<String> columns,
      Long timestampMicros,
      Boolean ifExists,
      String condition,
      String consistency,
      Boolean previewOnly) {}

  /** Contract: {@code RowMutationResult}. */
  public record RowMutationResult(
      boolean executed,
      String cql,
      Boolean applied,
      Map<String, Object> currentValues,
      long elapsedMillis,
      List<String> warnings) {}

  /** Contract: {@code RowStatementGenerationRequest}. */
  public record RowStatementGenerationRequest(
      @NotNull String statementKind,
      @NotEmpty List<Map<String, Object>> rows,
      List<String> columns,
      @Min(0) Integer ttlSeconds,
      Long timestampMicros,
      Boolean includeIfConditions,
      Boolean asBatch,
      Boolean formatted) {}

  /** Contract: {@code RowStatementGenerationResult}. */
  public record RowStatementGenerationResult(
      List<String> statements, String cql, int rowCount, List<String> warnings) {}

  /** Contract: {@code RowEditabilityRequest}. */
  public record RowEditabilityRequest(@NotNull List<String> projectedColumns, String resultHandle) {}

  /** Contract: {@code RowEditabilityResult}. */
  public record RowEditabilityResult(
      boolean editable,
      List<String> requiredKeyColumns,
      List<String> missingKeyColumns,
      String reason,
      String suggestedCql) {}
}
