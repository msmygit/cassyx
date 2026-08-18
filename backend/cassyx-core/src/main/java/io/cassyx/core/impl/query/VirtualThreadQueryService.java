package io.cassyx.core.impl.query;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.AsyncResultSet;
import com.datastax.oss.driver.api.core.cql.BatchStatement;
import com.datastax.oss.driver.api.core.cql.BatchStatementBuilder;
import com.datastax.oss.driver.api.core.cql.BatchableStatement;
import com.datastax.oss.driver.api.core.cql.DefaultBatchType;
import com.datastax.oss.driver.api.core.cql.ExecutionInfo;
import com.datastax.oss.driver.api.core.cql.ResultSet;
import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.api.core.cql.Statement;
import io.cassyx.core.api.CassyxCoreException;
import io.cassyx.core.api.query.BatchOutcome;
import io.cassyx.core.api.query.BatchSpec;
import io.cassyx.core.api.query.ColumnInfo;
import io.cassyx.core.api.query.CqlValueCodec;
import io.cassyx.core.api.query.QueryCancellation;
import io.cassyx.core.api.query.QueryService;
import io.cassyx.core.api.query.QuerySpec;
import io.cassyx.core.api.query.QueryTrace;
import io.cassyx.core.api.query.ResultPage;
import io.cassyx.core.api.query.ResultSetInfo;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * The interactive query engine of plan section 5.1.
 *
 * <p>Executions run on virtual threads and are tracked by {@code queryId}, so the UI can cancel one
 * mid-flight. The client may supply the id up front - it has to, otherwise it would only learn the
 * id in the response it is waiting for and could never cancel the query it most wants to.
 */
public final class VirtualThreadQueryService implements QueryService, AutoCloseable {

  /** How many completed executions to keep around for trace retrieval. */
  private static final int TRACE_HISTORY = 256;

  private final CqlValueCodec codec;
  private final ColumnMapper mapper;
  private final StatementFactory statements;
  private final ResultSetCache cache;
  private final java.util.concurrent.ExecutorService executor;

