package io.cassyx.bulk.impl;

import com.datastax.oss.driver.api.core.ConsistencyLevel;
import com.datastax.oss.driver.api.core.CqlIdentifier;
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.DefaultConsistencyLevel;
import com.datastax.oss.driver.api.core.cql.BoundStatement;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import com.datastax.oss.driver.api.core.cql.Statement;
import com.datastax.oss.driver.api.core.metadata.Metadata;
import com.datastax.oss.driver.api.core.metadata.Node;
import com.datastax.oss.driver.api.core.metadata.TokenMap;
import com.datastax.oss.driver.api.core.metadata.schema.ColumnMetadata;
import com.datastax.oss.driver.api.core.metadata.schema.TableMetadata;
import com.datastax.oss.driver.api.core.metadata.token.TokenRange;
import io.cassyx.bulk.api.BulkException;
import io.cassyx.bulk.api.Cancellation;
import io.cassyx.bulk.api.Encoder;
import io.cassyx.bulk.api.JobProgress;
import io.cassyx.bulk.api.ProgressListener;
import io.cassyx.bulk.api.ScanStrategy;
import io.cassyx.bulk.api.Sink;
import io.cassyx.bulk.api.TokenRangeSplitter;
import io.cassyx.bulk.api.UnloadEngine;
import io.cassyx.bulk.api.UnloadRequest;
import io.cassyx.bulk.api.UnloadResult;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The plan section 5.2 engine.
 *
 * <pre>
 * 1. tokenMap = session.getMetadata().getTokenMap()
 * 2. splits   = ranges.flatMap(r -&gt; r.splitEvenly(k)).flatMap(TokenRange::unwrap)
 * 3. per split: SELECT ... WHERE token(pk) &gt; ? AND token(pk) &lt;= ?
 *               .setRoutingToken(split.getEnd())
 * 4. work-stealing queue over the oversplit set; N virtual-thread consumers
 * 5. single-writer merge -&gt; encoder -&gt; sink
 * </pre>
 *
 * <h2>Why the shape is what it is</h2>
 *
 * <p><b>One shared queue, not a partitioned one.</b> {@code splitEvenly} divides the ring by token
 * count, and token count has nothing to do with row count - the seeded {@code demo.sensor_readings}
 * puts ~20k rows behind a single partition key. Hand each worker a fixed slice and the job takes as
 * long as whoever drew the hot slice. A single {@code ConcurrentLinkedQueue} drained by every
 * consumer is work-stealing in its simplest correct form, and combined with ~10k splits it is the
 * single biggest throughput lever in the product.
 *
 * <p><b>One writer thread behind a bounded queue.</b> {@code Encoder.Writer} is documented as not
 * thread-safe, and synchronising N producers on it would serialise them anyway. Instead the
 * consumers hand off row batches through an {@link ArrayBlockingQueue} of fixed capacity: when the
 * encoder is the bottleneck the queue fills, {@code put} blocks, and the readers throttle
 * themselves. That bound is what makes memory flat regardless of table size - the 50M-row
 * requirement of plan section 11.2 is a property of this queue, not of a heap setting.
 *
 * <p><b>Every unload runs the same code path.</b> There is no "small table" shortcut, so the
 * completeness property tested against the skewed seed keyspace holds for every export.
 */
public final class TokenRangeUnloadEngine implements UnloadEngine {

  private static final Logger LOG = LoggerFactory.getLogger(TokenRangeUnloadEngine.class);

  /** Rows handed to the writer thread per batch. Small enough to keep the queue bound meaningful. */
  static final int BATCH_ROWS = 512;

  /** Progress ticks are throttled to roughly this interval (the contract says ~1/s). */
  private static final long PROGRESS_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(1);

  /**
   * End-of-stream sentinel for the writer thread. A fresh instance, never {@code List.of()}: that
   * returns a shared singleton, so a legitimately empty batch would compare {@code ==} to it and
   * silently truncate the export.
   */
  private static final List<Map<String, Object>> POISON = new ArrayList<>();

  private final TokenRangeSplitter splitter;

  public TokenRangeUnloadEngine() {
    this(new EvenTokenRangeSplitter());
  }

  public TokenRangeUnloadEngine(TokenRangeSplitter splitter) {
    this.splitter = splitter;
  }

