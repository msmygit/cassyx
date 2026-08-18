package io.cassyx.core.api.schema;

/**
 * Generates CQL from structured definitions. A PURE FUNCTION - it never touches the cluster and
 * never executes anything.
 *
 * <p>This is what powers the "Preview CQL" pane every visual editor must show. The generated
 * statement is always displayed and always editable before execution (plan section 4).
 */
public interface DdlGenerator {

  DdlPreview createKeyspace(KeyspaceDefinition definition);

  DdlPreview alterKeyspace(String keyspace, KeyspaceDefinition definition);

  DdlPreview dropKeyspace(String keyspace, boolean ifExists);

  DdlPreview createTable(String keyspace, TableDefinition definition);

  DdlPreview alterTable(String keyspace, String table, TableOptions options);

  DdlPreview dropTable(String keyspace, String table, boolean ifExists);

  DdlPreview truncateTable(String keyspace, String table);

  DdlPreview addColumn(String keyspace, String table, ColumnDefinition definition);

  DdlPreview alterColumn(String keyspace, String table, String column, ColumnAlteration alteration);

  DdlPreview dropColumn(String keyspace, String table, String column);

  DdlPreview createIndex(String keyspace, String table, IndexDefinition definition);

  DdlPreview dropIndex(String keyspace, String table, String index, boolean ifExists);

  DdlPreview createMaterializedView(String keyspace, MaterializedViewDefinition definition);

  DdlPreview alterMaterializedView(String keyspace, String view, TableOptions options);

  DdlPreview dropMaterializedView(String keyspace, String view, boolean ifExists);

  DdlPreview createType(String keyspace, UserDefinedTypeDefinition definition);

  DdlPreview alterType(String keyspace, String type, UserDefinedTypeAlteration alteration);

  DdlPreview dropType(String keyspace, String type, boolean ifExists);

  DdlPreview createFunction(String keyspace, UserDefinedFunctionDefinition definition);

  DdlPreview dropFunction(String keyspace, String signature, boolean ifExists);

  DdlPreview createAggregate(String keyspace, UserDefinedAggregateDefinition definition);

  DdlPreview dropAggregate(String keyspace, String signature, boolean ifExists);

  DdlPreview createRole(RoleDefinition definition);

  DdlPreview alterRole(String role, RoleDefinition definition);

  DdlPreview dropRole(String role, boolean ifExists);

  DdlPreview grant(PermissionChange change);

  DdlPreview revoke(PermissionChange change);
}