  private final Map<String, CompletableFuture<AsyncResultSet>> inFlight = new ConcurrentHashMap<>();
  private final Map<String, ExecutionInfo> completed =
      Collections.synchronizedMap(
          new LinkedHashMap<>(16, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, ExecutionInfo> eldest) {
              return size() > TRACE_HISTORY;
            }
          });

  public VirtualThreadQueryService() {
    this(new DefaultCqlValueCodec(), new ResultSetCache());
  }

  public VirtualThreadQueryService(CqlValueCodec codec, ResultSetCache cache) {
    this.codec = codec;
    this.mapper = new ColumnMapper(codec);
    this.statements = new StatementFactory(codec);
    this.cache = cache;
    this.executor = Executors.newVirtualThreadPerTaskExecutor();
  }

  public ResultSetCache cache() {
    return cache;
  }

  @Override
  public ResultPage execute(CqlSession session, QuerySpec spec) {
    String queryId = spec.queryId() == null || spec.queryId().isBlank()
        ? UUID.randomUUID().toString()
        : spec.queryId();
    List<String> warnings = new ArrayList<>();

    BatchableStatement<?> bound =
        statements.build(session, spec.cql(), spec.positionalValues(), spec.namedValues());
    Statement<?> prepared =
        StatementFactory.applyOptions(
            session,
            bound,
            spec.keyspace(),
            spec.consistency(),
            spec.serialConsistency(),
            spec.fetchSize(),
            spec.timeout(),
            spec.tracing(),
            spec.idempotent(),
            warnings);

    long started = System.nanoTime();
    CompletableFuture<AsyncResultSet> future = new CompletableFuture<>();
    inFlight.put(queryId, future);
    // The driver's own CompletionStage is chained in from a virtual thread, so cancelling `future`
    // both stops us waiting and interrupts the carrier task.
    executor.execute(
        () -> {
          try {
            session.executeAsync(prepared).whenComplete(complete(future));
          } catch (RuntimeException e) {
            future.completeExceptionally(e);
          }
        });

    AsyncResultSet rs;
    try {
      rs = future.get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new CassyxCoreException("Query interrupted", e);
    } catch (java.util.concurrent.CancellationException e) {
      throw new CassyxCoreException("Query was cancelled", e);
    } catch (ExecutionException e) {
      throw unwrap(e);
    } finally {
      inFlight.remove(queryId);
    }

    long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    completed.put(queryId, rs.getExecutionInfo());
    warnings.addAll(rs.getExecutionInfo().getWarnings());

    List<ColumnInfo> columns = mapper.describe(session, rs.getColumnDefinitions());
    List<Map<String, Object>> rows = new ArrayList<>();
    for (Row row : rs.currentPage()) {
      rows.add(mapper.toWireRow(row, columns));
    }

    boolean wasVoid = columns.isEmpty();
    Boolean applied = appliedFlag(columns, rs.wasApplied());
    ByteBuffer nextState = rs.getExecutionInfo().getPagingState();

    String keyspace = columns.stream().map(ColumnInfo::keyspace).filter(k -> k != null).findFirst().orElse(null);
    String table = columns.stream().map(ColumnInfo::table).filter(t -> t != null).findFirst().orElse(null);

    ResultSetCache.Entry entry =
        cache.register(
            session,
            prepared,
            spec.cql(),
            columns,
            keyspace,
            table,
            projectsFullPrimaryKey(session, columns, keyspace, table),
            spec.fetchSize(),
            nextState,
            rows.size());

    return new ResultPage(
        entry.handle(),
        queryId,
        columns,
        rows,
        rows.size(),
        1,
        nextState != null,
        cache.nextTokenFor(entry),
        null,
        applied,
        wasVoid,
        elapsedMillis,
        warnings,
        traceId(rs.getExecutionInfo()),
        ColumnMapper.similarityColumns(columns),
        coordinator(rs.getExecutionInfo()),
        consistencyName(rs.getExecutionInfo()));
  }

  private static java.util.function.BiConsumer<AsyncResultSet, Throwable> complete(
      CompletableFuture<AsyncResultSet> future) {
    return (result, error) -> {
      if (error != null) {
        future.completeExceptionally(error);
      } else {
        future.complete(result);
      }
    };
  }

  @Override
  public ResultPage nextPage(String resultHandle, String pageToken, Integer fetchSize) {
    return page(resultHandle, pageToken, fetchSize);
  }

  @Override
  public ResultPage previousPage(String resultHandle, String pageToken, Integer fetchSize) {
    // Identical mechanics: `previous` is a REPLAY of a token this server retained on the way
    // forward. Cassandra cannot page backwards and CQL has no OFFSET, so there is nothing else it
    // could be.
    return page(resultHandle, pageToken, fetchSize);
  }

  private ResultPage page(String resultHandle, String pageToken, Integer fetchSize) {
    long started = System.nanoTime();
    ResultSetCache.Page page = cache.fetch(resultHandle, pageToken, fetchSize);
    ResultSet rs = page.resultSet();
    List<ColumnInfo> columns = page.entry().columns();

    List<Map<String, Object>> rows = new ArrayList<>();
    int available = rs.getAvailableWithoutFetching();
    for (int i = 0; i < available; i++) {
      Row row = rs.one();
      if (row == null) {
        break;
      }
      rows.add(mapper.toWireRow(row, columns));
    }
    cache.recordRows(page.entry(), rows.size());

    return new ResultPage(
        resultHandle,
        UUID.randomUUID().toString(),
        columns,
        rows,
        rows.size(),
        page.pageNumber(),
        page.entry().hasMorePages(),
        page.nextPageToken(),
        page.previousPageToken(),
        null,
        false,
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
        List.copyOf(rs.getExecutionInfo().getWarnings()),
        traceId(rs.getExecutionInfo()),
        ColumnMapper.similarityColumns(columns),
        coordinator(rs.getExecutionInfo()),
        consistencyName(rs.getExecutionInfo()));
  }

  @Override
  public ResultSetInfo resultSetInfo(String resultHandle) {
    return cache.info(resultHandle);
  }

  @Override
  public void closeResultSet(String resultHandle) {
    if (!cache.close(resultHandle)) {
      throw new io.cassyx.core.api.query.ResultHandleExpiredException(
          resultHandle, "Result handle " + resultHandle + " is unknown or has already been released.");
    }
  }

  @Override
  public int sweepExpiredResultSets() {
    return cache.evictExpired();
  }

  @Override
  public QueryCancellation cancel(String queryId) {
    CompletableFuture<AsyncResultSet> future = inFlight.get(queryId);
    if (future == null) {
      if (completed.containsKey(queryId)) {
        return new QueryCancellation(
            queryId, false, QueryCancellation.State.ALREADY_COMPLETED, "Query had already completed.");
      }
      return QueryCancellation.notFound(queryId);
    }
    boolean cancelled = future.cancel(true);
    inFlight.remove(queryId);
    return cancelled
        ? new QueryCancellation(queryId, true, QueryCancellation.State.CANCELLED, "Query cancelled.")
        : new QueryCancellation(
            queryId, false, QueryCancellation.State.ALREADY_COMPLETED, "Query had already completed.");
  }

  @Override
  public Optional<QueryTrace> trace(String queryId) {
    ExecutionInfo info = completed.get(queryId);
    if (info == null || info.getTracingId() == null) {
      return Optional.empty();
    }
    return Optional.of(TraceReader.read(info));
  }

  @Override
  public BatchOutcome executeBatch(CqlSession session, BatchSpec spec) {
    if (spec.statements().isEmpty()) {
      throw new CassyxCoreException("A batch needs at least one statement.");
    }
    List<String> warnings = new ArrayList<>();
    List<BatchableStatement<?>> children = new ArrayList<>();
    for (BatchSpec.Statement statement : spec.statements()) {
      children.add(
          statements.build(
              session, statement.cql(), statement.positionalValues(), statement.namedValues()));
    }

    PartitionAnalysis analysis = analysePartitions(children, warnings);
    String assembled = assemble(spec);

    if (spec.previewOnly()) {
      return new BatchOutcome(
          assembled,
          children.size(),
          analysis.spansMultiplePartitions(),
          analysis.distinctPartitions(),
          warnings,
          false,
          null,
          0L);
    }

    BatchStatementBuilder builder = BatchStatement.builder(batchType(spec.kind()));
    children.forEach(builder::addStatement);
    if (spec.timestampMicros() != null) {
      builder.setQueryTimestamp(spec.timestampMicros());
    }
    Statement<?> batch = builder.build();
    batch =
        StatementFactory.applyOptions(
            session,
            batch,
            spec.keyspace(),
            spec.consistency(),
            spec.serialConsistency(),
            QuerySpec.DEFAULT_FETCH_SIZE,
            null,
            false,
            false,
            warnings);

    long started = System.nanoTime();
    ResultSet rs = session.execute(batch);
    long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    warnings.addAll(rs.getExecutionInfo().getWarnings());

    Boolean applied =
        rs.getColumnDefinitions().contains("[applied]") ? Boolean.valueOf(rs.wasApplied()) : null;

    return new BatchOutcome(
        assembled,
        children.size(),
        analysis.spansMultiplePartitions(),
        analysis.distinctPartitions(),
        warnings,
        true,
        applied,
        elapsed);
  }

  private record PartitionAnalysis(boolean spansMultiplePartitions, int distinctPartitions) {}

  /**
   * A batch that spans partitions costs the coordinator far more than the same writes issued in
   * parallel, so it earns a warning (plan section 5.1).
   *
   * <p>Routing keys only exist for statements whose partition-key columns are BOUND. With inline
   * literals the driver cannot compute one, so we say so rather than quietly reporting
   * "single partition" - a false all-clear on this is worse than no analysis.
   */
  private static PartitionAnalysis analysePartitions(
      List<? extends Statement<?>> children, List<String> warnings) {
    Set<String> partitions = new LinkedHashSet<>();
    Set<String> tables = new LinkedHashSet<>();
    int unknown = 0;
    for (Statement<?> statement : children) {
      ByteBuffer routingKey = statement.getRoutingKey();
      String keyspace = statement.getRoutingKeyspace() == null ? "" : statement.getRoutingKeyspace().asInternal();
      if (routingKey == null) {
        unknown++;
      } else {
        partitions.add(keyspace + "|" + hex(routingKey));
      }
      tables.add(keyspace);
    }
    if (unknown > 0) {
      warnings.add(
          unknown
              + " statement(s) use inline literals, so their partition key could not be computed. "
              + "Bind values with ? for an exact multi-partition check.");
    }
    int distinct = partitions.size() + unknown;
    boolean spans = partitions.size() > 1 || tables.size() > 1 || (unknown > 0 && distinct > 1);
    return new PartitionAnalysis(spans, Math.max(distinct, 1));
  }

  private static String hex(ByteBuffer buffer) {
    ByteBuffer copy = buffer.duplicate();
    byte[] bytes = new byte[copy.remaining()];
    copy.get(bytes);
    return java.util.HexFormat.of().formatHex(bytes);
  }

  static String assemble(BatchSpec spec) {
    StringBuilder sb = new StringBuilder("BEGIN ");
    if (spec.kind() != BatchSpec.Kind.LOGGED) {
      sb.append(spec.kind().name()).append(' ');
    }
    sb.append("BATCH");
    if (spec.timestampMicros() != null) {
      sb.append(" USING TIMESTAMP ").append(spec.timestampMicros());
    }
    sb.append('\n');
    for (BatchSpec.Statement statement : spec.statements()) {
      sb.append("  ").append(statement.cql().strip().replaceAll(";$", "")).append(";\n");
    }
    sb.append("APPLY BATCH;");
    return sb.toString();
  }

  private static DefaultBatchType batchType(BatchSpec.Kind kind) {
    return switch (kind) {
      case LOGGED -> DefaultBatchType.LOGGED;
      case UNLOGGED -> DefaultBatchType.UNLOGGED;
      case COUNTER -> DefaultBatchType.COUNTER;
    };
  }

  /**
   * The LWT {@code [applied]} column, surfaced distinctly from the row data so the grid can render
   * the outcome as a first-class result rather than a mystery boolean column.
   */
  private static Boolean appliedFlag(List<ColumnInfo> columns, boolean wasApplied) {
    boolean conditional = columns.stream().anyMatch(c -> "[applied]".equals(c.name()));
    return conditional ? Boolean.valueOf(wasApplied) : null;
  }

  private boolean projectsFullPrimaryKey(
      CqlSession session, List<ColumnInfo> columns, String keyspace, String table) {
    if (keyspace == null || table == null) {
      return false;
    }
    return session
        .getMetadata()
        .getKeyspace(com.datastax.oss.driver.api.core.CqlIdentifier.fromInternal(keyspace))
        .flatMap(ks -> ks.getTable(com.datastax.oss.driver.api.core.CqlIdentifier.fromInternal(table)))
        .map(
            metadata -> {
              Set<String> projected = new LinkedHashSet<>();
              columns.forEach(c -> projected.add(c.name()));
              return metadata.getPrimaryKey().stream()
                  .allMatch(column -> projected.contains(column.getName().asInternal()));
            })
        .orElse(false);
  }

  private static String traceId(ExecutionInfo info) {
    UUID id = info.getTracingId();
    return id == null ? null : id.toString();
  }

  private static String coordinator(ExecutionInfo info) {
    return info.getCoordinator() == null ? null : info.getCoordinator().getEndPoint().toString();
  }

  private static String consistencyName(ExecutionInfo info) {
    Statement<?> statement = info.getRequest() instanceof Statement<?> s ? s : null;
    if (statement == null || statement.getConsistencyLevel() == null) {
      return null;
    }
    return statement.getConsistencyLevel().name();
  }

  private static RuntimeException unwrap(ExecutionException e) {
    Throwable cause = e.getCause();
    if (cause instanceof CompletionException completion && completion.getCause() != null) {
      cause = completion.getCause();
    }
    if (cause instanceof RuntimeException runtime) {
      return runtime;
    }
    return new CassyxCoreException(cause == null ? e.getMessage() : cause.getMessage(), cause);
  }

  /** Only for callers that own this service's lifecycle; the cache and executor are shut down. */
  @Override
  public void close() {
    executor.shutdownNow();
  }

}
