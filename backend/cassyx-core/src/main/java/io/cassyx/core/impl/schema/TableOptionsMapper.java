package io.cassyx.core.impl.schema;

import com.datastax.oss.driver.api.core.CqlIdentifier;
import io.cassyx.core.api.schema.CachingSettings;
import io.cassyx.core.api.schema.CompactionSettings;
import io.cassyx.core.api.schema.CompressionSettings;
import io.cassyx.core.api.schema.TableOptions;
import java.util.LinkedHashMap;
import java.util.Map;

/** Maps the driver's untyped {@code WITH} options map onto the typed contract shape. */
final class TableOptionsMapper {

  private TableOptionsMapper() {}

  static TableOptions fromMetadata(Map<CqlIdentifier, Object> options) {
    Map<String, Object> byName = new LinkedHashMap<>();
    options.forEach((key, value) -> byName.put(key.asInternal(), value));

    return new TableOptions(
        string(byName.get("comment")),
        compaction(map(byName.get("compaction"))),
        compression(map(byName.get("compression"))),
        caching(map(byName.get("caching"))),
        asDouble(byName.get("bloom_filter_fp_chance")),
        asInt(byName.get("gc_grace_seconds")),
        asInt(byName.get("default_time_to_live")),
        string(byName.get("read_repair")),
        asDouble(byName.get("read_repair_chance")),
        asDouble(byName.get("dclocal_read_repair_chance")),
        string(byName.get("speculative_retry")),
        string(byName.get("additional_write_policy")),
        asInt(byName.get("min_index_interval")),
        asInt(byName.get("max_index_interval")),
        asInt(byName.get("memtable_flush_period_in_ms")),
        asDouble(byName.get("crc_check_chance")),
        byName.get("cdc") instanceof Boolean cdc ? cdc : null,
        Map.of());
  }

  private static CompactionSettings compaction(Map<String, String> raw) {
    if (raw == null || raw.isEmpty()) {
      return null;
    }
    Map<String, String> subProperties = new LinkedHashMap<>(raw);
    String strategy = subProperties.remove("class");
    if (strategy == null) {
      return null;
    }
    return new CompactionSettings(
        strategy.substring(strategy.lastIndexOf('.') + 1), subProperties);
  }

  private static CompressionSettings compression(Map<String, String> raw) {
    if (raw == null || raw.isEmpty()) {
      return null;
    }
    String className = raw.get("class");
    return new CompressionSettings(
        className == null ? "none" : className.substring(className.lastIndexOf('.') + 1),
        asInt(raw.get("chunk_length_in_kb")),
        asDouble(raw.get("crc_check_chance")));
  }

  private static CachingSettings caching(Map<String, String> raw) {
    if (raw == null || raw.isEmpty()) {
      return null;
    }
    return new CachingSettings(raw.get("keys"), raw.get("rows_per_partition"));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, String> map(Object value) {
    return value instanceof Map<?, ?> ? (Map<String, String>) value : null;
  }

  private static String string(Object value) {
    return value == null ? null : value.toString();
  }

  private static Integer asInt(Object value) {
    if (value instanceof Number number) {
      return number.intValue();
    }
    try {
      return value == null ? null : Integer.valueOf(value.toString().trim());
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static Double asDouble(Object value) {
    if (value instanceof Number number) {
      return number.doubleValue();
    }
    try {
      return value == null ? null : Double.valueOf(value.toString().trim());
    } catch (NumberFormatException e) {
      return null;
    }
  }
}
