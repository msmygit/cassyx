package io.cassyx.bulk.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.datastax.oss.driver.api.core.metadata.token.TokenRange;
import com.datastax.oss.driver.internal.core.metadata.token.Murmur3Token;
import com.datastax.oss.driver.internal.core.metadata.token.Murmur3TokenRange;
import io.cassyx.bulk.api.TokenRangeSplitter;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The correctness core of plan section 5.2, tested against a real Murmur3 ring with no cluster.
 *
 * <p>Everything here is one property: <b>the splits must tile the ring exactly once</b>. A gap is
 * rows missing from an export; an overlap is rows duplicated. Neither raises an error anywhere in
 * the stack, which is what makes this the highest-value test in the module.
 *
 * <p>The companion end-to-end proof - union of splits equals the real {@code count(*)} over the
 * skewed seed table - lives in {@code TokenRangeUnloadIT}.
 */
class EvenTokenRangeSplitterTest {

  /** Murmur3 ring bounds; the minimum token is both the ring start and the ring end marker. */
  private static final BigInteger RING_MIN = BigInteger.valueOf(Long.MIN_VALUE);

  private static final BigInteger RING_END = BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE);

  private final TokenRangeSplitter splitter = new EvenTokenRangeSplitter();

  private static TokenRange range(long start, long end) {
    return new Murmur3TokenRange(new Murmur3Token(start), new Murmur3Token(end));
  }

  /**
   * A ring of {@code vnodes} contiguous ranges, the last of which wraps the minimum token - exactly
   * the shape {@code TokenMap#getTokenRanges()} returns from a real cluster.
   */
  private static Set<TokenRange> ring(int vnodes) {
    Set<TokenRange> ranges = new LinkedHashSet<>();
    BigInteger step = RING_END.subtract(RING_MIN).divide(BigInteger.valueOf(vnodes));
    long previous = Long.MIN_VALUE;
    for (int i = 1; i < vnodes; i++) {
      long boundary = RING_MIN.add(step.multiply(BigInteger.valueOf(i))).longValueExact();
      ranges.add(range(previous, boundary));
      previous = boundary;
    }
    // The wrapping range: (last boundary, MIN]. This is the one that silently loses rows if the
    // engine forgets to unwrap it.
    ranges.add(range(previous, Long.MIN_VALUE));
    return ranges;
  }

  /**
   * Maps a split onto a half-open numeric interval {@code (start, end]}, resolving the ring-end
   * marker. A split whose end is the minimum token does not mean "ends at the bottom of the ring";
   * it means "ends at the top". Getting this wrong in the engine is precisely the {@code unwrap()}
   * bug.
   */
  private static BigInteger[] interval(TokenRange split) {
    long start = ((Murmur3Token) split.getStart()).getValue();
    long end = ((Murmur3Token) split.getEnd()).getValue();
    return new BigInteger[] {
      BigInteger.valueOf(start), end == Long.MIN_VALUE ? RING_END : BigInteger.valueOf(end)
    };
  }

  private static void assertTilesTheRingExactlyOnce(List<TokenRange> splits) {
    List<BigInteger[]> intervals = new ArrayList<>(splits.size());
    for (TokenRange split : splits) {
      intervals.add(interval(split));
    }
    intervals.sort(Comparator.comparing(i -> i[0]));

    assertThat(intervals.get(0)[0]).as("first split must start at the ring minimum").isEqualTo(RING_MIN);
    assertThat(intervals.get(intervals.size() - 1)[1])
        .as("last split must reach the ring end")
        .isEqualTo(RING_END);

    for (int i = 1; i < intervals.size(); i++) {
      BigInteger previousEnd = intervals.get(i - 1)[1];
      BigInteger currentStart = intervals.get(i)[0];
      assertThat(currentStart)
          .as(
              "split %d starts at %s but the previous one ended at %s - a mismatch is a gap "
                  + "(rows silently missing) or an overlap (rows silently duplicated)",
              i, currentStart, previousEnd)
          .isEqualTo(previousEnd);
    }
  }

  @Test
  @DisplayName("splits tile the whole ring exactly once - no gaps, no duplicates")
  void splitsTileTheRingExactlyOnce() {
    assertTilesTheRingExactlyOnce(splitter.split(ring(16), 10_000));
  }

  @ParameterizedTest(name = "ring of {0} vnodes stays gap-free when oversplit")
  @ValueSource(ints = {1, 2, 3, 8, 16, 64, 256})
  void tilesTheRingForEveryRingSize(int vnodes) {
    assertTilesTheRingExactlyOnce(splitter.split(ring(vnodes), 10_000));
  }

  @ParameterizedTest(name = "ring stays gap-free at a target of {0} splits")
  @ValueSource(ints = {1, 2, 7, 100, 1_000, 10_000})
  void tilesTheRingForEverySplitTarget(int target) {
    assertTilesTheRingExactlyOnce(splitter.split(ring(8), target));
  }

  @Test
  @DisplayName("the wrapping range is unwrapped, never queried as-is")
  void unwrapsTheWrappingRange() {
    // A single range spanning the whole ring: (MIN, MIN] wraps by definition.
    TokenRange wrapping = range(Long.MIN_VALUE, Long.MIN_VALUE);
    List<TokenRange> splits = splitter.split(Set.of(wrapping), 4);

    assertThat(splits).isNotEmpty();
    for (TokenRange split : splits) {
      // unwrap() on an already-unwrapped range is the identity; anything else means a wrapping
      // range survived into the query path, where `token(pk) > big AND token(pk) <= small`
      // matches nothing and the export loses those rows without an error.
      assertThat(split.unwrap()).as("split %s is still wrapping", split).hasSize(1);
    }
    assertTilesTheRingExactlyOnce(splits);
  }

  @Test
  @DisplayName("oversplitting actually produces the requested order of magnitude")
  void oversplitsFarBeyondTheWorkerCount() {
    // The whole point of plan section 5.2: ~10k splits against a handful of workers, so a worker
    // that draws the skewed partition cannot hold the job back.
    List<TokenRange> splits = splitter.split(ring(16), 10_000);
    assertThat(splits.size()).isGreaterThanOrEqualTo(10_000);
  }

  @Test
  @DisplayName("empty and null input yield no work rather than an exception")
  void handlesEmptyInput() {
    assertThat(splitter.split(Set.of(), 1000)).isEmpty();
    assertThat(splitter.split(null, 1000)).isEmpty();
  }

  @Test
  @DisplayName("empty ranges are dropped - querying one is pure overhead")
  void dropsEmptyRanges() {
    // (x, x] with x != MIN is the empty range in driver semantics.
    TokenRange empty = range(42L, 42L);
    assertThat(splitter.split(Set.of(empty), 4)).isEmpty();
  }

  @Test
  @DisplayName("a range too small to split is used whole rather than dropped")
  void keepsRangesThatCannotBeSplit() {
    // Two adjacent tokens cannot be divided into 1000 pieces. Dropping it would lose its rows.
    List<TokenRange> splits = splitter.split(Set.of(range(10L, 11L)), 1000);
    assertThat(splits).hasSize(1);
    assertThat(interval(splits.get(0)))
        .containsExactly(BigInteger.valueOf(10L), BigInteger.valueOf(11L));
  }

  @ParameterizedTest(name = "{0} ranges, target {1} -> {2} per range")
  @CsvSource({
    "0, 10000, 1",
    "16, 0, 1",
    "16, 16, 1",
    "16, 10000, 625",
    "3, 10, 4",
    "7, 10000, 1429",
  })
  void splitsPerRangeCeilDivides(int rangeCount, int target, int expected) {
    assertThat(EvenTokenRangeSplitter.splitsPerRange(rangeCount, target)).isEqualTo(expected);
  }
}
