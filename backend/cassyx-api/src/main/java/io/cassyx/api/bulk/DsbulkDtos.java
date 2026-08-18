package io.cassyx.api.bulk;

import com.fasterxml.jackson.databind.JsonNode;
import io.cassyx.bulk.api.dsbulk.DsbulkCountReport;
import io.cassyx.bulk.api.dsbulk.DsbulkProbe;
import io.cassyx.bulk.api.dsbulk.DsbulkSetting;
import java.util.List;

/**
 * Wire shapes for the {@code bulk}-tagged operations this workstream owns, mirroring
 * {@code openapi/cassyx-api.yaml} exactly (plan section 2.3: the contract governs).
 *
 * <p>{@code dsbulkSettings} is carried as a raw {@link JsonNode} rather than a mirrored record
 * tree - see {@link DsbulkSettingsFlattener} for why. Everything else is a named record, because
 * these are the shapes the generated TypeScript client is typed against.
 */
public final class DsbulkDtos {

  private DsbulkDtos() {}

  /** Contract: {@code DerivedSetting}. The editable "auto" chip the UI renders. */
  public record DerivedSetting(
      String path,
      String value,
      boolean auto,
      String upstreamDefault,
      String rationale,
      String docsUrl,
      String group) {

    public static DerivedSetting from(DsbulkSetting setting) {
      return new DerivedSetting(
          setting.path(),
          setting.value(),
          setting.auto(),
          setting.upstreamDefault(),
          setting.rationale(),
          setting.docsUrl(),
          setting.group().contractName());
    }

    public static List<DerivedSetting> from(List<DsbulkSetting> settings) {
      return settings.stream().map(DerivedSetting::from).toList();
    }
  }

  /** Contract: {@code BulkProbeResult} - the cluster facts the derivations were computed from. */
  public record BulkProbeResult(
      int nodeCount,
      int clientCores,
      String flavour,
      boolean hasClusteringKey,
      boolean isCounterTable,
      boolean serverSideRateLimiting,
      Long estimatedRows,
      int recommendedSplits) {

    public static BulkProbeResult from(DsbulkProbe probe) {
      return new BulkProbeResult(
          probe.nodeCount(),
          probe.clientCores(),
          probe.flavour().name(),
          probe.hasClusteringKey(),
          probe.counterTable(),
          probe.serverSideRateLimiting(),
          probe.estimatedRows(),
          probe.recommendedSplits());
    }
  }

  /** Contract: {@code BulkDefaultsRequest}. */
  public record BulkDefaultsRequest(
      String operation,
      String keyspace,
      String table,
      String query,
      String format,
      JsonNode overrides) {}

  /** Contract: {@code DerivedSettingsResponse}. */
  public record DerivedSettingsResponse(
      String operation,
      String engine,
      List<DerivedSetting> settings,
      BulkProbeResult probe,
      List<String> warnings) {}

  /** Contract: {@code UnloadSink} - only the fields a DSBulk-engine job needs. */
  public record BulkSink(String type, String path, String s3Uri, String fileNamePattern, String compression) {}

  /** Contract: {@code LoadSource}. Exactly one of {@code uploadId}, {@code path}, {@code s3Uri}. */
  public record BulkSource(String uploadId, String path, String s3Uri, String format, String compression) {}

  /** Contract: {@code BulkCommandPreviewRequest}. */
  public record BulkCommandPreviewRequest(
      String operation,
      String keyspace,
      String table,
      String query,
      String format,
      BulkSink sink,
      BulkSource source,
      JsonNode dsbulkSettings) {}

  /** Contract: {@code BulkCommandPreview} - the copyable command builder output. */
  public record BulkCommandPreview(
      String command,
      List<String> argv,
      String hocon,
      List<String> maskedFields,
      List<DerivedSetting> derivedSettings) {}

  /** Contract: {@code LoadJobRequest}. */
  public record LoadJobRequest(
      String name,
      String keyspace,
      String table,
      BulkSource source,
      String mapping,
      Boolean dryRun,
      String templateId,
      JsonNode dsbulkSettings) {}

