package io.cassyx.core.api.schema;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

/**
 * A cached statistics snapshot produced by a COUNT job (plan section 5.4) - the panel the prior art
 * had an API for but never built. Absent until workstream E's count job has run for this table, at
 * which point the STATISTICS tab stops returning the contract's 404.
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
    List<PartitionSize> largestPartitions) {

  public TableStatistics {
    perReplica = perReplica == null ? List.of() : List.copyOf(perReplica);
    perTokenRange = perTokenRange == null ? List.of() : List.copyOf(perTokenRange);
    largestPartitions = largestPartitions == null ? List.of() : List.copyOf(largestPartitions);
  }
}
