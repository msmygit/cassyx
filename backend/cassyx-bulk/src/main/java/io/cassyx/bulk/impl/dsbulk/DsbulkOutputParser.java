package io.cassyx.bulk.impl.dsbulk;

import io.cassyx.bulk.api.dsbulk.DsbulkLogLine;
import io.cassyx.bulk.api.dsbulk.DsbulkProgress;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scrapes progress out of the DSBulk child process's output (plan section 5.3: "parse the runner's
 * exit status and tail its log directory for progress").
 *
 * <p>There is no IPC channel to an out-of-process DSBulk - the price of the isolation - so its own
 * periodic reporter is the progress signal. This parser is deliberately tolerant: it recognises the
 * report line in several shapes and returns {@code null} rather than throwing on anything it does
 * not understand, because a reporter-format change upstream must degrade progress reporting, never
 * fail the job.
 */
public final class DsbulkOutputParser {

  /** {@code total: 1,234,567} / {@code successful: 1,234} - the count DSBulk reports for a phase. */
  private static final Pattern TOTAL = Pattern.compile("total:\\s*([0-9,]+)", Pattern.CASE_INSENSITIVE);

  private static final Pattern FAILED = Pattern.compile("failed:\\s*([0-9,]+)", Pattern.CASE_INSENSITIVE);

  /** {@code 1,234 reads/second} / {@code 987 writes/second}. */
  private static final Pattern RATE =
      Pattern.compile("([0-9,]+(?:\\.[0-9]+)?)\\s*(?:reads|writes|records|rows)?/(?:second|sec|s)\\b",
          Pattern.CASE_INSENSITIVE);

  /** A standard logback line: {@code 2026-08-17 12:00:03 INFO  message}. */
  private static final Pattern LOG_LINE =
      Pattern.compile("^\\s*(?:\\d{4}-\\d{2}-\\d{2}[ T]\\d{2}:\\d{2}:\\d{2}[.,]?\\d*\\s+)?"
          + "(TRACE|DEBUG|INFO|WARN|WARNING|ERROR)\\s+(.*)$");

  /** {@code Operation UNLOAD_20260817-120003-123456 completed successfully in 53 seconds.} */
  private static final Pattern PHASE =
      Pattern.compile("Operation\\s+(\\S+)\\s+(started|completed|failed|aborted)", Pattern.CASE_INSENSITIVE);

  private DsbulkOutputParser() {}

  /**
   * True when the line came from the logger rather than from a workflow's own stdout.
   *
   * <p>The {@code count} workflow prints its report to stdout with no timestamp or level, so this
   * predicate is what separates the report from the log around it.
   */
  public static boolean isLogLine(String raw) {
    return raw != null && LOG_LINE.matcher(raw).matches();
  }

  /** Normalises one raw output line into the contract's {@code JobLogEvent} shape. */
  public static DsbulkLogLine toLogLine(String raw) {
    if (raw == null) {
      return null;
    }
    Matcher matcher = LOG_LINE.matcher(raw);
    if (matcher.matches()) {
      String level = matcher.group(1).toUpperCase(Locale.ROOT);
      return new DsbulkLogLine("WARNING".equals(level) ? "WARN" : level, matcher.group(2).trim(), raw);
    }
    return new DsbulkLogLine("INFO", raw.trim(), raw);
  }

  /**
   * A progress tick, or {@code null} when the line carries no counters.
   *
   * @param previous the last tick, so a line reporting only a rate keeps the previous row count
   */
  public static DsbulkProgress toProgress(String raw, DsbulkProgress previous) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    DsbulkProgress last = previous == null ? DsbulkProgress.NONE : previous;

    Matcher totalMatcher = TOTAL.matcher(raw);
    Matcher failedMatcher = FAILED.matcher(raw);
    Matcher rateMatcher = RATE.matcher(raw);
    boolean sawTotal = totalMatcher.find();
    boolean sawFailed = failedMatcher.find();
    boolean sawRate = rateMatcher.find();
    if (!sawTotal && !sawRate) {
      return phaseOnly(raw, last);
    }

    long rows = sawTotal ? parseCount(totalMatcher.group(1)) : last.rowsProcessed();
    long failures = sawFailed ? parseCount(failedMatcher.group(1)) : last.failures();
    long rate = sawRate ? (long) parseDecimal(rateMatcher.group(1)) : last.rowsPerSecond();
    return new DsbulkProgress(rows, rate, failures, last.phase());
  }

  private static DsbulkProgress phaseOnly(String raw, DsbulkProgress last) {
    Matcher phase = PHASE.matcher(raw);
    if (!phase.find()) {
      return null;
    }
    return new DsbulkProgress(
        last.rowsProcessed(), last.rowsPerSecond(), last.failures(),
        phase.group(2).toUpperCase(Locale.ROOT));
  }

  /** {@code 1,234,567} to {@code 1234567}. DSBulk always renders counters with grouping. */
  static long parseCount(String text) {
    try {
      return Long.parseLong(text.replace(",", "").replace("_", "").trim());
    } catch (NumberFormatException e) {
      return 0L;
    }
  }

  static double parseDecimal(String text) {
    try {
      return Double.parseDouble(text.replace(",", "").trim());
    } catch (NumberFormatException e) {
      return 0d;
    }
  }
}
