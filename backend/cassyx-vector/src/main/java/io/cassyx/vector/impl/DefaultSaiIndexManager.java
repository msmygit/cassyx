package io.cassyx.vector.impl;

import com.datastax.oss.driver.api.core.CqlIdentifier;
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import com.datastax.oss.driver.api.core.metadata.Node;
import com.datastax.oss.driver.api.core.metadata.schema.IndexKind;
import com.datastax.oss.driver.api.core.metadata.schema.IndexMetadata;
import com.datastax.oss.driver.api.core.metadata.schema.TableMetadata;
import com.datastax.oss.driver.api.core.type.VectorType;
import io.cassyx.vector.api.SaiIndexDefinition;
import io.cassyx.vector.api.SaiIndexDescriptor;
import io.cassyx.vector.api.SaiIndexManager;
import io.cassyx.vector.api.SaiIndexNodeStatus;
import io.cassyx.vector.api.SaiIndexState;
import io.cassyx.vector.api.SaiIndexStatus;
import io.cassyx.vector.api.SimilarityFunction;
import io.cassyx.vector.api.VectorException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Reference {@link SaiIndexManager}: emits SAI DDL for review, and reads live index state. */
public final class DefaultSaiIndexManager implements SaiIndexManager {

  private static final Logger LOG = LoggerFactory.getLogger(DefaultSaiIndexManager.class);

  /** Options that describe the index rather than configure it, so they are not re-emitted. */
  private static final List<String> STRUCTURAL_OPTIONS = List.of("class_name", "target");

  /**
   * Per-node built-index registry. Legacy naming: the {@code table_name} column actually holds the
   * KEYSPACE name.
   */
  private static final String INDEX_INFO_QUERY =
      "SELECT index_name FROM system.\"IndexInfo\" WHERE table_name = ?";

  @Override
  public String createIndexCql(String keyspace, String table, SaiIndexDefinition definition) {
    StringBuilder cql = new StringBuilder("CREATE CUSTOM INDEX ");
    if (definition.ifNotExists()) {
      cql.append("IF NOT EXISTS ");
    }
    cql.append(CqlLiterals.identifier(definition.name()))
        .append(" ON ")
        .append(CqlLiterals.qualified(keyspace, table))
        .append(" (")
        .append(CqlLiterals.indexTarget(definition.target()))
        .append(") USING '")
        .append(SaiIndexDescriptor.STORAGE_ATTACHED_INDEX)
        .append('\'');

    Map<String, String> options = definition.withOptions();
    if (!options.isEmpty()) {
      StringJoiner joiner = new StringJoiner(", ");
      options.forEach(
          (key, value) ->
              joiner.add(CqlLiterals.stringLiteral(key) + ": " + CqlLiterals.stringLiteral(value)));
      cql.append(" WITH OPTIONS = {").append(joiner).append('}');
    }
    return cql.toString();
  }

  @Override
  public List<String> alterIndexCql(String keyspace, String table, SaiIndexDefinition definition) {
    // Cassandra has no ALTER INDEX. Both statements are returned for preview; nothing runs until
    // the user confirms, because the drop leaves the column unindexed in between.
    return List.of(
        dropIndexCql(keyspace, definition.name(), true),
        createIndexCql(keyspace, table, definition));
  }

  @Override
  public String dropIndexCql(String keyspace, String indexName, boolean ifExists) {
    return "DROP INDEX "
        + (ifExists ? "IF EXISTS " : "")
        + CqlLiterals.qualified(keyspace, indexName);
  }

  @Override
  public List<SaiIndexDescriptor> list(CqlSession session, String keyspace, String table) {
    TableMetadata metadata = tableMetadata(session, keyspace, table);
    List<SaiIndexDescriptor> indexes = new ArrayList<>();
    for (IndexMetadata index : metadata.getIndexes().values()) {
      if (isStorageAttached(index)) {
        indexes.add(describe(keyspace, table, metadata, index));
      }
    }
    return List.copyOf(indexes);
  }

