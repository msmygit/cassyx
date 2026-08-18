package io.cassyx.core.api.query;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Row delete, or a column-level delete when {@code columns} is non-empty.
 *
 * @param columns non-primary-key columns to delete instead of the whole row
 */
public record RowDeleteSpec(
    Map<String, Object> primaryKey,
    List<String> columns,
    Long timestampMicros,
    boolean ifExists,
    String condition,
    String consistency,
    boolean previewOnly) {

  public RowDeleteSpec {
    primaryKey = primaryKey == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(primaryKey));
    columns = columns == null ? List.of() : List.copyOf(columns);
  }
}
