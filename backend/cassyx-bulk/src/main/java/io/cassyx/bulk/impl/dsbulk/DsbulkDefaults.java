package io.cassyx.bulk.impl.dsbulk;

import io.cassyx.bulk.api.dsbulk.DsbulkJobSpec;
import io.cassyx.bulk.api.dsbulk.DsbulkOperation;
import io.cassyx.bulk.api.dsbulk.DsbulkProbe;
import io.cassyx.bulk.api.dsbulk.DsbulkSetting;
import io.cassyx.core.api.ClusterFlavor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The derived-defaults table of plan section 5.3 - "the important part".
 *
 * <p>The whole point of this class is that <b>users should never NEED the Advanced tab</b>. Every
 * value it produces carries a {@code rationale}, so the UI renders an editable "auto" chip that
 * explains itself rather than a magic number.
 *
 * <p>A pure function of {@code (spec, probe)}: no driver, no I/O, no clock. That is deliberate - the
 * entire derivation table is unit-testable without a cluster, and the numbers are reproducible for
 * a given probe, which is what makes a completed job's persisted settings meaningful.
 *
 * <table>
 *   <caption>What is derived, and why</caption>
 *   <tr><th>Setting</th><th>Derivation</th></tr>
 *   <tr><td>{@code executor.maxPerSecond}</td>
 *       <td>unthrottled; on Astra the SERVER's own rate limiter governs (DSBulk 1.9+ honours it)</td></tr>
 *   <tr><td>{@code executor.maxInFlight} / {@code engine.maxConcurrentQueries}</td>
 *       <td>nodes x 32 for reads, nodes x 16 for writes, capped by client cores x 128</td></tr>
 *   <tr><td>{@code batch.mode}</td>
 *       <td>PARTITION_KEY for load; DISABLED with no clustering key; never set for reads</td></tr>
 *   <tr><td>{@code batch.maxBatchStatements}</td><td>32; 1 for counter tables</td></tr>
 *   <tr><td>{@code driver.basic.requestConsistency}</td>
 *       <td>LOCAL_ONE read / LOCAL_QUORUM write; LOCAL_ONE always on Amazon Keyspaces</td></tr>
 *   <tr><td>{@code driver.advanced.protocolCompression}</td><td>lz4</td></tr>
 *   <tr><td>{@code connector.csv.maxConcurrentFiles}</td><td>= split count, for unload</td></tr>
 *   <tr><td>{@code schema.splits}</td><td>nodes x cores x 8, oversplit per section 5.2</td></tr>
 *   <tr><td>{@code codec.*}</td><td>formats sniffed from the target's column types</td></tr>
 * </table>
 */
public final class DsbulkDefaults {

  /** Reads fan out this many concurrent requests per node before the client-core cap applies. */
  static final int READ_FANOUT_PER_NODE = 32;

  /** Writes fan out less: a write costs more on the coordinator than a token-routed read. */
  static final int WRITE_FANOUT_PER_NODE = 16;

  /** Hard ceiling, expressed in client cores. Beyond this the client is the bottleneck. */
  static final int IN_FLIGHT_PER_CORE = 128;

  /** DSBulk's own default batch size, and cassyx's, for everything except counters. */
  static final int DEFAULT_BATCH_STATEMENTS = 32;

  /** Concurrent output files per client core. Bounded by the process file-descriptor limit. */
  static final int FILES_PER_CORE = 4;

  /** The statistics modes DSBulk 1.11 accepts. There is no {@code biggest-partitions} upstream. */
  static final Set<String> SUPPORTED_STATS_MODES = Set.of("global", "ranges", "hosts", "partitions");

  private DsbulkDefaults() {}

