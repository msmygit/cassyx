package io.cassyx.bulk.api.dsbulk;

import io.cassyx.core.api.ClusterFlavor;
import java.util.Map;

/**
 * The cluster and target facts every derivation in plan section 5.3 is computed from - the API
 * contract's {@code BulkProbeResult}.
 *
 * <p>Deliberately a plain value object with no driver types in it, so the whole derivation table is
 * a pure function that unit-tests without a cluster. {@code DsbulkFactory.prober()} produces one of
 * these from a live {@code CqlSession}.
 *
 * @param nodeCount nodes in the local datacenter; the fan-out multiplier
 * @param clientCores cores available to this JVM; the cap on any concurrency derivation
 * @param flavour Cassandra / DSE / Astra / Keyspaces / Scylla (plan section 7.1)
 * @param hasClusteringKey false means batching by partition key buys nothing - batch.mode DISABLED
 * @param counterTable counter writes cannot be batched safely - maxBatchStatements drops to 1
 * @param serverSideRateLimiting true on Astra, where DSBulk 1.9+ detects and honours the server's
 *     own rate limiter, so cassyx must NOT replace it with a client-side throttle
 * @param estimatedRows from a cached count job, or null when none has been run
 * @param columnTypes column name to CQL type name, used to sniff codec formats
 */
public record DsbulkProbe(
    int nodeCount,
    int clientCores,
    ClusterFlavor flavour,
    boolean hasClusteringKey,
    boolean counterTable,
    boolean serverSideRateLimiting,
    Long estimatedRows,
    Map<String, String> columnTypes) {

  /** Fallback used when the cluster cannot be probed; every derivation still produces a value. */
  public static final DsbulkProbe UNKNOWN =
      new DsbulkProbe(
          1, Runtime.getRuntime().availableProcessors(), ClusterFlavor.UNKNOWN,
          false, false, false, null, Map.of());

  public DsbulkProbe {
    nodeCount = Math.max(1, nodeCount);
    clientCores = Math.max(1, clientCores);
    flavour = flavour == null ? ClusterFlavor.UNKNOWN : flavour;
    columnTypes = columnTypes == null ? Map.of() : Map.copyOf(columnTypes);
  }

  /**
   * {@code nodes x cores x 8}, deliberately oversplit (plan sections 5.2 / 5.3).
   *
   * <p>{@code splitEvenly} divides by token count, not data volume, so under partition skew equal
   * ranges take wildly unequal time. Oversplitting and letting the work-stealing queue drain them
   * is the single biggest throughput lever, so this number is intentionally large.
   */
  public int recommendedSplits() {
    long splits = (long) nodeCount * clientCores * 8L;
    return (int) Math.max(8, Math.min(100_000L, splits));
  }

  /** True when the target cannot do {@code token()} range scans (plan section 7.1). */
  public boolean supportsTokenRangeScan() {
    return flavour != ClusterFlavor.AMAZON_KEYSPACES;
  }
}
