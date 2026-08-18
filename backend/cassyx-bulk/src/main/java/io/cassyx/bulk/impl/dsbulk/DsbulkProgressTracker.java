package io.cassyx.bulk.impl.dsbulk;

import io.cassyx.bulk.api.dsbulk.DsbulkProgress;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the DSBulk child process's output stream into progress ticks.
 *
 * <p>Out-of-process DSBulk has no IPC channel back to cassyx - that is the price of the isolation
 * plan section 5.3 requires - so its own console reporter is the progress signal. cassyx therefore
 * leaves {@code monitoring.console} ON and sets {@code log.ansiMode = disabled}, which turns the
 * reporter from an in-place ANSI redraw into two plain appended lines every
 * {@code monitoring.reportRate}:
 *
 * <pre>
 * total | failed | rows/s | p50ms | p99ms | p999ms | batches
 *    15 |      0 |     59 | 12.14 | 17.83 |  17.83 |    3.00
 * </pre>
 *
 * <p><b>The column set is dynamic.</b> Columns appear and disappear with {@code trackBytes},
 * {@code expectedWrites}, batching, and terminal width, and the unit suffix on the latency columns
 * follows {@code monitoring.durationUnit}. Reading columns by position is therefore a latent bug
 * waiting for someone to enable byte tracking; this tracker reads the header row and maps by NAME.
 *
 * <p>Stateful by design (it remembers the header and the last tick), so it is one instance per job
 * and not thread-safe - the runner drives it from a single reader thread.
 */
public final class DsbulkProgressTracker {

  /** {@code Operation directory: /var/lib/cassyx/jobs/42/logs/UNLOAD_20260817-120003-123456} */
  private static final Pattern OPERATION_DIRECTORY =
      Pattern.compile("Operation directory:\\s*(\\S+)", Pattern.CASE_INSENSITIVE);

  private static final Pattern NUMERIC = Pattern.compile("-?[0-9][0-9,]*(?:\\.[0-9]+)?");

  private List<String> columns = List.of();
  private DsbulkProgress last = DsbulkProgress.NONE;
  private String operationDirectory;

  /** @return a new progress tick, or {@code null} when this line carried no progress */
  public DsbulkProgress accept(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    Matcher directory = OPERATION_DIRECTORY.matcher(raw);
    if (directory.find()) {
      operationDirectory = directory.group(1);
      return null;
    }
    if (isHeaderRow(raw)) {
      columns = splitCells(raw);
      return null;
    }
    DsbulkProgress fromTable = fromTableRow(raw);
    if (fromTable != null) {
      last = fromTable;
      return fromTable;
    }
    // Fall back to the stats block DSBulk writes into operation.log at the end of the run:
    // "Records: total: 15, successful: 15, failed: 0" / "Throughput: 56 writes/second".
    DsbulkProgress fromLog = DsbulkOutputParser.toProgress(raw, last);
    if (fromLog != null) {
      last = fromLog;
    }
    return fromLog;
  }

  public DsbulkProgress current() {
    return last;
  }

  /** The per-operation log directory DSBulk announced, once it has said so. */
  public String operationDirectory() {
    return operationDirectory;
  }

  static boolean isHeaderRow(String raw) {
    if (raw.indexOf('|') < 0) {
      return false;
    }
    String first = splitCells(raw).isEmpty() ? "" : splitCells(raw).get(0);
    return "total".equals(first.toLowerCase(Locale.ROOT));
  }

  private DsbulkProgress fromTableRow(String raw) {
    if (columns.isEmpty() || raw.indexOf('|') < 0) {
      return null;
    }
    List<String> cells = splitCells(raw);
    if (cells.size() != columns.size()) {
      return null;
    }
    long rows = last.rowsProcessed();
    long failures = last.failures();
    long rate = last.rowsPerSecond();
    boolean matched = false;
    for (int i = 0; i < columns.size(); i++) {
      String cell = cells.get(i);
      if (!NUMERIC.matcher(cell).matches()) {
        return null; // not a data row: a stray log line that happens to contain a pipe
      }
      String column = columns.get(i).toLowerCase(Locale.ROOT);
      if ("total".equals(column)) {
        rows = (long) DsbulkOutputParser.parseDecimal(cell);
        matched = true;
      } else if ("failed".equals(column)) {
        failures = (long) DsbulkOutputParser.parseDecimal(cell);
        matched = true;
      } else if (column.endsWith("/s") && !column.startsWith("mb")) {
        // rows/s, vertices/s, edges/s - the throughput column, whatever DSBulk called it.
        rate = (long) DsbulkOutputParser.parseDecimal(cell);
        matched = true;
      }
    }
    return matched ? new DsbulkProgress(rows, rate, failures, last.phase()) : null;
  }

  static List<String> splitCells(String raw) {
    List<String> cells = new ArrayList<>();
    for (String cell : raw.split("\\|")) {
      cells.add(cell.trim());
    }
    while (!cells.isEmpty() && cells.get(cells.size() - 1).isEmpty()) {
      cells.remove(cells.size() - 1);
    }
    return cells;
  }
}
