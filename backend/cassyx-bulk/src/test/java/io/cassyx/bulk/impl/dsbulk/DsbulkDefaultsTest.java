package io.cassyx.bulk.impl.dsbulk;

import static org.assertj.core.api.Assertions.assertThat;

import io.cassyx.bulk.api.dsbulk.DsbulkJobSpec;
import io.cassyx.bulk.api.dsbulk.DsbulkOperation;
import io.cassyx.bulk.api.dsbulk.DsbulkProbe;
import io.cassyx.bulk.api.dsbulk.DsbulkSetting;
import io.cassyx.core.api.ClusterFlavor;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The derivation table of plan section 5.3, row by row.
 *
 * <p>These are the tests that matter most in this module. A wrong derived default does not fail
 * loudly - it produces a job that runs, completes, and is quietly slower or less safe than it
 * should be. Every assertion here pins one row of the plan's table.
 */
class DsbulkDefaultsTest {

  private static DsbulkProbe probe(
      int nodes, int cores, ClusterFlavor flavour, boolean clustering, boolean counters) {
    return new DsbulkProbe(nodes, cores, flavour, clustering, counters,
        flavour == ClusterFlavor.ASTRA, null, Map.of());
  }

  private static Optional<DsbulkSetting> setting(List<DsbulkSetting> settings, String path) {
    return settings.stream().filter(s -> s.path().equals(path)).findFirst();
  }

  private static String value(List<DsbulkSetting> settings, String path) {
    return setting(settings, path).map(DsbulkSetting::value).orElse(null);
  }

  private static DsbulkJobSpec unload() {
    return DsbulkJobSpec.table(DsbulkOperation.UNLOAD, "demo", "users", "csv", "/out");
  }

  private static DsbulkJobSpec load() {
    return DsbulkJobSpec.table(DsbulkOperation.LOAD, "demo", "users", "csv", "/in/users.csv");
  }

  @Test
  @DisplayName("schema.splits is nodes x cores x 8, deliberately oversplit")
  void splitsAreOversplit() {
    List<DsbulkSetting> settings = DsbulkDefaults.derive(unload(), probe(3, 8, ClusterFlavor.CASSANDRA, true, false));
    assertThat(value(settings, "schema.splits")).isEqualTo("192");
    assertThat(setting(settings, "schema.splits")).get().extracting(DsbulkSetting::auto).isEqualTo(true);
    assertThat(setting(settings, "schema.splits").orElseThrow().rationale())
        .contains("splitEvenly")
        .contains("skew");
  }

  @Test
  @DisplayName("splits are not emitted for a target with no token() range scan")
  void keyspacesGetsNoSplits() {
    List<DsbulkSetting> settings =
        DsbulkDefaults.derive(unload(), probe(3, 8, ClusterFlavor.AMAZON_KEYSPACES, true, false));
    assertThat(value(settings, "schema.splits")).isNull();
  }

  @Test
  @DisplayName("maxInFlight is nodes x 32 on read, nodes x 16 on write, capped by client cores")
  void concurrencyDerivation() {
    assertThat(value(DsbulkDefaults.derive(unload(), probe(3, 8, ClusterFlavor.CASSANDRA, true, false)),
        "executor.maxInFlight")).isEqualTo("96");
    assertThat(value(DsbulkDefaults.derive(load(), probe(3, 8, ClusterFlavor.CASSANDRA, true, false)),
        "executor.maxInFlight")).isEqualTo("48");
    // 100 nodes x 32 = 3200, but a 2-core client caps it at 2 x 128 = 256.
    assertThat(value(DsbulkDefaults.derive(unload(), probe(100, 2, ClusterFlavor.CASSANDRA, true, false)),
        "executor.maxInFlight")).isEqualTo("256");
  }

  @Test
  @DisplayName("engine.maxConcurrentQueries tracks executor.maxInFlight")
  void concurrentQueriesMatchInFlight() {
    List<DsbulkSetting> settings = DsbulkDefaults.derive(unload(), probe(4, 8, ClusterFlavor.CASSANDRA, true, false));
    assertThat(value(settings, "engine.maxConcurrentQueries")).isEqualTo(value(settings, "executor.maxInFlight"));
  }

