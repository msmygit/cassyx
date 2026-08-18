package io.cassyx.vector.api;

import com.datastax.oss.driver.api.core.CqlSession;
import java.util.List;
import java.util.Map;

/**
 * Full SAI lifecycle - create / alter / drop / check - on vector <em>and</em> scalar columns
 * (plan section 6).
 *
 * <p>Every method that produces DDL returns a string rather than executing it. The generated
 * statement is always shown in the "Preview CQL" pane and is editable before execution; DDL is
 * never executed silently (plan section 4).
 */
public interface SaiIndexManager {

  /** {@code CREATE CUSTOM INDEX ... USING 'StorageAttachedIndex' WITH OPTIONS = {...}}. */
  String createIndexCql(String keyspace, String table, SaiIndexDefinition definition);

  /** Convenience overload for a vector index with no extra options. */
  default String createIndexCql(
      String keyspace,
      String table,
      String column,
      String indexName,
      SimilarityFunction similarityFunction,
      Map<String, String> options) {
    return createIndexCql(
        keyspace,
        table,
        SaiIndexDefinition.builder(indexName, column)
            .similarityFunction(similarityFunction)
            .options(options)
            .build());
  }

  /**
   * Cassandra has no {@code ALTER INDEX}, so an alter is a drop-and-recreate pair returned together
   * for preview. Nothing is executed until the user confirms.
   */
  List<String> alterIndexCql(String keyspace, String table, SaiIndexDefinition definition);

  String dropIndexCql(String keyspace, String indexName, boolean ifExists);

  /** {@code DROP INDEX IF EXISTS ks.name}. */
  default String dropIndexCql(String keyspace, String indexName) {
    return dropIndexCql(keyspace, indexName, true);
  }

  /** Every SAI index on a table - vector and scalar alike - read from driver schema metadata. */
  List<SaiIndexDescriptor> list(CqlSession session, String keyspace, String table);

  /**
   * Build state of one index, aggregated across replicas.
   *
   * <p>SAI builds per node, so this reads {@code system."IndexInfo"} on every node rather than
   * trusting the coordinator: an index that is queryable on the coordinator and still building on a
   * replica silently returns short ANN result sets.
   */
  SaiIndexStatus status(CqlSession session, String keyspace, String table, String indexName);
}
