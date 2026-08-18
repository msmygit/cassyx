package io.cassyx.vector.api;

import java.util.Map;

/** Full SAI lifecycle on vector and scalar columns (plan section 6). */
public interface SaiIndexManager {

  String createIndexCql(
      String keyspace,
      String table,
      String column,
      String indexName,
      SimilarityFunction similarityFunction,
      Map<String, String> options);

  String dropIndexCql(String keyspace, String indexName);
}
