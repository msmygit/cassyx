package io.cassyx.bulk.impl.dsbulk;

import static org.assertj.core.api.Assertions.assertThat;

import io.cassyx.bulk.api.dsbulk.DsbulkCountReport;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The conservation law the count report has to obey (plan sections 5.4, 11.1).
 *
 * <p>Two sums are the whole correctness argument for a statistics parser, because they cannot be
 * satisfied by accident:
 *
 * <ul>
 *   <li><b>{@code sum(perTokenRange) == N}.</b> Token ranges partition the ring exactly once, so
 *       their counts add up to the total and nothing else. Off-by-one column reads, percentage
 *       columns mistaken for counts and dropped rows all break this immediately.
 *   <li><b>{@code sum(perReplica) == N x RF}.</b> Per-replica counts do NOT partition the data -
 *       every row is counted once per replica that holds it. A parser that quietly treats the two
 *       sections as interchangeable produces a total that is off by exactly the replication factor,
 *       which is the kind of wrong that looks plausible on a screen.
 * </ul>
 *
 * <p>The fixtures are deliberately SKEWED. A uniform distribution passes a sum check even if the
 * parser is reading the wrong column, because every row happens to carry the same number.
 */
class DsbulkCountConservationTest {

  private static final int REPLICATION_FACTOR = 3;

  @Test
  @DisplayName("sum(perTokenRange) == N and sum(perReplica) == N x RF over a skewed dataset")
  void conservation() {
    Distribution data = skewed(new Random(20260818), 96, 6);
    DsbulkCountReport report = DsbulkCountParser.parse(render(data));

    assertThat(report.totalRows()).isEqualTo(data.total());
    assertThat(sumRanges(report)).isEqualTo(data.total());
    assertThat(sumReplicas(report)).isEqualTo(data.total() * REPLICATION_FACTOR);
    assertThat(report.perTokenRange()).hasSize(96);
    assertThat(report.perReplica()).hasSize(6);
  }

  @Test
  @DisplayName("the law holds across many random skews, not just the one that was tuned against")
  void conservationHoldsUnderRandomSkew() {
    Random random = new Random(7);
    for (int trial = 0; trial < 50; trial++) {
      int ranges = 1 + random.nextInt(64);
      int nodes = 1 + random.nextInt(12);
      Distribution data = skewed(random, ranges, nodes);
      DsbulkCountReport report = DsbulkCountParser.parse(render(data));

      assertThat(sumRanges(report)).as("ranges, trial %d", trial).isEqualTo(data.total());
      assertThat(sumReplicas(report)).as("replicas, trial %d", trial)
          .isEqualTo(data.total() * REPLICATION_FACTOR);
    }
  }

  @Test
  @DisplayName("without a global line the total is derived from the RANGES, never from the replicas")
  void totalComesFromRangesNotReplicas() {
    Distribution data = skewed(new Random(11), 24, 4);
    List<String> lines = new ArrayList<>(render(data));
    lines.remove(0); // the "Total rows:" header
    lines.remove(0); // the global count itself

    DsbulkCountReport report = DsbulkCountParser.parse(lines);

    // Summing the replica section instead would report RF times too many rows.
    assertThat(report.totalRows()).isEqualTo(data.total());
    assertThat(report.totalRows()).isNotEqualTo(data.total() * REPLICATION_FACTOR);
  }

  @Test
  @DisplayName("Long.MIN_VALUE survives the parse as text - it does not survive a JS number")
  void minimumTokenKeepsItsDigits() {
    String min = String.valueOf(Long.MIN_VALUE);
    String max = String.valueOf(Long.MAX_VALUE);

    DsbulkCountReport report = DsbulkCountParser.parse(List.of(
        "Total rows per token range:",
        min + " " + max + " 42 100.00"));

    DsbulkCountReport.RangeCount range = report.perTokenRange().get(0);
    assertThat(range.start()).isEqualTo("-9223372036854775808");
    assertThat(range.end()).isEqualTo("9223372036854775807");
    assertThat(range.rows()).isEqualTo(42);

    // The guard that matters. A token is int64; a JavaScript number is an IEEE-754 double with 53
    // bits of mantissa, so the top of the ring cannot be represented and comes back as a DIFFERENT
    // token. Carrying tokens as strings end to end is what makes that impossible, and this asserts
    // the hazard is real rather than theoretical.
    long neighbour = Long.MAX_VALUE - 1;
    assertThat((long) (double) neighbour).isNotEqualTo(neighbour);
    assertThat(range.start()).isEqualTo(min);
    assertThat(range.end()).isEqualTo(max);
  }

  /* --------------------------------------------------------------------------- fixtures */

  private record Distribution(long total, long[] perRange, long[] perReplica) {}

  /**
   * A skewed dataset whose sections are consistent by construction: ranges sum to the total,
   * replicas sum to the total times RF.
   */
  private static Distribution skewed(Random random, int ranges, int nodes) {
    long[] perRange = new long[ranges];
    long total = 0;
    for (int i = 0; i < ranges; i++) {
      // Heavy tail: most ranges nearly empty, a couple holding the bulk. This is what real partition
      // skew looks like and it is the case an averaged fixture never exercises.
      long rows = i % 17 == 0 ? 10_000L + random.nextInt(90_000) : random.nextInt(50);
      perRange[i] = rows;
      total += rows;
    }

    long[] perReplica = new long[nodes];
    long replicated = total * REPLICATION_FACTOR;
    for (int i = 0; i < nodes - 1; i++) {
      long share = replicated / nodes;
      perReplica[i] = share;
      replicated -= share;
    }
    perReplica[nodes - 1] = replicated;
    return new Distribution(total, perRange, perReplica);
  }

  /** The exact stdout shape DSBulk 1.11 prints for {@code stats.modes=[global,ranges,hosts]}. */
  private static List<String> render(Distribution data) {
    List<String> lines = new ArrayList<>();
    lines.add("Total rows:");
    lines.add(Long.toString(data.total()));
    lines.add("Total rows per token range:");
    long start = Long.MIN_VALUE;
    for (int i = 0; i < data.perRange().length; i++) {
      long end = start + (Long.MAX_VALUE / Math.max(1, data.perRange().length));
      lines.add(start + " " + end + " " + data.perRange()[i] + " " + percent(data.perRange()[i], data.total()));
      start = end;
    }
    lines.add("Total rows per node:");
    for (int i = 0; i < data.perReplica().length; i++) {
      lines.add("/10.0.0." + (i + 1) + ":9042 " + data.perReplica()[i]
          + " " + percent(data.perReplica()[i], data.total() * REPLICATION_FACTOR));
    }
    return lines;
  }

  private static String percent(long part, long whole) {
    return whole == 0 ? "0.00" : String.format("%.2f", 100.0 * part / whole);
  }

  private static long sumRanges(DsbulkCountReport report) {
    return report.perTokenRange().stream().mapToLong(DsbulkCountReport.RangeCount::rows).sum();
  }

  private static long sumReplicas(DsbulkCountReport report) {
    return report.perReplica().stream().mapToLong(DsbulkCountReport.ReplicaCount::rows).sum();
  }
}
