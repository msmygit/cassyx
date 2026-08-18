package io.cassyx.bulk.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.datastax.oss.driver.api.core.CqlSession;
import io.cassyx.bulk.api.BulkFactory;
import io.cassyx.bulk.api.Cancellation;
import io.cassyx.bulk.api.ProgressListener;
import io.cassyx.bulk.api.UnloadRequest;
import io.cassyx.bulk.api.UnloadResult;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Plan section 11.2's memory requirement: "a 50M-row unload must hold flat memory".
 *
 * <p>Fifty million rows is not a number a unit test can drive, and it does not have to be. Flat
 * memory is not an emergent property that appears at some row count - it is a structural
 * consequence of two things in {@link TokenRangeUnloadEngine}: consumers hand rows to the writer
 * through a fixed-capacity {@link java.util.concurrent.ArrayBlockingQueue}, and the encoders write
 * straight through. So the number of rows that exist at once has a ceiling that is a function of
 * {@code concurrency} alone, and is independent of the table's size. This test pins that ceiling
 * directly, at a row count large enough that a buffering implementation would be caught (a
 * buffering engine would be holding ~1.5M {@code LinkedHashMap}s, several hundred MB, well past
 * the budget asserted below), and cross-checks it with a heap measurement.
 *
 * <p>The two assertions are deliberately of different kinds. The in-flight ceiling is exact and
 * cannot flake. The heap reading is noisy by nature, so it is asserted only against a generous
 * absolute budget - it is corroboration, not the proof.
 */
class TokenRangeUnloadMemoryTest {

  private static final int SPLITS = 512;
  private static final int CONSUMERS = 4;

  /** The small run. */
  private static final int SMALL_ROWS_PER_SPLIT = 60;

  /** The large run: 50x the rows, and the same engine, queue and writer. */
  private static final int LARGE_ROWS_PER_SPLIT = SMALL_ROWS_PER_SPLIT * 50;

  /** {@link CsvEncoder} wraps the sink in a 64 KiB {@code BufferedWriter} ... */
  private static final int CSV_BUFFER_BYTES = 1 << 16;

  /** ... which in turn sits on an {@code OutputStreamWriter}, whose encoder buffers 8 KiB more. */
  private static final int STREAM_ENCODER_BYTES = 8 * 1024;

  private static final long MEGABYTE = 1024L * 1024L;

  /** Generous enough that ordinary JVM noise cannot trip it, far below what buffering would cost. */
  private static final long HEAP_BUDGET_BYTES = 96 * MEGABYTE;

  @Test
  @DisplayName("a multi-million-row unload holds flat, bounded memory - nothing buffers")
  void aLargeUnloadHoldsFlatMemory() {
    long baseline = usedHeapAfterGc();

    Run small = unload(SMALL_ROWS_PER_SPLIT);
    long afterSmall = usedHeapAfterGc();

    Run large = unload(LARGE_ROWS_PER_SPLIT);
    long afterLarge = usedHeapAfterGc();

    assertThat(large.rows())
        .as("the large run really is 50x the small one, through the same code path")
        .isEqualTo(small.rows() * 50);

    // ---- The structural proof: rows alive at once are capped by concurrency, not by table size.
    assertThat(large.maxInFlight())
        .as(
            "rows read but not yet written, peak over %,d rows (bound %,d)",
            large.rows(), inFlightBound())
        .isLessThanOrEqualTo(inFlightBound());
    assertThat(small.maxInFlight())
        .as("the same ceiling holds at 1/50th the size - it is not a function of row count")
        .isLessThanOrEqualTo(inFlightBound());
    assertThat(large.maxInFlight())
        .as("an engine that buffered would have all %,d rows in flight", large.rows())
        .isLessThan(large.rows() / 100);

    // ---- The corroborating heap reading.
    assertThat(afterLarge - baseline)
        .as("retained heap after a %,d-row unload", large.rows())
        .isLessThanOrEqualTo(HEAP_BUDGET_BYTES);
    assertThat(afterLarge - baseline)
        .as("retained heap does not scale with row count")
        .isLessThanOrEqualTo(Math.max(0L, afterSmall - baseline) + HEAP_BUDGET_BYTES);
  }

  /**
   * The ceiling the engine's shape imposes, in rows:
   *
   * <ul>
   *   <li>the handoff queue holds at most {@code consumers * 2 + 2} batches;
   *   <li>each consumer is filling one further batch of its own;
   *   <li>the writer thread is draining one batch;
   *   <li>and rows already handed to the encoder are still uncounted here until its writer buffers
   *       flush, because this test observes writes at the {@link OutputStream}.
   * </ul>
   *
   * <p>Every term is a function of {@code CONSUMERS}; none is a function of the row count. That is
   * the whole property, and it is why 50M rows needs no more heap than 50k.
   */
  private static long inFlightBound() {
    long batch = TokenRangeUnloadEngine.BATCH_ROWS;
    long queued = batch * (CONSUMERS * 2L + 2L);
    long accumulating = batch * CONSUMERS;
    long inWriter = batch;
    long unflushed =
        (CSV_BUFFER_BYTES + STREAM_ENCODER_BYTES) / FakeCluster.CSV_ROW_BYTES + 1L;
    // One batch of slack so the test cannot flake on a JDK that resizes its writer buffers.
    return queued + accumulating + inWriter + unflushed + batch;
  }

  private record Run(long rows, long maxInFlight) {}

  private static Run unload(int rowsPerSplit) {
    AtomicLong produced = new AtomicLong();
    AtomicLong written = new AtomicLong();
    AtomicLong maxInFlight = new AtomicLong();

    // Rows are counted as they are written by counting line terminators reaching the sink; the
    // bytes themselves are thrown away, so the sink is never what is being measured.
    OutputStream out =
        new OutputStream() {
          @Override
          public void write(int b) {
            if (b == '\n') {
              written.incrementAndGet();
            }
          }

          @Override
          public void write(byte[] b, int off, int len) {
            for (int i = off; i < off + len; i++) {
              if (b[i] == '\n') {
                written.incrementAndGet();
              }
            }
          }
        };

    CqlSession session =
        FakeCluster.lazySession(
            8,
            ordinal -> rowsPerSplit,
            () -> {
              long inFlight = produced.incrementAndGet() - written.get();
              maxInFlight.accumulateAndGet(inFlight, Math::max);
            });

    UnloadRequest request =
        new UnloadRequest(
            FakeCluster.KEYSPACE,
            FakeCluster.TABLE,
            List.of(),
            "csv",
            "/dev/null",
            SPLITS,
            CONSUMERS,
            Map.of());

    UnloadResult result =
        BulkFactory.unloadEngine()
            .unloadTo(session, request, out, ProgressListener.noop(), Cancellation.never());

    assertThat(result.rowsWritten())
        .as("every generated row reached the encoder")
        .isEqualTo(produced.get());
    closeQuietly(out);
    return new Run(result.rowsWritten(), maxInFlight.get());
  }

  private static void closeQuietly(OutputStream out) {
    try {
      out.close();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private static long usedHeapAfterGc() {
    Runtime runtime = Runtime.getRuntime();
    for (int i = 0; i < 3; i++) {
      System.gc();
      try {
        Thread.sleep(60);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    return runtime.totalMemory() - runtime.freeMemory();
  }
}
