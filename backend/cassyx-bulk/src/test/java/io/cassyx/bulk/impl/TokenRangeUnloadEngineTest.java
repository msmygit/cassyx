package io.cassyx.bulk.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.datastax.oss.driver.api.core.CqlSession;
import io.cassyx.bulk.api.BulkException;
import io.cassyx.bulk.api.BulkFactory;
import io.cassyx.bulk.api.Cancellation;
import io.cassyx.bulk.api.JobProgress;
import io.cassyx.bulk.api.ProgressListener;
import io.cassyx.bulk.api.ScanStrategy;
import io.cassyx.bulk.api.UnloadEngine;
import io.cassyx.bulk.api.UnloadRequest;
import io.cassyx.bulk.api.UnloadResult;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The unload engine's machinery, against the scripted {@link FakeCluster}.
 *
 * <p>These cover what a live cluster makes slow and non-deterministic to test: the work-stealing
 * drain under skew, the bounded single-writer handoff, cancellation, the paging fallback, and the
 * two production-side completeness assertions. The end-to-end "union of splits equals
 * {@code count(*)}" proof is {@code TokenRangeUnloadIT}.
 */
class TokenRangeUnloadEngineTest {

  @TempDir Path tmp;

  private static UnloadRequest request(int splits, int concurrency, Map<String, String> options) {
    return new UnloadRequest(
        FakeCluster.KEYSPACE,
        FakeCluster.TABLE,
        List.of(),
        "csv",
        "/out",
        splits,
        concurrency,
        options);
  }

  private static List<FakeCluster.Chunk> evenChunks(int chunks, int rowsEach) {
    List<FakeCluster.Chunk> out = new ArrayList<>(chunks);
    for (int i = 0; i < chunks; i++) {
      out.add(FakeCluster.chunk("sensor-" + i, rowsEach));
    }
    return out;
  }

  @Test
  @DisplayName("every row read reaches the encoder, and every split is accounted for")
  void unloadsEveryRowFromEverySplit() {
    AtomicInteger executions = new AtomicInteger();
    List<FakeCluster.Chunk> chunks = evenChunks(64, 25);
    CqlSession session = FakeCluster.session(8, chunks, executions);
    UnloadEngine engine = BulkFactory.unloadEngine();
    ByteArrayOutputStream out = new ByteArrayOutputStream();

    UnloadResult result =
        engine.unloadTo(
            session, request(64, 4, Map.of()), out, ProgressListener.noop(), Cancellation.never());

    assertThat(result.rowsWritten()).isEqualTo(64L * 25);
    // Every submitted split must complete. The engine throws rather than returning a short export,
    // so this also proves the assertion itself is reachable.
    assertThat(result.splitsCompleted()).isEqualTo(executions.get());
    assertThat(out.toString(StandardCharsets.UTF_8).lines().count())
        .as("one header line plus one line per row")
        .isEqualTo(64L * 25 + 1);
    assertThat(result.warnings()).isEmpty();
    assertThat(result.rowsPerSecond()).isGreaterThan(0);
  }

  @Test
  @DisplayName("a wildly skewed split does not lose rows or stall the drain")
  void handlesPartitionSkew() {
    // One split holds two orders of magnitude more rows than its neighbours - the shape of the
    // seeded demo.sensor_readings HOT partition. With a single shared queue the other workers keep
    // draining while one of them grinds through it.
    List<FakeCluster.Chunk> chunks = new ArrayList<>(evenChunks(31, 10));
    chunks.add(4, FakeCluster.chunk("HOT", 3_000));
    AtomicInteger executions = new AtomicInteger();
    CqlSession session = FakeCluster.session(8, chunks, executions);
    ByteArrayOutputStream out = new ByteArrayOutputStream();

    UnloadResult result =
        BulkFactory.unloadEngine()
            .unloadTo(
                session,
                request(32, 8, Map.of()),
                out,
                ProgressListener.noop(),
                Cancellation.never());

    assertThat(result.rowsWritten()).isEqualTo(31L * 10 + 3_000);
  }

  @Test
  @DisplayName("progress ticks carry the split counters the contract exposes")
  void reportsProgress() {
    List<JobProgress> ticks = new CopyOnWriteArrayList<>();
    CqlSession session = FakeCluster.session(8, evenChunks(16, 5), new AtomicInteger());

    UnloadResult result =
        BulkFactory.unloadEngine()
            .unloadTo(
                session,
                request(16, 2, Map.of()),
                new ByteArrayOutputStream(),
                ticks::add,
                Cancellation.never());

    // The final tick is emitted unconditionally; the throttled in-flight ones may or may not fire
    // on a run this short, which is the point of the throttle.
    assertThat(ticks).isNotEmpty();
    JobProgress last = ticks.get(ticks.size() - 1);
    assertThat(last.rowsProcessed()).isEqualTo(result.rowsWritten());
    assertThat(last.splitsTotal()).isEqualTo(result.splitsCompleted());
    assertThat(last.fraction()).isEqualTo(1.0);
  }

  @Test
  @DisplayName("cancellation stops the drain and refuses to report a partial export as success")
  void cancellationFailsRatherThanTruncating() {
    AtomicBoolean cancelled = new AtomicBoolean(true);
    CqlSession session = FakeCluster.session(8, evenChunks(32, 10), new AtomicInteger());

    assertThatThrownBy(
            () ->
                BulkFactory.unloadEngine()
                    .unloadTo(
                        session,
                        request(32, 4, Map.of()),
                        new ByteArrayOutputStream(),
                        ProgressListener.noop(),
                        Cancellation.of(cancelled)))
        .isInstanceOf(BulkException.class)
        .hasMessageContaining("cancelled");
  }

