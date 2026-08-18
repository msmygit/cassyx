package io.cassyx.core.impl.schema;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import com.datastax.oss.driver.api.core.CqlIdentifier;
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.metadata.Metadata;
import com.datastax.oss.driver.api.core.metadata.Node;
import com.datastax.oss.driver.api.core.metadata.schema.AggregateMetadata;
import com.datastax.oss.driver.api.core.metadata.schema.ClusteringOrder;
import com.datastax.oss.driver.api.core.metadata.schema.ColumnMetadata;
import com.datastax.oss.driver.api.core.metadata.schema.FunctionMetadata;
import com.datastax.oss.driver.api.core.metadata.schema.FunctionSignature;
import com.datastax.oss.driver.api.core.metadata.schema.IndexKind;
import com.datastax.oss.driver.api.core.metadata.schema.IndexMetadata;
import com.datastax.oss.driver.api.core.metadata.schema.KeyspaceMetadata;
import com.datastax.oss.driver.api.core.metadata.schema.TableMetadata;
import com.datastax.oss.driver.api.core.metadata.schema.ViewMetadata;
import com.datastax.oss.driver.api.core.type.DataType;
import com.datastax.oss.driver.api.core.type.DataTypes;
import com.datastax.oss.driver.api.core.type.UserDefinedType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * A hand-built driver metadata graph.
 *
 * <p>The alternative - only testing the reader against a container - would put the whole mapping
 * layer outside the JaCoCo report and behind a Docker requirement. This fixture keeps the mapping
 * covered by fast unit tests; the container integration test then proves the fixture is faithful.
 */
final class FakeSchema {

  private FakeSchema() {}

  static CqlSession session(KeyspaceMetadata... keyspaces) {
    Map<CqlIdentifier, KeyspaceMetadata> byName = new LinkedHashMap<>();
    for (KeyspaceMetadata keyspace : keyspaces) {
      byName.put(keyspace.getName(), keyspace);
    }
    Node node = mock(Node.class);
    lenient().when(node.getSchemaVersion()).thenReturn(UUID.fromString("6f8a1b2c-3d4e-4f50-a617-283949506172"));
    Metadata metadata = mock(Metadata.class);
    lenient().when(metadata.getKeyspaces()).thenReturn(byName);
    // getKeyspace(CqlIdentifier) is a default method, and a Mockito mock intercepts those too.
    lenient()
        .when(metadata.getKeyspace(org.mockito.ArgumentMatchers.any(CqlIdentifier.class)))
        .thenAnswer(call -> Optional.ofNullable(byName.get(call.getArgument(0))));
    lenient().when(metadata.getNodes()).thenReturn(Map.of(UUID.randomUUID(), node));
    CqlSession session = mock(CqlSession.class);
    lenient().when(session.getMetadata()).thenReturn(metadata);
    return session;
  }

  static KeyspaceMetadata keyspace(
      String name,
      Map<String, String> replication,
      List<TableMetadata> tables,
      List<ViewMetadata> views,
      List<UserDefinedType> types,
      List<FunctionMetadata> functions,
      List<AggregateMetadata> aggregates) {
    KeyspaceMetadata keyspace = mock(KeyspaceMetadata.class);
    lenient().when(keyspace.getName()).thenReturn(CqlIdentifier.fromInternal(name));
    lenient().when(keyspace.isDurableWrites()).thenReturn(true);
    lenient().when(keyspace.getReplication()).thenReturn(replication);
    Map<CqlIdentifier, TableMetadata> tableMap = byName(tables, TableMetadata::getName);
    Map<CqlIdentifier, ViewMetadata> viewMap = byName(views, ViewMetadata::getName);
    Map<CqlIdentifier, UserDefinedType> typeMap = byName(types, UserDefinedType::getName);
    lenient().when(keyspace.getTables()).thenReturn(tableMap);
    lenient().when(keyspace.getViews()).thenReturn(viewMap);
    lenient().when(keyspace.getUserDefinedTypes()).thenReturn(typeMap);
    lenient().when(keyspace.describe(anyBoolean())).thenReturn("CREATE KEYSPACE " + name + ";");
    lenient().when(keyspace.describeWithChildren(anyBoolean())).thenReturn("CREATE KEYSPACE " + name + ";");

    Map<FunctionSignature, FunctionMetadata> functionMap = new LinkedHashMap<>();
    functions.forEach(function -> functionMap.put(function.getSignature(), function));
    lenient().when(keyspace.getFunctions()).thenReturn(functionMap);

    Map<FunctionSignature, AggregateMetadata> aggregateMap = new LinkedHashMap<>();
    aggregates.forEach(aggregate -> aggregateMap.put(aggregate.getSignature(), aggregate));
    lenient().when(keyspace.getAggregates()).thenReturn(aggregateMap);

    lenient()
        .when(keyspace.getTable(org.mockito.ArgumentMatchers.any(CqlIdentifier.class)))
        .thenAnswer(call -> Optional.ofNullable(tableMap.get(call.getArgument(0))));
    lenient()
        .when(keyspace.getView(org.mockito.ArgumentMatchers.any(CqlIdentifier.class)))
        .thenAnswer(call -> Optional.ofNullable(viewMap.get(call.getArgument(0))));
    lenient()
        .when(keyspace.getUserDefinedType(org.mockito.ArgumentMatchers.any(CqlIdentifier.class)))
        .thenAnswer(call -> Optional.ofNullable(typeMap.get(call.getArgument(0))));
    return keyspace;
  }

