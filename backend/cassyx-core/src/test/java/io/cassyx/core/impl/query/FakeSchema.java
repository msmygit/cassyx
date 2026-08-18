package io.cassyx.core.impl.query;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import com.datastax.oss.driver.api.core.CqlIdentifier;
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.metadata.Metadata;
import com.datastax.oss.driver.api.core.metadata.schema.ClusteringOrder;
import com.datastax.oss.driver.api.core.metadata.schema.ColumnMetadata;
import com.datastax.oss.driver.api.core.metadata.schema.KeyspaceMetadata;
import com.datastax.oss.driver.api.core.metadata.schema.TableMetadata;
import com.datastax.oss.driver.api.core.type.DataType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Builds a mocked {@link CqlSession} whose schema metadata describes one table.
 *
 * <p>Driver metadata is an interface tree, so mocking it is the only way to unit-test the
 * primary-key rules without a container. The integration suite covers the same paths against real
 * Cassandra.
 */
final class FakeSchema {

  private final String keyspace;
  private final String table;
  private final List<ColumnMetadata> partitionKey = new ArrayList<>();
  private final Map<ColumnMetadata, ClusteringOrder> clustering = new LinkedHashMap<>();
  private final Map<CqlIdentifier, ColumnMetadata> columns = new LinkedHashMap<>();

  private FakeSchema(String keyspace, String table) {
    this.keyspace = keyspace;
    this.table = table;
  }

  static FakeSchema table(String keyspace, String table) {
    return new FakeSchema(keyspace, table);
  }

  FakeSchema partitionKey(String name, DataType type) {
    ColumnMetadata column = column(name, type, false);
    partitionKey.add(column);
    return this;
  }

  FakeSchema clustering(String name, DataType type) {
    ColumnMetadata column = column(name, type, false);
    clustering.put(column, ClusteringOrder.ASC);
    return this;
  }

  FakeSchema regular(String name, DataType type) {
    column(name, type, false);
    return this;
  }

  FakeSchema staticColumn(String name, DataType type) {
    column(name, type, true);
    return this;
  }

  private ColumnMetadata column(String name, DataType type, boolean isStatic) {
    ColumnMetadata column = mock(ColumnMetadata.class);
    CqlIdentifier id = CqlIdentifier.fromInternal(name);
    lenient().when(column.getName()).thenReturn(id);
    lenient().when(column.getType()).thenReturn(type);
    lenient().when(column.isStatic()).thenReturn(isStatic);
    lenient().when(column.getKeyspace()).thenReturn(CqlIdentifier.fromInternal(keyspace));
    lenient().when(column.getParent()).thenReturn(CqlIdentifier.fromInternal(table));
    columns.put(id, column);
    return column;
  }

  TableMetadata metadata() {
    TableMetadata metadata = mock(TableMetadata.class);
    lenient().when(metadata.getName()).thenReturn(CqlIdentifier.fromInternal(table));
    lenient().when(metadata.getKeyspace()).thenReturn(CqlIdentifier.fromInternal(keyspace));
    lenient().when(metadata.getPartitionKey()).thenReturn(List.copyOf(partitionKey));
    lenient().when(metadata.getClusteringColumns()).thenReturn(Map.copyOf(clustering));
    lenient().when(metadata.getColumns()).thenReturn(Map.copyOf(columns));
    lenient().when(metadata.getPrimaryKey()).thenCallRealMethod();
    lenient()
        .when(metadata.getColumn(any(CqlIdentifier.class)))
        .thenAnswer(i -> Optional.ofNullable(columns.get(i.<CqlIdentifier>getArgument(0))));
    return metadata;
  }

  /** A session whose metadata resolves this one table and nothing else. */
  CqlSession session() {
    TableMetadata tableMetadata = metadata();
    KeyspaceMetadata keyspaceMetadata = mock(KeyspaceMetadata.class);
    lenient()
        .when(keyspaceMetadata.getTable(any(CqlIdentifier.class)))
        .thenAnswer(
            i ->
                CqlIdentifier.fromInternal(table).equals(i.getArgument(0))
                    ? Optional.of(tableMetadata)
                    : Optional.empty());

    Metadata metadata = mock(Metadata.class);
    lenient()
        .when(metadata.getKeyspace(any(CqlIdentifier.class)))
        .thenAnswer(
            i ->
                CqlIdentifier.fromInternal(keyspace).equals(i.getArgument(0))
                    ? Optional.of(keyspaceMetadata)
                    : Optional.empty());

    CqlSession session = mock(CqlSession.class);
    lenient().when(session.getMetadata()).thenReturn(metadata);
    return session;
  }
}
