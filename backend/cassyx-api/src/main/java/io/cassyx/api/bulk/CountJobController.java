package io.cassyx.api.bulk;

import io.cassyx.api.bulk.DsbulkDtos.CountJobRequest;
import io.cassyx.api.bulk.DsbulkDtos.DsbulkJobView;
import io.cassyx.bulk.api.dsbulk.DsbulkJobSpec;
import io.cassyx.bulk.api.dsbulk.DsbulkOperation;
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

  @PostMapping("/api/connections/{connectionId}/jobs/count")
  public ResponseEntity<DsbulkJobView> createCountJob(
      @PathVariable String connectionId, @RequestBody CountJobRequest request) {
    DsbulkJobSpec spec = new DsbulkJobSpec(
        DsbulkOperation.COUNT,
        request.keyspace(),
        request.table(),
        null,
        "csv",
        null,
        null,
        false,
        request.modes(),
        request.topPartitions() == null ? 10 : request.topPartitions(),
        DsbulkSettingsFlattener.flatten(request.dsbulkSettings()));

    DsbulkJobView job = jobs.submit(connectionId, request.name(), spec);
    return ResponseEntity.accepted().header("Location", "/api/jobs/" + job.id()).body(job);
  }
}
