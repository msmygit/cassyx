package io.cassyx.bulk.impl.dsbulk;

import io.cassyx.bulk.api.dsbulk.DsbulkCountReport;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Parses the stdout of the DSBulk {@code count} workflow into the Statistics tab's data model
 * (plan section 5.4).
 *
 * <p>{@code count} writes no structured report. It prints space-separated columns to {@code stdout}
 * (progress and logging go to stderr), in a shape that depends on the {@code stats.modes} selected,
 * verified against DSBulk 1.11:
 *
 * <pre>
 * global      15
 * hosts       /127.0.0.1:9042 15 100.00              &lt;endpoint&gt; &lt;rows&gt; &lt;percent&gt;
 * ranges      -8195964801255556181 -7228600558177020682 5 33.33
 *                                                    &lt;startToken&gt; &lt;endToken&gt; &lt;rows&gt; &lt;percent&gt;
 * partitions  5 5 33.33                              &lt;partitionKey&gt; &lt;rows&gt; &lt;percent&gt;
 * </pre>
 *
 * <p>Two traps, both of which this parser exists to survive:
 *
 * <ul>
 *   <li><b>The last column is a percentage, not a count.</b> Reading it as the row count produces
 *       plausible-looking totals that are quietly wrong.
 *   <li><b>{@code hosts} and {@code partitions} rows have identical arity.</b> When more than one
 *       mode is requested DSBulk emits section headers ({@code Total rows per node:} and friends),
 *       which are authoritative; with a single mode there is no header and the shape of the first
 *       column decides.
 * </ul>
 *
 * <p>Tokens stay STRINGS throughout. Murmur3 tokens use the full signed 64-bit range and lose
 * precision the moment they become a JavaScript number.
 */
public final class DsbulkCountParser {

  private DsbulkCountParser() {}

  /** Section headers DSBulk prints when more than one statistics mode was requested. */
  private enum Section {
    UNKNOWN,
    GLOBAL,
    HOSTS,
    RANGES,
    PARTITIONS
  }

  public static DsbulkCountReport parse(List<String> lines) {
    long total = 0;
    List<DsbulkCountReport.ReplicaCount> replicas = new ArrayList<>();
    List<DsbulkCountReport.RangeCount> ranges = new ArrayList<>();
    List<DsbulkCountReport.PartitionCount> partitions = new ArrayList<>();
    Section section = Section.UNKNOWN;

    for (String raw : lines == null ? List.<String>of() : lines) {
      if (raw == null || raw.isBlank()) {
        continue;
      }
      Section header = headerOf(raw);
      if (header != Section.UNKNOWN) {
        section = header;
        continue;
      }
      String[] parts = raw.trim().split("\\s+");

      if (parts.length == 1) {
        if (isLong(parts[0])) {
          total = Math.max(total, parseLong(parts[0]));
        }
        continue;
      }
      if (parts.length == 4 && isLong(parts[0]) && isLong(parts[1]) && isLong(parts[2])) {
        ranges.add(new DsbulkCountReport.RangeCount(parts[0], parts[1], parseLong(parts[2])));
        continue;
      }
      if (parts.length == 3 && isLong(parts[1])) {
        long rows = parseLong(parts[1]);
        if (section == Section.HOSTS || (section == Section.UNKNOWN && isEndpoint(parts[0]))) {
          replicas.add(new DsbulkCountReport.ReplicaCount(normaliseEndpoint(parts[0]), rows));
        } else {
          partitions.add(new DsbulkCountReport.PartitionCount(parts[0], rows));
        }
      }
    }

    if (total == 0) {
      // No global mode requested. The total is still knowable, and the Statistics tab needs it -
      // ranges first, because per-range counts partition the data exactly once, whereas per-replica
      // counts multiply by the replication factor.
      total = sumRanges(ranges);
      if (total == 0) {
        total = sumReplicas(replicas);
      }
    }

    partitions.sort((a, b) -> Long.compare(b.rows(), a.rows()));
    return new DsbulkCountReport(total, List.copyOf(replicas), List.copyOf(ranges), List.copyOf(partitions));
  }

  private static long sumRanges(List<DsbulkCountReport.RangeCount> ranges) {
    return ranges.stream().mapToLong(DsbulkCountReport.RangeCount::rows).sum();
  }

  private static long sumReplicas(List<DsbulkCountReport.ReplicaCount> replicas) {
    return replicas.stream().mapToLong(DsbulkCountReport.ReplicaCount::rows).sum();
  }

  static Section headerOf(String raw) {
    String text = raw.trim().toLowerCase(Locale.ROOT);
    if (!text.startsWith("total rows")) {
      return Section.UNKNOWN;
    }
    if (text.contains("per node")) {
      return Section.HOSTS;
    }
    if (text.contains("per token range")) {
      return Section.RANGES;
    }
    if (text.contains("per partition")) {
      return Section.PARTITIONS;
    }
    return Section.GLOBAL;
  }

  static boolean isLong(String text) {
    if (text == null || text.isEmpty()) {
      return false;
    }
    try {
      Long.parseLong(text.replace(",", ""));
      return true;
    } catch (NumberFormatException e) {
      return false;
    }
  }

  static long parseLong(String text) {
    return Long.parseLong(text.replace(",", ""));
  }

  /** {@code /10.0.0.1:9042} or {@code host:9042}: a colon with a non-numeric head. */
  static boolean isEndpoint(String text) {
    int colon = text.lastIndexOf(':');
    return colon > 0 && !isLong(text.substring(0, colon));
  }

  static String normaliseEndpoint(String text) {
    return text.startsWith("/") ? text.substring(1) : text;
  }
}
