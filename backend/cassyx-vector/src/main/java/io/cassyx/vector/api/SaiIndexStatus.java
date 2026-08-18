package io.cassyx.vector.api;

import java.util.List;

/**
 * Aggregated SAI index status (plan section 6: "create/alter/drop/check").
 *
 * @param buildProgressPercent share of nodes reporting the index as built, 0-100
 */
public record SaiIndexStatus(
    String keyspace,
    String table,
    String name,
    SaiIndexState state,
    boolean queryable,
    Double buildProgressPercent,
    List<SaiIndexNodeStatus> perNode,
    SaiIndexDescriptor definition) {

  public SaiIndexStatus {
    perNode = perNode == null ? List.of() : List.copyOf(perNode);
  }

  /** Status for an index that is not in the schema at all. */
  public static SaiIndexStatus unknown(String keyspace, String table, String name) {
    return new SaiIndexStatus(
        keyspace, table, name, SaiIndexState.UNKNOWN, false, null, List.of(), null);
  }
}
