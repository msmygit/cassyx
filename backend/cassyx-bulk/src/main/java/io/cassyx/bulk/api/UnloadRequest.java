package io.cassyx.bulk.api;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable unload job description (plan section 5.2).
 *
 * @param splits target number of token-range splits. Oversplit - {@code splitEvenly} divides by
 *     token count, not data volume, so under partition skew equal ranges take wildly unequal time.
 *     ~10k splits fed to a work-stealing queue is the single biggest throughput lever.
 * @param concurrency number of virtual-thread consumers draining the split queue
 */
public record UnloadRequest(
    String keyspace,
    String table,
    List<String> columns,
    String format,
    String target,
    int splits,
    int concurrency,
    Map<String, String> options) {

  public static final int DEFAULT_SPLITS = 10_000;

  public UnloadRequest {
    Objects.requireNonNull(keyspace, "keyspace");
    Objects.requireNonNull(table, "table");
    columns = columns == null ? List.of() : List.copyOf(columns);
    options = options == null ? Map.of() : Map.copyOf(options);
    format = format == null ? "csv" : format;
    splits = splits <= 0 ? DEFAULT_SPLITS : splits;
    concurrency = concurrency <= 0 ? Runtime.getRuntime().availableProcessors() * 4 : concurrency;
  }

  public static UnloadRequest of(String keyspace, String table, String format, String target) {
    return new UnloadRequest(keyspace, table, List.of(), format, target, 0, 0, Map.of());
  }
}
