package io.cassyx.core.api.schema;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * Generated CQL. ALWAYS shown to the user and ALWAYS editable before execution - generated DDL is
 * never executed silently (plan section 4).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DdlPreview(
    String cql, List<String> statements, List<String> warnings, SchemaIdentity targetIdentity) {

  public DdlPreview {
    statements = statements == null ? List.of() : List.copyOf(statements);
    warnings = warnings == null ? List.of() : List.copyOf(warnings);
  }

  /** Builds a preview from statements, joining them into an editable script. */
  public static DdlPreview of(
      SchemaIdentity target, List<String> statements, List<String> warnings) {
    return new DdlPreview(String.join("\n", statements), statements, warnings, target);
  }
}
