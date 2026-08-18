package io.cassyx.api.bulk;

import com.fasterxml.jackson.databind.JsonNode;
import io.cassyx.api.bulk.DsbulkDtos.SchemaIdentity;
import java.util.List;

/**
 * Wire types for the shared job substrate (plan section 5.5, OpenAPI tag {@code bulk}).
 *
 * <p>Records, not classes: every one of these is an immutable value crossing the HTTP boundary, and
 * the contract is the source of truth for their shape. Each is annotated with the contract schema it
 * serialises to, so drift is visible in review rather than at runtime.
 *
 * <p>{@link SchemaIdentity} is deliberately reused from {@link DsbulkDtos} rather than redeclared:
 * both engines report the same {@code identity} object, and two records serialising one contract
 * schema is exactly how a response drifts from the spec on only one code path.
 */
public final class JobDtos {

  private JobDtos() {}

  /* --------------------------------------------------------------------------- responses */

  /**
   * Contract: {@code Job}. The shared substrate for every long-running operation.
   *
   * <p>{@code engine} is {@code NATIVE} for the token-range parallel scan of plan section 5.2 and
   * {@code DSBULK} for the out-of-process runner of section 5.3; the resource shape is identical
   * either way, which is the whole point of a shared substrate.
   */
  public record Job(
      String id,
      String name,
      String type,
      String status,
      String engine,
      String connectionId,
      String targetConnectionId,
      SchemaIdentity identity,
      String createdAt,
      String startedAt,
      String finishedAt,
      Long durationMillis,
      JobProgressView progress,
      List<JobArtifact> artifacts,
      String eventsUrl,
      String logsUrl,
      Integer exitCode,
      Object error) {}

  /** Contract: {@code JobPage}. */
  public record JobPage(List<Job> items, int total, int limit, int offset) {}

  /**
   * Contract: {@code JobProgress}, and the {@code data} payload of the SSE {@code progress} event.
   *
   * <p>{@code splitsCompleted}/{@code splitsTotal} are the native engine's work-stealing counters.
   * They matter to the UI beyond cosmetics: until a row-count estimate exists, the split ratio is
   * the only honest progress measure, so a progress bar that ignored them would sit indeterminate
   * for the whole scan.
   */
  public record JobProgressView(
      long rowsProcessed,
      Long totalRowsEstimate,
      Double percent,
      long rowsPerSecond,
      long bytesWritten,
      long elapsedMillis,
      Long etaMillis,
      int splitsCompleted,
      int splitsTotal,
      long failures,
      String currentPhase) {

    public static JobProgressView empty() {
      return new JobProgressView(0, null, null, 0, 0, 0, null, 0, 0, 0, "");
    }
  }

  /** Contract: {@code JobArtifact}. */
  public record JobArtifact(
      String artifactId,
      String fileName,
      long sizeBytes,
      String contentType,
      String downloadUrl,
      String checksumSha256,
      String kind,
      String retainedUntil) {}

  /** Contract: {@code JobLogLine}. */
  public record JobLogLine(String at, String level, String message, String source) {}

  /** Contract: {@code JobLogPage}. */
  public record JobLogPage(
      String jobId, List<JobLogLine> lines, boolean truncated, int totalLines) {}

  /* ---------------------------------------------------------------------------- requests */

  /**
   * Contract: {@code UnloadJobRequest}. Supply either {@code keyspace} + {@code table}, or
   * {@code query}.
   *
   * <p>Every field beyond {@code format} and {@code sink} is optional; anything unset is derived.
   */
  public record UnloadJobRequest(
      String name,
      String keyspace,
      String table,
      String query,
      List<String> columns,
      String format,
      UnloadSink sink,
      String engine,
      String consistency,
      String templateId,
      JsonNode dsbulkSettings) {}

  /**
   * Contract: {@code UnloadSink}.
   *
   * <p>{@code DOWNLOAD} retains the artifact server-side and exposes it through
   * {@code downloadJobArtifact}; the browser never holds the bytes (plan section 2).
   */
  public record UnloadSink(
      String type,
      String path,
      String s3Uri,
      String fileNamePattern,
      String compression,
      Long maxFileSizeBytes) {}
}
