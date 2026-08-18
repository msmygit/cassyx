package io.cassyx.api.bulk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.cassyx.api.bulk.DsbulkDtos.BulkSink;
import io.cassyx.api.bulk.DsbulkDtos.BulkSource;
import io.cassyx.api.bulk.DsbulkDtos.DerivedSetting;
import io.cassyx.api.bulk.DsbulkDtos.TableStatistics;
import io.cassyx.bulk.api.dsbulk.DsbulkCountReport;
import io.cassyx.bulk.api.dsbulk.DsbulkException;
import io.cassyx.bulk.api.dsbulk.DsbulkOperation;
import io.cassyx.bulk.api.dsbulk.DsbulkProbe;
import io.cassyx.bulk.api.dsbulk.DsbulkSetting;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The adapter layer: flattening, DTO mapping, upload safety and the named SSE substrate. */
class DsbulkApiLayerTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @TempDir Path tmp;

  private static JsonNode json(String text) {
    try {
      return MAPPER.readTree(text);
    } catch (Exception e) {
      throw new IllegalArgumentException(e);
    }
  }

  /* ------------------------------------------------------------------------ flattening */

  @Test
  @DisplayName("the contract's nested settings flatten to DSBulk setting paths, types intact")
  void flattening() {
    Map<String, String> flat = DsbulkSettingsFlattener.flatten(json("""
        {
          "connector": { "name": "csv", "csv": { "delimiter": ";", "header": true, "maxColumns": 1024 } },
          "batch": { "mode": "PARTITION_KEY", "maxBatchStatements": 64 },
          "codec": { "nullStrings": ["", "NULL"] },
          "driver": { "basic": { "requestConsistency": "LOCAL_QUORUM" } }
        }
        """));

    assertThat(flat).containsEntry("connector.name", "csv");
    assertThat(flat).containsEntry("connector.csv.delimiter", ";");
    // Types survive: a boolean is not quoted into a string, a number is not turned into text.
    assertThat(flat).containsEntry("connector.csv.header", "true");
    assertThat(flat).containsEntry("connector.csv.maxColumns", "1024");
    assertThat(flat).containsEntry("batch.maxBatchStatements", "64");
    assertThat(flat).containsEntry("codec.nullStrings", "[\"\",\"NULL\"]");
    assertThat(flat).containsEntry("driver.basic.requestConsistency", "LOCAL_QUORUM");
  }

  @Test
  @DisplayName("the extra passthrough map is applied LAST, so it overrides everything")
  void extraOverridesEverything() {
    // The contract promises "applied last, overriding everything" - that is what makes a new DSBulk
    // option usable without an API change.
    Map<String, String> flat = DsbulkSettingsFlattener.flatten(json("""
        {
          "batch": { "maxBatchStatements": 64, "extra": { "batch.maxBatchStatements": "128" } },
          "extra": { "dsbulk.connector.csv.ignoreLeadingWhitespaces": "true" }
        }
        """));
    assertThat(flat).containsEntry("batch.maxBatchStatements", "128");
    assertThat(flat).containsEntry("dsbulk.connector.csv.ignoreLeadingWhitespaces", "true");
  }

  @Test
  @DisplayName("maps of Jackson features are values, not more nesting")
  void featureMapsAreNotRecursedInto() {
    Map<String, String> flat = DsbulkSettingsFlattener.flatten(json("""
        {"connector": {"json": {"parserFeatures": {"ALLOW_COMMENTS": true}}}}
        """));
    assertThat(flat).containsEntry("connector.json.parserFeatures", "{\"ALLOW_COMMENTS\":true}");
    assertThat(flat).doesNotContainKey("connector.json.parserFeatures.ALLOW_COMMENTS");
  }

  @Test
  @DisplayName("null, absent and empty settings flatten to nothing at all")
  void emptySettings() {
    assertThat(DsbulkSettingsFlattener.flatten(null)).isEmpty();
    assertThat(DsbulkSettingsFlattener.flatten(json("{}"))).isEmpty();
    assertThat(DsbulkSettingsFlattener.flatten(json("{\"batch\":{\"mode\":null}}"))).isEmpty();
    assertThat(DsbulkSettingsFlattener.flatten(json("\"scalar\""))).isEmpty();
  }

  /* --------------------------------------------------------------------- dto mapping */

  @Test
  @DisplayName("a derived setting keeps its auto marker, rationale and group on the way to the UI")
  void derivedSettingMapping() {
    DerivedSetting dto = DerivedSetting.from(
        DsbulkSetting.derived("batch.maxBatchStatements", "1", "32", "counters are not idempotent", "u"));
    assertThat(dto.auto()).isTrue();
    assertThat(dto.group()).isEqualTo("batch");
    assertThat(dto.rationale()).contains("idempotent");
    assertThat(DerivedSetting.from(List.of())).isEmpty();
  }

  @Test
  @DisplayName("the probe result carries the facts the derivations were computed from")
  void probeMapping() {
    DsbulkDtos.BulkProbeResult dto = DsbulkDtos.BulkProbeResult.from(DsbulkProbe.UNKNOWN);
    assertThat(dto.flavour()).isEqualTo("UNKNOWN");
    assertThat(dto.nodeCount()).isEqualTo(1);
    assertThat(dto.recommendedSplits()).isPositive();
  }

  @Test
  @DisplayName("count statistics map onto the contract, tokens still strings")
  void statisticsMapping() {
    DsbulkCountReport report = new DsbulkCountReport(
        99,
        List.of(new DsbulkCountReport.ReplicaCount("127.0.0.1:9042", 99)),
        List.of(new DsbulkCountReport.RangeCount("-9223372036854775808", "0", 99)),
        List.of(new DsbulkCountReport.PartitionCount("user-1", 42)));

    TableStatistics stats = TableStatistics.from(report, "demo", "users", "job-1", "2026-08-18T00:00:00Z", 1234);

    assertThat(stats.totalRows()).isEqualTo(99);
    assertThat(stats.identity().qualifiedName()).isEqualTo("demo.users");
    assertThat(stats.perTokenRange().get(0).start()).isEqualTo("-9223372036854775808");
    assertThat(stats.largestPartitions().get(0).rows()).isEqualTo(42);
    assertThat(stats.durationMillis()).isEqualTo(1234);
  }

  @Test
  @DisplayName("formats map onto the two connectors DSBulk actually ships")
  void connectorSelection() {
    assertThat(DsbulkPlanningController.connectorFor("JSON")).isEqualTo("json");
    assertThat(DsbulkPlanningController.connectorFor("JSONL")).isEqualTo("json");
    assertThat(DsbulkPlanningController.connectorFor("CSV")).isEqualTo("csv");
    // Parquet/XML/XLSX are the native engine's job; pretending otherwise generates a command DSBulk
    // rejects at start-up.
    assertThat(DsbulkPlanningController.connectorFor("PARQUET")).isEqualTo("csv");
    assertThat(DsbulkPlanningController.connectorFor(null)).isEqualTo("csv");

    assertThat(DsbulkPlanningController.url(DsbulkOperation.UNLOAD,
        new BulkSink("VOLUME_PATH", "/out", null, null, null), null)).isEqualTo("/out");
    assertThat(DsbulkPlanningController.url(DsbulkOperation.UNLOAD,
        new BulkSink("S3", null, "s3://b/p", null, null), null)).isEqualTo("s3://b/p");
    assertThat(DsbulkPlanningController.url(DsbulkOperation.LOAD, null,
        new BulkSource(null, "/in.csv", null, "CSV", null))).isEqualTo("/in.csv");
    assertThat(DsbulkPlanningController.url(DsbulkOperation.LOAD, null, null)).isNull();
    assertThat(DsbulkPlanningController.groups()).hasSize(11).contains("connector", "stats");
    assertThat(DsbulkPlanningController.emptyOverrides()).isEmpty();
  }

  /* ----------------------------------------------------------------------- uploads */

  @Test
  @DisplayName("an uploaded file name cannot escape the staging directory")
  void uploadFileNamesAreSanitised() {
    // Unsanitised, an attacker-controlled file name is a write-anywhere primitive.
    assertThat(LoadJobController.safeFileName("../../etc/passwd")).isEqualTo("passwd");
    assertThat(LoadJobController.safeFileName("C:\\windows\\evil.csv")).isEqualTo("evil.csv");
    assertThat(LoadJobController.safeFileName("..")).isEqualTo("upload.dat");
    assertThat(LoadJobController.safeFileName(null)).isEqualTo("upload.dat");
    assertThat(LoadJobController.safeFileName("users (1).csv")).isEqualTo("users__1_.csv");
  }

  @Test
  @DisplayName("a load source resolves an upload handle, a path or an S3 URL - and nothing else")
  void sourceResolution() throws Exception {
    LoadJobController controller = new LoadJobController(null, null, tmp, Clock.systemUTC());
    Files.createDirectories(tmp.resolve("up_known"));

    assertThat(controller.resolveSource(new BulkSource("up_known", null, null, "CSV", null)))
        .isEqualTo(tmp.resolve("up_known").toString());
    assertThat(controller.resolveSource(new BulkSource(null, "/data/in.csv", null, "CSV", null)))
        .isEqualTo("/data/in.csv");
    assertThat(controller.resolveSource(new BulkSource(null, null, "s3://b/k", "CSV", null)))
        .isEqualTo("s3://b/k");

    assertThatThrownBy(() -> controller.resolveSource(null))
        .isInstanceOf(DsbulkException.class).hasMessageContaining("needs a source");
    assertThatThrownBy(() -> controller.resolveSource(new BulkSource(null, null, null, "CSV", null)))
        .isInstanceOf(DsbulkException.class);
    assertThatThrownBy(() -> controller.resolveSource(new BulkSource("up_gone", null, null, "CSV", null)))
        .isInstanceOf(DsbulkException.class).hasMessageContaining("expired");
  }

  /* -------------------------------------------------------------------- SSE substrate */

  @Test
  @DisplayName("events are NAMED, sequenced, and replayable from Last-Event-ID")
  void namedEventStream() {
    DsbulkJobEventStream stream = new DsbulkJobEventStream();
    stream.publish("job-1", "status", Map.of("status", "QUEUED"));
    stream.publish("job-1", "progress", Map.of("rowsProcessed", 10));
    stream.publish("job-1", "log", Map.of("message", "hello"));

    // Anonymous messages would be invisible to an EventSource with only an onmessage handler.
    assertThat(stream.snapshot("job-1")).extracting(DsbulkJobEventStream.Event::name)
        .containsExactly("status", "progress", "log");
    assertThat(stream.snapshot("job-1")).extracting(DsbulkJobEventStream.Event::id).isSorted();

    stream.subscribe("job-1", "1");
    assertThat(stream.subscriberCount("job-1")).isEqualTo(1);

    stream.complete("job-1", Map.of("status", "SUCCEEDED"));
    assertThat(stream.snapshot("job-1")).last()
        .extracting(DsbulkJobEventStream.Event::name).isEqualTo("completed");
    assertThat(stream.subscriberCount("job-1")).isZero();

    stream.forget("job-1");
    assertThat(stream.snapshot("job-1")).isEmpty();
  }

  @Test
  @DisplayName("the replay buffer is bounded - a long job must not be a memory leak")
  void replayBufferIsBounded() {
    DsbulkJobEventStream stream = new DsbulkJobEventStream();
    for (int i = 0; i < DsbulkJobEventStream.REPLAY_BUFFER + 50; i++) {
      stream.publish("job-2", "log", Map.of("n", i));
    }
    assertThat(stream.snapshot("job-2")).hasSize(DsbulkJobEventStream.REPLAY_BUFFER);
  }

  @Test
  @DisplayName("a malformed Last-Event-ID replays from the start rather than exploding")
  void lastEventIdParsing() {
    assertThat(DsbulkJobEventStream.parseId(null)).isZero();
    assertThat(DsbulkJobEventStream.parseId("  ")).isZero();
    assertThat(DsbulkJobEventStream.parseId("not-a-number")).isZero();
    assertThat(DsbulkJobEventStream.parseId(" 42 ")).isEqualTo(42);
  }

  /* ---------------------------------------------------------------------- job service */

  @Test
  @DisplayName("completed-with-errors is a SUCCESS with a failure count, not a failure")
  void exitStatusToJobStatus() {
    assertThat(DsbulkJobService.status(result(0))).isEqualTo("SUCCEEDED");
    // Exit 1 means "ran to the end, wrote its output, rejected some records". Reporting that as
    // FAILED hides a mostly-successful load; reporting it as clean hides data loss.
    assertThat(DsbulkJobService.status(result(1))).isEqualTo("SUCCEEDED");
    assertThat(DsbulkJobService.status(result(2))).isEqualTo("FAILED");
    assertThat(DsbulkJobService.status(result(4))).isEqualTo("CANCELLED");
  }

  private static io.cassyx.bulk.api.dsbulk.DsbulkResult result(int exitCode) {
    return new io.cassyx.bulk.api.dsbulk.DsbulkResult(
        exitCode, 0, 0, null, tmpPath(), List.of(), DsbulkCountReport.EMPTY, "");
  }

  private static Path tmpPath() {
    return Path.of(System.getProperty("java.io.tmpdir"));
  }

  @Test
  @DisplayName("the DSBulk execution id is filesystem-safe - it names a log directory")
  void executionIdIsSafe() {
    assertThat(DsbulkJobService.executionId(DsbulkOperation.LOAD, "6c8f2a10-b4f9-4a1e-9a12-5f0a7e2d3b44"))
        .isEqualTo("LOAD_6c8f2a10b4f94a1e9a125f0a7e2d3b44");
  }

  @Test
  @DisplayName("error text stored on a job is a message, never a stack trace")
  void safeErrorMessages() {
    assertThat(DsbulkJobService.safeMessage(new IllegalStateException("boom"))).isEqualTo("boom");
    assertThat(DsbulkJobService.safeMessage(new IllegalStateException())).isEqualTo("IllegalStateException");
  }

  @Test
  @DisplayName("an over-long DSBulk error is truncated to fit the column instead of failing the UPDATE")
  void errorTruncation() {
    assertThat(DsbulkJobRepository.truncate(null)).isNull();
    assertThat(DsbulkJobRepository.truncate("short")).isEqualTo("short");
    assertThat(DsbulkJobRepository.truncate("x".repeat(5000))).hasSize(4000).endsWith("...");
  }
}