  private static <T> Map<CqlIdentifier, T> byName(
      List<T> values, java.util.function.Function<T, CqlIdentifier> key) {
    Map<CqlIdentifier, T> map = new LinkedHashMap<>();
    values.forEach(value -> map.put(key.apply(value), value));
    return map;
  }

  static ColumnMetadata column(String name, DataType type, boolean isStatic) {
    ColumnMetadata column = mock(ColumnMetadata.class);
    lenient().when(column.getName()).thenReturn(CqlIdentifier.fromInternal(name));
    lenient().when(column.getType()).thenReturn(type);
    lenient().when(column.isStatic()).thenReturn(isStatic);
    return column;
  }

  static TableMetadata table(
      String name,
      List<ColumnMetadata> partitionKey,
      Map<ColumnMetadata, ClusteringOrder> clustering,
      List<ColumnMetadata> other,
      List<IndexMetadata> indexes,
      Map<CqlIdentifier, Object> options) {
    TableMetadata table = mock(TableMetadata.class);
    lenient().when(table.getName()).thenReturn(CqlIdentifier.fromInternal(name));
    lenient().when(table.getPartitionKey()).thenReturn(partitionKey);
    lenient().when(table.getClusteringColumns()).thenReturn(clustering);
    List<ColumnMetadata> all = new ArrayList<>(partitionKey);
    all.addAll(clustering.keySet());
    all.addAll(other);
    Map<CqlIdentifier, ColumnMetadata> columnMap = byName(all, ColumnMetadata::getName);
    Map<CqlIdentifier, IndexMetadata> indexMap = byName(indexes, IndexMetadata::getName);
    lenient().when(table.getColumns()).thenReturn(columnMap);
    lenient().when(table.getIndexes()).thenReturn(indexMap);
    lenient().when(table.getOptions()).thenReturn(options);
    lenient().when(table.isVirtual()).thenReturn(false);
    lenient().when(table.describe(anyBoolean())).thenReturn("CREATE TABLE " + name + ";");
    lenient()
        .when(table.describeWithChildren(anyBoolean()))
        .thenReturn("CREATE TABLE " + name + ";\nCREATE INDEX ...;");
    return table;
  }

  static ViewMetadata view(
      String name,
      String baseTable,
      List<ColumnMetadata> partitionKey,
      List<ColumnMetadata> other,
      String whereClause) {
    ViewMetadata view = mock(ViewMetadata.class);
    lenient().when(view.getName()).thenReturn(CqlIdentifier.fromInternal(name));
    lenient().when(view.getBaseTable()).thenReturn(CqlIdentifier.fromInternal(baseTable));
    lenient().when(view.getPartitionKey()).thenReturn(partitionKey);
    lenient().when(view.getClusteringColumns()).thenReturn(Map.of());
    List<ColumnMetadata> all = new ArrayList<>(partitionKey);
    all.addAll(other);
    Map<CqlIdentifier, ColumnMetadata> viewColumns = byName(all, ColumnMetadata::getName);
    lenient().when(view.getColumns()).thenReturn(viewColumns);
    lenient().when(view.getOptions()).thenReturn(Map.of());
    lenient().when(view.getWhereClause()).thenReturn(Optional.ofNullable(whereClause));
    lenient().when(view.includesAllColumns()).thenReturn(false);
    lenient().when(view.describe(anyBoolean())).thenReturn("CREATE MATERIALIZED VIEW " + name + ";");
    lenient()
        .when(view.describeWithChildren(anyBoolean()))
        .thenReturn("CREATE MATERIALIZED VIEW " + name + ";");
    return view;
  }

