package io.cassyx.bulk.impl;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import com.datastax.oss.driver.api.core.CqlIdentifier;
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.BoundStatement;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.cql.ResultSet;
import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.api.core.cql.Statement;
import com.datastax.oss.driver.api.core.metadata.EndPoint;
import com.datastax.oss.driver.api.core.metadata.Metadata;
import com.datastax.oss.driver.api.core.metadata.Node;
import com.datastax.oss.driver.api.core.metadata.TokenMap;
import com.datastax.oss.driver.api.core.metadata.schema.ColumnMetadata;
import com.datastax.oss.driver.api.core.metadata.schema.KeyspaceMetadata;
import com.datastax.oss.driver.api.core.metadata.schema.TableMetadata;
import com.datastax.oss.driver.api.core.metadata.token.TokenRange;
import com.datastax.oss.driver.api.core.type.DataTypes;
import com.datastax.oss.driver.internal.core.metadata.token.Murmur3Token;
import com.datastax.oss.driver.internal.core.metadata.token.Murmur3TokenRange;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntUnaryOperator;

/**
 * A mocked {@link CqlSession} that behaves like a small cluster: a token ring, one table, and a
 * scripted supply of rows handed out one chunk per query.
 *
 * <p>Its purpose is to exercise the unload engine's <em>machinery</em> - the work-stealing queue,
 * the bounded single-writer handoff, cancellation, and the completeness assertions - deterministically
 * and without Docker. The end-to-end proof that the generated CQL really covers every row runs
 * against a live Cassandra in {@code TokenRangeUnloadIT}.
 */
final class FakeCluster {

  static final String KEYSPACE = "demo";
  static final String TABLE = "sensor_readings";
  static final List<String> COLUMNS = List.of("sensor_id", "reading_ts", "value");

  private FakeCluster() {}

  /** Rows for one split. */
  record Chunk(List<Map<String, Object>> rows) {}

  /**
   * Builds a session serving {@code chunks} - one per {@code execute} call, in order.
   *
   * @param vnodes number of token ranges the ring reports; {@code 0} means "no token map", which is
   *     how Amazon Keyspaces presents itself and forces the paging fallback
   */
  static CqlSession session(int vnodes, List<Chunk> chunks, AtomicInteger executions) {
    CqlSession session = mock(CqlSession.class);
    Metadata metadata = mock(Metadata.class);

    // Every nested mock is fully built BEFORE it is handed to when(...): stubbing one mock inside
    // another's when() argument is the classic UnfinishedStubbingException.
    Map<UUID, Node> nodes = Map.of(UUID.randomUUID(), node("/10.0.0.1:9042"));
    Optional<TokenMap> tokenMap = vnodes <= 0 ? Optional.empty() : Optional.of(tokenMap(vnodes));
    Optional<KeyspaceMetadata> keyspace = Optional.of(keyspace());
    PreparedStatement prepared = mock(PreparedStatement.class);
    BoundStatement bound = mock(BoundStatement.class, withSettings().defaultAnswer(RETURNS_SELF));

    when(session.getMetadata()).thenReturn(metadata);
    when(metadata.getNodes()).thenReturn(nodes);
    when(metadata.getTokenMap()).thenReturn(tokenMap);
    when(metadata.getKeyspace(CqlIdentifier.fromInternal(KEYSPACE))).thenReturn(keyspace);
    when(session.prepare(any(String.class))).thenReturn(prepared);
    when(prepared.bind()).thenReturn(bound);

    Queue<Chunk> pending = new ConcurrentLinkedQueue<>(chunks);
    when(session.execute(any(Statement.class)))
        .thenAnswer(
            invocation -> {
              executions.incrementAndGet();
              Chunk chunk = pending.poll();
              return resultSet(chunk == null ? List.of() : chunk.rows());
            });
    return session;
  }

  private static Node node(String endpoint) {
    Node node = mock(Node.class);
    // A real implementation rather than a mock: Mockito refuses to stub toString(), and the engine
    // renders the endpoint with String.valueOf when sniffing for Amazon Keyspaces.
    when(node.getEndPoint()).thenReturn(new TestEndPoint(endpoint));
    return node;
  }

  /** Minimal {@link EndPoint} whose {@code toString} is the host label under test. */
  private record TestEndPoint(String label) implements EndPoint {

