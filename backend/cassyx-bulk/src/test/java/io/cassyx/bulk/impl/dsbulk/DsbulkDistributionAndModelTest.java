package io.cassyx.bulk.impl.dsbulk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.cassyx.bulk.api.dsbulk.DsbulkCountReport;
import io.cassyx.bulk.api.dsbulk.DsbulkDistribution;
import io.cassyx.bulk.api.dsbulk.DsbulkException;
import io.cassyx.bulk.api.dsbulk.DsbulkFactory;
import io.cassyx.bulk.api.dsbulk.DsbulkJobSpec;
import io.cassyx.bulk.api.dsbulk.DsbulkListener;
import io.cassyx.bulk.api.dsbulk.DsbulkLogLine;
import io.cassyx.bulk.api.dsbulk.DsbulkOperation;
import io.cassyx.bulk.api.dsbulk.DsbulkProbe;
import io.cassyx.bulk.api.dsbulk.DsbulkProgress;
import io.cassyx.bulk.api.dsbulk.DsbulkSetting;
import io.cassyx.bulk.api.dsbulk.DsbulkSettingGroup;
import io.cassyx.core.api.ClusterFlavor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The shipped distribution, the settings reference, and the module's value objects. */
class DsbulkDistributionAndModelTest {

  @TempDir Path tmp;

  /* --------------------------------------------------------------------- distribution */

  private Path distributionWith(String... jars) throws IOException {
    Path home = Files.createDirectories(tmp.resolve("dsbulk"));
    Path lib = Files.createDirectories(home.resolve("lib"));
    for (String jar : jars) {
      Files.createFile(lib.resolve(jar));
    }
    return home;
  }

  @Test
  @DisplayName("a complete distribution reports all three ServiceLoader workflows")
  void completeDistribution() throws IOException {
    Path home = distributionWith(
        "dsbulk-workflow-load-1.11.1.jar",
        "dsbulk-workflow-unload-1.11.1.jar",
        "dsbulk-workflow-count-1.11.1.jar",
        "dsbulk-runner-1.11.1.jar",
        "not-a-jar.txt");
    LocalDsbulkDistribution distribution = new LocalDsbulkDistribution(home);

    assertThat(distribution.isComplete()).isTrue();
    assertThat(distribution.workflows()).containsExactlyElementsOf(DsbulkDistribution.REQUIRED_WORKFLOWS);
    assertThat(distribution.jars()).contains("dsbulk-runner-1.11.1.jar").doesNotContain("not-a-jar.txt");
    assertThat(distribution.libraryDirectory()).isEqualTo(home.resolve("lib"));
    assertThat(distribution.launcher()).isEqualTo(home.resolve("bin").resolve("dsbulk"));
    assertThat(distribution.toString()).contains("dsbulk-workflow-load");
    distribution.verify();
  }

  @Test
  @DisplayName("a missing workflow jar is caught eagerly, by name")
  void missingWorkflowIsNamed() throws IOException {
    // Workflows resolve through ServiceLoader, so a missing jar otherwise fails at job time with
    // DSBulk's own opaque "first argument must be a subcommand".
    Path home = distributionWith("dsbulk-workflow-load-1.11.1.jar", "dsbulk-workflow-unload-1.11.1.jar");
    LocalDsbulkDistribution distribution = new LocalDsbulkDistribution(home);

    assertThat(distribution.isComplete()).isFalse();
    assertThatThrownBy(distribution::verify)
        .isInstanceOf(DsbulkException.class)
        .hasMessageContaining("dsbulk-workflow-count")
        .hasMessageContaining("ServiceLoader");
  }

  @Test
  @DisplayName("the executable jar is rejected in favour of the binary distribution")
  void executableJarIsRejected() throws IOException {
    Path home = Files.createDirectories(tmp.resolve("just-a-jar"));
    assertThatThrownBy(() -> new LocalDsbulkDistribution(home).verify())
        .isInstanceOf(DsbulkException.class)
        .hasMessageContaining("evaluation-only");
  }

  @Test
  @DisplayName("a missing distribution reports how to fix it, and does not stop the app booting")
  void missingDistribution() {
    LocalDsbulkDistribution absent = new LocalDsbulkDistribution(tmp.resolve("nope"));
    assertThat(absent.isComplete()).isFalse();
    assertThat(absent.jars()).isEmpty();
    assertThat(absent.workflows()).isEmpty();
    assertThatThrownBy(absent::verify)
        .isInstanceOf(DsbulkException.class)
        .hasMessageContaining(DsbulkDistribution.HOME_ENV);
    assertThat(LocalDsbulkDistribution.fromEnvironment().home()).isNotNull();
    assertThat(new LocalDsbulkDistribution(null).launcher()).isNull();
  }

  /* ----------------------------------------------------------------------- reference */

