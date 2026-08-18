package io.cassyx.api.bulk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.cassyx.api.bulk.JobDtos.Job;
import io.cassyx.api.bulk.JobDtos.UnloadJobRequest;
import io.cassyx.api.bulk.JobDtos.UnloadSink;
import io.cassyx.bulk.api.UnloadRequest;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The job adapter layer, unit-tested without a Spring context.
 *
 * <p>Everything here is a total function over contract values - row mapping, request validation,
 * file-name sanitising, {@code Range} parsing - which is exactly where the adapter's real bugs live.
 * The wiring proof lives in {@code JobEndpointsTest}.
 */
class JobApiLayerTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final JobRepository REPOSITORY = new JobRepository(null, MAPPER);

  @TempDir Path tmp;

  private static Map<String, Object> row(String status, Object... extra) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("ID", "6c8f2a10-b4f9-4a1e-9a12-5f0a7e2d3b44");
    row.put("TYPE", "UNLOAD");
    row.put("STATUS", status);
    row.put("ENGINE", "NATIVE");
    row.put("CONNECTION_ID", "8f2b1c6e-2a55-4f47-9f2a-4c1c3f0d9a11");
    row.put("KEYSPACE_NAME", "demo");
    row.put("TABLE_NAME", "users");
    row.put("SETTINGS_JSON", "{\"name\":\"Export demo.users\"}");
    row.put("ROWS_PROCESSED", 4210000L);
    row.put("SPLITS_COMPLETED", 4103);
    row.put("SPLITS_TOTAL", 10000);
    row.put("PROGRESS_PERCENT", 42);
    row.put("CREATED_AT", Timestamp.from(Instant.parse("2026-08-17T12:00:00Z")));
    row.put("STARTED_AT", Timestamp.from(Instant.parse("2026-08-17T12:00:03Z")));
    for (int i = 0; i + 1 < extra.length; i += 2) {
      row.put((String) extra[i], extra[i + 1]);
    }
    return row;
  }

  /* ------------------------------------------------------------------------- row mapping */

  @Test
  @DisplayName("column lookup is case-insensitive - H2 upper-cases, PostgreSQL lower-cases")
  void columnLookupIsCaseInsensitive() {
    // Keying the result map directly is a bug that only appears on the OTHER database.
    Map<String, Object> upper = Map.of("STATUS", "RUNNING");
    Map<String, Object> lower = Map.of("status", "RUNNING");
    assertThat(JobRepository.string(upper, "status")).isEqualTo("RUNNING");
    assertThat(JobRepository.string(lower, "STATUS")).isEqualTo("RUNNING");
    assertThat(JobRepository.string(upper, "missing")).isNull();
    assertThat(JobRepository.number(upper, "missing")).isZero();
    assertThat(JobRepository.instant(upper, "missing")).isNull();
  }

  @Test
  @DisplayName("a running job maps to the contract's Job with its split counters intact")
  void mapsARunningJob() {
    Job job = REPOSITORY.toJob(row("RUNNING"));

    assertThat(job.id()).isEqualTo("6c8f2a10-b4f9-4a1e-9a12-5f0a7e2d3b44");
    assertThat(job.name()).isEqualTo("Export demo.users");
    assertThat(job.type()).isEqualTo("UNLOAD");
    assertThat(job.engine()).isEqualTo("NATIVE");
    assertThat(job.status()).isEqualTo("RUNNING");
    assertThat(job.identity().kind()).isEqualTo("TABLE");
    assertThat(job.identity().qualifiedName()).isEqualTo("demo.users");
    assertThat(job.createdAt()).isEqualTo("2026-08-17T12:00:00Z");
    assertThat(job.finishedAt()).isNull();
    assertThat(job.durationMillis()).isNull();
    assertThat(job.eventsUrl()).isEqualTo("/api/jobs/" + job.id() + "/events");
    assertThat(job.logsUrl()).isEqualTo("/api/jobs/" + job.id() + "/logs");

    // The work-stealing counters are the only honest progress measure until a row estimate lands.
    assertThat(job.progress().splitsCompleted()).isEqualTo(4103);
    assertThat(job.progress().splitsTotal()).isEqualTo(10_000);
    assertThat(job.progress().rowsProcessed()).isEqualTo(4_210_000);
  }

  @Test
  @DisplayName("artifacts are advertised only once the job has SUCCEEDED")
  void artifactsOnlyAfterSuccess() throws IOException {
    Path artifact = Files.writeString(tmp.resolve("users.csv"), "id,email\n1,a@b.c\n");

    Job running =
        REPOSITORY.toJob(row("RUNNING", "ARTIFACT_PATH", artifact.toString()));
    // A download link on a half-written file is how a user ends up with a truncated export they
    // believe is complete.
    assertThat(running.artifacts()).isEmpty();

    Job done =
        REPOSITORY.toJob(
            row(
                "SUCCEEDED",
                "ARTIFACT_PATH",
                artifact.toString(),
                "FINISHED_AT",
                Timestamp.from(Instant.parse("2026-08-17T12:00:53Z"))));
    assertThat(done.artifacts()).hasSize(1);
    assertThat(done.artifacts().get(0).fileName()).isEqualTo("users.csv");
    assertThat(done.artifacts().get(0).contentType()).isEqualTo("text/csv");
    assertThat(done.artifacts().get(0).sizeBytes()).isEqualTo(Files.size(artifact));
    assertThat(done.artifacts().get(0).downloadUrl()).endsWith("/artifact");
    assertThat(done.durationMillis()).isEqualTo(50_000);
  }

  @Test
  @DisplayName("a failed job carries an RFC 9457 problem, never a stack trace")
  void failedJobCarriesAProblem() {
    Job job =
        REPOSITORY.toJob(
            row("FAILED", "ERROR_MESSAGE", "Split (-9223372036854775808,0] failed"));
    assertThat(job.error()).isInstanceOf(Map.class);
    Map<?, ?> problem = (Map<?, ?>) job.error();
    assertThat(problem.get("status")).isEqualTo(500);
    assertThat(problem.get("detail").toString()).contains("Split");
    assertThat(REPOSITORY.toJob(row("SUCCEEDED")).error()).isNull();
  }

  @ParameterizedTest
  @CsvSource({
    "users.csv,text/csv",
    "out.json,application/json",
    "out.jsonl,application/json",
    "out.xml,application/xml",
    "out.xlsx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "out.parquet,application/vnd.apache.parquet",
    "tree.zip,application/zip",
    "mystery.bin,application/octet-stream"
  })
  void contentTypesFollowTheExtension(String fileName, String expected) {
    assertThat(JobRepository.contentTypeFor(fileName)).isEqualTo(expected);
  }

  @Test
  @DisplayName("the limit is clamped to the contract's default and ceiling")
  void limitIsClamped() {
    assertThat(JobRepository.clampLimit(0)).isEqualTo(50);
    assertThat(JobRepository.clampLimit(-5)).isEqualTo(50);
    assertThat(JobRepository.clampLimit(10)).isEqualTo(10);
    assertThat(JobRepository.clampLimit(50_000)).isEqualTo(500);
  }

  @Test
  @DisplayName("an over-long error message is truncated rather than failing the UPDATE")
  void errorMessagesAreTruncated() {
    assertThat(JobRepository.truncate(null)).isNull();
    assertThat(JobRepository.truncate("short")).isEqualTo("short");
    // error_message is VARCHAR(4000): an unbounded driver error must not lose the whole row.
    assertThat(JobRepository.truncate("x".repeat(5000))).hasSize(4000).endsWith("...");
  }

  /* --------------------------------------------------------------------------- validation */

  private static UnloadJobRequest unload(String keyspace, String table, String query, UnloadSink sink) {
    return new UnloadJobRequest(
        "Export", keyspace, table, query, List.of(), "CSV", sink, "NATIVE", null, null, null);
  }

  private static UnloadSink sink(String type, String path, String s3Uri, String pattern) {
    return new UnloadSink(type, path, s3Uri, pattern, "NONE", 0L);
  }

  @Test
  @DisplayName("an unload with neither table nor query is a 400, not a mysterious FAILED job")
  void unloadNeedsATarget() {
    assertThatThrownBy(() -> JobService.validate(unload(null, null, null, sink("DOWNLOAD", null, null, null))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("keyspace + table");
    assertThatThrownBy(() -> JobService.validate(null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> JobService.validate(unload("demo", "users", null, null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("sink");
  }

  @Test
  @DisplayName("an unknown format fails before the scan runs, not after")
  void unknownFormatFailsFast() {
    UnloadJobRequest request =
        new UnloadJobRequest(
            null, "demo", "users", null, List.of(), "PROTOBUF",
            sink("DOWNLOAD", null, null, null), null, null, null, null);
    assertThatThrownBy(() -> JobService.validate(request))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("a query-only unload is valid and forces the paging strategy")
  void queryOnlyUnloadIsValid() {
    UnloadJobRequest request =
        unload(null, null, "SELECT * FROM demo.users", sink("DOWNLOAD", null, null, null));
    JobService.validate(request);

    JobService service = service();
    UnloadRequest engineRequest = service.toUnloadRequest(request);
    // A custom SELECT may already carry a WHERE clause, so it cannot be split by token range.
    assertThat(engineRequest.options()).containsEntry("scanStrategy", "PAGING");
    assertThat(engineRequest.options()).containsEntry("query", "SELECT * FROM demo.users");
  }

  @Test
  @DisplayName("the contract's upper-case format maps to the lower-case Encoder SPI key")
  void formatIdsAreNormalised() {
    assertThat(JobService.formatId("CSV")).isEqualTo("csv");
    assertThat(JobService.formatId("JSONL")).isEqualTo("jsonl");
    assertThat(JobService.formatId(null)).isEqualTo("csv");
    assertThat(JobService.formatId("  XLSX ")).isEqualTo("xlsx");
  }

  @Test
  @DisplayName("every sink type resolves to an engine target, and an unknown one is rejected")
  void sinkTargets() {
    assertThat(JobService.sinkType(sink("download", null, null, null))).isEqualTo("DOWNLOAD");
    assertThat(JobService.sinkType(null)).isEqualTo("DOWNLOAD");
    assertThat(JobService.targetFor(sink("VOLUME_PATH", "/out/users", null, null)))
        .isEqualTo("/out/users");
    assertThat(JobService.targetFor(sink("S3", null, "s3://bucket/exports/", null)))
        .isEqualTo("s3://bucket/exports/");
    assertThat(JobService.targetFor(sink("DOWNLOAD", null, null, null))).isEqualTo("/out");

    assertThatThrownBy(() -> JobService.sinkType(sink("FTP", null, null, null)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> JobService.targetFor(sink("S3", null, null, null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("s3Uri");
    assertThatThrownBy(() -> JobService.targetFor(sink("VOLUME_PATH", null, null, null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("path");
  }

  @Test
  @DisplayName("an artifact file name cannot escape the job directory")
  void fileNamesAreSanitised() {
    // fileNamePattern is caller-supplied; unsanitised it is a write-anywhere primitive.
    assertThat(
            JobService.fileName(
                unload("demo", "users", null, sink("DOWNLOAD", null, null, "../../etc/passwd"))))
        .isEqualTo("passwd");
    assertThat(JobService.fileName(unload("demo", "users", null, sink("DOWNLOAD", null, null, null))))
        .isEqualTo("users.csv");
    assertThat(JobService.fileName(unload(null, null, "SELECT 1", sink("DOWNLOAD", null, null, null))))
        .isEqualTo("export.csv");
    assertThat(
            JobService.fileName(
                unload("demo", "users", null, sink("DOWNLOAD", null, null, "users-%d.csv"))))
        .isEqualTo("users-1.csv");
    assertThat(JobService.safeFileName("..")).isEqualTo("export.dat");
    assertThat(JobService.safeFileName(null)).isEqualTo("export.dat");
  }

  @Test
  @DisplayName("the engine request oversplits by default - never one split per worker")
  void requestsAreOversplit() {
    UnloadRequest request =
        service().toUnloadRequest(unload("demo", "users", null, sink("DOWNLOAD", null, null, null)));
    // splitEvenly divides by token count, not data volume: the throughput lever is more splits.
    assertThat(request.splits()).isEqualTo(UnloadRequest.DEFAULT_SPLITS).isGreaterThanOrEqualTo(10_000);
    assertThat(request.keyspace()).isEqualTo("demo");
    assertThat(request.format()).isEqualTo("csv");
  }

  private static JobService service() {
    return new JobService(
        REPOSITORY,
        new DsbulkJobEventStream(),
        null,
        null,
        MAPPER,
        Path.of("/tmp"),
        java.time.Clock.systemUTC(),
        4);
  }

  /* ------------------------------------------------------------------------ log filtering */

  @Test
  @DisplayName("the level filter is at-or-above, the way every log viewer behaves")
  void logLevelFilterIsAtOrAbove() {
    assertThat(JobController.atLeast("ERROR", "WARN")).isTrue();
    assertThat(JobController.atLeast("INFO", "WARN")).isFalse();
    assertThat(JobController.atLeast("WARN", "WARN")).isTrue();
    assertThat(JobController.atLeast("info", "DEBUG")).isTrue();
    assertThat(JobController.atLeast("INFO", "NONSENSE")).isTrue();
  }

  @Test
  @DisplayName("comma-joined filters are parsed as the contract declares (explode: false)")
  void filtersAreCommaJoined() {
    assertThat(JobController.csv("running, succeeded")).containsExactly("RUNNING", "SUCCEEDED");
    assertThat(JobController.csv("")).isEmpty();
    assertThat(JobController.csv(null)).isEmpty();
    assertThat(JobController.csv(",,")).isEmpty();
  }

  @Test
  void terminalStatesAreFinal() {
    assertThat(JobController.isTerminal("SUCCEEDED")).isTrue();
    assertThat(JobController.isTerminal("FAILED")).isTrue();
    assertThat(JobController.isTerminal("CANCELLED")).isTrue();
    assertThat(JobController.isTerminal("RUNNING")).isFalse();
    assertThat(JobController.isTerminal("QUEUED")).isFalse();
  }

  /* -------------------------------------------------------------------- range / streaming */

  @Test
  @DisplayName("Range is parsed for resumable downloads and degrades to the whole file")
  void rangeParsing() {
    assertThat(JobController.parseRange(null, 100)).containsExactly(0, 100);
    assertThat(JobController.parseRange("bytes=0-9", 100)).containsExactly(0, 10);
    assertThat(JobController.parseRange("bytes=10-", 100)).containsExactly(10, 90);
    assertThat(JobController.parseRange("bytes=-20", 100)).containsExactly(80, 20);
    assertThat(JobController.parseRange("bytes=0-9999", 100)).containsExactly(0, 100);
    // Anything malformed, multi-range or unsatisfiable serves the whole file rather than erroring:
    // refusing the artifact because a proxy rewrote a header is the worse outcome.
    assertThat(JobController.parseRange("bytes=abc-def", 100)).containsExactly(0, 100);
    assertThat(JobController.parseRange("bytes=0-9,20-29", 100)).containsExactly(0, 100);
    assertThat(JobController.parseRange("items=0-9", 100)).containsExactly(0, 100);
    assertThat(JobController.parseRange("bytes=500-", 100)).containsExactly(0, 100);
    assertThat(JobController.parseRange("bytes=50-10", 100)).containsExactly(0, 100);
    assertThat(JobController.parseRange("bytes=0", 100)).containsExactly(0, 100);
  }

  @Test
  @DisplayName("the artifact copy writes exactly the requested window, one buffer at a time")
  void artifactCopyHonoursTheWindow() throws IOException {
    Path file = Files.writeString(tmp.resolve("out.csv"), "0123456789");
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    JobController.copy(file, 3, 4, out);
    assertThat(out.toString(StandardCharsets.UTF_8)).isEqualTo("3456");

    ByteArrayOutputStream whole = new ByteArrayOutputStream();
    JobController.copy(file, 0, 10, whole);
    assertThat(whole.toString(StandardCharsets.UTF_8)).isEqualTo("0123456789");
  }

  @Test
  @DisplayName("a file name cannot inject a response header")
  void headerValuesAreSanitised() {
    assertThat(JobController.safeHeader("a\r\nSet-Cookie: x=1")).doesNotContain("\r").doesNotContain("\n");
    assertThat(JobController.safeHeader("we\"ird.csv")).isEqualTo("we_ird.csv");
  }

  /* ------------------------------------------------------------------------- SSE contract */

  @Test
  @DisplayName("SSE events are NAMED - an onmessage-only client would receive nothing otherwise")
  void sseEventsAreNamed() {
    DsbulkJobEventStream stream = new DsbulkJobEventStream();
    stream.publish("job-1", "status", Map.of("status", "RUNNING"));
    stream.publish("job-1", "progress", Map.of("rowsProcessed", 10));
    stream.publish("job-1", "log", Map.of("message", "hello"));
    stream.complete("job-1", Map.of("status", "SUCCEEDED"));

    assertThat(stream.snapshot("job-1"))
        .extracting(DsbulkJobEventStream.Event::name)
        .containsExactly("status", "progress", "log", "completed");
    // Monotonic ids are what make Last-Event-ID resume gap-free.
    assertThat(stream.snapshot("job-1"))
        .extracting(DsbulkJobEventStream.Event::id)
        .isSorted();
  }

  @Test
  @DisplayName("the progress payload carries the split counters and a derived ETA")
  void progressPayloadShape() {
    JobService service = service();
    Map<String, Object> payload =
        service.progressPayload(
            "job-1",
            new io.cassyx.bulk.api.JobProgress(4_210_000, 4103, 10_000, "Unloading"),
            Instant.now().minusMillis(20_000));

    assertThat(payload).containsEntry("jobId", "job-1");
    assertThat(payload).containsEntry("splitsCompleted", 4103);
    assertThat(payload).containsEntry("splitsTotal", 10_000);
    assertThat(payload).containsEntry("currentPhase", "Unloading");
    assertThat((Double) payload.get("percent")).isBetween(40.0, 43.0);
    assertThat(payload).containsKey("etaMillis");
    assertThat((Long) payload.get("rowsPerSecond")).isPositive();
  }
}