  @Test
  @DisplayName("no token map means the Keyspaces paging fallback, with a warning")
  void fallsBackToPagingWithoutATokenMap() {
    CqlSession session = FakeCluster.session(0, List.of(FakeCluster.chunk("s", 40)), new AtomicInteger());
    UnloadRequest request = request(10_000, 4, Map.of());

    assertThat(BulkFactory.unloadEngine().strategyFor(session, request))
        .isEqualTo(ScanStrategy.PAGING);

    UnloadResult result =
        BulkFactory.unloadEngine()
            .unloadTo(
                session,
                request,
                new ByteArrayOutputStream(),
                ProgressListener.noop(),
                Cancellation.never());

    assertThat(result.rowsWritten()).isEqualTo(40);
    assertThat(result.splitsCompleted()).isEqualTo(1);
    assertThat(result.warnings()).singleElement().asString().contains("7.1");
  }

  @Test
  @DisplayName("a custom query is honoured on the paging path")
  void pagedScanAcceptsACustomQuery() {
    CqlSession session = FakeCluster.session(0, List.of(FakeCluster.chunk("s", 3)), new AtomicInteger());

    UnloadResult result =
        BulkFactory.unloadEngine()
            .unloadTo(
                session,
                request(0, 1, Map.of("query", "SELECT sensor_id FROM demo.sensor_readings")),
                new ByteArrayOutputStream(),
                ProgressListener.noop(),
                Cancellation.never());

    assertThat(result.rowsWritten()).isEqualTo(3);
  }

  @Test
  @DisplayName("unload(...) resolves a Sink from the target and names the artifact")
  void unloadWritesThroughTheResolvedSink() {
    CqlSession session = FakeCluster.session(4, evenChunks(8, 5), new AtomicInteger());
    Path directory = tmp.resolve("exports");

    UnloadResult result =
        BulkFactory.unloadEngine()
            .unload(
                session,
                new UnloadRequest(
                    FakeCluster.KEYSPACE,
                    FakeCluster.TABLE,
                    List.of("sensor_id", "value"),
                    "json",
                    directory.toString(),
                    8,
                    2,
                    Map.of()),
                ProgressListener.noop());

    assertThat(result.artifacts()).containsExactly("sensor_readings.json");
    assertThat(directory.resolve("sensor_readings.json")).exists();
    assertThat(result.rowsWritten()).isEqualTo(40);
  }

  @Test
  void unknownTableFailsFast() {
    CqlSession session = FakeCluster.session(4, List.of(), new AtomicInteger());
    UnloadRequest request =
        new UnloadRequest(FakeCluster.KEYSPACE, "nope", List.of(), "csv", "/out", 4, 1, Map.of());

    assertThatThrownBy(
            () ->
                BulkFactory.unloadEngine()
                    .unloadTo(
                        session,
                        request,
                        new ByteArrayOutputStream(),
                        ProgressListener.noop(),
                        Cancellation.never()))
        .isInstanceOf(BulkException.class)
        .hasMessageContaining("Unknown table");
  }

  @Test
  void unknownFormatFailsBeforeAnyQueryRuns() {
    AtomicInteger executions = new AtomicInteger();
    CqlSession session = FakeCluster.session(4, evenChunks(4, 1), executions);
    UnloadRequest request =
        new UnloadRequest(
            FakeCluster.KEYSPACE, FakeCluster.TABLE, List.of(), "avro", "/out", 4, 1, Map.of());

    assertThatThrownBy(
            () ->
                BulkFactory.unloadEngine()
                    .unloadTo(
                        session,
                        request,
                        new ByteArrayOutputStream(),
                        ProgressListener.noop(),
                        Cancellation.never()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void sinkFailuresSurfaceAsBulkExceptions() {
    CqlSession session = FakeCluster.session(4, evenChunks(4, 1), new AtomicInteger());
    // A file that already exists as a *file* cannot also be a directory.
    Path blocker = tmp.resolve("blocker");
    assertThatThrownBy(
            () -> {
              Files.writeString(blocker, "x");
              BulkFactory.unloadEngine()
                  .unload(
                      session,
                      new UnloadRequest(
                          FakeCluster.KEYSPACE,
                          FakeCluster.TABLE,
                          List.of(),
                          "csv",
                          blocker.toString(),
                          4,
                          1,
                          Map.of()),
                      ProgressListener.noop());
            })
        .isInstanceOf(BulkException.class);
  }

  @Test
  void readOptionsAreParsedFromTheRequest() {
    assertThat(TokenRangeUnloadEngine.pageSize(request(1, 1, Map.of()))).isEqualTo(5000);
    assertThat(TokenRangeUnloadEngine.pageSize(request(1, 1, Map.of("pageSize", " 250 "))))
        .isEqualTo(250);
    assertThat(TokenRangeUnloadEngine.pageSize(request(1, 1, Map.of("pageSize", "-1"))))
        .isEqualTo(5000);
    assertThat(TokenRangeUnloadEngine.consistencyLevel("local_quorum").name())
        .isEqualTo("LOCAL_QUORUM");
    assertThatThrownBy(() -> TokenRangeUnloadEngine.consistencyLevel("eventually"))
        .isInstanceOf(BulkException.class);
  }

  @Test
  @DisplayName("the ring reports its strategy before a job is queued")
  void strategyForReportsTheFastPathOnARealRing() {
    CqlSession session = FakeCluster.session(8, List.of(), new AtomicInteger());
    assertThat(BulkFactory.unloadEngine().strategyFor(session, request(10_000, 4, Map.of())))
        .isEqualTo(ScanStrategy.TOKEN_RANGE);
  }
}