  /** Contract: {@code CountJobRequest}. */
  public record CountJobRequest(
      String name,
      String keyspace,
      String table,
      List<String> modes,
      Integer topPartitions,
      JsonNode dsbulkSettings) {}

  /** Contract: {@code BulkUpload}. */
  public record BulkUpload(
      String uploadId, String fileName, long sizeBytes, String format, String uploadedAt, String expiresAt) {}

  /** Contract: {@code JobTemplateRequest}. */
  public record JobTemplateRequest(
      String name, String description, String operation, String format, String engine, JsonNode dsbulkSettings) {}

  /** Contract: {@code JobTemplate}. */
  public record JobTemplate(
      String id,
      String name,
      String description,
      String operation,
      String format,
      String engine,
      JsonNode dsbulkSettings,
      String createdAt,
      String updatedAt) {}

  /** Contract: {@code TableStatistics}, produced by a successful {@code COUNT} job. */
  public record TableStatistics(
      SchemaIdentity identity,
      long totalRows,
      Long partitionCount,
      String computedAt,
      String jobId,
      long durationMillis,
      List<ReplicaRowCount> perReplica,
      List<TokenRangeRowCount> perTokenRange,
      List<PartitionSize> largestPartitions) {

    /** Tokens stay STRINGS: Murmur3 tokens do not survive a JavaScript number. */
    public static TableStatistics from(
        DsbulkCountReport report, String keyspace, String table, String jobId, String computedAt, long millis) {
      return new TableStatistics(
          new SchemaIdentity("TABLE", keyspace, table, keyspace + "." + table),
          report.totalRows(),
          report.largestPartitions().isEmpty() ? null : (long) report.largestPartitions().size(),
          computedAt,
          jobId,
          millis,
          report.perReplica().stream()
              .map(r -> new ReplicaRowCount(r.endpoint(), null, r.rows()))
              .toList(),
          report.perTokenRange().stream()
              .map(r -> new TokenRangeRowCount(r.start(), r.end(), r.rows(), List.of()))
              .toList(),
          report.largestPartitions().stream()
              .map(p -> new PartitionSize(p.partitionKey(), p.rows(), null))
              .toList());
    }
  }

  /** Contract: {@code SchemaIdentity}. */
  public record SchemaIdentity(String kind, String keyspace, String table, String qualifiedName) {}

  /** Contract: {@code ReplicaRowCount}. */
  public record ReplicaRowCount(String endpoint, String datacenter, long rows) {}

  /** Contract: {@code TokenRangeRowCount}. */
  public record TokenRangeRowCount(String start, String end, long rows, List<String> replicas) {}

  /** Contract: {@code PartitionSize}. */
  public record PartitionSize(String partitionKey, long rows, Long sizeBytes) {}

  /**
   * Contract: {@code Job}, restricted to what a DSBulk job populates.
   *
   * <p>Named {@code DsbulkJobView} rather than {@code Job} on purpose: the {@code Job*} types in
   * this package belong to the native-engine workstream, and two agents defining the same class is
   * a merge conflict waiting to happen. The JSON shape is the contract's {@code Job} either way.
   */
  public record DsbulkJobView(
      String id,
      String name,
      String type,
      String status,
      String engine,
      String connectionId,
      SchemaIdentity identity,
      String createdAt,
      String startedAt,
      String finishedAt,
      Long durationMillis,
      JobProgressView progress,
      List<DerivedSetting> derivedSettings,
      String eventsUrl,
      String logsUrl,
      Integer exitCode,
      TableStatistics statistics) {}

  /** Contract: {@code JobProgress}, and the payload of the SSE {@code progress} event. */
  public record JobProgressView(
      long rowsProcessed,
      Long totalRowsEstimate,
      Double percent,
      long rowsPerSecond,
      long elapsedMillis,
      long failures,
      String currentPhase) {}
}