  @Test
  @DisplayName("executor.maxPerSecond is UNSET on Astra so DSBulk's own cloud rate limit survives")
  void astraKeepsServerSideRateLimiting() {
    List<DsbulkSetting> astra = DsbulkDefaults.derive(load(), probe(3, 8, ClusterFlavor.ASTRA, true, false));
    // Setting it to -1 would read as "unthrottled" and would in fact DISABLE the server-aware
    // limiter DSBulk 1.9+ applies only when the user has not set this. Absence is the feature.
    assertThat(value(astra, "executor.maxPerSecond")).isNull();

    List<DsbulkSetting> selfManaged =
        DsbulkDefaults.derive(load(), probe(3, 8, ClusterFlavor.CASSANDRA, true, false));
    assertThat(value(selfManaged, "executor.maxPerSecond")).isEqualTo("-1");
  }

  @Test
  @DisplayName("batch.mode is PARTITION_KEY for load, DISABLED without a clustering key")
  void batchMode() {
    assertThat(value(DsbulkDefaults.derive(load(), probe(1, 4, ClusterFlavor.CASSANDRA, true, false)), "batch.mode"))
        .isEqualTo("PARTITION_KEY");
    assertThat(value(DsbulkDefaults.derive(load(), probe(1, 4, ClusterFlavor.CASSANDRA, false, false)), "batch.mode"))
        .isEqualTo("DISABLED");
    // Batching is a write-path concept: an unload preview should not mention it at all.
    assertThat(value(DsbulkDefaults.derive(unload(), probe(1, 4, ClusterFlavor.CASSANDRA, true, false)), "batch.mode"))
        .isNull();
  }

  @Test
  @DisplayName("maxBatchStatements is 32, and 1 for counter tables")
  void counterTablesGetSingleStatementBatches() {
    assertThat(value(DsbulkDefaults.derive(load(), probe(1, 4, ClusterFlavor.CASSANDRA, true, false)),
        "batch.maxBatchStatements")).isEqualTo("32");
    List<DsbulkSetting> counters = DsbulkDefaults.derive(load(), probe(1, 4, ClusterFlavor.CASSANDRA, true, true));
    assertThat(value(counters, "batch.maxBatchStatements")).isEqualTo("1");
    assertThat(setting(counters, "batch.maxBatchStatements").orElseThrow().rationale())
        .contains("not idempotent");
  }

  @Test
  @DisplayName("consistency is LOCAL_ONE unload / LOCAL_QUORUM load, LOCAL_ONE on Keyspaces")
  void consistencyDerivation() {
    assertThat(DsbulkDefaults.consistencyFor(DsbulkOperation.UNLOAD, ClusterFlavor.CASSANDRA)).isEqualTo("LOCAL_ONE");
    assertThat(DsbulkDefaults.consistencyFor(DsbulkOperation.COUNT, ClusterFlavor.CASSANDRA)).isEqualTo("LOCAL_ONE");
    assertThat(DsbulkDefaults.consistencyFor(DsbulkOperation.LOAD, ClusterFlavor.CASSANDRA)).isEqualTo("LOCAL_QUORUM");
    assertThat(DsbulkDefaults.consistencyFor(DsbulkOperation.LOAD, ClusterFlavor.AMAZON_KEYSPACES))
        .isEqualTo("LOCAL_ONE");
    assertThat(value(DsbulkDefaults.derive(load(), probe(1, 4, ClusterFlavor.CASSANDRA, true, false)),
        "driver.basic.requestConsistency")).isEqualTo("LOCAL_QUORUM");
  }

  @Test
  @DisplayName("compression is lz4")
  void compressionIsLz4() {
    assertThat(value(DsbulkDefaults.derive(unload(), DsbulkProbe.UNKNOWN), "driver.advanced.protocolCompression"))
        .isEqualTo("lz4");
  }

  @Test
  @DisplayName("maxConcurrentFiles is capped by client cores, not set to the full split count")
  void maxConcurrentFilesIsCapped() {
    DsbulkProbe big = probe(10, 8, ClusterFlavor.CASSANDRA, true, false);
    assertThat(big.recommendedSplits()).isEqualTo(640);
    // The full split count would be 640 simultaneously open files; the process fd limit says no.
    assertThat(value(DsbulkDefaults.derive(unload(), big), "connector.csv.maxConcurrentFiles")).isEqualTo("32");
    assertThat(DsbulkDefaults.maxConcurrentFiles(probe(1, 1, ClusterFlavor.CASSANDRA, true, false))).isEqualTo(4);
  }

