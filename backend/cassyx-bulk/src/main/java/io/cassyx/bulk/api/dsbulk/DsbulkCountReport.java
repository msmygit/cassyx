package io.cassyx.bulk.api.dsbulk;

import java.util.List;

/**
 * Output of the DSBulk {@code count} workflow (plan section 5.4): total rows, per-replica,
 * per-token-range and the top-N largest partitions.
 *
 * <p>This is what powers the table Statistics tab that the prior-art prototype had an API for but
 * never built, and the pre-flight row estimate shown before an export job starts.
 *
 * <p>Tokens are carried as STRINGS throughout. Murmur3 tokens span the full signed 64-bit range and
 * do not survive a round trip through a JavaScript number.
 */
public record DsbulkCountReport(
    long totalRows,
    List<ReplicaCount> perReplica,
    List<RangeCount> perTokenRange,
    List<PartitionCount> largestPartitions) {

  public static final DsbulkCountReport EMPTY = new DsbulkCountReport(0, List.of(), List.of(), List.of());

  public DsbulkCountReport {
    perReplica = perReplica == null ? List.of() : List.copyOf(perReplica);
    perTokenRange = perTokenRange == null ? List.of() : List.copyOf(perTokenRange);
    largestPartitions = largestPartitions == null ? List.of() : List.copyOf(largestPartitions);
  }

  /** Rows attributed to one replica endpoint. */
  public record ReplicaCount(String endpoint, long rows) {}

  /** Rows in one token range; {@code start} exclusive, {@code end} inclusive, as DSBulk reports. */
  public record RangeCount(String start, String end, long rows) {}

  /** One of the top-N largest partitions - the skew signal that drives oversplitting. */
  public record PartitionCount(String partitionKey, long rows) {}
}