  @Override
  public ScanStrategy strategyFor(CqlSession session, UnloadRequest request) {
    Metadata metadata = session.getMetadata();
    int rangeCount = metadata.getTokenMap().map(map -> map.getTokenRanges().size()).orElse(0);
    return ScanStrategyResolver.resolve(
        rangeCount, endpoints(metadata), request.options().get(ScanStrategyResolver.OPTION_STRATEGY));
  }

  private static List<String> endpoints(Metadata metadata) {
    List<String> endpoints = new ArrayList<>();
    for (Node node : metadata.getNodes().values()) {
      endpoints.add(String.valueOf(node.getEndPoint()));
    }
    return endpoints;
  }

  @Override
  public UnloadResult unload(
      CqlSession session,
      UnloadRequest request,
      ProgressListener listener,
      Cancellation cancellation) {
    Encoder encoder = Encoder.forFormat(request.format());
    Sink sink = Sink.forTarget(request.target());
    String partName =
        request.options().getOrDefault(
            "fileName", request.table() + "." + encoder.fileExtension());
    Map<String, String> sinkOptions = new LinkedHashMap<>(request.options());
    sinkOptions.putIfAbsent("contentType", encoder.contentType());
    try (OutputStream out = sink.open(request.target(), partName, sinkOptions)) {
      UnloadResult result = unloadTo(session, request, out, listener, cancellation);
      return new UnloadResult(
          result.rowsWritten(),
          result.splitsCompleted(),
          result.elapsed(),
          List.of(partName),
          result.warnings());
    } catch (IOException e) {
      throw new BulkException("Unload sink failed for " + request.target(), e);
    }
  }

