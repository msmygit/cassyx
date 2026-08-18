package io.cassyx.api.bulk;

import io.cassyx.api.bulk.JobDtos.Job;
import io.cassyx.api.bulk.JobDtos.JobLogLine;
import io.cassyx.api.bulk.JobDtos.JobPage;
import io.cassyx.api.bulk.JobService.JobNotFoundException;
import io.cassyx.api.bulk.JobService.JobStateException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * The shared job substrate's HTTP surface (plan section 5.5): list, get, cancel, delete, the named
 * SSE stream, retained logs, and the streaming artifact download.
 *
 * <p><b>Engine-agnostic on purpose.</b> Native (plan section 5.2) and DSBulk (5.3) jobs are one
 * resource collection here. A user asking "where is my export?" does not think about which engine
 * served it, and the frontend has one jobs panel, not two.
 *
 * <p>These paths are mapped in exactly one place. {@code DsbulkJobEventStream} deliberately declares
 * no request mapping of its own and is injected here instead: two controllers mapping
 * {@code /api/jobs/{jobId}/events} is a startup failure for the whole application.
 */
@RestController
public class JobController {

  private static final Logger LOG = LoggerFactory.getLogger(JobController.class);

  /** Copy buffer for the artifact stream. Fixed and small - see {@link #downloadJobArtifact}. */
  static final int COPY_BUFFER = 64 * 1024;

  private final JobRepository repository;
  private final JobService nativeJobs;
  private final DsbulkJobEventStream events;
  private final ObjectProvider<DsbulkJobService> dsbulkJobs;

  public JobController(
      JobRepository repository,
      JobService nativeJobs,
      DsbulkJobEventStream events,
      ObjectProvider<DsbulkJobService> dsbulkJobs) {
    this.repository = repository;
    this.nativeJobs = nativeJobs;
    this.events = events;
    this.dsbulkJobs = dsbulkJobs;
  }

  /* -------------------------------------------------------------------------------- list */

  @GetMapping("/api/jobs")
  public JobPage listJobs(
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String type,
      @RequestParam(required = false) String connectionId,
      @RequestParam(required = false, defaultValue = "0") int limit,
      @RequestParam(required = false, defaultValue = "0") int offset) {
    List<String> statuses = csv(status);
    List<String> types = csv(type);
    int effectiveLimit = JobRepository.clampLimit(limit);
    int effectiveOffset = Math.max(0, offset);
    List<Job> items = repository.list(statuses, types, connectionId, effectiveLimit, effectiveOffset);
    int total = repository.count(statuses, types, connectionId);
    return new JobPage(items, total, effectiveLimit, effectiveOffset);
  }

  /**
   * {@code style: form, explode: false} in the contract - one comma-joined parameter, not a
   * repeated one. Values are upper-cased so the SQL {@code IN} matches the persisted enum text.
   */
  static List<String> csv(String value) {
    if (value == null || value.isBlank()) {
      return List.of();
    }
    List<String> values = new ArrayList<>();
    for (String part : value.split(",")) {
      String trimmed = part.trim();
      if (!trimmed.isEmpty()) {
        values.add(trimmed.toUpperCase(Locale.ROOT));
      }
    }
    return values;
  }

  /* ------------------------------------------------------------------------------ get/CRUD */

  @GetMapping("/api/jobs/{jobId}")
  public Job getJob(@PathVariable String jobId) {
    return repository.find(jobId).orElseThrow(() -> new JobNotFoundException(jobId));
  }

  @DeleteMapping("/api/jobs/{jobId}")
  public ResponseEntity<Void> deleteJob(@PathVariable String jobId) {
    Job job = repository.find(jobId).orElseThrow(() -> new JobNotFoundException(jobId));
    if (!isTerminal(job.status())) {
      throw new JobStateException(
          "Job " + jobId + " is " + job.status() + "; cancel it before deleting it.");
    }
    nativeJobs.forget(jobId);
    repository.delete(jobId);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/api/jobs/{jobId}/cancel")
  public ResponseEntity<Job> cancelJob(@PathVariable String jobId) {
    Job job = repository.find(jobId).orElseThrow(() -> new JobNotFoundException(jobId));
    if (isTerminal(job.status())) {
      throw new JobStateException("Job " + jobId + " already finished as " + job.status() + ".");
    }
    // Native first: it owns the in-memory cancellation flag and answers instantly if it is its job.
    if (!nativeJobs.requestCancel(jobId)) {
      DsbulkJobService dsbulk = dsbulkJobs.getIfAvailable();
      if (dsbulk != null) {
        dsbulk.cancel(jobId);
      }
    }
    return ResponseEntity.accepted().body(repository.find(jobId).orElse(job));
  }

  static boolean isTerminal(String status) {
    return "SUCCEEDED".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status);
  }

