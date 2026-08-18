package io.cassyx.core.impl.query;

import com.datastax.oss.driver.api.core.CqlIdentifier;
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.ColumnDefinition;
import com.datastax.oss.driver.api.core.cql.ColumnDefinitions;
import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.api.core.metadata.schema.ColumnMetadata;
import com.datastax.oss.driver.api.core.metadata.schema.TableMetadata;
import com.datastax.oss.driver.api.core.type.DataType;
import com.datastax.oss.driver.api.core.type.ListType;
import com.datastax.oss.driver.api.core.type.MapType;
import com.datastax.oss.driver.api.core.type.SetType;
import com.datastax.oss.driver.api.core.type.UserDefinedType;
import com.datastax.oss.driver.api.core.type.VectorType;
import io.cassyx.core.api.query.ColumnInfo;
import io.cassyx.core.api.query.CqlValueCodec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Turns driver result metadata and rows into the contract's wire shapes. */
final class ColumnMapper {

  /** Prefix of the {@code similarity_cosine} / {@code _dot_product} / {@code _euclidean} projections. */
  private static final String SIMILARITY_PREFIX = "similarity_";

  private final CqlValueCodec codec;

  ColumnMapper(CqlValueCodec codec) {
    this.codec = codec;
  }

  List<ColumnInfo> describe(CqlSession session, ColumnDefinitions definitions) {
    List<ColumnInfo> columns = new ArrayList<>();
    for (ColumnDefinition definition : definitions) {
      columns.add(describe(session, definition));
    }
    return columns;
  }

  private ColumnInfo describe(CqlSession session, ColumnDefinition definition) {
    String name = definition.getName().asInternal();
    DataType type = definition.getType();
    String keyspace = definition.getKeyspace() == null ? null : definition.getKeyspace().asInternal();
    String table = definition.getTable() == null ? null : definition.getTable().asInternal();

    Optional<ColumnMetadata> metadata = columnMetadata(session, definition);
    boolean primaryKey = metadata.map(m -> isPrimaryKey(session, definition, m)).orElse(false);
    String kind = metadata.map(m -> kindOf(session, definition, m)).orElse(null);

    Integer dimensions = type instanceof VectorType vector ? vector.getDimensions() : null;
    return new ColumnInfo(
        name,
        type.asCql(true, false),
        keyspace,
        table,
        primaryKey,
        kind,
        type instanceof ListType || type instanceof SetType || type instanceof MapType,
        type instanceof VectorType,
        dimensions,
        type instanceof UserDefinedType,
        name.toLowerCase(Locale.ROOT).startsWith(SIMILARITY_PREFIX));
  }

  private static Optional<ColumnMetadata> columnMetadata(CqlSession session, ColumnDefinition definition) {
    return tableMetadata(session, definition)
        .flatMap(table -> table.getColumn(definition.getName()));
  }

  private static Optional<TableMetadata> tableMetadata(CqlSession session, ColumnDefinition definition) {
    CqlIdentifier keyspace = definition.getKeyspace();
    CqlIdentifier table = definition.getTable();
    if (keyspace == null || table == null) {
      return Optional.empty();
    }
    return session.getMetadata().getKeyspace(keyspace).flatMap(ks -> ks.getTable(table));
  }

  private static boolean isPrimaryKey(
      CqlSession session, ColumnDefinition definition, ColumnMetadata column) {
    return tableMetadata(session, definition)
        .map(
            table ->
                table.getPartitionKey().contains(column)
                    || table.getClusteringColumns().containsKey(column))
        .orElse(false);
  }

  private static String kindOf(CqlSession session, ColumnDefinition definition, ColumnMetadata column) {
    return tableMetadata(session, definition)
        .map(
            table -> {
              if (table.getPartitionKey().contains(column)) {
                return "PARTITION_KEY";
              }
              if (table.getClusteringColumns().containsKey(column)) {
                return "CLUSTERING";
              }
              return column.isStatic() ? "STATIC" : "REGULAR";
            })
        .orElse(null);
  }

  /** Row to a wire-encoded, column-name-keyed map, preserving projection order. */
  Map<String, Object> toWireRow(Row row, List<ColumnInfo> columns) {
    Map<String, Object> values = new LinkedHashMap<>();
    for (int i = 0; i < columns.size(); i++) {
      values.put(columns.get(i).name(), codec.toWire(row.getObject(i)));
    }
    return values;
  }

  static List<String> similarityColumns(List<ColumnInfo> columns) {
    return columns.stream().filter(ColumnInfo::similarity).map(ColumnInfo::name).toList();
  }
}
