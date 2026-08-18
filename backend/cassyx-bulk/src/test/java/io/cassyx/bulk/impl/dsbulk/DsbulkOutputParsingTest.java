package io.cassyx.bulk.impl.dsbulk;

import static org.assertj.core.api.Assertions.assertThat;

import io.cassyx.bulk.api.dsbulk.DsbulkCountReport;
import io.cassyx.bulk.api.dsbulk.DsbulkLogLine;
import io.cassyx.bulk.api.dsbulk.DsbulkProgress;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Parsing DSBulk's own output - the only progress and statistics channel an out-of-process runner
 * has. Every fixture here is a real line captured from DSBulk 1.11.
 */
class DsbulkOutputParsingTest {

  /* ------------------------------------------------------------------- progress reporter */

  @Test
  @DisplayName("the console reporter's table is read BY COLUMN NAME, not by position")
  void consoleReporterTable() {
    DsbulkProgressTracker tracker = new DsbulkProgressTracker();
    assertThat(tracker.accept("total | failed | rows/s | p50ms | p99ms | p999ms | batches")).isNull();

    DsbulkProgress progress = tracker.accept("   15 |      0 |     59 | 12.14 | 17.83 |  17.83 |    3.00");
    assertThat(progress).isNotNull();
    assertThat(progress.rowsProcessed()).isEqualTo(15);
    assertThat(progress.failures()).isZero();
    assertThat(progress.rowsPerSecond()).isEqualTo(59);
  }

  @Test
  @DisplayName("a different column set - trackBytes on - still resolves total/failed/rate correctly")
  void dynamicColumnSet() {
    DsbulkProgressTracker tracker = new DsbulkProgressTracker();
    tracker.accept("total | failed | rows/s | mb/s | kb/row | p50ms | p99ms | p999ms");
    DsbulkProgress progress =
        tracker.accept("1,000,000 | 12 | 186,420 | 22.40 | 0.12 | 12.14 | 17.83 | 17.83");
    assertThat(progress).isNotNull();
    assertThat(progress.rowsProcessed()).isEqualTo(1_000_000);
    assertThat(progress.failures()).isEqualTo(12);
    // mb/s must not be mistaken for the row rate just because it also ends in "/s".
    assertThat(progress.rowsPerSecond()).isEqualTo(186_420);
  }

  @Test
  @DisplayName("a data row before any header is ignored rather than guessed at")
  void dataRowWithoutHeaderIsIgnored() {
    assertThat(new DsbulkProgressTracker().accept("   15 |      0 |     59")).isNull();
  }

  @Test
  @DisplayName("a log line containing a pipe is not mistaken for a progress row")
  void logLineWithAPipeIsNotProgress() {
    DsbulkProgressTracker tracker = new DsbulkProgressTracker();
    tracker.accept("total | failed | rows/s");
    assertThat(tracker.accept("INFO  reading a|b|c")).isNull();
  }

  @Test
  @DisplayName("the operation directory is captured from DSBulk's own announcement")
  void operationDirectoryIsCaptured() {
    DsbulkProgressTracker tracker = new DsbulkProgressTracker();
    assertThat(tracker.operationDirectory()).isNull();
    tracker.accept("Operation directory: /var/lib/cassyx/jobs/42/logs/LOAD_20260818-130804-406570");
    assertThat(tracker.operationDirectory())
        .isEqualTo("/var/lib/cassyx/jobs/42/logs/LOAD_20260818-130804-406570");
  }

  @Test
  @DisplayName("the end-of-run stats block in operation.log is a progress source too")
  void statsBlockFallback() {
    DsbulkProgressTracker tracker = new DsbulkProgressTracker();
    DsbulkProgress records = tracker.accept("Records: total: 1,234, successful: 1,230, failed: 4");
    assertThat(records).isNotNull();
    assertThat(records.rowsProcessed()).isEqualTo(1234);
    assertThat(records.failures()).isEqualTo(4);

    DsbulkProgress throughput = tracker.accept("Throughput: 56 writes/second");
    assertThat(throughput).isNotNull();
    assertThat(throughput.rowsPerSecond()).isEqualTo(56);
    // A rate-only line must not reset the row count to zero.
    assertThat(throughput.rowsProcessed()).isEqualTo(1234);
    assertThat(tracker.current().rowsProcessed()).isEqualTo(1234);
  }

  @Test
  @DisplayName("the operation phase is picked up from the lifecycle lines")
  void phaseTracking() {
    DsbulkProgress started =
        DsbulkOutputParser.toProgress("Operation UNLOAD_20260817-120003-123456 started.", null);
    assertThat(started).isNotNull();
    assertThat(started.phase()).isEqualTo("STARTED");
  }

  @Test
  @DisplayName("unparseable output degrades progress reporting, never fails the job")
  void unparseableLinesAreHarmless() {
    DsbulkProgressTracker tracker = new DsbulkProgressTracker();
    assertThat(tracker.accept("some entirely unexpected output")).isNull();
    assertThat(tracker.accept("")).isNull();
    assertThat(tracker.accept(null)).isNull();
    assertThat(DsbulkOutputParser.toProgress(null, null)).isNull();
    assertThat(DsbulkOutputParser.parseCount("not a number")).isZero();
    assertThat(DsbulkOutputParser.parseDecimal("not a number")).isZero();
  }

