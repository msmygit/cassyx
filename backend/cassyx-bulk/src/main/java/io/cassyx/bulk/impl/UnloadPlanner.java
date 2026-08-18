package io.cassyx.bulk.impl;

import com.datastax.oss.driver.api.core.CqlIdentifier;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Pure CQL construction for the unload engine. No driver session, no I/O - so it is unit-testable
 * without a cluster, which is where the interesting bugs (quoting, the {@code >} / {@code <=}
 * asymmetry) actually live.
 */
public final class UnloadPlanner {

  private UnloadPlanner() {}

  /** Quotes an identifier exactly as the schema stores it (case-sensitive names keep their case). */
  public static String quote(String identifier) {
    return CqlIdentifier.fromInternal(identifier).asCql(true);
  }

  public static String qualify(String keyspace, String table) {
    return quote(keyspace) + "." + quote(table);
  }

  private static String projection(List<String> columns) {
    if (columns == null || columns.isEmpty()) {
      return "*";
    }
    return columns.stream().map(UnloadPlanner::quote).collect(Collectors.joining(", "));
  }

  /** {@code SELECT ... FROM ks.tbl} - the paging fallback of plan section 7.1. */
  public static String fullScanQuery(String keyspace, String table, List<String> columns) {
    return "SELECT " + projection(columns) + " FROM " + qualify(keyspace, table);
  }

  /**
   * {@code SELECT ... FROM ks.tbl WHERE token(pk...) > ? AND token(pk...) <= ?}
   *
   * <p>The bounds are deliberately asymmetric: <b>start-exclusive, end-inclusive</b>, matching the
   * driver's own {@code TokenRange} semantics. Making both inclusive duplicates every boundary
   * partition across two splits; making both exclusive drops it. Either way the export is wrong and
   * nothing complains.
   */
  public static String tokenRangeQuery(
      String keyspace, String table, List<String> columns, List<String> partitionKey) {
    if (partitionKey == null || partitionKey.isEmpty()) {
      throw new IllegalArgumentException(
          "Cannot build a token-range query for " + keyspace + "." + table + ": no partition key");
    }
    String tokenExpr =
        "token(" + partitionKey.stream().map(UnloadPlanner::quote).collect(Collectors.joining(", "))
            + ")";
    return fullScanQuery(keyspace, table, columns)
        + " WHERE "
        + tokenExpr
        + " > ? AND "
        + tokenExpr
        + " <= ?";
  }

  /**
   * Intersects the requested projection with the table's real columns, preserving the request's
   * order; an empty request means every column in schema order.
   */
  public static List<String> resolveColumns(List<String> tableColumns, List<String> requested) {
    if (requested == null || requested.isEmpty()) {
      return List.copyOf(tableColumns);
    }
    List<String> resolved = new ArrayList<>(requested.size());
    for (String column : requested) {
      if (!tableColumns.contains(column)) {
        throw new IllegalArgumentException("Unknown column '" + column + "'");
      }
      resolved.add(column);
    }
    return List.copyOf(resolved);
  }

  /**
   * The token-range query projects only the requested columns, but the CQL {@code token()} function
   * does not require the partition key to be selected - so nothing needs adding here. Kept as an
   * explicit note because the instinct to "add the PK to the projection" is a real one and it would
   * silently change the exported column set.
   */
  public static int splitsFor(int requested, int rangeCount) {
    return requested > 0 ? requested : Math.max(rangeCount, 1);
  }
}