    @Override
    public java.net.SocketAddress resolve() {
      return java.net.InetSocketAddress.createUnresolved("localhost", 9042);
    }

    @Override
    public String asMetricPrefix() {
      return label;
    }

    @Override
    public String toString() {
      return label;
    }
  }

  private static TokenMap tokenMap(int vnodes) {
    TokenMap tokenMap = mock(TokenMap.class);
    when(tokenMap.getTokenRanges()).thenReturn(ring(vnodes));
    return tokenMap;
  }

  /** A contiguous ring whose last range wraps the minimum token, exactly like a real cluster. */
  static Set<TokenRange> ring(int vnodes) {
    Set<TokenRange> ranges = new LinkedHashSet<>();
    long step = (Long.MAX_VALUE / vnodes) * 2;
    long previous = Long.MIN_VALUE;
    for (int i = 1; i < vnodes; i++) {
      long boundary = Long.MIN_VALUE + step * i;
      ranges.add(new Murmur3TokenRange(new Murmur3Token(previous), new Murmur3Token(boundary)));
      previous = boundary;
    }
    ranges.add(
        new Murmur3TokenRange(new Murmur3Token(previous), new Murmur3Token(Long.MIN_VALUE)));
    return ranges;
  }

  private static KeyspaceMetadata keyspace() {
    KeyspaceMetadata keyspace = mock(KeyspaceMetadata.class);
    Optional<TableMetadata> table = Optional.of(table());
    when(keyspace.getTable(CqlIdentifier.fromInternal(TABLE))).thenReturn(table);
    return keyspace;
  }

  private static TableMetadata table() {
    TableMetadata table = mock(TableMetadata.class);
    Map<CqlIdentifier, ColumnMetadata> columns = new LinkedHashMap<>();
    for (String name : COLUMNS) {
      columns.put(CqlIdentifier.fromInternal(name), column(name));
    }
    List<ColumnMetadata> partitionKey =
        List.of(columns.get(CqlIdentifier.fromInternal("sensor_id")));
    when(table.getColumns()).thenReturn(columns);
    when(table.getPartitionKey()).thenReturn(partitionKey);
    return table;
  }

  private static ColumnMetadata column(String name) {
    ColumnMetadata column = mock(ColumnMetadata.class);
    when(column.getName()).thenReturn(CqlIdentifier.fromInternal(name));
    when(column.getType()).thenReturn(DataTypes.TEXT);
    return column;
  }

  private static ResultSet resultSet(List<Map<String, Object>> rows) {
    List<Row> driverRows = new ArrayList<>(rows.size());
    for (Map<String, Object> values : rows) {
      Row row = mock(Row.class);
      for (int i = 0; i < COLUMNS.size(); i++) {
        when(row.getObject(i)).thenReturn(values.get(COLUMNS.get(i)));
      }
      driverRows.add(row);
    }
    ResultSet resultSet = mock(ResultSet.class);
    when(resultSet.iterator()).thenReturn(driverRows.iterator());
    return resultSet;
  }

  // ===========================================================================================
  // Lazy variant: rows are generated on the fly, never materialised.
  // ===========================================================================================

  /** Fixed-width cell values, precomputed so that generating a row allocates nothing. */
  private static final String[] SENSOR_IDS = new String[1000];
  private static final String[] TIMESTAMPS = new String[60];
  private static final String[] VALUES = new String[1000];

  /**
   * Bytes one generated row occupies in CSV: {@code sensor-000000,2026-08-18T00:00:00Z,000000000\n}.
   * Every cell is fixed width and free of characters CSV would quote, so this is exact.
   */
  static final int CSV_ROW_BYTES = 13 + 1 + 20 + 1 + 9 + 1;

  static {
    for (int i = 0; i < SENSOR_IDS.length; i++) {
      SENSOR_IDS[i] = String.format("sensor-%06d", i);
    }
    for (int i = 0; i < TIMESTAMPS.length; i++) {
      TIMESTAMPS[i] = String.format("2026-08-18T00:00:%02dZ", i);
    }
    for (int i = 0; i < VALUES.length; i++) {
      VALUES[i] = String.format("%09d", i);
    }
  }

