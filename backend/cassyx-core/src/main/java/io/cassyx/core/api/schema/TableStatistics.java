package io.cassyx.core.api.schema;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

/**
 * A cached statistics snapshot produced by a COUNT job (plan section 5.4) - the panel the prior art
 * had an API for but never built. Absent until a count job has run for this table, at which point
 * the STATISTICS tab stops returning the contract's 404.
 *
 * <p>Two fields carry a deliberate absence rather than a number:
 *
 * <ul>
 *   <li>{@code partitionCount} is nullable and, for a DSBulk-sourced snapshot, always null. DSBulk
 *       reports the top-N largest partitions, never a total partition count; deriving one from the
 *       size of that list produces the constant N dressed up as a measurement.
 *   <li>{@code perTokenRange} / {@code perReplica} are CAPPED. A 12-node vnode cluster reports
 *       roughly 3000 token ranges, most of them empty, and shipping all of them makes the response
 *       unreadable and the table unrenderable. The {@code *Truncated} / {@code *Reported} pairs say
 *       so out loud, because a silently shortened list is indistinguishable from a small cluster.
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TableStatistics(
    SchemaIdentity identity,
    long totalRows,
    Long partitionCount,
    Instant computedAt,
    String jobId,
    Long durationMillis,
    List<ReplicaRowCount> perReplica,
    List<TokenRangeRowCount> perTokenRange,
    List<PartitionSize> largestPartitions,
    boolean perReplicaTruncated,
    Integer perReplicaReported,
    boolean perTokenRangeTruncated,
    Integer perTokenRangeReported) {

  public TableStatistics {
    perReplica = perReplica == null ? List.of() : List.copyOf(perReplica);
    perTokenRange = perTokenRange == null ? List.of() : List.copyOf(perTokenRange);
    largestPartitions = largestPartitions == null ? List.of() : List.copyOf(largestPartitions);
  }

  /**
   * Snapshot with no truncation - everything the count reported is present.
   *
   * <p>A static factory rather than a second constructor: a record with two constructors is a
   * "conflicting creators" hazard for Jackson, and this type is deserialised straight out of the
   * persisted job row.
   */
  public static TableStatistics untruncated(
      SchemaIdentity identity,
      long totalRows,
      Long partitionCount,
      Instant computedAt,
      String jobId,
      Long durationMillis,
      List<ReplicaRowCount> perReplica,
      List<TokenRangeRowCount> perTokenRange,
      List<PartitionSize> largestPartitions) {
    return new TableStatistics(
        identity,
        totalRows,
        partitionCount,
        computedAt,
        jobId,
        durationMillis,
        perReplica,
        perTokenRange,
        largestPartitions,
        false,
        perReplica == null ? 0 : perReplica.size(),
        false,
        perTokenRange == null ? 0 : perTokenRange.size());
  }
}
