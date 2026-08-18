package io.cassyx.api.data;

import com.datastax.oss.driver.api.core.CqlSession;
import io.cassyx.api.query.CqlExecution;
import io.cassyx.core.api.SessionRegistry;
import io.cassyx.core.api.query.EditabilityVerdict;
import io.cassyx.core.api.query.GeneratedStatements;
import io.cassyx.core.api.query.RowDeleteSpec;
import io.cassyx.core.api.query.RowInsertSpec;
import io.cassyx.core.api.query.RowMutationOutcome;
import io.cassyx.core.api.query.RowMutationService;
import io.cassyx.core.api.query.RowUpdateSpec;
import io.cassyx.core.api.query.StatementGenerationSpec;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Locale;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The {@code data} tag: row CRUD, statement generation and the editability verdict (plan section 7).
 *
 * <p>The hard rule is enforced in {@code cassyx-core} and surfaces here as {@code 422
 * IncompletePrimaryKey} with the missing columns named, so the grid can explain the refusal rather
 * than silently disabling itself.
 */
@RestController
public class DataController {

  private static final String ROWS = "/api/connections/{connectionId}/keyspaces/{keyspace}/tables/{table}/rows";

  private final SessionRegistry sessions;
  private final RowMutationService rows;

  public DataController(SessionRegistry sessions, RowMutationService rows) {
    this.sessions = sessions;
    this.rows = rows;
  }

  @PostMapping(ROWS)
  public DataDtos.RowMutationResult insertRow(
      @PathVariable String connectionId,
      @PathVariable String keyspace,
      @PathVariable String table,
      @Valid @RequestBody DataDtos.RowInsertRequest request) {

    CqlSession session = sessions.session(connectionId);
    RowInsertSpec spec =
        new RowInsertSpec(
            request.values(),
            request.ttlSeconds(),
            request.timestampMicros(),
            Boolean.TRUE.equals(request.ifNotExists()),
            request.consistency(),
            request.serialConsistency(),
            Boolean.TRUE.equals(request.previewOnly()));
    return toDto(execute(() -> rows.insert(session, keyspace, table, spec)));
  }

  @PatchMapping(ROWS)
  public DataDtos.RowMutationResult updateRow(
      @PathVariable String connectionId,
      @PathVariable String keyspace,
      @PathVariable String table,
      @Valid @RequestBody DataDtos.RowUpdateRequest request) {

    CqlSession session = sessions.session(connectionId);
    RowUpdateSpec spec =
        new RowUpdateSpec(
            request.primaryKey(),
            request.values(),
            request.ttlSeconds(),
            request.timestampMicros(),
            request.condition(),
            Boolean.TRUE.equals(request.ifExists()),
            request.consistency(),
            request.serialConsistency(),
            Boolean.TRUE.equals(request.previewOnly()));
    return toDto(execute(() -> rows.update(session, keyspace, table, spec)));
  }

  @DeleteMapping(ROWS)
  public DataDtos.RowMutationResult deleteRow(
      @PathVariable String connectionId,
      @PathVariable String keyspace,
      @PathVariable String table,
      @Valid @RequestBody DataDtos.RowDeleteRequest request) {

    CqlSession session = sessions.session(connectionId);
    RowDeleteSpec spec =
        new RowDeleteSpec(
            request.primaryKey(),
            request.columns() == null ? List.of() : request.columns(),
            request.timestampMicros(),
            Boolean.TRUE.equals(request.ifExists()),
            request.condition(),
            request.consistency(),
            Boolean.TRUE.equals(request.previewOnly()));
    return toDto(execute(() -> rows.delete(session, keyspace, table, spec)));
  }

  @PostMapping(ROWS + "/statements")
  public DataDtos.RowStatementGenerationResult generateRowStatements(
      @PathVariable String connectionId,
      @PathVariable String keyspace,
      @PathVariable String table,
      @Valid @RequestBody DataDtos.RowStatementGenerationRequest request) {

    CqlSession session = sessions.session(connectionId);
    StatementGenerationSpec spec =
        new StatementGenerationSpec(
            parseKind(request.statementKind()),
            request.rows(),
            request.columns(),
            request.ttlSeconds(),
            request.timestampMicros(),
            Boolean.TRUE.equals(request.includeIfConditions()),
            Boolean.TRUE.equals(request.asBatch()),
            request.formatted() == null || request.formatted());

    GeneratedStatements generated = rows.generate(session, keyspace, table, spec);
    return new DataDtos.RowStatementGenerationResult(
        generated.statements(), generated.cql(), generated.rowCount(), generated.warnings());
  }

  @PostMapping(ROWS + "/editability")
  public DataDtos.RowEditabilityResult checkRowEditability(
      @PathVariable String connectionId,
      @PathVariable String keyspace,
      @PathVariable String table,
      @Valid @RequestBody DataDtos.RowEditabilityRequest request) {

    CqlSession session = sessions.session(connectionId);
    EditabilityVerdict verdict =
        rows.editability(session, keyspace, table, request.projectedColumns());
    return new DataDtos.RowEditabilityResult(
        verdict.editable(),
        verdict.requiredKeyColumns(),
        verdict.missingKeyColumns(),
        verdict.reason(),
        verdict.suggestedCql());
  }

  /**
   * Wraps driver failures as {@code 422 CqlError}. The generated statement is inside the outcome we
   * never got, so it cannot be echoed here - the driver message names the offending column anyway.
   */
  private static RowMutationOutcome execute(java.util.function.Supplier<RowMutationOutcome> action) {
    return CqlExecution.run(null, action);
  }

  private static StatementGenerationSpec.Kind parseKind(String kind) {
    try {
      return StatementGenerationSpec.Kind.valueOf(kind.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException | NullPointerException e) {
      throw new IllegalArgumentException(
          "statementKind must be INSERT, UPDATE or DELETE, got '" + kind + "'");
    }
  }

  private static DataDtos.RowMutationResult toDto(RowMutationOutcome outcome) {
    return new DataDtos.RowMutationResult(
        outcome.executed(),
        outcome.cql(),
        outcome.applied(),
        outcome.currentValues(),
        outcome.elapsedMillis(),
        outcome.warnings());
  }
}