  /**
   * Resolves every setting the job will run with.
   *
   * <p>Order matters and is deliberate: derived values first, then the caller's overrides, which
   * replace them and come back with {@code auto: false}. The UI shows exactly this list.
   */
  public static List<DsbulkSetting> derive(DsbulkJobSpec spec, DsbulkProbe probe) {
    DsbulkProbe facts = probe == null ? DsbulkProbe.UNKNOWN : probe;
    Map<String, DsbulkSetting> resolved = new LinkedHashMap<>();

    deriveIdentity(spec, resolved);
    deriveConnector(spec, facts, resolved);
    deriveConcurrency(spec, facts, resolved);
    deriveBatching(spec, facts, resolved);
    deriveDriver(spec, facts, resolved);
    deriveCodec(facts, resolved);
    deriveMonitoring(resolved);
    deriveStats(spec, resolved);

    for (Map.Entry<String, String> override : spec.overrides().entrySet()) {
      String path = DsbulkReference.normalise(override.getKey());
      if (path.isEmpty() || override.getValue() == null) {
        continue;
      }
      resolved.put(path, DsbulkSetting.override(path,
          DsbulkReference.translateValue(path, override.getValue()),
          DsbulkReference.upstreamDefault(path), DsbulkReference.docsUrl(path)));
    }

    return List.copyOf(resolved.values());
  }

  /* ------------------------------------------------------------------ what to read/write */

  private static void deriveIdentity(DsbulkJobSpec spec, Map<String, DsbulkSetting> out) {
    if (spec.isQueryDriven()) {
      put(out, "schema.query", spec.query(),
          "Query-driven unload: schema.query is mutually exclusive with schema.keyspace/table.");
    } else {
      put(out, "schema.keyspace", spec.keyspace(), "The keyspace selected in the UI.");
      if (spec.table() != null && !spec.table().isBlank()) {
        put(out, "schema.table", spec.table(), "The table selected in the UI.");
      }
    }
    if (spec.mapping() != null && !spec.mapping().isBlank()) {
      put(out, "schema.mapping", spec.mapping(), "Field-to-column mapping from the mapping editor.");
    }
    if (spec.operation() == DsbulkOperation.LOAD) {
      put(out, "schema.nullToUnset", "true",
          "Null fields are written as UNSET rather than as a tombstone, so a load does not create "
              + "millions of tombstones out of empty CSV cells.");
      if (spec.dryRun()) {
        put(out, "engine.dryRun", "true", "Dry run: records are read, mapped and validated but never written.");
      }
    }
  }

  private static void deriveConnector(DsbulkJobSpec spec, DsbulkProbe probe, Map<String, DsbulkSetting> out) {
    String connector = "json".equals(spec.format()) ? "json" : "csv";
    put(out, "connector.name", connector, "Selected output format.");
    if (spec.url() != null && !spec.url().isBlank()) {
      put(out, "connector." + connector + ".url", spec.url(),
          spec.operation() == DsbulkOperation.LOAD ? "The uploaded file, mounted path or S3 URL to load."
              : "The job's sink: a server-side directory, never the browser.");
    }
    if (spec.operation() == DsbulkOperation.UNLOAD) {
      int files = maxConcurrentFiles(probe);
      put(out, "connector." + connector + ".maxConcurrentFiles", Integer.toString(files),
          "One output file per concurrent reader, so no two writers contend on a file handle. "
              + "Deliberately NOT the full split count of " + probe.recommendedSplits() + ": splits are "
              + "oversplit into the thousands on purpose (plan section 5.2), and that many "
              + "simultaneously open files hits the process file-descriptor limit long before it "
              + "helps throughput. Capped at cores x 4 = " + probe.clientCores() * FILES_PER_CORE + ".");
    }
  }

  /* ------------------------------------------------------------------------- concurrency */

