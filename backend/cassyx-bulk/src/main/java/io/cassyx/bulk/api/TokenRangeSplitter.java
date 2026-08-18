package io.cassyx.bulk.api;

import com.datastax.oss.driver.api.core.metadata.token.TokenRange;
import java.util.Collection;
import java.util.List;

/**
 * Turns the cluster's owned token ranges into the oversplit work set of plan section 5.2.
 *
 * <p>Two invariants every implementation must hold, because violating either is silent data loss -
 * the worst failure mode this product has:
 *
 * <ol>
 *   <li><b>{@code unwrap()} always.</b> CQL cannot express the range that wraps the ring minimum;
 *       querying it returns wrong results with no error.
 *   <li><b>The union of the returned splits equals the union of the input ranges, exactly once.</b>
 *       No gaps, no overlaps.
 * </ol>
 */
public interface TokenRangeSplitter {

  /**
   * @param ranges the cluster's token ranges, from {@code TokenMap#getTokenRanges()}
   * @param targetSplits total number of splits wanted across all ranges; oversplit heavily
   *     ({@link UnloadRequest#DEFAULT_SPLITS}) because {@code splitEvenly} divides by token count,
   *     not data volume
   * @return unwrapped, non-empty splits in a stable order
   */
  List<TokenRange> split(Collection<TokenRange> ranges, int targetSplits);
}
