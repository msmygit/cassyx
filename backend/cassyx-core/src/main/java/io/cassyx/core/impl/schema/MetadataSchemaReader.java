package io.cassyx.core.impl.schema;

import com.datastax.oss.driver.api.core.CqlIdentifier;
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.metadata.schema.AggregateMetadata;
import com.datastax.oss.driver.api.core.metadata.schema.ColumnMetadata;
import com.datastax.oss.driver.api.core.metadata.schema.Describable;
import com.datastax.oss.driver.api.core.metadata.schema.FunctionMetadata;
import com.datastax.oss.driver.api.core.metadata.schema.IndexMetadata;
import com.datastax.oss.driver.api.core.metadata.schema.KeyspaceMetadata;
import com.datastax.oss.driver.api.core.metadata.schema.RelationMetadata;
import com.datastax.oss.driver.api.core.metadata.schema.TableMetadata;
import com.datastax.oss.driver.api.core.metadata.schema.ViewMetadata;
import com.datastax.oss.driver.api.core.type.DataType;
import com.datastax.oss.driver.api.core.type.UserDefinedType;
import com.datastax.oss.driver.api.core.type.VectorType;
import io.cassyx.core.api.schema.ClusteringKeyColumn;
import io.cassyx.core.api.schema.ColumnInfo;
import io.cassyx.core.api.schema.ColumnKind;
import io.cassyx.core.api.schema.FunctionArgument;
import io.cassyx.core.api.schema.IndexInfo;
import io.cassyx.core.api.schema.IndexKind;
import io.cassyx.core.api.schema.KeyspaceInfo;
import io.cassyx.core.api.schema.MaterializedViewInfo;
import io.cassyx.core.api.schema.PrimaryKeyDefinition;
import io.cassyx.core.api.schema.ReplicationSettings;
import io.cassyx.core.api.schema.ReplicationStrategy;
import io.cassyx.core.api.schema.SchemaIdentity;
import io.cassyx.core.api.schema.SchemaNode;
import io.cassyx.core.api.schema.SchemaNotFoundException;
import io.cassyx.core.api.schema.SchemaObjectKind;
import io.cassyx.core.api.schema.SchemaReader;
import io.cassyx.core.api.schema.SchemaSearchMatch;
import io.cassyx.core.api.schema.SchemaSearchResult;
import io.cassyx.core.api.schema.SchemaTreeSnapshot;
import io.cassyx.core.api.schema.SearchMatchKind;
import io.cassyx.core.api.schema.TableDetail;
import io.cassyx.core.api.schema.TableInfo;
import io.cassyx.core.api.schema.UdfNullHandling;
import io.cassyx.core.api.schema.UserDefinedAggregateInfo;
import io.cassyx.core.api.schema.UserDefinedFunctionInfo;
import io.cassyx.core.api.schema.UserDefinedTypeField;
import io.cassyx.core.api.schema.UserDefinedTypeInfo;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Reads the catalog from the driver's live, event-driven metadata cache.
 *
 * <p>There is deliberately not one {@code system_schema} query in this class (plan section 4). The
 * driver already maintains the cache and pushes schema-change events into it; polling would be both
 * slower and racier.
 *
 * <p>Every object is stamped with its own {@link SchemaIdentity} at the point it is read, so no
 * consumer downstream can resolve a keyspace from tree position.
 */
public final class MetadataSchemaReader implements SchemaReader {

  @Override
  public SchemaTreeSnapshot tree(CqlSession session, String connectionId, boolean includeSystem) {
    List<SchemaNode> keyspaces = new ArrayList<>();
    for (KeyspaceMetadata keyspace : sortedKeyspaces(session)) {
      String name = keyspace.getName().asInternal();
      boolean system = SchemaReader.isSystemKeyspace(name);
      if (system && !includeSystem) {
        continue;
      }
      keyspaces.add(keyspaceNode(keyspace, name, system));
    }
    return new SchemaTreeSnapshot(
        connectionId,
        Instant.now(),
        includeSystem,
        schemaVersion(session),
        keyspaces);
  }