  /**
   * A session with the same ring and schema as {@link #session}, but whose rows are <em>generated
   * on demand</em> rather than scripted into a list.
   *
   * <p>This is what makes a multi-million-row test possible at all: a test that first builds a
   * {@code List} of a million rows has already disproved nothing, because the peak heap it measures
   * would be the test's own. Here the fake driver holds exactly one reusable {@link Row} per result
   * set - legitimate because the engine copies every value out with {@code toMap} before advancing
   * the iterator.
   *
   * @param vnodes ring size, as in {@link #session}
   * @param rowsPerExecution given the 0-based ordinal of the {@code execute} call, how many rows
   *     that split yields. Invoked on the worker thread that drew the split, so a test can record
   *     scheduling or block a chosen split from inside it
   * @param onRowProduced run as each row leaves the fake driver, before the engine sees it
   */
  static CqlSession lazySession(
      int vnodes, IntUnaryOperator rowsPerExecution, Runnable onRowProduced) {
    CqlSession session = mock(CqlSession.class);
    Metadata metadata = mock(Metadata.class);

    Map<UUID, Node> nodes = Map.of(UUID.randomUUID(), node("/10.0.0.1:9042"));
    Optional<TokenMap> tokenMap = Optional.of(tokenMap(vnodes));
    Optional<KeyspaceMetadata> keyspace = Optional.of(keyspace());
    PreparedStatement prepared = mock(PreparedStatement.class);
    BoundStatement bound = mock(BoundStatement.class, withSettings().defaultAnswer(RETURNS_SELF));

    when(session.getMetadata()).thenReturn(metadata);
    when(metadata.getNodes()).thenReturn(nodes);
    when(metadata.getTokenMap()).thenReturn(tokenMap);
    when(metadata.getKeyspace(CqlIdentifier.fromInternal(KEYSPACE))).thenReturn(keyspace);
    when(session.prepare(any(String.class))).thenReturn(prepared);
    when(prepared.bind()).thenReturn(bound);

    AtomicInteger ordinal = new AtomicInteger();
    when(session.execute(any(Statement.class)))
        .thenAnswer(
            invocation ->
                lazyResultSet(
                    rowsPerExecution.applyAsInt(ordinal.getAndIncrement()), onRowProduced));
    return session;
  }

  /** A {@link ResultSet} of {@code rowCount} rows built one at a time, holding none of them. */
  private static ResultSet lazyResultSet(int rowCount, Runnable onRowProduced) {
    int[] cursor = {0};
    Row row =
        (Row)
            Proxy.newProxyInstance(
                FakeCluster.class.getClassLoader(),
                new Class<?>[] {Row.class},
                (proxy, method, args) -> {
                  if ("getObject".equals(method.getName())
                      && args != null
                      && args.length == 1
                      && args[0] instanceof Integer index) {
                    return cell(index, cursor[0]);
                  }
                  return fallback(proxy, method);
                });
    Iterator<Row> iterator =
        new Iterator<>() {
          private int emitted;

          @Override
          public boolean hasNext() {
            return emitted < rowCount;
          }

          @Override
          public Row next() {
            if (emitted >= rowCount) {
              throw new NoSuchElementException();
            }
            cursor[0] = emitted++;
            onRowProduced.run();
            return row;
          }
        };
    return (ResultSet)
        Proxy.newProxyInstance(
            FakeCluster.class.getClassLoader(),
            new Class<?>[] {ResultSet.class},
            (proxy, method, args) ->
                "iterator".equals(method.getName()) ? iterator : fallback(proxy, method));
  }

  private static Object fallback(Object proxy, java.lang.reflect.Method method) {
    return switch (method.getName()) {
      case "hashCode" -> System.identityHashCode(proxy);
      case "equals" -> false;
      case "toString" -> "lazy-" + method.getDeclaringClass().getSimpleName();
      default -> throw new UnsupportedOperationException(method.getName());
    };
  }

  private static String cell(int column, int rowIndex) {
    return switch (column) {
      case 0 -> SENSOR_IDS[rowIndex % SENSOR_IDS.length];
      case 1 -> TIMESTAMPS[rowIndex % TIMESTAMPS.length];
      default -> VALUES[rowIndex % VALUES.length];
    };
  }

  /** {@code count} rows all belonging to one partition - the skew case of plan section 5.2. */
  static Chunk chunk(String sensorId, int count) {
    List<Map<String, Object>> rows = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("sensor_id", sensorId);
      row.put("reading_ts", "2026-08-18T00:00:" + (i % 60));
      row.put("value", i);
      rows.add(row);
    }
    return new Chunk(rows);
  }
}