  @Test
  @DisplayName("contract paths that upstream nests deeper are aliased, not silently ignored")
  void logPathsAreAliased() {
    // Typesafe Config accepts unknown keys without complaint, so the flat contract spelling would
    // appear in the preview and do absolutely nothing.
    assertThat(DsbulkReference.normalise("log.maxQueryStringLength")).isEqualTo("log.stmt.maxQueryStringLength");
    assertThat(DsbulkReference.normalise("log.maxBoundValueLength")).isEqualTo("log.stmt.maxBoundValueLength");
    assertThat(DsbulkReference.normalise("log.maxResultSetValueLength"))
        .isEqualTo("log.row.maxResultSetValueLength");
    assertThat(DsbulkReference.upstreamDefault("log.maxQueryStringLength")).isEqualTo("500");
  }

  @Test
  @DisplayName("driver settings are translated into the driver's own namespace")
  void driverPathTranslation() {
    assertThat(DsbulkReference.toDsbulkPath("driver.basic.requestConsistency"))
        .isEqualTo("datastax-java-driver.basic.request.consistency");
    assertThat(DsbulkReference.toDsbulkPath("driver.advanced.protocolCompression"))
        .isEqualTo("datastax-java-driver.advanced.protocol.compression");
    assertThat(DsbulkReference.toDsbulkPath("driver.basic.cloud.secureConnectBundle"))
        .isEqualTo("datastax-java-driver.basic.cloud.secure-connect-bundle");
    // An unmodelled driver option still lands in the right namespace, kebab-cased.
    assertThat(DsbulkReference.toDsbulkPath("driver.advanced.someNewOption"))
        .isEqualTo("datastax-java-driver.advanced.some-new-option");
    assertThat(DsbulkReference.toDsbulkPath("datastax-java-driver.basic.request.timeout"))
        .isEqualTo("datastax-java-driver.basic.request.timeout");
    assertThat(DsbulkReference.toDsbulkPath("batch.mode")).isEqualTo("dsbulk.batch.mode");
    assertThat(DsbulkReference.toDsbulkPath("dsbulk.batch.mode")).isEqualTo("dsbulk.batch.mode");
    assertThat(DsbulkReference.toKebab("aBC")).isEqualTo("a-b-c");
  }

  @Test
  @DisplayName("every modelled setting resolves a group and a docs link")
  void everySettingIsDocumented() {
    assertThat(DsbulkReference.knownPaths()).hasSizeGreaterThan(80);
    assertThat(DsbulkReference.knownPaths()).allSatisfy(path -> {
      assertThat(DsbulkReference.group(path)).isNotNull();
      assertThat(DsbulkReference.docsUrl(path)).startsWith(DsbulkReference.DOCS_BASE + "#");
    });
    assertThat(DsbulkReference.normalise(null)).isEmpty();
    assertThat(DsbulkReference.translateValue("batch.mode", null)).isNull();
    assertThat(DsbulkReference.translateValue("batch.mode", "DISABLED")).isEqualTo("DISABLED");
    assertThat(DsbulkReference.translateValue("log.verbosity", "9")).isEqualTo("9");
  }

  @Test
  @DisplayName("groups cover every namespace the contract models")
  void settingGroups() {
    assertThat(DsbulkSettingGroup.of("connector.csv.url")).isEqualTo(DsbulkSettingGroup.CONNECTOR);
    assertThat(DsbulkSettingGroup.of("dsbulk.stats.modes")).isEqualTo(DsbulkSettingGroup.STATS);
    assertThat(DsbulkSettingGroup.of("driver.basic.requestConsistency")).isEqualTo(DsbulkSettingGroup.DRIVER);
    assertThat(DsbulkSettingGroup.of("datastax-java-driver.basic.request.timeout"))
        .isEqualTo(DsbulkSettingGroup.DRIVER);
    assertThat(DsbulkSettingGroup.S3.contractName()).isEqualTo("s3");
    assertThatThrownBy(() -> DsbulkSettingGroup.of("nonsense.setting")).isInstanceOf(DsbulkException.class);
    assertThatThrownBy(() -> DsbulkSettingGroup.of(" ")).isInstanceOf(DsbulkException.class);
  }

  /* -------------------------------------------------------------------- value objects */

  @Test
  @DisplayName("operations map onto the CLI subcommands")
  void operations() {
    assertThat(DsbulkOperation.UNLOAD.command()).isEqualTo("unload");
    assertThat(DsbulkOperation.UNLOAD.isRead()).isTrue();
    assertThat(DsbulkOperation.LOAD.isRead()).isFalse();
    assertThat(DsbulkOperation.parse(" count ")).isEqualTo(DsbulkOperation.COUNT);
    assertThatThrownBy(() -> DsbulkOperation.parse("merge")).isInstanceOf(DsbulkException.class);
    assertThatThrownBy(() -> DsbulkOperation.parse(null)).isInstanceOf(DsbulkException.class);
  }