  /* --------------------------------------------------------------------------------- SSE */

  /**
   * The named-event SSE stream.
   *
   * <p>Events are {@code status} / {@code progress} / {@code log} / {@code completed} /
   * {@code error} - <b>named</b>, per the contract. This is not cosmetic: an {@code EventSource}
   * with only an {@code onmessage} handler receives nothing at all from a named stream, so getting
   * this wrong makes every job appear to hang forever with no error anywhere to explain it.
   *
   * <p>{@code Last-Event-ID} is honoured for gap-free resume after a reconnect.
   */
  @GetMapping(value = "/api/jobs/{jobId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter streamJobEvents(
      @PathVariable String jobId,
      @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
    if (repository.find(jobId).isEmpty()) {
      throw new JobNotFoundException(jobId);
    }
    return events.subscribe(jobId, lastEventId);
  }

  /* -------------------------------------------------------------------------------- logs */

  @GetMapping("/api/jobs/{jobId}/logs")
  public JobDtos.JobLogPage getJobLogs(
      @PathVariable String jobId,
      @RequestParam(required = false, defaultValue = "1000") int tail,
      @RequestParam(required = false) String level) {
    if (repository.find(jobId).isEmpty()) {
      throw new JobNotFoundException(jobId);
    }
    List<JobLogLine> all = nativeJobs.logs(jobId);
    List<JobLogLine> filtered =
        level == null || level.isBlank()
            ? all
            : all.stream()
                .filter(line -> atLeast(line.level(), level))
                .toList();
    int limit = Math.max(1, Math.min(tail, 100_000));
    boolean truncated = filtered.size() > limit;
    List<JobLogLine> lines =
        truncated ? filtered.subList(filtered.size() - limit, filtered.size()) : filtered;
    return new JobDtos.JobLogPage(jobId, List.copyOf(lines), truncated, filtered.size());
  }

  private static final List<String> LEVELS = List.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR");

  /** {@code level} filters at-or-above, the way every log viewer behaves. */
  static boolean atLeast(String actual, String minimum) {
    int actualIndex = LEVELS.indexOf(actual == null ? "" : actual.toUpperCase(Locale.ROOT));
    int minimumIndex = LEVELS.indexOf(minimum.toUpperCase(Locale.ROOT));
    return minimumIndex < 0 || (actualIndex >= 0 && actualIndex >= minimumIndex);
  }

  /* ---------------------------------------------------------------------------- artifact */

