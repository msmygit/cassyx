package io.cassyx.core.api.schema;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * The outcome of executing reviewed DDL.
 *
 * <p>{@code executedCql} echoes exactly what was sent, except that role passwords are redacted -
 * a response body must never carry a secret back to the client (plan section 2.3).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DdlExecutionResult(
    boolean success,
    List<String> executedCql,
    int statementsExecuted,
    long elapsedMillis,
    boolean schemaAgreement,
    List<String> warnings,
    SchemaIdentity affectedIdentity) {

  public DdlExecutionResult {
    executedCql = executedCql == null ? List.of() : List.copyOf(executedCql);
    warnings = warnings == null ? List.of() : List.copyOf(warnings);
  }
}