  @Override
  public SaiIndexStatus status(CqlSession session, String keyspace, String table, String indexName) {
    TableMetadata metadata = tableMetadata(session, keyspace, table);
    Optional<IndexMetadata> index = metadata.getIndex(CqlIdentifier.fromCql(quoted(indexName)));
    if (index.isEmpty() || !isStorageAttached(index.get())) {
      return SaiIndexStatus.unknown(keyspace, table, indexName);
    }
    SaiIndexDescriptor descriptor = describe(keyspace, table, metadata, index.get());

    List<SaiIndexNodeStatus> perNode = new ArrayList<>();
    for (Node node : session.getMetadata().getNodes().values()) {
      perNode.add(
          new SaiIndexNodeStatus(endpointOf(node), nodeState(session, node, keyspace, indexName)));
    }

    long built = perNode.stream().filter(n -> n.state() == SaiIndexState.QUERYABLE).count();
    long known = perNode.stream().filter(n -> n.state() != SaiIndexState.UNKNOWN).count();
    SaiIndexState aggregate;
    if (perNode.isEmpty() || known == 0) {
      aggregate = SaiIndexState.UNKNOWN;
    } else if (built == perNode.size()) {
      aggregate = SaiIndexState.QUERYABLE;
    } else {
      aggregate = SaiIndexState.BUILDING;
    }
    Double progress = perNode.isEmpty() ? null : (double) built * 100.0d / (double) perNode.size();

    return new SaiIndexStatus(
        keyspace,
        table,
        indexName,
        aggregate,
        aggregate == SaiIndexState.QUERYABLE,
        progress,
        perNode,
        descriptor);
  }

  /* --------------------------------------------------------------------- internals */

  private SaiIndexState nodeState(CqlSession session, Node node, String keyspace, String indexName) {
    try {
      SimpleStatement statement =
          SimpleStatement.builder(INDEX_INFO_QUERY)
              .addPositionalValue(keyspace)
              .setNode(node)
              .build();
      for (Row row : session.execute(statement)) {
        if (indexName.equals(row.getString("index_name"))) {
          return SaiIndexState.QUERYABLE;
        }
      }
      return SaiIndexState.BUILDING;
    } catch (RuntimeException e) {
      // A node that is down, or a cluster without system."IndexInfo" (Astra), must not fail the
      // whole status call - it degrades to UNKNOWN, which the UI renders as "unavailable".
      LOG.debug("Could not read SAI build state for {} from {}", indexName, node.getEndPoint(), e);
      return SaiIndexState.UNKNOWN;
    }
  }

  static boolean isStorageAttached(IndexMetadata index) {
    if (index.getKind() != IndexKind.CUSTOM) {
      return false;
    }
    return index.getClassName().orElse("").endsWith(SaiIndexDescriptor.STORAGE_ATTACHED_INDEX);
  }

  static SaiIndexDescriptor describe(
      String keyspace, String table, TableMetadata metadata, IndexMetadata index) {
    Map<String, String> options = new LinkedHashMap<>(index.getOptions());
    STRUCTURAL_OPTIONS.forEach(options::remove);

    String target = index.getTarget();
    SimilarityFunction similarity =
        options.containsKey("similarity_function")
            ? SimilarityFunction.fromCql(options.get("similarity_function"))
            : null;

    boolean vectorIndex = isVectorTarget(metadata, target);
    if (vectorIndex && similarity == null) {
      // Cassandra defaults an unspecified similarity_function to cosine; reporting null here would
      // make the ANN builder generate a score column that silently disagrees with the ranking.
      similarity = SimilarityFunction.COSINE;
    }

    return new SaiIndexDescriptor(
        keyspace,
        table,
        index.getName().asInternal(),
        target,
        vectorIndex,
        similarity,
        options.get("source_model"),
        options,
        index.getClassName().orElse(SaiIndexDescriptor.STORAGE_ATTACHED_INDEX));
  }

  private static boolean isVectorTarget(TableMetadata metadata, String target) {
    return metadata
        .getColumn(CqlIdentifier.fromCql(quoted(target)))
        .map(column -> column.getType() instanceof VectorType)
        .orElse(false);
  }

  static TableMetadata tableMetadata(CqlSession session, String keyspace, String table) {
    return session
        .getMetadata()
        .getKeyspace(CqlIdentifier.fromCql(quoted(keyspace)))
        .flatMap(ks -> ks.getTable(CqlIdentifier.fromCql(quoted(table))))
        .orElseThrow(() -> new VectorException("No table " + keyspace + "." + table));
  }

  /**
   * Wraps a raw name so {@code CqlIdentifier.fromCql} treats it as a case-sensitive literal.
   * {@code fromCql("Foo")} would otherwise be rejected, and an unquoted name would be lowercased.
   */
  static String quoted(String name) {
    if (name == null || name.isBlank()) {
      throw new VectorException("A CQL identifier is required");
    }
    return '"' + name.replace("\"", "\"\"") + '"';
  }

  private static String endpointOf(Node node) {
    return node.getBroadcastRpcAddress()
        .map(address -> address.getAddress().getHostAddress() + ":" + address.getPort())
        .orElseGet(() -> node.getEndPoint().toString());
  }
}