  private SchemaNode keyspaceNode(KeyspaceMetadata keyspace, String name, boolean system) {
    List<SchemaNode> children = new ArrayList<>();
    for (TableMetadata table : sorted(keyspace.getTables().values(), t -> t.getName().asInternal())) {
      children.add(tableNode(name, table, system));
    }
    for (ViewMetadata view : sorted(keyspace.getViews().values(), v -> v.getName().asInternal())) {
      children.add(
          SchemaNode.leaf(
              SchemaIdentity.view(name, view.getName().asInternal()),
              view.getName().asInternal(),
              SchemaObjectKind.VIEW,
              system,
              "view on " + view.getBaseTable().asInternal()));
    }
    for (UserDefinedType type :
        sorted(keyspace.getUserDefinedTypes().values(), t -> t.getName().asInternal())) {
      children.add(
          SchemaNode.leaf(
              SchemaIdentity.type(name, type.getName().asInternal()),
              type.getName().asInternal(),
              SchemaObjectKind.TYPE,
              system,
              type.getFieldNames().size() + " fields"));
    }
    for (FunctionMetadata function : keyspace.getFunctions().values()) {
      String functionName = function.getSignature().getName().asInternal();
      children.add(
          SchemaNode.leaf(
              SchemaIdentity.function(name, functionName, signature(function)),
              functionName + signature(function),
              SchemaObjectKind.FUNCTION,
              system,
              function.getReturnType().asCql(true, true)));
    }
    for (AggregateMetadata aggregate : keyspace.getAggregates().values()) {
      String aggregateName = aggregate.getSignature().getName().asInternal();
      children.add(
          SchemaNode.leaf(
              SchemaIdentity.aggregate(name, aggregateName, signature(aggregate)),
              aggregateName + signature(aggregate),
              SchemaObjectKind.AGGREGATE,
              system,
              aggregate.getReturnType().asCql(true, true)));
    }
    return new SchemaNode(
        SchemaIdentity.keyspace(name),
        name,
        SchemaObjectKind.KEYSPACE,
        system,
        keyspace.getTables().size() + " tables",
        children);
  }

  private SchemaNode tableNode(String keyspace, TableMetadata table, boolean system) {
    String tableName = table.getName().asInternal();
    List<SchemaNode> children = new ArrayList<>();
    for (ColumnInfo column : columnsOf(keyspace, tableName, table)) {
      children.add(
          SchemaNode.leaf(
              column.identity(),
              column.name(),
              SchemaObjectKind.COLUMN,
              system,
              // Parsed by the tree renderer: "<cql type> | <column kind>".
              column.type() + " | " + column.kind()));
    }
    for (IndexInfo index : indexesOf(keyspace, tableName, table)) {
      children.add(
          SchemaNode.leaf(
              index.identity(),
              index.name(),
              SchemaObjectKind.INDEX,
              system,
              index.kind() + " on " + index.target()));
    }
    return new SchemaNode(
        SchemaIdentity.table(keyspace, tableName),
        tableName,
        SchemaObjectKind.TABLE,
        system,
        table.getPartitionKey().size()
            + " partition keys - "
            + table.getColumns().size()
            + " columns",
        children);
  }

  /* --------------------------------------------------------------------- search */

  @Override
  public SchemaSearchResult search(
      CqlSession session,
      String query,
      Set<SchemaObjectKind> kinds,
      boolean includeSystem,
      int limit) {
    String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
    if (needle.isEmpty()) {
      return new SchemaSearchResult(query, false, List.of());
    }
    int cap = limit <= 0 ? 100 : limit;
    List<SchemaSearchMatch> matches = new ArrayList<>();
    boolean truncated = false;

    for (KeyspaceMetadata keyspace : sortedKeyspaces(session)) {
      String keyspaceName = keyspace.getName().asInternal();
      if (SchemaReader.isSystemKeyspace(keyspaceName) && !includeSystem) {
        continue;
      }
      for (SchemaSearchMatch candidate : candidates(keyspace, keyspaceName, needle, kinds)) {
        if (matches.size() >= cap) {
          truncated = true;
          break;
        }
        matches.add(candidate);
      }
      if (truncated) {
        break;
      }
    }
    return new SchemaSearchResult(query, truncated, matches);
  }