  static IndexMetadata index(String name, String target, IndexKind kind, String className) {
    IndexMetadata index = mock(IndexMetadata.class);
    lenient().when(index.getName()).thenReturn(CqlIdentifier.fromInternal(name));
    lenient().when(index.getTarget()).thenReturn(target);
    lenient().when(index.getKind()).thenReturn(kind);
    lenient().when(index.getClassName()).thenReturn(Optional.ofNullable(className));
    lenient().when(index.getOptions()).thenReturn(Map.of());
    return index;
  }

  static UserDefinedType udt(String name, Map<String, DataType> fields) {
    UserDefinedType type = mock(UserDefinedType.class);
    lenient().when(type.getName()).thenReturn(CqlIdentifier.fromInternal(name));
    List<CqlIdentifier> fieldNames =
        fields.keySet().stream().map(CqlIdentifier::fromInternal).toList();
    lenient().when(type.getFieldNames()).thenReturn(fieldNames);
    lenient().when(type.getFieldTypes()).thenReturn(List.copyOf(fields.values()));
    lenient().when(type.describe(anyBoolean())).thenReturn("CREATE TYPE " + name + ";");
    lenient().when(type.describeWithChildren(anyBoolean())).thenReturn("CREATE TYPE " + name + ";");
    return type;
  }

  static FunctionMetadata function(String name, DataType... parameterTypes) {
    FunctionMetadata function = mock(FunctionMetadata.class);
    FunctionSignature signature =
        new FunctionSignature(CqlIdentifier.fromInternal(name), parameterTypes);
    lenient().when(function.getSignature()).thenReturn(signature);
    List<CqlIdentifier> names = new ArrayList<>();
    for (int i = 0; i < parameterTypes.length; i++) {
      names.add(CqlIdentifier.fromInternal("arg" + i));
    }
    lenient().when(function.getParameterNames()).thenReturn(names);
    lenient().when(function.getReturnType()).thenReturn(DataTypes.DOUBLE);
    lenient().when(function.getLanguage()).thenReturn("java");
    lenient().when(function.getBody()).thenReturn("return 1;");
    lenient().when(function.isCalledOnNullInput()).thenReturn(true);
    lenient().when(function.describe(anyBoolean())).thenReturn("CREATE FUNCTION " + name + ";");
    lenient()
        .when(function.describeWithChildren(anyBoolean()))
        .thenReturn("CREATE FUNCTION " + name + ";");
    return function;
  }

  static AggregateMetadata aggregate(String name, String stateFunction, DataType... parameterTypes) {
    AggregateMetadata aggregate = mock(AggregateMetadata.class);
    FunctionSignature signature =
        new FunctionSignature(CqlIdentifier.fromInternal(name), parameterTypes);
    FunctionSignature stateSignature =
        new FunctionSignature(CqlIdentifier.fromInternal(stateFunction), parameterTypes);
    lenient().when(aggregate.getSignature()).thenReturn(signature);
    lenient().when(aggregate.getStateFuncSignature()).thenReturn(stateSignature);
    lenient().when(aggregate.getStateType()).thenReturn(DataTypes.DOUBLE);
    lenient().when(aggregate.getReturnType()).thenReturn(DataTypes.DOUBLE);
    lenient().when(aggregate.getFinalFuncSignature()).thenReturn(Optional.empty());
    lenient().when(aggregate.formatInitCond()).thenReturn(Optional.of("0.0"));
    lenient().when(aggregate.describe(anyBoolean())).thenReturn("CREATE AGGREGATE " + name + ";");
    lenient()
        .when(aggregate.describeWithChildren(anyBoolean()))
        .thenReturn("CREATE AGGREGATE " + name + ";");
    return aggregate;
  }
}
