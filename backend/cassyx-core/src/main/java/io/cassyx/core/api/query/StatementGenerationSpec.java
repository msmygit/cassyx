package io.cassyx.core.api.query;

import java.util.List;
import java.util.Map;

/**
 * Generate INSERT / UPDATE / DELETE for a set of selected rows (plan section 7). Pure generation, no
 * execution - the output goes to the editor or the clipboard.
 */
public record StatementGenerationSpec(
    Kind kind,
    List<Map<String, Object>> rows,
    List<String> columns,
    Integer ttlSeconds,
    Long timestampMicros,
    boolean includeIfConditions,
    boolean asBatch,
    boolean formatted) {

  public StatementGenerationSpec {
    rows = rows == null ? List.of() : List.copyOf(rows);
    columns = columns == null ? List.of() : List.copyOf(columns);
  }

  public enum Kind {
    INSERT,
    UPDATE,
    DELETE
  }
}
