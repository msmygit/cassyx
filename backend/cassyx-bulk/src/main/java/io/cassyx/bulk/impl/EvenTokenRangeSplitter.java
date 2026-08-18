package io.cassyx.bulk.impl;

import com.datastax.oss.driver.api.core.metadata.token.TokenRange;
import io.cassyx.bulk.api.TokenRangeSplitter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The plan section 5.2 split step:
 *
 * <pre>{@code
 * splits = ranges.flatMap(r -> r.splitEvenly(k)).flatMap(TokenRange::unwrap)
 * }</pre>
 *
 * <p><b>Trap 1 - {@code unwrap()} is not optional.</b> Exactly one range on a Cassandra ring wraps
 * the minimum token ({@code (max, min]} in ring order). CQL has no way to express it: {@code token(pk)
 * > 9000 AND token(pk) <= -9000} matches nothing, so the rows in that range vanish from the export
 * with no error anywhere. {@code unwrap()} rewrites it as the two non-wrapping halves. It is applied
 * <em>after</em> {@code splitEvenly} because splitting a wrapping range is what produces a wrapping
 * sub-range in the first place.
 *
 * <p><b>Trap 2 - {@code splitEvenly(n)} divides by token count, not data volume.</b> Under partition
 * skew (the seeded {@code demo.sensor_readings} HOT partition is ~20k rows in one token) equal-token
 * splits take wildly unequal wall time. The answer is not smarter splitting, it is <em>more</em>
 * splitting: oversplit to ~10k and let a work-stealing queue even it out. One-split-per-worker makes
 * the whole unload as slow as its unluckiest worker.
 */
public final class EvenTokenRangeSplitter implements TokenRangeSplitter {

  private static final Logger LOG = LoggerFactory.getLogger(EvenTokenRangeSplitter.class);

  @Override
  public List<TokenRange> split(Collection<TokenRange> ranges, int targetSplits) {
    if (ranges == null || ranges.isEmpty()) {
      return List.of();
    }
    int perRange = splitsPerRange(ranges.size(), targetSplits);
    List<TokenRange> splits = new ArrayList<>(Math.max(16, ranges.size() * perRange));
    for (TokenRange range : ranges) {
      for (TokenRange piece : splitEvenlySafely(range, perRange)) {
        // unwrap() ALWAYS - see the class javadoc. For a non-wrapping range it returns
        // singletonList(this), so this is free in the common case.
        splits.addAll(piece.unwrap());
      }
    }
    splits.removeIf(TokenRange::isEmpty);
    LOG.debug(
        "Split {} token range(s) into {} unwrapped split(s) (target {})",
        ranges.size(),
        splits.size(),
        targetSplits);
    return List.copyOf(splits);
  }

  /**
   * Ceil-divides the target across the ring's ranges, never below 1.
   *
   * <p>Visible for testing: this is pure arithmetic and the place an off-by-one would quietly halve
   * the parallelism.
   */
  public static int splitsPerRange(int rangeCount, int targetSplits) {
    if (rangeCount <= 0) {
      return 1;
    }
    if (targetSplits <= rangeCount) {
      return 1;
    }
    return (targetSplits + rangeCount - 1) / rangeCount;
  }

  /**
   * {@code splitEvenly} throws when a range holds fewer tokens than the requested split count (a
   * genuine case on small vnode rings and on single-token test clusters). Falling back to the
   * unsplit range keeps coverage total, which is the property that actually matters.
   */
  private static List<TokenRange> splitEvenlySafely(TokenRange range, int perRange) {
    if (perRange <= 1) {
      return List.of(range);
    }
    try {
      return range.splitEvenly(perRange);
    } catch (RuntimeException e) {
      LOG.debug("Range {} could not be split into {} pieces; using it whole", range, perRange);
      return List.of(range);
    }
  }
}