  private List<SchemaSearchMatch> candidates(
      KeyspaceMetadata keyspace, String keyspaceName, String needle, Set<SchemaObjectKind> kinds) {
    List<SchemaSearchMatch> found = new ArrayList<>();
    if (wanted(kinds, SchemaObjectKind.KEYSPACE) && contains(keyspaceName, needle)) {
      found.add(
          new SchemaSearchMatch(
              SchemaIdentity.keyspace(keyspaceName),
              keyspaceName,
              SchemaObjectKind.KEYSPACE,
              SearchMatchKind.NAME,
              keyspaceName));
    }
    for (TableMetadata table : sorted(keyspace.getTables().values(), t -> t.getName().asInternal())) {
      String tableName = table.getName().asInternal();
      String context = keyspaceName + "." + tableName;
      if (wanted(kinds, SchemaObjectKind.TABLE)) {
        if (contains(tableName, needle)) {
          found.add(
              new SchemaSearchMatch(
                  SchemaIdentity.table(keyspaceName, tableName),
                  tableName,
                  SchemaObjectKind.TABLE,
                  SearchMatchKind.NAME,
                  context));
        } else if (contains(comment(table), needle)) {
          found.add(
              new SchemaSearchMatch(
                  SchemaIdentity.table(keyspaceName, tableName),
                  tableName,
                  SchemaObjectKind.TABLE,
                  SearchMatchKind.COMMENT,
                  context));
        }
      }
      if (wanted(kinds, SchemaObjectKind.COLUMN)) {
        for (ColumnInfo column : columnsOf(keyspaceName, tableName, table)) {
          if (contains(column.name(), needle)) {
            found.add(
                new SchemaSearchMatch(
                    column.identity(), column.name(), SchemaObjectKind.COLUMN, SearchMatchKind.NAME, context));
          } else if (contains(column.type(), needle)) {
            found.add(
                new SchemaSearchMatch(
                    column.identity(), column.name(), SchemaObjectKind.COLUMN, SearchMatchKind.TYPE, context));
          }
        }
      }
      if (wanted(kinds, SchemaObjectKind.INDEX)) {
        for (IndexInfo index : indexesOf(keyspaceName, tableName, table)) {
          if (contains(index.name(), needle)) {
            found.add(
                new SchemaSearchMatch(
                    index.identity(), index.name(), SchemaObjectKind.INDEX, SearchMatchKind.NAME, context));
          } else if (contains(index.target(), needle)) {
            found.add(
                new SchemaSearchMatch(
                    index.identity(), index.name(), SchemaObjectKind.INDEX, SearchMatchKind.TARGET, context));
          }
        }
      }
    }
    if (wanted(kinds, SchemaObjectKind.VIEW)) {
      for (ViewMetadata view : sorted(keyspace.getViews().values(), v -> v.getName().asInternal())) {
        String viewName = view.getName().asInternal();
        if (contains(viewName, needle)) {
          found.add(
              new SchemaSearchMatch(
                  SchemaIdentity.view(keyspaceName, viewName),
                  viewName,
                  SchemaObjectKind.VIEW,
                  SearchMatchKind.NAME,
                  keyspaceName + "." + view.getBaseTable().asInternal()));
        }
      }
    }
    if (wanted(kinds, SchemaObjectKind.TYPE)) {
      for (UserDefinedType type :
          sorted(keyspace.getUserDefinedTypes().values(), t -> t.getName().asInternal())) {
        String typeName = type.getName().asInternal();
        if (contains(typeName, needle)) {
          found.add(
              new SchemaSearchMatch(
                  SchemaIdentity.type(keyspaceName, typeName),
                  typeName,
                  SchemaObjectKind.TYPE,
                  SearchMatchKind.NAME,
                  keyspaceName));
        }
      }
    }
    if (wanted(kinds, SchemaObjectKind.FUNCTION)) {
      for (FunctionMetadata function : keyspace.getFunctions().values()) {
        String functionName = function.getSignature().getName().asInternal();
        if (contains(functionName, needle)) {
          found.add(
              new SchemaSearchMatch(
                  SchemaIdentity.function(keyspaceName, functionName, signature(function)),
                  functionName + signature(function),
                  SchemaObjectKind.FUNCTION,
                  SearchMatchKind.NAME,
                  keyspaceName));
        }
      }
    }
    if (wanted(kinds, SchemaObjectKind.AGGREGATE)) {
      for (AggregateMetadata aggregate : keyspace.getAggregates().values()) {
        String aggregateName = aggregate.getSignature().getName().asInternal();
        if (contains(aggregateName, needle)) {
          found.add(
              new SchemaSearchMatch(
                  SchemaIdentity.aggregate(keyspaceName, aggregateName, signature(aggregate)),
                  aggregateName + signature(aggregate),
                  SchemaObjectKind.AGGREGATE,
                  SearchMatchKind.NAME,
                  keyspaceName));
        }
      }
    }
    return found;
  }

  private static boolean wanted(Set<SchemaObjectKind> kinds, SchemaObjectKind kind) {
    return kinds == null || kinds.isEmpty() || kinds.contains(kind);
  }

  private static boolean contains(String haystack, String needle) {
    return haystack != null && haystack.toLowerCase(Locale.ROOT).contains(needle);
  }

  /* ------------------------------------------------------------------ keyspaces */