  /**
   * Streams the job's artifact.
   *
   * <p>{@link StreamingResponseBody} with a fixed copy buffer, deliberately: the artifact can be
   * gigabytes, and any of the obvious alternatives - {@code Files.readAllBytes}, a
   * {@code ByteArrayResource}, buffering into the response - turns a 2 GB export into a 2 GB heap
   * allocation and an OOM in the API process. Memory here is flat regardless of artifact size.
   *
   * <p>{@code Range} is honoured so an interrupted multi-gigabyte download resumes rather than
   * restarting.
   */
  @GetMapping("/api/jobs/{jobId}/artifact")
  public ResponseEntity<StreamingResponseBody> downloadJobArtifact(
      @PathVariable String jobId,
      @RequestParam(required = false) String artifactId,
      @RequestHeader(value = HttpHeaders.RANGE, required = false) String range)
      throws IOException {

    Job job = repository.find(jobId).orElseThrow(() -> new JobNotFoundException(jobId));
    if (!"SUCCEEDED".equals(job.status())) {
      throw new JobStateException(
          "Job " + jobId + " is " + job.status() + "; the artifact exists once it SUCCEEDS.");
    }
    Path file = artifactPath(jobId).orElseThrow(() -> new JobNotFoundException(jobId));

    long size = Files.size(file);
    String fileName = file.getFileName().toString();
    String contentType = JobRepository.contentTypeFor(fileName);

    long[] window = parseRange(range, size);
    long start = window[0];
    long length = window[1];
    boolean partial = start > 0 || length < size;

    HttpHeaders headers = new HttpHeaders();
    headers.set(HttpHeaders.CONTENT_TYPE, contentType);
    headers.set(
        HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + safeHeader(fileName) + "\"");
    headers.setContentLength(length);
    headers.set(HttpHeaders.ACCEPT_RANGES, "bytes");
    if (partial) {
      headers.set(
          HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + (start + length - 1) + "/" + size);
    }

    StreamingResponseBody body = out -> copy(file, start, length, out);
    return ResponseEntity.status(partial ? HttpStatus.PARTIAL_CONTENT : HttpStatus.OK)
        .headers(headers)
        .body(body);
  }

  private Optional<Path> artifactPath(String jobId) {
    return repository
        .findRow(jobId)
        .map(row -> JobRepository.string(row, "artifact_path"))
        .filter(path -> path != null && !path.isBlank())
        .map(Path::of)
        .filter(Files::isRegularFile);
  }

  /** Copies a window of the file to the response, one fixed buffer at a time. */
  static void copy(Path file, long start, long length, OutputStream out) throws IOException {
    try (InputStream in = Files.newInputStream(file)) {
      long skipped = 0;
      while (skipped < start) {
        long step = in.skip(start - skipped);
        if (step <= 0) {
          break;
        }
        skipped += step;
      }
      byte[] buffer = new byte[COPY_BUFFER];
      long remaining = length;
      while (remaining > 0) {
        int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
        if (read < 0) {
          break;
        }
        out.write(buffer, 0, read);
        remaining -= read;
      }
      out.flush();
    } catch (IOException e) {
      // A browser that cancelled a download is the normal case, not a server fault.
      LOG.debug("Artifact stream for {} ended early: {}", file, e.toString());
    }
  }

  /**
   * Parses {@code Range: bytes=start-end} into {@code [start, length]}.
   *
   * <p>Anything malformed, multi-range or unsatisfiable degrades to the whole file rather than
   * erroring: a resumable download is an optimisation, and refusing to serve the artifact because a
   * proxy rewrote the header would be a worse outcome than ignoring it.
   */
  static long[] parseRange(String range, long size) {
    long whole = Math.max(0, size);
    if (range == null || !range.trim().toLowerCase(Locale.ROOT).startsWith("bytes=")) {
      return new long[] {0, whole};
    }
    String spec = range.trim().substring("bytes=".length());
    if (spec.contains(",")) {
      return new long[] {0, whole};
    }
    int dash = spec.indexOf('-');
    if (dash < 0) {
      return new long[] {0, whole};
    }
    try {
      String from = spec.substring(0, dash).trim();
      String to = spec.substring(dash + 1).trim();
      if (from.isEmpty()) {
        // A suffix range: the LAST n bytes.
        long suffix = Long.parseLong(to);
        long start = Math.max(0, whole - suffix);
        return new long[] {start, whole - start};
      }
      long start = Long.parseLong(from);
      if (start >= whole) {
        return new long[] {0, whole};
      }
      long end = to.isEmpty() ? whole - 1 : Math.min(Long.parseLong(to), whole - 1);
      if (end < start) {
        return new long[] {0, whole};
      }
      return new long[] {start, end - start + 1};
    } catch (NumberFormatException e) {
      return new long[] {0, whole};
    }
  }

  /** Strips CR/LF and quotes so a file name cannot inject a response header. */
  static String safeHeader(String value) {
    return value.replaceAll("[\\r\\n\"]", "_");
  }

  /** Exposed for tests and for the DSBulk workstream's statistics lookup. */
  Map<String, Object> rawRow(String jobId) {
    return repository.findRow(jobId).orElseThrow(() -> new JobNotFoundException(jobId));
  }
}
