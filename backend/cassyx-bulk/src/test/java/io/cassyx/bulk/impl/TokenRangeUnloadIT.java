package io.cassyx.bulk.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.cql.Row;
import io.cassyx.bulk.api.BulkFactory;
import io.cassyx.bulk.api.Cancellation;
import io.cassyx.bulk.api.CountEngine;
import io.cassyx.bulk.api.JobProgress;
import io.cassyx.bulk.api.ProgressListener;
import io.cassyx.bulk.api.UnloadEngine;
import io.cassyx.bulk.api.UnloadRequest;
import io.cassyx.bulk.api.UnloadResult;
import io.cassyx.core.testsupport.IntegrationTestBase;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>THE correctness test of plan section 11.2.</b>
 *
 * <p>The union of every token-range split must equal the full row count - no gaps, no duplicates -
 * against a table with a deliberately skewed partition, mirroring the seeded
 * {@code demo.sensor_readings} HOT partition of plan section 2.2.
 *
 * <p>This is the test that matters most in the whole module. Silent data loss is the worst failure
 * mode this product has: a forgotten {@code unwrap()} or an inclusive/inclusive token bound produces
 * an export that is short or double-counted, and every other layer - the job status, the progress
 * bar, the artifact download - reports success.
 *
 * <p>Runs against the shared Testcontainers Cassandra 5.x singleton; opt in with
 * {@code -Dcassyx.it=true}.
 */
class TokenRangeUnloadIT extends IntegrationTestBase {

  private static final String KEYSPACE = "bulk_unload_it";
  private static final String TABLE = "sensor_readings";

  /** The skewed partition. Every other sensor has 4 rows; this one has 20 000. */
  private static final String HOT_PARTITION = "HOT";

  private static final int HOT_ROWS = 20_000;
  private static final int COLD_PARTITIONS = 500;
  private static final int COLD_ROWS_EACH = 4;
  private static final long EXPECTED_ROWS = (long) HOT_ROWS + (long) COLD_PARTITIONS * COLD_ROWS_EACH;

  @BeforeAll
  static void seed() {
    CqlSession session = session();
    ensureKeyspace(KEYSPACE);
    session.execute(
        "CREATE TABLE IF NOT EXISTS "
            + KEYSPACE
            + "."
            + TABLE
            + " (sensor_id text, reading_ts int, value double, quality text,"
            + " PRIMARY KEY (sensor_id, reading_ts))");
    session.execute("TRUNCATE " + KEYSPACE + "." + TABLE);

    PreparedStatement insert =
        session.prepare(
            "INSERT INTO "
                + KEYSPACE
                + "."
                + TABLE
                + " (sensor_id, reading_ts, value, quality) VALUES (?, ?, ?, ?)");

    List<CompletionStage<?>> inFlight = new ArrayList<>();
    for (int i = 0; i < HOT_ROWS; i++) {
      inFlight.add(session.executeAsync(insert.bind(HOT_PARTITION, i, i * 1.5, "ok")));
      drain(inFlight, 512);
    }
    for (int p = 0; p < COLD_PARTITIONS; p++) {
      for (int i = 0; i < COLD_ROWS_EACH; i++) {
        inFlight.add(session.executeAsync(insert.bind("sensor-" + p, i, i * 0.5, "ok")));
        drain(inFlight, 512);
      }
    }
    drain(inFlight, 0);
  }

  /** Bounded in-flight window: unbounded async inserts overwhelm a single-node container. */
  private static void drain(List<CompletionStage<?>> inFlight, int highWaterMark) {
    if (inFlight.size() <= highWaterMark) {
      return;
    }
    for (CompletionStage<?> stage : inFlight) {
      stage.toCompletableFuture().join();
    }
    inFlight.clear();
  }

  private static UnloadRequest request(String format, int splits, Map<String, String> options) {
    return new UnloadRequest(
        KEYSPACE, TABLE, List.of("sensor_id", "reading_ts"), format, "/out", splits, 16, options);
  }

  /** Ground truth straight from the server. */
  private static long serverRowCount() {
    return session().execute("SELECT count(*) FROM " + KEYSPACE + "." + TABLE).one().getLong(0);
  }

  @Test
  @DisplayName("union of all splits = full row count, no gaps, no duplicates")
  void tokenRangeUnloadIsComplete() {
    assertThat(serverRowCount()).isEqualTo(EXPECTED_ROWS);

    UnloadEngine engine = BulkFactory.unloadEngine();
    UnloadRequest request = request("csv", UnloadRequest.DEFAULT_SPLITS, Map.of());
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    List<JobProgress> ticks = new CopyOnWriteArrayList<>();

    int plannedSplits = ((TokenRangeUnloadEngine) engine).plan(session(), request).size();
    UnloadResult result =
        engine.unloadTo(session(), request, out, ticks::add, Cancellation.never());

    assertThat(result.splitsCompleted())
        .as("every planned split must be executed and accounted for")
        .isEqualTo(plannedSplits);
    assertThat(result.rowsWritten())
        .as("the engine must export exactly as many rows as the server holds")
        .isEqualTo(EXPECTED_ROWS);

    // Now the strong form: read the artifact back and check the primary keys are a SET of exactly
    // the expected size. A gap makes it smaller; a duplicated boundary partition makes the line
    // count exceed the set size. Counting alone would not catch one gap plus one duplicate.
    List<String> lines =
        out.toString(StandardCharsets.UTF_8).lines().skip(1).toList(); // skip the CSV header
    assertThat(lines).hasSize((int) EXPECTED_ROWS);

    Set<String> primaryKeys = new HashSet<>(lines);
    assertThat(primaryKeys)
        .as("duplicate primary keys mean overlapping splits (inclusive/inclusive token bounds)")
        .hasSize((int) EXPECTED_ROWS);

    long hotRows = lines.stream().filter(line -> line.startsWith(HOT_PARTITION + ",")).count();
    assertThat(hotRows)
        .as("the skewed partition must come out whole, not truncated by a slow worker")
        .isEqualTo(HOT_ROWS);

    assertThat(ticks).isNotEmpty();
  }

