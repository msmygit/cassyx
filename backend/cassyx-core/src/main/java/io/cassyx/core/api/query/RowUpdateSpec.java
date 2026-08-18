package io.cassyx.core.api.query;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Row update. {@code primaryKey} must contain EVERY partition-key and clustering column of the
 * table; anything less is rejected with {@link IncompletePrimaryKeyException} naming what is missing.
 */
public record RowUpdateSpec(
    Map<String, Object> primaryKey,
    Map<String, Object> values,
    Integer ttlSeconds,
    Long timestampMicros,
    String condition,
    boolean ifExists,
    String consistency,
    String serialConsistency,
    boolean previewOnly) {

  public RowUpdateSpec {
    primaryKey = primaryKey == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(primaryKey));
    values = values == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(values));
  }
}
