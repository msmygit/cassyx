package io.cassyx.vector.impl;

import io.cassyx.vector.api.SaiIndexManager;
import io.cassyx.vector.api.SimilarityFunction;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;

/** Reference {@link SaiIndexManager}: emits SAI DDL for review before execution. */
public final class DefaultSaiIndexManager implements SaiIndexManager {

  @Override
  public String createIndexCql(
      String keyspace,
      String table,
      String column,
      String indexName,
      SimilarityFunction similarityFunction,
      Map<String, String> options) {
    Map<String, String> withOptions = new LinkedHashMap<>();
    if (similarityFunction != null) {
      withOptions.put("similarity_function", similarityFunction.cqlValue());
    }
    if (options != null) {
      withOptions.putAll(options);
    }
    StringBuilder cql =
        new StringBuilder("CREATE CUSTOM INDEX IF NOT EXISTS ")
            .append(indexName)
            .append(" ON ")
            .append(keyspace)
            .append('.')
            .append(table)
            .append(" (")
            .append(column)
            .append(") USING 'StorageAttachedIndex'");
    if (!withOptions.isEmpty()) {
      StringJoiner joiner = new StringJoiner(", ");
      withOptions.forEach((k, v) -> joiner.add("'" + k + "': '" + v + "'"));
      cql.append(" WITH OPTIONS = {").append(joiner).append('}');
    }
    return cql.toString();
  }

  @Override
  public String dropIndexCql(String keyspace, String indexName) {
    return "DROP INDEX IF EXISTS " + keyspace + "." + indexName;
  }
}