  private static void deriveConcurrency(DsbulkJobSpec spec, DsbulkProbe probe, Map<String, DsbulkSetting> out) {
    if (spec.operation() != DsbulkOperation.LOAD && probe.supportsTokenRangeScan()) {
      int splits = probe.recommendedSplits();
      put(out, "schema.splits", Integer.toString(splits),
          "nodes x cores x 8 = " + probe.nodeCount() + " x " + probe.clientCores() + " x 8. splitEvenly() "
              + "divides by token count, not data volume, so under partition skew equal ranges take "
              + "wildly unequal time. Oversplitting lets fast workers steal the slow ranges' neighbours.");
    }

    int inFlight = maxInFlight(spec.operation(), probe);
    put(out, "executor.maxInFlight", Integer.toString(inFlight),
        "nodes x " + fanout(spec.operation()) + " = " + probe.nodeCount() * fanout(spec.operation())
            + ", capped at clientCores x " + IN_FLIGHT_PER_CORE + " = " + probe.clientCores() * IN_FLIGHT_PER_CORE
            + ". Past the client-core cap the bottleneck is this JVM, not the cluster.");
    put(out, "engine.maxConcurrentQueries", Integer.toString(inFlight),
        "Kept equal to executor.maxInFlight: allowing more concurrent queries than in-flight "
            + "requests only builds an internal queue.");

    if (probe.serverSideRateLimiting()) {
      // DELIBERATELY NOT SET. DSBulk 1.9+ auto-applies a cloud rate limit
      // (3000 ops/s per coordinator) *only when the user has not set executor.maxPerSecond*.
      // Writing "-1" here would look like "unthrottled" and would in fact DISABLE the server-aware
      // limiter - the exact opposite of "respect Astra's server-side rate limiting". The absence of
      // this setting is the feature; the UI still shows it, as an unset field with this rationale.
      return;
    }
    put(out, "executor.maxPerSecond", "-1",
        "Unthrottled: a self-managed cluster has no server-side rate limiter, so the operator "
            + "decides. Set a value here to protect a cluster that is also serving live traffic.");
  }

  /** {@code min(splits, cores x 4)} - see the rationale text for why the cap exists. */
  static int maxConcurrentFiles(DsbulkProbe probe) {
    return Math.max(1, Math.min(probe.recommendedSplits(), probe.clientCores() * FILES_PER_CORE));
  }

  static int fanout(DsbulkOperation operation) {
    return operation == DsbulkOperation.LOAD ? WRITE_FANOUT_PER_NODE : READ_FANOUT_PER_NODE;
  }

  /** {@code nodes x fanout}, floored at the fan-out itself and capped by client cores. */
  static int maxInFlight(DsbulkOperation operation, DsbulkProbe probe) {
    long base = (long) probe.nodeCount() * fanout(operation);
    long cap = (long) probe.clientCores() * IN_FLIGHT_PER_CORE;
    return (int) Math.max(fanout(operation), Math.min(base, cap));
  }

  /* ---------------------------------------------------------------------------- batching */

  private static void deriveBatching(DsbulkJobSpec spec, DsbulkProbe probe, Map<String, DsbulkSetting> out) {
    if (spec.operation() != DsbulkOperation.LOAD) {
      return; // batching is a write-path concept; setting it on a read is noise in the preview
    }
    if (!probe.hasClusteringKey()) {
      put(out, "batch.mode", "DISABLED",
          "The target has no clustering key, so every row is its own partition and PARTITION_KEY "
              + "batching would group nothing. Batching unrelated partitions is slower than not "
              + "batching, not faster - it serialises writes through one coordinator.");
    } else {
      put(out, "batch.mode", "PARTITION_KEY",
          "Rows sharing a partition key are batched together, which is the one case where a "
              + "Cassandra batch is genuinely cheaper: single partition, single replica set.");
    }
    if (probe.counterTable()) {
      put(out, "batch.maxBatchStatements", "1",
          "Counter table: counter updates are not idempotent, so a retried batch can double-count. "
              + "One statement per batch keeps every update individually retryable.");
    } else {
      put(out, "batch.maxBatchStatements", Integer.toString(DEFAULT_BATCH_STATEMENTS),
          DEFAULT_BATCH_STATEMENTS + " statements per batch. Larger batches raise coordinator heap "
              + "pressure and trip batch_size_fail_threshold long before they help throughput.");
    }
  }

  /* ------------------------------------------------------------------------------ driver */

  private static void deriveDriver(DsbulkJobSpec spec, DsbulkProbe probe, Map<String, DsbulkSetting> out) {
    String consistency = consistencyFor(spec.operation(), probe.flavour());
    put(out, "driver.basic.requestConsistency", consistency, consistencyRationale(spec.operation(), probe.flavour()));
    put(out, "driver.advanced.protocolCompression", "lz4",
        "LZ4 costs a little CPU and saves a lot of network on bulk transfers; it is the right "
            + "trade for every path in this module.");
    put(out, "driver.basic.requestTimeout", "5 minutes",
        "Bulk range reads legitimately take minutes. The driver's 2-second default would fail them "
            + "as timeouts and make the job look broken.");
    put(out, "driver.basic.requestDefaultIdempotence", "true",
        "Range reads and mapped writes are idempotent, which is what allows the retry policy and "
            + "speculative execution to do anything at all.");
  }

