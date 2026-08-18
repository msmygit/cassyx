package io.cassyx.migrate.api;

import com.datastax.oss.driver.api.core.CqlSession;
import java.util.List;

/**
 * MySQL / SQL Server import (plan section 8): JDBC introspection, then a suggested CQL schema
 * proposing partition and clustering keys from the source primary key, then a DSBulk load.
 */
public interface JdbcImporter {

  SchemaSuggestion suggestSchema(String jdbcUrl, String table, String targetKeyspace);

  long importTable(CqlSession session, ImportRequest request);

  record SchemaSuggestion(
      String createTableCql, List<String> partitionKey, List<String> clusteringColumns) {

    public SchemaSuggestion {
      partitionKey = partitionKey == null ? List.of() : List.copyOf(partitionKey);
      clusteringColumns = clusteringColumns == null ? List.of() : List.copyOf(clusteringColumns);
    }
  }
}
