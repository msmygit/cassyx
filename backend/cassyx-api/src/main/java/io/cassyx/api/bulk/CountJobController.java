package io.cassyx.api.bulk;

import io.cassyx.api.bulk.DsbulkDtos.CountJobRequest;
import io.cassyx.api.bulk.DsbulkDtos.DsbulkJobView;
import io.cassyx.bulk.api.dsbulk.DsbulkJobSpec;
import io.cassyx.bulk.api.dsbulk.DsbulkOperation;
import io.cassyx.bulk.api.dsbulk.DsbulkProbe;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Count / statistics jobs (plan section 5.4).
 *
 * <p>This is the endpoint behind the table Statistics tab the prior-art prototype had an API for
 * and never built: total rows, per-replica, per-token-range and the top-N largest partitions. The
 * last of those is the one that earns its keep - partition skew is exactly what makes an
 * equal-token split unequal in practice, so a count job is also the pre-flight estimate that tells
 * an export how far to oversplit.
 */
@RestController
public class CountJobController {

  private final DsbulkJobService jobs;

  public CountJobController(DsbulkJobService jobs) {
    this.jobs = jobs;
  }

  /** The statistics modes DSBulk 1.11 accepts, after the contract's spelling is folded in. */
  private static final List<String> RANGE_SCAN_MODES = List.of("ranges", "hosts", "partitions");

  @PostMapping("/api/connections/{connectionId}/jobs/count")
  public ResponseEntity<DsbulkJobView> createCountJob(
      @PathVariable String connectionId, @RequestBody CountJobRequest request) {
    DsbulkProbe probe = jobs.probe(connectionId, request.keyspace(), request.table());
    List<String> modes = normalise(request.modes());
    reject(probe, modes, request.keyspace(), request.table());

    DsbulkJobSpec spec = new DsbulkJobSpec(
        DsbulkOperation.COUNT,
        request.keyspace(),
        request.table(),
        null,
        "csv",
        null,
        null,
        false,
        modes,
        request.topPartitions() == null ? 10 : request.topPartitions(),
        DsbulkSettingsFlattener.flatten(request.dsbulkSettings()));

    DsbulkJobView job = jobs.submit(connectionId, request.name(), spec, probe);
    return ResponseEntity.accepted().header("Location", "/api/jobs/" + job.id()).body(job);
  }

  /**
   * Refuses the modes this table or this target genuinely cannot produce (plan sections 5.4, 7.1).
   *
   * <p>Both failures used to happen deep inside the child process, minutes after the 202, as a
   * DSBulk workflow-init exception in a log nobody was watching. They are knowable from the probe
   * before the job is created, so they are answered as 422 with the reason.
   *
   * <p>Nothing is silently downgraded. Dropping {@code partitions} and returning "success" would
   * hand back a snapshot with an empty largest-partitions table that looks exactly like a table
   * with no skew.
   */
  static void reject(DsbulkProbe probe, List<String> modes, String keyspace, String table) {
    if (probe == DsbulkProbe.UNKNOWN) {
      // Not connected, or the probe failed. Refusing on facts we do not have would block counts on
      // every cluster we cannot fingerprint; let the job run and fail honestly if it must.
      return;
    }
    if (!probe.supportsTokenRangeScan()) {
      List<String> refused = modes.stream().filter(RANGE_SCAN_MODES::contains).toList();
      if (!refused.isEmpty()) {
        throw new CountModeUnsupportedException(refused, "This target does not implement token() "
            + "range scans, so " + String.join(", ", refused) + " cannot be computed: there is no "
            + "way to attribute rows to a range, a replica or a partition without one. A 'global' "
            + "count still works and falls back to the native paging engine.");
      }
    }
    if (modes.contains("partitions") && !probe.hasClusteringKey()) {
      throw new CountModeUnsupportedException(List.of("partitions"), keyspace + "." + table
          + " has no clustering column, so the 'partitions' mode cannot run: DSBulk counts rows per "
          + "partition with a GROUP BY over the partition key, and where the partition IS the row "
          + "every partition holds exactly one. DSBulk rejects the mode at workflow start-up.");
    }
  }

  /**
   * The contract's mode spellings, folded onto DSBulk's own.
   *
   * <p>{@code biggest-partitions} is a contract-only name for {@code partitions}; gating on the raw
   * spelling would let it past the check and then fail inside DSBulk.
   */
  static List<String> normalise(List<String> modes) {
    if (modes == null || modes.isEmpty()) {
      return DsbulkJobSpec.DEFAULT_STATS_MODES;
    }
    List<String> out = new ArrayList<>(modes.size());
    for (String mode : modes) {
      String value = mode == null ? "" : mode.trim().toLowerCase(Locale.ROOT);
      if ("biggest-partitions".equals(value)) {
        value = "partitions";
      }
      if (!value.isEmpty() && !out.contains(value)) {
        out.add(value);
      }
    }
    return out.isEmpty() ? DsbulkJobSpec.DEFAULT_STATS_MODES : List.copyOf(out);
  }
}
