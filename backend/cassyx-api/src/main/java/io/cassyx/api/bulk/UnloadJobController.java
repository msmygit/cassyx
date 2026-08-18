package io.cassyx.api.bulk;

import io.cassyx.api.bulk.JobDtos.Job;
import io.cassyx.api.bulk.JobDtos.UnloadJobRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Unload (export) job creation - {@code POST /api/connections/{connectionId}/jobs/unload}.
 *
 * <p>The response is a {@code 202} with the {@code QUEUED} job and a {@code Location} header, never
 * the exported data. That is the architectural rule of plan section 2 expressed in the contract:
 * bulk data leaves through {@code downloadJobArtifact}, streamed, so it never round-trips through
 * the browser's memory. An unload endpoint that returned rows inline would work beautifully in the
 * demo and fall over on the first real table.
 *
 * <p>Engine routing follows the contract's {@code BulkEngine}: {@code NATIVE} and {@code AUTO} run
 * the token-range parallel scan of plan section 5.2 here; {@code DSBULK} is plan section 5.3 and
 * belongs to the DSBulk workstream's service, which this controller delegates to rather than
 * duplicating.
 */
@RestController
public class UnloadJobController {

  private final JobService jobs;

  public UnloadJobController(JobService jobs) {
    this.jobs = jobs;
  }

  @PostMapping("/api/connections/{connectionId}/jobs/unload")
  public ResponseEntity<Job> createUnloadJob(
      @PathVariable String connectionId, @RequestBody UnloadJobRequest request) {
    Job job = jobs.submitUnload(connectionId, request);
    return ResponseEntity.accepted().header("Location", "/api/jobs/" + job.id()).body(job);
  }
}
