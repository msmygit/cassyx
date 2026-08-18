package io.cassyx.core.api.schema;

import com.datastax.oss.driver.api.core.CqlSession;
import java.util.List;
import java.util.Set;

/**
 * Reads the catalog from {@code session.getMetadata()} - the driver keeps a live, event-driven
 * schema cache, so this NEVER polls {@code system_schema} (plan section 4).
 *
 * <p>Every object returned carries its own {@link SchemaIdentity}.
 */
public interface SchemaReader {

  SchemaTreeSnapshot tree(CqlSession session, String connectionId, boolean includeSystem);

  SchemaSearchResult search(
      CqlSession session, String query, Set<SchemaObjectKind> kinds, boolean includeSystem, int limit);

  List<KeyspaceInfo> keyspaces(CqlSession session, boolean includeSystem);

  KeyspaceInfo keyspace(CqlSession session, String keyspace);

  List<TableDetail> tables(CqlSession session, String keyspace);

  TableDetail table(CqlSession session, String keyspace, String table);

  TableInfo tableInfo(CqlSession session, String keyspace, String table, boolean statisticsAvailable);

  List<ColumnInfo> columns(CqlSession session, String keyspace, String table);

  List<IndexInfo> indexes(CqlSession session, String keyspace, String table);

  List<MaterializedViewInfo> views(CqlSession session, String keyspace);

  MaterializedViewInfo view(CqlSession session, String keyspace, String view);

  List<UserDefinedTypeInfo> types(CqlSession session, String keyspace);

  UserDefinedTypeInfo type(CqlSession session, String keyspace, String name);

  List<UserDefinedFunctionInfo> functions(CqlSession session, String keyspace);

  UserDefinedFunctionInfo function(CqlSession session, String keyspace, String signature);

  List<UserDefinedAggregateInfo> aggregates(CqlSession session, String keyspace);

  UserDefinedAggregateInfo aggregate(CqlSession session, String keyspace, String signature);

  /**
   * {@code describe} for any object - {@code TableMetadata#describe(true)} and its siblings.
   *
   * <p>Requires driver 4.19.0 for correct {@code vector<float, N>} rendering: earlier 4.x patches
   * emit invalid CQL for vector columns (CASSJAVA-2, plan section 4).
   */
  String describe(CqlSession session, SchemaIdentity identity, boolean withChildren, boolean formatted);

  /** True for {@code system*}, {@code dse_*} and the other vendor-internal keyspaces. */
  static boolean isSystemKeyspace(String name) {
    return SystemKeyspaces.matches(name);
  }
}
