package io.cassyx.core.api.query;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Row insert with {@code USING TTL} / {@code USING TIMESTAMP} and optional {@code IF NOT EXISTS}.
 *
 * <p>{@code values} is wire-encoded: a key mapped to {@link CqlValueCodec#UNSET} - or simply absent -
 * means <b>unset</b> and the column is left out of the statement entirely; an explicit {@code null}
 * writes a tombstone. Cassandra treats those differently and this is where the difference is kept.
 */
public record RowInsertSpec(
    Map<String, Object> values,
    Integer ttlSeconds,
    Long timestampMicros,
    boolean ifNotExists,
    String consistency,
    String serialConsistency,
    boolean previewOnly) {

  public RowInsertSpec {
    // LinkedHashMap, not Map.copyOf: copyOf loses insertion order (so generated CQL column order
    // would vary between runs) AND rejects null values - and a null here is meaningful, it is the
    // tombstone-writing case that must stay distinct from unset.
    values = values == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(values));
  }
}