  @Override
  public UnloadResult unloadTo(
      CqlSession session,
      UnloadRequest request,
      OutputStream out,
      ProgressListener listener,
      Cancellation cancellation) {
    long startedAt = System.nanoTime();
    ProgressListener progress = listener == null ? ProgressListener.noop() : listener;
    Cancellation cancel = cancellation == null ? Cancellation.never() : cancellation;

    TableMetadata table = tableMetadata(session, request);
    List<String> columns = UnloadPlanner.resolveColumns(columnNames(table), request.columns());
    Map<String, String> columnTypes = columnTypes(table, columns);
    ScanStrategy strategy = strategyFor(session, request);

    List<TokenRange> splits =
        strategy == ScanStrategy.TOKEN_RANGE ? plan(session, request) : List.of();
    if (strategy == ScanStrategy.TOKEN_RANGE && splits.isEmpty()) {
      // Defensive: a token map that reports ranges but splits to nothing would silently export
      // zero rows. Degrade rather than lie.
      LOG.warn("Token map produced no splits for {}; falling back to paging", request.table());
      strategy = ScanStrategy.PAGING;
    }

    Encoder encoder = Encoder.forFormat(request.format());
    Encoder.EncoderContext context =
        new Encoder.EncoderContext(columns, columnTypes, request.options());

    List<String> warnings = new ArrayList<>();
    if (strategy == ScanStrategy.PAGING) {
      warnings.add(
          "Token-range scan unavailable on this cluster (plan section 7.1); "
              + "fell back to a single paged full scan.");
    }

    AtomicLong rowsRead = new AtomicLong();
    AtomicInteger splitsCompleted = new AtomicInteger();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    int splitCount = strategy == ScanStrategy.TOKEN_RANGE ? splits.size() : 1;
    int consumers = Math.max(1, Math.min(request.concurrency(), splitCount));

    BlockingQueue<List<Map<String, Object>>> handoff = new ArrayBlockingQueue<>(consumers * 2 + 2);
    AtomicLong rowsWritten = new AtomicLong();
    CountDownLatch writerDone = new CountDownLatch(1);

    Thread.ofVirtual()
        .name("cassyx-unload-writer")
        .start(
                () -> {
                  try (Encoder.Writer sink = encoder.open(out, context)) {
                    for (;;) {
                      List<Map<String, Object>> batch = handoff.take();
                      if (batch == POISON) {
                        break;
                      }
                      for (Map<String, Object> row : batch) {
                        sink.write(row);
                      }
                      rowsWritten.addAndGet(batch.size());
                    }
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    failure.compareAndSet(null, e);
                  } catch (IOException | RuntimeException e) {
                    failure.compareAndSet(null, e);
                    // Drain so blocked producers can observe the failure and stop.
                    handoff.clear();
                  } finally {
                    writerDone.countDown();
                  }
                });

    try {
      if (strategy == ScanStrategy.TOKEN_RANGE) {
        runTokenRangeScan(
            session, request, columns, splits, consumers, handoff, rowsRead, splitsCompleted,
            failure, cancel, progress);
      } else {
        runPagedScan(session, request, columns, handoff, rowsRead, failure, cancel, progress);
        splitsCompleted.set(1);
      }
    } finally {
      putQuietly(handoff, POISON);
      awaitQuietly(writerDone);
    }

    Throwable error = failure.get();
    if (error != null) {
      throw error instanceof BulkException bulk
          ? bulk
          : new BulkException("Unload of " + request.keyspace() + "." + request.table() + " failed",
              error);
    }
    if (cancel.isCancelled()) {
      throw new BulkException("Unload cancelled");
    }

    // THE completeness assertion of plan section 11.2, enforced in production and not only in
    // tests: if a split went missing, the export is short and nothing else would notice.
    if (splitsCompleted.get() != splitCount) {
      throw new BulkException(
          "Incomplete unload: " + splitsCompleted.get() + " of " + splitCount + " splits completed");
    }
    if (rowsWritten.get() != rowsRead.get()) {
      throw new BulkException(
          "Incomplete unload: read " + rowsRead.get() + " rows but wrote " + rowsWritten.get());
    }

    Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);
    progress.onProgress(
        new JobProgress(rowsWritten.get(), splitsCompleted.get(), splitCount, "Completed"));
    return new UnloadResult(rowsWritten.get(), splitsCompleted.get(), elapsed, List.of(), warnings);
  }

  /** Builds the oversplit work set. Public so the completeness test can inspect the plan. */
  public List<TokenRange> plan(CqlSession session, UnloadRequest request) {
    Optional<TokenMap> tokenMap = session.getMetadata().getTokenMap();
    if (tokenMap.isEmpty()) {
      return List.of();
    }
    Collection<TokenRange> ranges = tokenMap.get().getTokenRanges();
    return splitter.split(ranges, UnloadPlanner.splitsFor(request.splits(), ranges.size()));
  }

  private void runTokenRangeScan(
      CqlSession session,
      UnloadRequest request,
      List<String> columns,
      List<TokenRange> splits,
      int consumers,
      BlockingQueue<List<Map<String, Object>>> handoff,
      AtomicLong rowsRead,
      AtomicInteger splitsCompleted,
      AtomicReference<Throwable> failure,
      Cancellation cancel,
      ProgressListener progress) {

    TableMetadata table = tableMetadata(session, request);
    List<String> partitionKey =
        table.getPartitionKey().stream().map(c -> c.getName().asInternal()).toList();
    String cql =
        UnloadPlanner.tokenRangeQuery(request.keyspace(), request.table(), columns, partitionKey);
    LOG.debug("Unload CQL: {}", cql);
    PreparedStatement prepared = session.prepare(cql);

    // ONE queue for ALL consumers: that is the work-stealing property. Splits are not partitioned
    // per worker, so a worker that draws the hot partition does not hold the job back.
    Queue<TokenRange> work = new ConcurrentLinkedQueue<>(splits);
    int total = splits.size();
    AtomicLong lastTick = new AtomicLong(System.nanoTime());

    try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < consumers; i++) {
        pool.execute(
            () -> {
              List<Map<String, Object>> batch = new ArrayList<>(BATCH_ROWS);
              TokenRange split;
              while ((split = work.poll()) != null) {
                if (cancel.isCancelled() || failure.get() != null) {
                  return;
                }
                try {
                  BoundStatement statement =
                      prepared
                          .bind()
                          .setToken(0, split.getStart())
                          .setToken(1, split.getEnd())
                          // Routes the read to a replica that owns the range - no coordinator hop.
                          .setRoutingToken(split.getEnd());
                  statement = applyReadOptions(statement, request);
                  for (Row row : session.execute(statement)) {
                    batch.add(toMap(row, columns));
                    rowsRead.incrementAndGet();
                    if (batch.size() >= BATCH_ROWS) {
                      handoff.put(batch);
                      batch = new ArrayList<>(BATCH_ROWS);
                    }
                    if (cancel.isCancelled() || failure.get() != null) {
                      return;
                    }
                  }
                  int done = splitsCompleted.incrementAndGet();
                  tick(progress, rowsRead, done, total, lastTick);
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  failure.compareAndSet(null, e);
                  return;
                } catch (RuntimeException e) {
                  failure.compareAndSet(
                      null, new BulkException("Split " + split + " failed", e));
                  return;
                }
              }
              if (!batch.isEmpty()) {
                putQuietly(handoff, batch);
              }
            });
      }
    }
  }

  private void runPagedScan(
      CqlSession session,
      UnloadRequest request,
      List<String> columns,
      BlockingQueue<List<Map<String, Object>>> handoff,
      AtomicLong rowsRead,
      AtomicReference<Throwable> failure,
      Cancellation cancel,
      ProgressListener progress) {

    String query = request.options().get("query");
    SimpleStatement statement =
        SimpleStatement.newInstance(
            query != null && !query.isBlank()
                ? query
                : UnloadPlanner.fullScanQuery(request.keyspace(), request.table(), columns));
    statement = applyReadOptions(statement, request);
    AtomicLong lastTick = new AtomicLong(System.nanoTime());
    List<Map<String, Object>> batch = new ArrayList<>(BATCH_ROWS);
    try {
      for (Row row : session.execute(statement)) {
        if (cancel.isCancelled()) {
          return;
        }
        batch.add(toMap(row, columns));
        rowsRead.incrementAndGet();
        if (batch.size() >= BATCH_ROWS) {
          handoff.put(batch);
          batch = new ArrayList<>(BATCH_ROWS);
          tick(progress, rowsRead, 0, 1, lastTick);
        }
      }
      if (!batch.isEmpty()) {
        handoff.put(batch);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      failure.compareAndSet(null, e);
    } catch (RuntimeException e) {
      failure.compareAndSet(null, new BulkException("Paged unload failed", e));
    }
  }

  private static <S extends Statement<S>> S applyReadOptions(S statement, UnloadRequest request) {
    S configured = statement.setPageSize(pageSize(request));
    String consistency = request.options().get("consistency");
    if (consistency != null && !consistency.isBlank()) {
      configured = configured.setConsistencyLevel(consistencyLevel(consistency));
    } else {
      // Unload reads default to LOCAL_ONE (plan section 5.3 derived-defaults table).
      configured = configured.setConsistencyLevel(DefaultConsistencyLevel.LOCAL_ONE);
    }
    return configured;
  }

  static ConsistencyLevel consistencyLevel(String name) {
    try {
      return DefaultConsistencyLevel.valueOf(name.trim().toUpperCase(java.util.Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new BulkException("Unknown consistency level '" + name + "'", e);
    }
  }

  static int pageSize(UnloadRequest request) {
    String configured = request.options().get("pageSize");
    if (configured == null || configured.isBlank()) {
      return 5000;
    }
    int parsed = Integer.parseInt(configured.trim());
    return parsed > 0 ? parsed : 5000;
  }

  private static void tick(
      ProgressListener progress,
      AtomicLong rowsRead,
      int splitsCompleted,
      int splitsTotal,
      AtomicLong lastTick) {
    long now = System.nanoTime();
    long previous = lastTick.get();
    if (now - previous < PROGRESS_INTERVAL_NANOS) {
      return;
    }
    if (!lastTick.compareAndSet(previous, now)) {
      return;
    }
    progress.onProgress(
        new JobProgress(rowsRead.get(), splitsCompleted, splitsTotal, "Unloading"));
  }

  private static Map<String, Object> toMap(Row row, List<String> columns) {
    Map<String, Object> values = new LinkedHashMap<>(columns.size() * 2);
    for (int i = 0; i < columns.size(); i++) {
      values.put(columns.get(i), row.getObject(i));
    }
    return values;
  }

  private static TableMetadata tableMetadata(CqlSession session, UnloadRequest request) {
    return session
        .getMetadata()
        .getKeyspace(CqlIdentifier.fromInternal(request.keyspace()))
        .flatMap(ks -> ks.getTable(CqlIdentifier.fromInternal(request.table())))
        .orElseThrow(
            () ->
                new BulkException(
                    "Unknown table " + request.keyspace() + "." + request.table()));
  }

  private static List<String> columnNames(TableMetadata table) {
    return table.getColumns().values().stream()
        .map(column -> column.getName().asInternal())
        .toList();
  }

  private static Map<String, String> columnTypes(TableMetadata table, List<String> columns) {
    Map<String, String> types = new LinkedHashMap<>();
    for (String column : columns) {
      ColumnMetadata metadata = table.getColumns().get(CqlIdentifier.fromInternal(column));
      if (metadata != null) {
        types.put(column, metadata.getType().asCql(true, false));
      }
    }
    return types;
  }

  private static <T> void putQuietly(BlockingQueue<T> queue, T value) {
    try {
      queue.put(value);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static void awaitQuietly(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