  @Test
  @DisplayName("codec formats are sniffed from the target's column types, not set blindly")
  void codecFormatsAreSniffed() {
    DsbulkProbe plain = new DsbulkProbe(1, 4, ClusterFlavor.CASSANDRA, true, false, false, null,
        Map.of("id", "uuid", "name", "text"));
    List<DsbulkSetting> plainSettings = DsbulkDefaults.derive(unload(), plain);
    assertThat(value(plainSettings, "codec.timestamp")).isNull();
    assertThat(value(plainSettings, "codec.binary")).isNull();
    // Locale and time zone are always pinned: a job must be reproducible wherever the container runs.
    assertThat(value(plainSettings, "codec.locale")).isEqualTo("en_US");
    assertThat(value(plainSettings, "codec.timeZone")).isEqualTo("UTC");

    DsbulkProbe rich = new DsbulkProbe(1, 4, ClusterFlavor.CASSANDRA, true, false, false, null,
        Map.of("at", "timestamp", "day", "date", "clock", "time", "payload", "blob"));
    List<DsbulkSetting> richSettings = DsbulkDefaults.derive(unload(), rich);
    assertThat(value(richSettings, "codec.timestamp")).isEqualTo("CQL_TIMESTAMP");
    assertThat(value(richSettings, "codec.date")).isEqualTo("ISO_LOCAL_DATE");
    assertThat(value(richSettings, "codec.time")).isEqualTo("ISO_LOCAL_TIME");
    assertThat(value(richSettings, "codec.binary")).isEqualTo("BASE64");
  }

  @Test
  @DisplayName("collection element types are stripped before sniffing")
  void collectionTypesAreStripped() {
    DsbulkProbe probe = new DsbulkProbe(1, 1, ClusterFlavor.CASSANDRA, false, false, false, null,
        Map.of("tags", "list<text>", "when", "timestamp"));
    assertThat(DsbulkDefaults.columnTypeNames(probe)).containsExactlyInAnyOrder("list", "timestamp");
  }

  @Test
  @DisplayName("nullStrings are sniffed from the sample, and only tokens that actually occur")
  void nullStringsAreEvidenceBased() {
    assertThat(DsbulkDefaults.sniffNullStrings(List.of("ada", "NULL", " N/A ", "42")))
        .containsExactly("NULL", "N/A");
    // Declaring a token that never appears is how a real value silently becomes null.
    assertThat(DsbulkDefaults.sniffNullStrings(List.of("ada", "42"))).isEmpty();
    assertThat(DsbulkDefaults.sniffNullStrings(null)).isEmpty();
  }

  @Test
  @DisplayName("caller overrides win and come back with auto=false")
  void overridesWinAndAreMarked() {
    DsbulkJobSpec spec = new DsbulkJobSpec(DsbulkOperation.LOAD, "demo", "users", null, "csv",
        "/in.csv", null, false, null, 10, Map.of("batch.maxBatchStatements", "64"));
    List<DsbulkSetting> settings = DsbulkDefaults.derive(spec, probe(1, 4, ClusterFlavor.CASSANDRA, true, false));
    DsbulkSetting override = setting(settings, "batch.maxBatchStatements").orElseThrow();
    assertThat(override.value()).isEqualTo("64");
    assertThat(override.auto()).isFalse();
    assertThat(override.rationale()).isEmpty();
    assertThat(override.upstreamDefault()).isEqualTo("32");
  }

  @Test
  @DisplayName("a dsbulk.-prefixed override collides with the same setting written plainly")
  void overridesAreNormalised() {
    DsbulkJobSpec spec = new DsbulkJobSpec(DsbulkOperation.UNLOAD, "demo", "users", null, "csv", "/out",
        null, false, null, 10, Map.of("dsbulk.schema.splits", "16"));
    List<DsbulkSetting> settings = DsbulkDefaults.derive(spec, probe(3, 8, ClusterFlavor.CASSANDRA, true, false));
    assertThat(settings.stream().filter(s -> s.path().equals("schema.splits"))).hasSize(1);
    assertThat(value(settings, "schema.splits")).isEqualTo("16");
  }

  @Test
  @DisplayName("log.verbosity is translated from the contract's integer to DSBulk's enum")
  void verbosityIsTranslated() {
    DsbulkJobSpec spec = new DsbulkJobSpec(DsbulkOperation.UNLOAD, "demo", "users", null, "csv", "/out",
        null, false, null, 10, Map.of("log.verbosity", "2"));
    assertThat(value(DsbulkDefaults.derive(spec, DsbulkProbe.UNKNOWN), "log.verbosity")).isEqualTo("high");
  }