  /** LOCAL_ONE for reads, LOCAL_QUORUM for writes - and LOCAL_ONE always on Amazon Keyspaces. */
  static String consistencyFor(DsbulkOperation operation, ClusterFlavor flavour) {
    if (flavour == ClusterFlavor.AMAZON_KEYSPACES) {
      return "LOCAL_ONE";
    }
    return operation == DsbulkOperation.LOAD ? "LOCAL_QUORUM" : "LOCAL_ONE";
  }

  private static String consistencyRationale(DsbulkOperation operation, ClusterFlavor flavour) {
    if (flavour == ClusterFlavor.AMAZON_KEYSPACES) {
      return "Amazon Keyspaces supports LOCAL_ONE and LOCAL_QUORUM only, and bills quorum reads at a "
          + "higher rate; LOCAL_ONE is the compatible choice for both directions.";
    }
    if (operation == DsbulkOperation.LOAD) {
      return "LOCAL_QUORUM on write: a bulk load that lands on one replica and is then read at "
          + "quorum reads back as missing data. Durability wins over throughput on the write path.";
    }
    return "LOCAL_ONE on read: a full-table scan is token-routed to a replica that owns the range, "
        + "so a quorum read would fan out to nodes that add latency and no new rows.";
  }

  /* ------------------------------------------------------------------------------- codec */

  private static final Set<String> TIMESTAMP_TYPES = Set.of("timestamp");
  private static final Set<String> DATE_TYPES = Set.of("date");
  private static final Set<String> TIME_TYPES = Set.of("time");
  private static final Set<String> BINARY_TYPES = Set.of("blob");

  private static void deriveCodec(DsbulkProbe probe, Map<String, DsbulkSetting> out) {
    put(out, "codec.locale", "en_US",
        "A fixed locale, not the container's: a job must produce byte-identical output wherever it runs.");
    put(out, "codec.timeZone", "UTC",
        "UTC, not the container's zone, for the same reason - and because Cassandra timestamps are "
            + "UTC instants with no zone of their own.");

    Set<String> types = columnTypeNames(probe);
    if (containsAny(types, TIMESTAMP_TYPES)) {
      put(out, "codec.timestamp", "CQL_TIMESTAMP",
          "The target has timestamp columns. CQL_TIMESTAMP accepts every literal cqlsh accepts, "
              + "which is what makes a CSV exported here loadable back without edits.");
    }
    if (containsAny(types, DATE_TYPES)) {
      put(out, "codec.date", "ISO_LOCAL_DATE", "The target has date columns; ISO-8601 round-trips exactly.");
    }
    if (containsAny(types, TIME_TYPES)) {
      put(out, "codec.time", "ISO_LOCAL_TIME", "The target has time columns; ISO-8601 round-trips exactly.");
    }
    if (containsAny(types, BINARY_TYPES)) {
      put(out, "codec.binary", "BASE64",
          "The target has blob columns. Base64 survives a CSV round trip; hex is ambiguous against "
              + "text columns holding hex-looking strings.");
    }
  }

  /** Type names sniffed from the probe, lower-cased and stripped of any parameters. */
  static Set<String> columnTypeNames(DsbulkProbe probe) {
    Set<String> names = new LinkedHashSet<>();
    for (String type : probe.columnTypes().values()) {
      if (type == null) {
        continue;
      }
      String head = type.toLowerCase(Locale.ROOT).trim();
      int paren = head.indexOf('<');
      names.add(paren < 0 ? head : head.substring(0, paren));
    }
    return names;
  }