  @Test
  @DisplayName("a job spec insists on a target")
  void jobSpecValidation() {
    assertThatThrownBy(() -> new DsbulkJobSpec(DsbulkOperation.UNLOAD, null, null, null, "csv",
        "/out", null, false, null, 10, Map.of()))
        .isInstanceOf(DsbulkException.class)
        .hasMessageContaining("keyspace+table or a query");
    assertThatThrownBy(() -> new DsbulkJobSpec(DsbulkOperation.LOAD, "demo", null, null, "csv",
        "/in", null, false, null, 10, Map.of()))
        .isInstanceOf(DsbulkException.class)
        .hasMessageContaining("needs a table");

    DsbulkJobSpec spec = DsbulkJobSpec.table(DsbulkOperation.COUNT, "demo", "users", null, null);
    assertThat(spec.format()).isEqualTo("csv");
    assertThat(spec.statsModes()).isEqualTo(DsbulkJobSpec.DEFAULT_STATS_MODES);
    assertThat(spec.topPartitions()).isEqualTo(10);
    assertThat(spec.qualifiedName()).isEqualTo("demo.users");
    assertThat(spec.isQueryDriven()).isFalse();
  }

  @Test
  @DisplayName("the probe clamps nonsense and derives an oversplit count")
  void probeDefaults() {
    DsbulkProbe clamped = new DsbulkProbe(0, 0, null, false, false, false, null, null);
    assertThat(clamped.nodeCount()).isEqualTo(1);
    assertThat(clamped.clientCores()).isEqualTo(1);
    assertThat(clamped.flavour()).isEqualTo(ClusterFlavor.UNKNOWN);
    assertThat(clamped.recommendedSplits()).isEqualTo(8);
    assertThat(clamped.supportsTokenRangeScan()).isTrue();
    assertThat(DsbulkProbe.UNKNOWN.columnTypes()).isEmpty();

    DsbulkProbe huge = new DsbulkProbe(100_000, 64, ClusterFlavor.CASSANDRA, true, false, false, null, Map.of());
    assertThat(huge.recommendedSplits()).isEqualTo(100_000);
  }

  @Test
  @DisplayName("a setting knows whether it merely restates the upstream default")
  void settingValueObject() {
    DsbulkSetting derived = DsbulkSetting.derived("batch.maxBatchStatements", "32", "32", "why", "url");
    assertThat(derived.auto()).isTrue();
    assertThat(derived.matchesUpstreamDefault()).isTrue();
    assertThat(derived.group()).isEqualTo(DsbulkSettingGroup.BATCH);

    DsbulkSetting edited = derived.asOverride("64");
    assertThat(edited.auto()).isFalse();
    assertThat(edited.value()).isEqualTo("64");
    assertThat(edited.rationale()).isEmpty();
    assertThat(edited.matchesUpstreamDefault()).isFalse();
    assertThat(DsbulkSetting.override("batch.mode", "DISABLED", "PARTITION_KEY", "url").auto()).isFalse();
  }

  @Test
  @DisplayName("progress, log and count value objects normalise their nulls")
  void miscValueObjects() {
    assertThat(new DsbulkProgress(1, 2, 3, null).phase()).isEmpty();
    assertThat(new DsbulkLogLine(null, null, null).level()).isEqualTo("INFO");
    assertThat(new DsbulkLogLine("", "msg", null).raw()).isEqualTo("msg");
    assertThat(new DsbulkCountReport(1, null, null, null).perReplica()).isEmpty();
    DsbulkListener.noop().onProgress(DsbulkProgress.NONE);
    DsbulkListener.noop().onLog(new DsbulkLogLine("INFO", "x", "x"));
  }

  /* ------------------------------------------------------------------------- factory */

  @Test
  @DisplayName("the factory is a usable entry point with nothing but a job spec")
  void factoryEntryPoint() {
    DsbulkJobSpec spec = DsbulkJobSpec.table(DsbulkOperation.UNLOAD, "demo", "users", "csv", "/out");
    List<DsbulkSetting> settings = DsbulkFactory.derive(spec, DsbulkProbe.UNKNOWN);
    assertThat(settings).isNotEmpty();
    assertThat(DsbulkFactory.plan(spec, DsbulkProbe.UNKNOWN, tmp, "X").command()).startsWith("dsbulk unload");
    assertThat(DsbulkFactory.configurationFile(
        DsbulkFactory.plan(spec, DsbulkProbe.UNKNOWN, tmp, "X"), Map.of())).contains("dsbulk.");
    assertThat(DsbulkFactory.upstreamDefault("batch.maxBatchStatements")).isEqualTo("32");
    assertThat(DsbulkFactory.docsUrl("batch.mode")).contains("#batch.mode");
    assertThat(DsbulkFactory.isSecret("s3.accessKeyId")).isTrue();
    assertThat(DsbulkFactory.sniffNullStrings(List.of("NULL"))).containsExactly("NULL");
    assertThat(DsbulkFactory.parseCountOutput(List.of("7")).totalRows()).isEqualTo(7);
    assertThat(DsbulkFactory.probe(null, "demo", "users")).isEqualTo(DsbulkProbe.UNKNOWN);
    assertThat(DsbulkFactory.distribution()).isNotNull();
    assertThat(DsbulkFactory.distribution(tmp).home()).isEqualTo(tmp);
    assertThat(DsbulkFactory.runner("1g")).isNotNull();
    assertThat(DsbulkFactory.runner(DsbulkFactory.distribution(tmp), "1g")).isNotNull();
  }
}