  @Test
  @DisplayName("stats.modes folds biggest-partitions into partitions, which upstream has no name for")
  void statsModesAreNormalised() {
    assertThat(DsbulkDefaults.normaliseStatsModes(List.of("global", "biggest-partitions", "partitions")))
        .containsExactly("global", "partitions");
    assertThat(DsbulkDefaults.normaliseStatsModes(List.of("nonsense"))).containsExactly("global");
    assertThat(DsbulkDefaults.normaliseStatsModes(List.of("HOSTS", "ranges")))
        .containsExactly("hosts", "ranges");
  }

  @Test
  @DisplayName("count derives stats.modes and the top-N partition count")
  void countDerivations() {
    DsbulkJobSpec spec = new DsbulkJobSpec(DsbulkOperation.COUNT, "demo", "users", null, "csv", null,
        null, false, List.of("global", "ranges", "partitions"), 25, Map.of());
    List<DsbulkSetting> settings = DsbulkDefaults.derive(spec, probe(1, 4, ClusterFlavor.CASSANDRA, true, false));
    assertThat(value(settings, "stats.modes")).isEqualTo("[global,ranges,partitions]");
    assertThat(value(settings, "stats.numPartitions")).isEqualTo("25");
  }

  @Test
  @DisplayName("load sets nullToUnset so empty CSV cells do not become millions of tombstones")
  void loadAvoidsTombstones() {
    assertThat(value(DsbulkDefaults.derive(load(), DsbulkProbe.UNKNOWN), "schema.nullToUnset")).isEqualTo("true");
  }

  @Test
  @DisplayName("dry run is only emitted when asked for")
  void dryRun() {
    DsbulkJobSpec spec = new DsbulkJobSpec(DsbulkOperation.LOAD, "demo", "users", null, "csv", "/in.csv",
        "a=b", true, null, 10, Map.of());
    List<DsbulkSetting> settings = DsbulkDefaults.derive(spec, DsbulkProbe.UNKNOWN);
    assertThat(value(settings, "engine.dryRun")).isEqualTo("true");
    assertThat(value(settings, "schema.mapping")).isEqualTo("a=b");
    assertThat(value(DsbulkDefaults.derive(load(), DsbulkProbe.UNKNOWN), "engine.dryRun")).isNull();
  }

  @Test
  @DisplayName("a query-driven unload sets schema.query instead of keyspace/table")
  void queryDrivenUnload() {
    DsbulkJobSpec spec = new DsbulkJobSpec(DsbulkOperation.UNLOAD, null, null,
        "SELECT id FROM demo.users", "json", "/out", null, false, null, 10, Map.of());
    List<DsbulkSetting> settings = DsbulkDefaults.derive(spec, DsbulkProbe.UNKNOWN);
    assertThat(value(settings, "schema.query")).isEqualTo("SELECT id FROM demo.users");
    assertThat(value(settings, "schema.keyspace")).isNull();
    assertThat(value(settings, "connector.name")).isEqualTo("json");
    assertThat(value(settings, "connector.json.url")).isEqualTo("/out");
  }

  @Test
  @DisplayName("monitoring keeps the console reporter on - it IS the progress signal")
  void monitoringKeepsTheProgressSignal() {
    List<DsbulkSetting> settings = DsbulkDefaults.derive(unload(), DsbulkProbe.UNKNOWN);
    assertThat(value(settings, "monitoring.console")).isEqualTo("true");
    assertThat(value(settings, "monitoring.reportRate")).isEqualTo("1 second");
    assertThat(value(settings, "log.ansiMode")).isEqualTo("disabled");
    assertThat(value(settings, "monitoring.jmx")).isEqualTo("false");
  }

  @Test
  @DisplayName("every derived setting carries a rationale and a docs link")
  void everyDerivedSettingExplainsItself() {
    List<DsbulkSetting> settings = DsbulkDefaults.derive(load(), probe(3, 8, ClusterFlavor.ASTRA, true, false));
    assertThat(settings).isNotEmpty();
    assertThat(settings).allSatisfy(setting -> {
      if (setting.auto()) {
        assertThat(setting.rationale()).as("rationale for %s", setting.path()).isNotBlank();
      }
      assertThat(setting.docsUrl()).as("docs for %s", setting.path()).startsWith("https://");
      assertThat(setting.group()).isNotNull();
    });
  }

  @Test
  @DisplayName("a null probe still produces a complete, runnable set of defaults")
  void nullProbeDegradesGracefully() {
    assertThat(DsbulkDefaults.derive(unload(), null)).isNotEmpty();
  }
}