  @Override
  public List<KeyspaceInfo> keyspaces(CqlSession session, boolean includeSystem) {
    List<KeyspaceInfo> result = new ArrayList<>();
    for (KeyspaceMetadata keyspace : sortedKeyspaces(session)) {
      KeyspaceInfo info = toKeyspaceInfo(keyspace);
      if (includeSystem || !info.system()) {
        result.add(info);
      }
    }
    return List.copyOf(result);
  }

  @Override
  public KeyspaceInfo keyspace(CqlSession session, String keyspace) {
    return toKeyspaceInfo(requireKeyspace(session, keyspace));
  }

  private KeyspaceInfo toKeyspaceInfo(KeyspaceMetadata keyspace) {
    String name = keyspace.getName().asInternal();
    return new KeyspaceInfo(
        SchemaIdentity.keyspace(name),
        name,
        toReplication(keyspace.getReplication()),
        keyspace.isDurableWrites(),
        SchemaReader.isSystemKeyspace(name),
        keyspace.getTables().size(),
        keyspace.getViews().size(),
        keyspace.getUserDefinedTypes().size(),
        keyspace.getFunctions().size(),
        keyspace.getAggregates().size());
  }

  static ReplicationSettings toReplication(Map<String, String> raw) {
    String strategyClass = raw.getOrDefault("class", "");
    String simpleName = strategyClass.substring(strategyClass.lastIndexOf('.') + 1);
    ReplicationStrategy strategy;
    try {
      strategy = ReplicationStrategy.valueOf(simpleName);
    } catch (IllegalArgumentException e) {
      strategy = ReplicationStrategy.SimpleStrategy;
    }
    Integer replicationFactor = parseInt(raw.get("replication_factor"));
    Map<String, Integer> datacenters = new java.util.LinkedHashMap<>();
    for (Map.Entry<String, String> entry : raw.entrySet()) {
      if ("class".equals(entry.getKey()) || "replication_factor".equals(entry.getKey())) {
        continue;
      }
      Integer value = parseInt(entry.getValue());
      if (value != null) {
        datacenters.put(entry.getKey(), value);
      }
    }
    return new ReplicationSettings(strategy, replicationFactor, datacenters);
  }