  private static boolean containsAny(Set<String> haystack, Set<String> needles) {
    for (String needle : needles) {
      if (haystack.contains(needle)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Null tokens sniffed from a sample of the source (plan section 5.3: "{@code nullStrings} from a
   * sniffed sample").
   *
   * <p>Only tokens that actually occur are proposed. Declaring {@code N/A} as null when the file
   * never contains it is harmless; declaring it when a real value happens to be the literal string
   * {@code N/A} silently destroys data, so the sniff is evidence-based and conservative.
   */
  public static List<String> sniffNullStrings(List<String> sampleValues) {
    List<String> candidates = List.of("NULL", "null", "N/A", "n/a", "NA", "\\N", "NONE", "None", "nil");
    List<String> found = new ArrayList<>();
    if (sampleValues == null) {
      return found;
    }
    for (String candidate : candidates) {
      for (String value : sampleValues) {
        if (candidate.equals(value == null ? null : value.trim())) {
          found.add(candidate);
          break;
        }
      }
    }
    return List.copyOf(found);
  }

  /* -------------------------------------------------------------------- monitoring/stats */

  private static void deriveMonitoring(Map<String, DsbulkSetting> out) {
    put(out, "monitoring.reportRate", "1 second",
        "One report per second, matching the SSE progress cadence the contract specifies. DSBulk's "
            + "own 5-second default makes a short job look frozen in the UI.");
    put(out, "monitoring.console", "true",
        "The console reporter IS the progress signal: an out-of-process DSBulk has no IPC channel "
            + "back to cassyx, so its periodic report line is what the SSE progress events are "
            + "parsed from. Turning it off silently flatlines the progress bar.");
    put(out, "log.ansiMode", "disabled",
        "Without this the console reporter redraws in place with ANSI cursor-up escapes, which is "
            + "unparseable and unreadable once captured to a file. Disabled, each report is two "
            + "plain appended lines.");
    put(out, "monitoring.jmx", "false",
        "DSBulk enables a JMX reporter by default. The child process is short-lived and nobody "
            + "attaches to it, so it is one less port and one less thread per job.");
  }

  private static void deriveStats(DsbulkJobSpec spec, Map<String, DsbulkSetting> out) {
    if (spec.operation() != DsbulkOperation.COUNT) {
      return;
    }
    List<String> modes = normaliseStatsModes(spec.statsModes());
    put(out, "stats.modes", renderList(modes),
        "The statistics the Statistics tab renders: total, per-replica, per-token-range and the "
            + "top-N largest partitions (plan section 5.4).");
    // Read off the NORMALISED modes: the contract spells the largest-partitions report
    // `biggest-partitions`, and matching the raw spelling missed `stats.numPartitions` for exactly
    // the requests that needed it.
    if (modes.contains("partitions")) {
      put(out, "stats.numPartitions", Integer.toString(spec.topPartitions()),
          "Top-" + spec.topPartitions() + " largest partitions - the skew signal that decides how "
              + "far to oversplit a later unload of this table.");
    }
  }

  /**
   * Maps the contract's stats modes onto the four DSBulk 1.11 actually accepts.
   *
   * <p>The contract offers {@code biggest-partitions}; upstream has no such mode - {@code partitions}
   * IS the "N biggest partitions" report, with N from {@code stats.numPartitions}. Passing the
   * contract spelling through would fail the job at start-up on a config validation error, so it is
   * folded into {@code partitions} here.
   */
  static List<String> normaliseStatsModes(List<String> modes) {
    List<String> out = new ArrayList<>();
    for (String mode : modes) {
      String normalised = mode == null ? "" : mode.trim().toLowerCase(Locale.ROOT);
      if ("biggest-partitions".equals(normalised)) {
        normalised = "partitions";
      }
      if (SUPPORTED_STATS_MODES.contains(normalised) && !out.contains(normalised)) {
        out.add(normalised);
      }
    }
    return out.isEmpty() ? List.of("global") : List.copyOf(out);
  }

  /** HOCON list literal: {@code [global,ranges]}. */
  static String renderList(List<String> values) {
    return "[" + String.join(",", values) + "]";
  }

  private static void put(Map<String, DsbulkSetting> out, String path, String value, String rationale) {
    out.put(path, DsbulkSetting.derived(path, value, DsbulkReference.upstreamDefault(path), rationale,
        DsbulkReference.docsUrl(path)));
  }
}