  /* ------------------------------------------------------------------------- log lines */

  @Test
  @DisplayName("log lines are normalised to the contract's level/message shape")
  void logLineNormalisation() {
    DsbulkLogLine info = DsbulkOutputParser.toLogLine("2026-08-17 12:00:03 INFO  Operation started.");
    assertThat(info.level()).isEqualTo("INFO");
    assertThat(info.message()).isEqualTo("Operation started.");
    assertThat(info.raw()).contains("2026-08-17");

    assertThat(DsbulkOutputParser.toLogLine("2026-08-17 12:00:03 WARNING  odd").level()).isEqualTo("WARN");
    assertThat(DsbulkOutputParser.toLogLine("ERROR  boom").level()).isEqualTo("ERROR");
    assertThat(DsbulkOutputParser.toLogLine("bare text").level()).isEqualTo("INFO");
    assertThat(DsbulkOutputParser.toLogLine(null)).isNull();

    assertThat(DsbulkOutputParser.isLogLine("2026-08-17 12:00:03 INFO  x")).isTrue();
    assertThat(DsbulkOutputParser.isLogLine("15")).isFalse();
    assertThat(DsbulkOutputParser.isLogLine(null)).isFalse();
  }

  /* ------------------------------------------------------------------------ count report */

  @Test
  @DisplayName("global mode is a single bare number")
  void countGlobal() {
    assertThat(DsbulkCountParser.parse(List.of("15")).totalRows()).isEqualTo(15);
  }

  @Test
  @DisplayName("hosts mode: the LAST column is a percentage, not a count")
  void countHosts() {
    DsbulkCountReport report = DsbulkCountParser.parse(List.of("/127.0.0.1:19142 15 100.00"));
    assertThat(report.perReplica()).singleElement().satisfies(replica -> {
      assertThat(replica.endpoint()).isEqualTo("127.0.0.1:19142");
      assertThat(replica.rows()).isEqualTo(15);
    });
    // With no global mode the total is still knowable.
    assertThat(report.totalRows()).isEqualTo(15);
  }

  @Test
  @DisplayName("ranges mode keeps tokens as STRINGS - they exceed the JS safe-integer range")
  void countRanges() {
    DsbulkCountReport report = DsbulkCountParser.parse(List.of(
        "-9223372036854775808 -7228600558177020682 5 33.33",
        "-7228600558177020682 -5638596005663122044 10 66.67"));
    assertThat(report.perTokenRange()).hasSize(2);
    assertThat(report.perTokenRange().get(0).start()).isEqualTo("-9223372036854775808");
    assertThat(report.perTokenRange().get(0).rows()).isEqualTo(5);
    assertThat(report.totalRows()).isEqualTo(15);
  }

  @Test
  @DisplayName("partitions mode is sorted largest-first - it is the skew signal")
  void countPartitions() {
    DsbulkCountReport report = DsbulkCountParser.parse(List.of(
        "Total rows per partition:", "3 3 20.00", "5 5 33.33", "4 4 26.67"));
    assertThat(report.largestPartitions()).extracting(DsbulkCountReport.PartitionCount::rows)
        .containsExactly(5L, 4L, 3L);
    assertThat(report.largestPartitions().get(0).partitionKey()).isEqualTo("5");
  }

  @Test
  @DisplayName("multi-mode output is disambiguated by DSBulk's own section headers")
  void countMultiMode() {
    DsbulkCountReport report = DsbulkCountParser.parse(List.of(
        "Total rows:",
        "15",
        "Total rows per node:",
        "/127.0.0.1:9042 15 100.00",
        "Total rows per token range:",
        "-9223372036854775808 -7228600558177020682 15 100.00",
        "Total rows per partition:",
        // A partition key that looks exactly like a host:port - only the header can tell them apart.
        "10.0.0.1:9042 9 60.00",
        "other|key 6 40.00"));

    assertThat(report.totalRows()).isEqualTo(15);
    assertThat(report.perReplica()).hasSize(1);
    assertThat(report.perTokenRange()).hasSize(1);
    assertThat(report.largestPartitions()).hasSize(2);
    assertThat(report.largestPartitions().get(0).partitionKey()).isEqualTo("10.0.0.1:9042");
  }

  @Test
  @DisplayName("an empty or garbled report is an empty report, not an exception")
  void countDegradesGracefully() {
    assertThat(DsbulkCountParser.parse(null)).isEqualTo(DsbulkCountReport.EMPTY);
    assertThat(DsbulkCountParser.parse(List.of("", "   ", "garbage line here")).totalRows()).isZero();
    assertThat(DsbulkCountParser.isEndpoint("127.0.0.1:9042")).isTrue();
    assertThat(DsbulkCountParser.isEndpoint("12345")).isFalse();
    assertThat(DsbulkCountParser.isLong(null)).isFalse();
  }
}