  private static Integer parseInt(String value) {
    if (value == null) {
      return null;
    }
    try {
      return Integer.valueOf(value.trim());
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /* --------------------------------------------------------------------- tables */

  @Override
  public List<TableDetail> tables(CqlSession session, String keyspace) {
    KeyspaceMetadata metadata = requireKeyspace(session, keyspace);
    List<TableDetail> result = new ArrayList<>();
    for (TableMetadata table : sorted(metadata.getTables().values(), t -> t.getName().asInternal())) {
      result.add(toTableDetail(metadata, keyspace, table));
    }
    return List.copyOf(result);
  }

  @Override
  public TableDetail table(CqlSession session, String keyspace, String table) {
    KeyspaceMetadata metadata = requireKeyspace(session, keyspace);
    return toTableDetail(metadata, keyspace, requireTable(metadata, keyspace, table));
  }

  private TableDetail toTableDetail(
      KeyspaceMetadata keyspaceMetadata, String keyspace, TableMetadata table) {
    String name = table.getName().asInternal();
    List<ColumnInfo> columns = columnsOf(keyspace, name, table);
    List<String> viewNames = new ArrayList<>();
    for (ViewMetadata view : keyspaceMetadata.getViews().values()) {
      if (view.getBaseTable().asInternal().equals(name)) {
        viewNames.add(view.getName().asInternal());
      }
    }
    return new TableDetail(
        SchemaIdentity.table(keyspace, name),
        name,
        keyspace,
        columns,
        primaryKeyOf(table),
        TableOptionsMapper.fromMetadata(table.getOptions()),
        indexesOf(keyspace, name, table),
        viewNames,
        table.isVirtual(),
        SchemaReader.isSystemKeyspace(keyspace),
        columns.stream().anyMatch(ColumnInfo::counter),
        columns.stream().anyMatch(ColumnInfo::vector));
  }

  @Override
  public TableInfo tableInfo(
      CqlSession session, String keyspace, String table, boolean statisticsAvailable) {
    KeyspaceMetadata metadata = requireKeyspace(session, keyspace);
    TableMetadata tableMetadata = requireTable(metadata, keyspace, table);
    String name = tableMetadata.getName().asInternal();
    List<SchemaIdentity> views = new ArrayList<>();
    for (ViewMetadata view : metadata.getViews().values()) {
      if (view.getBaseTable().asInternal().equals(name)) {
        views.add(SchemaIdentity.view(keyspace, view.getName().asInternal()));
      }
    }
    return new TableInfo(
        SchemaIdentity.table(keyspace, name),
        columnsOf(keyspace, name, tableMetadata),
        indexesOf(keyspace, name, tableMetadata),
        comment(tableMetadata),
        tableMetadata.describeWithChildren(true),
        views,
        statisticsAvailable);
  }

  @Override
  public List<ColumnInfo> columns(CqlSession session, String keyspace, String table) {
    KeyspaceMetadata metadata = requireKeyspace(session, keyspace);
    return columnsOf(keyspace, table, requireTable(metadata, keyspace, table));
  }

  @Override
  public List<IndexInfo> indexes(CqlSession session, String keyspace, String table) {
    KeyspaceMetadata metadata = requireKeyspace(session, keyspace);
    return indexesOf(keyspace, table, requireTable(metadata, keyspace, table));
  }

  private static String comment(TableMetadata table) {
    Object comment = table.getOptions().get(CqlIdentifier.fromInternal("comment"));
    return comment == null ? "" : comment.toString();
  }

  private static PrimaryKeyDefinition primaryKeyOf(RelationMetadata table) {
    List<String> partitionKey =
        table.getPartitionKey().stream().map(c -> c.getName().asInternal()).toList();
    List<ClusteringKeyColumn> clustering = new ArrayList<>();
    table
        .getClusteringColumns()
        .forEach(
            (column, order) ->
                clustering.add(
                    new ClusteringKeyColumn(
                        column.getName().asInternal(),
                        order == com.datastax.oss.driver.api.core.metadata.schema.ClusteringOrder.DESC
                            ? io.cassyx.core.api.schema.ClusteringOrder.DESC
                            : io.cassyx.core.api.schema.ClusteringOrder.ASC)));
    return new PrimaryKeyDefinition(partitionKey, clustering);
  }

  private List<ColumnInfo> columnsOf(String keyspace, String table, RelationMetadata metadata) {
    Set<String> indexedColumns = new LinkedHashSet<>();
    if (metadata instanceof TableMetadata tableMetadata) {
      for (IndexMetadata index : tableMetadata.getIndexes().values()) {
        indexedColumns.add(baseColumnOfTarget(index.getTarget()));
      }
    }
    List<ColumnInfo> columns = new ArrayList<>();
    List<String> partitionKey =
        metadata.getPartitionKey().stream().map(c -> c.getName().asInternal()).toList();
    Map<ColumnMetadata, com.datastax.oss.driver.api.core.metadata.schema.ClusteringOrder> clustering =
        metadata.getClusteringColumns();
    List<String> clusteringNames =
        clustering.keySet().stream().map(c -> c.getName().asInternal()).toList();

    for (ColumnMetadata column : metadata.getColumns().values()) {
      String name = column.getName().asInternal();
      DataType type = column.getType();
      ColumnKind kind;
      int position;
      io.cassyx.core.api.schema.ClusteringOrder order = null;
      if (partitionKey.contains(name)) {
        kind = ColumnKind.PARTITION_KEY;
        position = partitionKey.indexOf(name);
      } else if (clusteringNames.contains(name)) {
        kind = ColumnKind.CLUSTERING;
        position = clusteringNames.indexOf(name);
        order =
            clustering.get(column)
                    == com.datastax.oss.driver.api.core.metadata.schema.ClusteringOrder.DESC
                ? io.cassyx.core.api.schema.ClusteringOrder.DESC
                : io.cassyx.core.api.schema.ClusteringOrder.ASC;
      } else {
        kind = column.isStatic() ? ColumnKind.STATIC : ColumnKind.REGULAR;
        position = -1;
      }
      columns.add(
          new ColumnInfo(
              SchemaIdentity.column(keyspace, table, name),
              name,
              type.asCql(true, true),
              kind,
              position,
              order,
              CqlTypes.isFrozen(type),
              CqlTypes.isCollection(type),
              CqlTypes.isCounter(type),
              type instanceof VectorType,
              type instanceof VectorType vector ? vector.getDimensions() : null,
              null,
              indexedColumns.contains(name)));
    }
    return List.copyOf(columns);
  }

  private List<IndexInfo> indexesOf(String keyspace, String table, TableMetadata metadata) {
    List<IndexInfo> indexes = new ArrayList<>();
    for (IndexMetadata index : metadata.getIndexes().values()) {
      String className = index.getClassName().orElse(null);
      indexes.add(
          new IndexInfo(
              SchemaIdentity.index(keyspace, table, index.getName().asInternal()),
              index.getName().asInternal(),
              index.getTarget(),
              indexKind(index, className),
              className,
              index.getOptions()));
    }
    indexes.sort(Comparator.comparing(IndexInfo::name));
    return List.copyOf(indexes);
  }

  static IndexKind indexKind(IndexMetadata index, String className) {
    if (className != null) {
      if (className.contains("StorageAttachedIndex")) {
        return IndexKind.SAI;
      }
      if (className.contains("SolrSecondaryIndex")) {
        return IndexKind.DSE_SEARCH;
      }
      return IndexKind.CUSTOM;
    }
    return switch (index.getKind()) {
      case KEYS -> IndexKind.KEYS;
      case CUSTOM -> IndexKind.CUSTOM;
      default -> IndexKind.COMPOSITES;
    };
  }

  /** {@code values(tags)} / {@code keys(tags)} -> {@code tags}. */
  static String baseColumnOfTarget(String target) {
    if (target == null) {
      return "";
    }
    int open = target.indexOf('(');
    if (open < 0 || !target.endsWith(")")) {
      return target;
    }
    String inner = target.substring(open + 1, target.length() - 1).trim();
    return inner.startsWith("\"") && inner.endsWith("\"") && inner.length() > 1
        ? inner.substring(1, inner.length() - 1)
        : inner;
  }

  /* ---------------------------------------------------------------------- views */

  @Override
  public List<MaterializedViewInfo> views(CqlSession session, String keyspace) {
    KeyspaceMetadata metadata = requireKeyspace(session, keyspace);
    List<MaterializedViewInfo> result = new ArrayList<>();
    for (ViewMetadata view : sorted(metadata.getViews().values(), v -> v.getName().asInternal())) {
      result.add(toViewInfo(keyspace, view));
    }
    return List.copyOf(result);
  }

  @Override
  public MaterializedViewInfo view(CqlSession session, String keyspace, String view) {
    KeyspaceMetadata metadata = requireKeyspace(session, keyspace);
    ViewMetadata found =
        metadata
            .getView(CqlIdentifier.fromInternal(view))
            .orElseThrow(
                () ->
                    new SchemaNotFoundException(
                        "No materialized view " + keyspace + "." + view + " in this cluster.",
                        SchemaIdentity.view(keyspace, view)));
    return toViewInfo(keyspace, found);
  }

  private MaterializedViewInfo toViewInfo(String keyspace, ViewMetadata view) {
    String name = view.getName().asInternal();
    return new MaterializedViewInfo(
        SchemaIdentity.view(keyspace, name),
        name,
        SchemaIdentity.table(keyspace, view.getBaseTable().asInternal()),
        columnsOf(keyspace, name, view),
        primaryKeyOf(view),
        view.getWhereClause().orElse(null),
        view.includesAllColumns(),
        TableOptionsMapper.fromMetadata(view.getOptions()));
  }

  /* ----------------------------------------------------------------------- UDTs */

  @Override
  public List<UserDefinedTypeInfo> types(CqlSession session, String keyspace) {
    KeyspaceMetadata metadata = requireKeyspace(session, keyspace);
    List<UserDefinedTypeInfo> result = new ArrayList<>();
    for (UserDefinedType type :
        sorted(metadata.getUserDefinedTypes().values(), t -> t.getName().asInternal())) {
      result.add(toTypeInfo(metadata, keyspace, type));
    }
    return List.copyOf(result);
  }

  @Override
  public UserDefinedTypeInfo type(CqlSession session, String keyspace, String name) {
    KeyspaceMetadata metadata = requireKeyspace(session, keyspace);
    UserDefinedType type =
        metadata
            .getUserDefinedType(CqlIdentifier.fromInternal(name))
            .orElseThrow(
                () ->
                    new SchemaNotFoundException(
                        "No type " + keyspace + "." + name + " in this cluster.",
                        SchemaIdentity.type(keyspace, name)));
    return toTypeInfo(metadata, keyspace, type);
  }

  private UserDefinedTypeInfo toTypeInfo(
      KeyspaceMetadata metadata, String keyspace, UserDefinedType type) {
    String name = type.getName().asInternal();
    List<UserDefinedTypeField> fields = new ArrayList<>();
    for (int i = 0; i < type.getFieldNames().size(); i++) {
      fields.add(
          new UserDefinedTypeField(
              type.getFieldNames().get(i).asInternal(),
              type.getFieldTypes().get(i).asCql(true, true)));
    }
    List<SchemaIdentity> usedBy = new ArrayList<>();
    for (TableMetadata table : metadata.getTables().values()) {
      for (ColumnMetadata column : table.getColumns().values()) {
        if (CqlTypes.references(column.getType(), name)) {
          usedBy.add(
              SchemaIdentity.column(
                  keyspace, table.getName().asInternal(), column.getName().asInternal()));
        }
      }
    }
    return new UserDefinedTypeInfo(SchemaIdentity.type(keyspace, name), name, fields, usedBy);
  }

  /* ---------------------------------------------------------------- UDFs / UDAs */

  @Override
  public List<UserDefinedFunctionInfo> functions(CqlSession session, String keyspace) {
    KeyspaceMetadata metadata = requireKeyspace(session, keyspace);
    List<UserDefinedFunctionInfo> result = new ArrayList<>();
    for (FunctionMetadata function : metadata.getFunctions().values()) {
      result.add(toFunctionInfo(keyspace, function));
    }
    result.sort(Comparator.comparing(UserDefinedFunctionInfo::signature));
    return List.copyOf(result);
  }

  @Override
  public UserDefinedFunctionInfo function(CqlSession session, String keyspace, String signature) {
    return functions(session, keyspace).stream()
        .filter(function -> matchesSignature(function.name(), function.signature(), signature))
        .findFirst()
        .orElseThrow(
            () ->
                new SchemaNotFoundException(
                    "No function " + keyspace + "." + signature + " in this cluster.",
                    SchemaIdentity.function(
                        keyspace, CqlDdlGenerator.nameOfSignature(signature), signature)));
  }

  private UserDefinedFunctionInfo toFunctionInfo(String keyspace, FunctionMetadata function) {
    String name = function.getSignature().getName().asInternal();
    List<FunctionArgument> arguments = new ArrayList<>();
    for (int i = 0; i < function.getParameterNames().size(); i++) {
      arguments.add(
          new FunctionArgument(
              function.getParameterNames().get(i).asInternal(),
              function.getSignature().getParameterTypes().get(i).asCql(true, true)));
    }
    return new UserDefinedFunctionInfo(
        SchemaIdentity.function(keyspace, name, signature(function)),
        name,
        name + signature(function),
        arguments,
        function.getReturnType().asCql(true, true),
        function.getLanguage(),
        function.getBody(),
        function.isCalledOnNullInput()
            ? UdfNullHandling.CALLED_ON_NULL_INPUT
            : UdfNullHandling.RETURNS_NULL_ON_NULL_INPUT,
        false);
  }

  @Override
  public List<UserDefinedAggregateInfo> aggregates(CqlSession session, String keyspace) {
    KeyspaceMetadata metadata = requireKeyspace(session, keyspace);
    List<UserDefinedAggregateInfo> result = new ArrayList<>();
    for (AggregateMetadata aggregate : metadata.getAggregates().values()) {
      result.add(toAggregateInfo(keyspace, aggregate));
    }
    result.sort(Comparator.comparing(UserDefinedAggregateInfo::signature));
    return List.copyOf(result);
  }

  @Override
  public UserDefinedAggregateInfo aggregate(CqlSession session, String keyspace, String signature) {
    return aggregates(session, keyspace).stream()
        .filter(aggregate -> matchesSignature(aggregate.name(), aggregate.signature(), signature))
        .findFirst()
        .orElseThrow(
            () ->
                new SchemaNotFoundException(
                    "No aggregate " + keyspace + "." + signature + " in this cluster.",
                    SchemaIdentity.aggregate(
                        keyspace, CqlDdlGenerator.nameOfSignature(signature), signature)));
  }

  private UserDefinedAggregateInfo toAggregateInfo(String keyspace, AggregateMetadata aggregate) {
    String name = aggregate.getSignature().getName().asInternal();
    return new UserDefinedAggregateInfo(
        SchemaIdentity.aggregate(keyspace, name, signature(aggregate)),
        name,
        name + signature(aggregate),
        aggregate.getSignature().getParameterTypes().stream().map(t -> t.asCql(true, true)).toList(),
        aggregate.getStateFuncSignature().getName().asInternal(),
        aggregate.getStateType().asCql(true, true),
        aggregate.getFinalFuncSignature().map(s -> s.getName().asInternal()).orElse(null),
        aggregate.formatInitCond().orElse(null),
        aggregate.getReturnType().asCql(true, true));
  }

  /** Compares {@code name(t1,t2)} ignoring case and whitespace. */
  static boolean matchesSignature(String name, String fullSignature, String requested) {
    String wanted = normalise(requested);
    if (wanted.equals(normalise(fullSignature))) {
      return true;
    }
    return wanted.equals(normalise(name)) && !wanted.contains("(");
  }

  private static String normalise(String value) {
    return value == null ? "" : value.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
  }

  private static String signature(FunctionMetadata function) {
    return "("
        + String.join(
            ",",
            function.getSignature().getParameterTypes().stream().map(t -> t.asCql(true, true)).toList())
        + ")";
  }

  private static String signature(AggregateMetadata aggregate) {
    return "("
        + String.join(
            ",",
            aggregate.getSignature().getParameterTypes().stream()
                .map(t -> t.asCql(true, true))
                .toList())
        + ")";
  }

  /* ------------------------------------------------------------------- describe */

  @Override
  public String describe(
      CqlSession session, SchemaIdentity identity, boolean withChildren, boolean formatted) {
    KeyspaceMetadata keyspace = requireKeyspace(session, identity.keyspace());
    Describable describable =
        switch (identity.kind()) {
          case KEYSPACE -> keyspace;
          case TABLE -> requireTable(keyspace, identity.keyspace(), identity.table());
          case COLUMN, INDEX -> requireTable(keyspace, identity.keyspace(), identity.table());
          case VIEW ->
              keyspace
                  .getView(CqlIdentifier.fromInternal(identity.view()))
                  .orElseThrow(() -> notFound(identity));
          case TYPE ->
              keyspace
                  .getUserDefinedType(CqlIdentifier.fromInternal(identity.name()))
                  .orElseThrow(() -> notFound(identity));
          case FUNCTION -> findFunction(keyspace, identity).orElseThrow(() -> notFound(identity));
          case AGGREGATE -> findAggregate(keyspace, identity).orElseThrow(() -> notFound(identity));
          default -> throw notFound(identity);
        };
    return withChildren ? describable.describeWithChildren(formatted) : describable.describe(formatted);
  }

  private Optional<FunctionMetadata> findFunction(KeyspaceMetadata keyspace, SchemaIdentity identity) {
    String requested = identity.name() + (identity.signature() == null ? "" : identity.signature());
    return keyspace.getFunctions().values().stream()
        .filter(
            function ->
                matchesSignature(
                    function.getSignature().getName().asInternal(),
                    function.getSignature().getName().asInternal() + signature(function),
                    requested))
        .findFirst();
  }

  private Optional<AggregateMetadata> findAggregate(
      KeyspaceMetadata keyspace, SchemaIdentity identity) {
    String requested = identity.name() + (identity.signature() == null ? "" : identity.signature());
    return keyspace.getAggregates().values().stream()
        .filter(
            aggregate ->
                matchesSignature(
                    aggregate.getSignature().getName().asInternal(),
                    aggregate.getSignature().getName().asInternal() + signature(aggregate),
                    requested))
        .findFirst();
  }

  /* -------------------------------------------------------------------- helpers */

  private static SchemaNotFoundException notFound(SchemaIdentity identity) {
    return new SchemaNotFoundException("No " + identity.kind() + " " + identity.qualifiedName(), identity);
  }

  private static List<KeyspaceMetadata> sortedKeyspaces(CqlSession session) {
    return sorted(session.getMetadata().getKeyspaces().values(), k -> k.getName().asInternal());
  }

  private static <T> List<T> sorted(
      java.util.Collection<T> values, java.util.function.Function<T, String> key) {
    List<T> list = new ArrayList<>(values);
    list.sort(Comparator.comparing(key));
    return list;
  }

  private static KeyspaceMetadata requireKeyspace(CqlSession session, String keyspace) {
    if (keyspace == null || keyspace.isBlank()) {
      throw new SchemaNotFoundException("A keyspace name is required.");
    }
    return session
        .getMetadata()
        .getKeyspace(CqlIdentifier.fromInternal(keyspace))
        .orElseThrow(
            () ->
                new SchemaNotFoundException(
                    "No keyspace " + keyspace + " in this cluster.",
                    SchemaIdentity.keyspace(keyspace)));
  }

  private static TableMetadata requireTable(
      KeyspaceMetadata keyspace, String keyspaceName, String table) {
    if (table == null || table.isBlank()) {
      throw new SchemaNotFoundException("A table name is required.");
    }
    return keyspace
        .getTable(CqlIdentifier.fromInternal(table))
        .orElseThrow(
            () ->
                new SchemaNotFoundException(
                    "No table " + keyspaceName + "." + table + " in this cluster.",
                    SchemaIdentity.table(keyspaceName, table)));
  }

  private static String schemaVersion(CqlSession session) {
    return session.getMetadata().getNodes().values().stream()
        .map(node -> node.getSchemaVersion())
        .filter(java.util.Objects::nonNull)
        .map(Object::toString)
        .findFirst()
        .orElse(null);
  }
}
