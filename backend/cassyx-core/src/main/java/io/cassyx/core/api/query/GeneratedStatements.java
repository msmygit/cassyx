package io.cassyx.core.api.query;

import java.util.List;

/** Generated, never-executed statements. Contract: {@code RowStatementGenerationResult}. */
public record GeneratedStatements(
    List<String> statements, String cql, int rowCount, List<String> warnings) {

  public GeneratedStatements {
    statements = statements == null ? List.of() : List.copyOf(statements);
    warnings = warnings == null ? List.of() : List.copyOf(warnings);
  }
}