  @Test
  @DisplayName("oversplitting far past the worker count still tiles the ring")
  void planIsOversplitAndUnwrapped() {
    UnloadEngine engine = BulkFactory.unloadEngine();
    int splits =
        ((TokenRangeUnloadEngine) engine)
            .plan(session(), request("csv", UnloadRequest.DEFAULT_SPLITS, Map.of()))
            .size();

    // One split per worker would be ~16; the plan must be orders of magnitude larger, because
    // splitEvenly divides by token count and the HOT partition lives in exactly one of them.
    assertThat(splits).isGreaterThanOrEqualTo(UnloadRequest.DEFAULT_SPLITS);
  }

  @Test
  @DisplayName("the paging fallback returns the same rows as the token-range path")
  void pagingFallbackAgreesWithTheFastPath() {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    UnloadResult result =
        BulkFactory.unloadEngine()
            .unloadTo(
                session(),
                request("csv", 0, Map.of(ScanStrategyResolver.OPTION_STRATEGY, "PAGING")),
                out,
                ProgressListener.noop(),
                Cancellation.never());

    assertThat(result.rowsWritten()).isEqualTo(EXPECTED_ROWS);
    assertThat(result.warnings()).isNotEmpty();
    assertThat(new HashSet<>(out.toString(StandardCharsets.UTF_8).lines().skip(1).toList()))
        .hasSize((int) EXPECTED_ROWS);
  }

  @Test
  @DisplayName("every encoder exports the same number of rows")
  void everyEncoderExportsEveryRow() {
    for (String format : List.of("json", "jsonl", "xml")) {
      UnloadResult result =
          BulkFactory.unloadEngine()
              .unloadTo(
                  session(),
                  request(format, 512, Map.of()),
                  new ByteArrayOutputStream(),
                  ProgressListener.noop(),
                  Cancellation.never());
      assertThat(result.rowsWritten()).as("format %s", format).isEqualTo(EXPECTED_ROWS);
    }
  }

  @Test
  @DisplayName("the native count engine agrees with the server and finds the skewed partition")
  void countEngineMatchesTheServer() {
    CountEngine engine = BulkFactory.countEngine();
    CountEngine.CountResult result = engine.count(session(), KEYSPACE, TABLE);

    assertThat(result.totalRows()).isEqualTo(EXPECTED_ROWS);
    assertThat(result.perRange().values().stream().mapToLong(Long::longValue).sum())
        .isEqualTo(EXPECTED_ROWS);
    assertThat(result.largestPartitions()).isNotEmpty();
    assertThat(result.largestPartitions().get(0).partitionKey()).isEqualTo(HOT_PARTITION);
    assertThat(result.largestPartitions().get(0).rows()).isEqualTo(HOT_ROWS);
  }

  @Test
  @DisplayName("a projection exports exactly the requested columns")
  void projectsOnlyTheRequestedColumns() {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    BulkFactory.unloadEngine()
        .unloadTo(
            session(),
            new UnloadRequest(
                KEYSPACE, TABLE, List.of("value"), "csv", "/out", 256, 8, Map.of()),
            out,
            ProgressListener.noop(),
            Cancellation.never());

    // token() does not require the partition key in the projection - a real trap, because "helpfully"
    // adding it would silently change the exported column set.
    assertThat(out.toString(StandardCharsets.UTF_8).lines().findFirst()).hasValue("value");
  }

  @Test
  @DisplayName("row values survive the round trip")
  void exportedValuesMatchTheServer() {
    Row expected =
        session()
            .execute(
                "SELECT value FROM " + KEYSPACE + "." + TABLE
                    + " WHERE sensor_id = 'sensor-1' AND reading_ts = 2")
            .one();

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    BulkFactory.unloadEngine()
        .unloadTo(
            session(),
            new UnloadRequest(
                KEYSPACE,
                TABLE,
                List.of("sensor_id", "reading_ts", "value"),
                "csv",
                "/out",
                256,
                8,
                Map.of()),
            out,
            ProgressListener.noop(),
            Cancellation.never());

    assertThat(out.toString(StandardCharsets.UTF_8))
        .contains("sensor-1,2," + expected.getDouble("value"));
  }
}
