package io.cassyx.bulk.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.datastax.oss.driver.api.core.CqlSession;
import io.cassyx.bulk.api.BulkFactory;
import io.cassyx.bulk.api.Cancellation;
import io.cassyx.bulk.api.ProgressListener;
import io.cassyx.bulk.api.UnloadRequest;
import io.cassyx.bulk.api.UnloadResult;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The work-stealing property of {@link TokenRangeUnloadEngine}, asserted as a property rather than
 * as "no rows were lost".
 *
 * <p>{@code splitEvenly} divides the ring by token count, which has nothing to do with row count.
 * The obvious implementation - hand each worker a fixed slice of the split list - therefore makes
 * the whole unload as slow as its unluckiest worker. The engine instead drains ONE
 * {@link java.util.concurrent.ConcurrentLinkedQueue} from every consumer.
 *
 * <p>"No rows lost" cannot tell those two designs apart: a partitioned engine loses no rows either,
 * it is just slow. What tells them apart is the <em>distribution</em> of splits across workers. Under
 * a one-split-per-worker partitioning every worker ends up with exactly {@code splits / consumers}
 * by construction, so the assertion below - that one worker completed far more than its even share
 * because another was stuck - is unsatisfiable there. That is what makes this test meaningful.
 */
class TokenRangeUnloadWorkStealingTest {

  private static final int SPLITS = 512;
  private static final int CONSUMERS = 2;

  /** Rows behind the one deliberately hot split - the shape of the seeded HOT partition. */
  private static final int HOT_ROWS = 5_000;

  private static final int COLD_ROWS = 20;

  /**
   * How many splits a single worker must drain before the hot split is allowed to finish. Chosen
   * above the even share ({@code SPLITS / CONSUMERS} = 256) so the outcome is decided by a latch,
   * not by a sleep racing the scheduler.
   */
  private static final int RELEASE_AFTER = 400;

  @Test
  @DisplayName(
      "work-stealing: while one worker grinds through a hot split the others keep draining the"
          + " shared queue, so splits land unevenly")
  void fastWorkersKeepStealingWhileOneWorkerIsStuck() throws InterruptedException {
    // splitsPerWorker is keyed by virtual-thread id: the executor gives one thread per consumer
    // task, and the fake driver's execute answer runs on the worker that drew the split.
    Map<Long, AtomicInteger> splitsPerWorker = new ConcurrentHashMap<>();
    CountDownLatch releaseHotSplit = new CountDownLatch(1);
    AtomicLong hotWorker = new AtomicLong(-1);
    AtomicInteger releasedAfter = new AtomicInteger();

    CqlSession session =
        FakeCluster.lazySession(
            8,
            ordinal -> {
              long worker = Thread.currentThread().threadId();
              int done =
                  splitsPerWorker
                      .computeIfAbsent(worker, key -> new AtomicInteger())
                      .incrementAndGet();
              if (ordinal == 0) {
                // The hot split: huge, and it blocks until somebody else has drained the ring.
                hotWorker.set(worker);
                awaitQuietly(releaseHotSplit);
                return HOT_ROWS;
              }
              if (done >= RELEASE_AFTER && releaseHotSplit.getCount() > 0) {
                releasedAfter.compareAndSet(0, done);
                releaseHotSplit.countDown();
              }
              return COLD_ROWS;
            },
            () -> {});

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
            .unloadTo(
                session,
                request,
                OutputStream.nullOutputStream(),
                ProgressListener.noop(),
                Cancellation.never());

    assertThat(releaseHotSplit.await(0, TimeUnit.SECONDS))
        .as("the hot split was released by another worker's progress, not by a timeout")
        .isTrue();
    assertThat(result.splitsCompleted()).isEqualTo(SPLITS);
    assertThat(result.rowsWritten()).isEqualTo((long) HOT_ROWS + (long) (SPLITS - 1) * COLD_ROWS);

    int evenShare = SPLITS / CONSUMERS;
    int busiest =
        splitsPerWorker.values().stream().mapToInt(AtomicInteger::get).max().orElseThrow();

    assertThat(splitsPerWorker.keySet())
        .as("no more workers than consumers were used")
        .hasSizeLessThanOrEqualTo(CONSUMERS);
    assertThat(splitsPerWorker.values().stream().mapToInt(AtomicInteger::get).sum())
        .as("every split was executed exactly once")
        .isEqualTo(SPLITS);

    // THE assertion. A partitioned engine gives every worker exactly evenShare and can never get
    // here; only a shared queue lets a free worker steal the work its stuck peer never touched.
    assertThat(busiest)
        .as(
            "busiest worker's splits (%d) vs the even share a one-split-per-worker split would"
                + " force (%d)",
            busiest, evenShare)
        .isGreaterThan(evenShare * 3 / 2);
    assertThat(hotWorker.get()).as("the hot split really did run on a worker").isNotEqualTo(-1L);
  }

  private static void awaitQuietly(CountDownLatch latch) {
    try {
      // Bounded so a regression fails the assertions instead of hanging the build.
      latch.await(60, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
