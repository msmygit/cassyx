package io.cassyx.bulk.impl;

import com.datastax.oss.driver.api.core.CqlIdentifier;
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import com.datastax.oss.driver.api.core.metadata.schema.TableMetadata;
import com.datastax.oss.driver.api.core.metadata.token.TokenRange;
import io.cassyx.bulk.api.BulkException;
import io.cassyx.bulk.api.CountEngine;
import io.cassyx.bulk.api.TokenRangeSplitter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Native count / statistics (plan section 5.4) over the same token-range plan as the unload engine.
 *
 * <p>{@code SELECT count(*)} without a token predicate is a full-cluster scan through one
 * coordinator and times out on anything real. Splitting it by token range makes each query local to
 * a replica, and {@code GROUP BY <partition key>} on the same pass yields the top-N largest
 * partitions for free - which is exactly the pre-flight estimate an export job wants, and the input
 * to the skew handling the unload engine is built around.
 */
public final class TokenRangeCountEngine implements CountEngine {

  private static final int DEFAULT_TOP_PARTITIONS = 10;

  private final TokenRangeSplitter splitter;
  private final int topPartitions;

  public TokenRangeCountEngine() {
    this(new EvenTokenRangeSplitter(), DEFAULT_TOP_PARTITIONS);
  }

  public TokenRangeCountEngine(TokenRangeSplitter splitter, int topPartitions) {
    this.splitter = splitter;
    this.topPartitions = topPartitions;
  }

  @Override
  public CountResult count(CqlSession session, String keyspace, String table) {
    TableMetadata metadata =
        session
            .getMetadata()
            .getKeyspace(CqlIdentifier.fromInternal(keyspace))
            .flatMap(ks -> ks.getTable(CqlIdentifier.fromInternal(table)))
            .orElseThrow(() -> new BulkException("Unknown table " + keyspace + "." + table));

    List<String> partitionKey =
        metadata.getPartitionKey().stream().map(c -> c.getName().asInternal()).toList();

    List<TokenRange> ranges =
        session
            .getMetadata()
            .getTokenMap()
            .map(map -> splitter.split(map.getTokenRanges(), map.getTokenRanges().size()))
            .orElse(List.of());

    if (ranges.isEmpty()) {
      // Keyspaces / no token map: one plain aggregate, accepting the coordinator cost.
      long total =
          session
              .execute(
                  SimpleStatement.newInstance(
                      "SELECT count(*) FROM " + UnloadPlanner.qualify(keyspace, table)))
              .one()
              .getLong(0);
      return new CountResult(total, Map.of(), List.of());
    }

    String cql = groupByQuery(keyspace, table, partitionKey);
    PreparedStatement prepared = session.prepare(cql);

    long total = 0;
    Map<String, Long> perRange = new LinkedHashMap<>();
    List<PartitionStat> partitions = new ArrayList<>();

    for (TokenRange range : ranges) {
      long inRange = 0;
      for (Row row :
          session.execute(
              prepared.bind().setToken(0, range.getStart()).setToken(1, range.getEnd())
                  .setRoutingToken(range.getEnd()))) {
        long rows = row.getLong(partitionKey.size());
        inRange += rows;
        partitions.add(new PartitionStat(renderKey(row, partitionKey), rows));
      }
      perRange.put(range.toString(), inRange);
      total += inRange;
      partitions =
          partitions.stream()
              .sorted(Comparator.comparingLong(PartitionStat::rows).reversed())
              .limit(topPartitions)
              .collect(Collectors.toCollection(ArrayList::new));
    }
    return new CountResult(total, perRange, List.copyOf(partitions));
  }

  /** Visible for testing. */
  public static String groupByQuery(String keyspace, String table, List<String> partitionKey) {
    if (partitionKey == null || partitionKey.isEmpty()) {
      throw new IllegalArgumentException("No partition key for " + keyspace + "." + table);
    }
    String keyList =
        partitionKey.stream().map(UnloadPlanner::quote).collect(Collectors.joining(", "));
    return "SELECT "
        + keyList
        + ", count(*) FROM "
        + UnloadPlanner.qualify(keyspace, table)
        + " WHERE token("
        + keyList
        + ") > ? AND token("
        + keyList
        + ") <= ? GROUP BY "
        + keyList;
  }

  private static String renderKey(Row row, List<String> partitionKey) {
    if (partitionKey.size() == 1) {
      return String.valueOf(CellValues.asText(row.getObject(0)));
    }
    List<String> parts = new ArrayList<>(partitionKey.size());
    for (int i = 0; i < partitionKey.size(); i++) {
      parts.add(String.valueOf(CellValues.asText(row.getObject(i))));
    }
    return "(" + String.join(", ", parts) + ")";
  }
}
