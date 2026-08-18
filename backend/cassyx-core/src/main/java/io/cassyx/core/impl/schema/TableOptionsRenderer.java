package io.cassyx.core.impl.schema;

import io.cassyx.core.api.schema.CachingSettings;
import io.cassyx.core.api.schema.ClusteringKeyColumn;
import io.cassyx.core.api.schema.CompactionSettings;
import io.cassyx.core.api.schema.CompressionSettings;
import io.cassyx.core.api.schema.CqlNames;
import io.cassyx.core.api.schema.TableOptions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Renders the full {@code WITH} options surface of plan section 4 into CQL.
 *
 * <p>Only non-null fields are emitted, so an ALTER touches exactly what the user changed rather
 * than rewriting every option back at its current value.
 */
final class TableOptionsRenderer {

  private TableOptionsRenderer() {}

  /** {@code CLUSTERING ORDER BY (a ASC, b DESC)}, or empty when there is no clustering key. */
  static List<String> clusteringOrder(List<ClusteringKeyColumn> clusteringKey) {
    if (clusteringKey == null || clusteringKey.isEmpty()) {
      return List.of();
    }
    List<String> parts = new ArrayList<>();
    for (ClusteringKeyColumn column : clusteringKey) {
      parts.add(CqlNames.quote(column.column()) + " " + column.order().name());
    }
    return List.of("CLUSTERING ORDER BY (" + String.join(", ", parts) + ")");
  }

  /** One {@code key = value} fragment per set option, ready to be joined with {@code AND}. */
  static List<String> render(TableOptions options) {
    List<String> parts = new ArrayList<>();
    if (options == null) {
      return parts;
    }
    if (options.comment() != null) {
      parts.add("comment = " + CqlNames.literal(options.comment()));
    }
    if (options.compaction() != null) {
      parts.add("compaction = " + compaction(options.compaction()));
    }
    if (options.compression() != null) {
      parts.add("compression = " + compression(options.compression()));
    }
    if (options.caching() != null) {
      parts.add("caching = " + caching(options.caching()));
    }
    addIfSet(parts, "bloom_filter_fp_chance", options.bloomFilterFpChance());
    addIfSet(parts, "gc_grace_seconds", options.gcGraceSeconds());
    addIfSet(parts, "default_time_to_live", options.defaultTimeToLive());
    if (options.readRepair() != null) {
      parts.add("read_repair = " + CqlNames.literal(options.readRepair()));
    }
    addIfSet(parts, "read_repair_chance", options.readRepairChance());
    addIfSet(parts, "dclocal_read_repair_chance", options.dclocalReadRepairChance());
    if (options.speculativeRetry() != null) {
      parts.add("speculative_retry = " + CqlNames.literal(options.speculativeRetry()));
    }
    if (options.additionalWritePolicy() != null) {
      parts.add("additional_write_policy = " + CqlNames.literal(options.additionalWritePolicy()));
    }
    addIfSet(parts, "min_index_interval", options.minIndexInterval());
    addIfSet(parts, "max_index_interval", options.maxIndexInterval());
    addIfSet(parts, "memtable_flush_period_in_ms", options.memtableFlushPeriodInMs());
    addIfSet(parts, "crc_check_chance", options.crcCheckChance());
    if (options.cdc() != null) {
      parts.add("cdc = " + options.cdc());
    }
    for (Map.Entry<String, String> extension : new TreeMap<>(options.extensions()).entrySet()) {
      parts.add(extension.getKey() + " = " + CqlNames.literal(extension.getValue()));
    }
    return parts;
  }

  private static void addIfSet(List<String> parts, String key, Number value) {
    if (value != null) {
      parts.add(key + " = " + value);
    }
  }

  private static String compaction(CompactionSettings compaction) {
    Map<String, String> all = new TreeMap<>(compaction.options());
    all.put("class", compaction.strategyClass());
    return stringMap(all);
  }

  private static String compression(CompressionSettings compression) {
    Map<String, String> all = new TreeMap<>();
    if (compression.compressionClass() != null) {
      if ("none".equalsIgnoreCase(compression.compressionClass())) {
        return "{'enabled': 'false'}";
      }
      all.put("class", compression.compressionClass());
    }
    if (compression.chunkLengthInKb() != null) {
      all.put("chunk_length_in_kb", String.valueOf(compression.chunkLengthInKb()));
    }
    if (compression.crcCheckChance() != null) {
      all.put("crc_check_chance", String.valueOf(compression.crcCheckChance()));
    }
    return stringMap(all);
  }

  private static String caching(CachingSettings caching) {
    Map<String, String> all = new TreeMap<>();
    if (caching.keys() != null) {
      all.put("keys", caching.keys());
    }
    if (caching.rowsPerPartition() != null) {
      all.put("rows_per_partition", caching.rowsPerPartition());
    }
    return stringMap(all);
  }

  /** A CQL map literal with deterministic (sorted) key order - previews must be stable. */
  static String stringMap(Map<String, String> map) {
    List<String> entries = new ArrayList<>();
    for (Map.Entry<String, String> entry : new TreeMap<>(map).entrySet()) {
      entries.add(CqlNames.literal(entry.getKey()) + ": " + CqlNames.literal(entry.getValue()));
    }
    return "{" + String.join(", ", entries) + "}";
  }
}
