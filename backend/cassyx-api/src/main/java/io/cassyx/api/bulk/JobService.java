package io.cassyx.api.bulk;

import com.datastax.oss.driver.api.core.CqlSession;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.cassyx.api.bulk.JobDtos.Job;
import io.cassyx.api.bulk.JobDtos.JobLogLine;
import io.cassyx.api.bulk.JobDtos.UnloadJobRequest;
import io.cassyx.api.bulk.JobDtos.UnloadSink;
import io.cassyx.bulk.api.BulkFactory;
import io.cassyx.bulk.api.Cancellation;
import io.cassyx.bulk.api.Encoder;
import io.cassyx.bulk.api.JobProgress;
import io.cassyx.bulk.api.ScanStrategy;
import io.cassyx.bulk.api.UnloadEngine;
import io.cassyx.bulk.api.UnloadRequest;
import io.cassyx.bulk.api.UnloadResult;
import io.cassyx.core.api.SessionRegistry;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * The native-engine half of the job substrate (plan sections 5.2 and 5.5).
 *
 * <p>{@code QUEUED -> RUNNING -> SUCCEEDED | FAILED | CANCELLED}, persisted in {@code cassyx_job},
 * progress and logs published as <b>named</b> SSE events, cancellable, artifact retained and
 * downloadable.
 *
 * <p>Three decisions worth stating outright:
 *
 * <ul>
 *   <li><b>Nothing is buffered, anywhere.</b> The engine writes straight into the artifact's
 *       {@link OutputStream}, and the download endpoint streams that file back with
 *       {@code StreamingResponseBody}. A 50M-row unload therefore holds flat memory in the API
 *       process as well as in the engine - the streaming property is only worth anything if every
 *       layer preserves it, and a single {@code toByteArray()} anywhere on the path destroys it.
 *   <li><b>Cancellation is cooperative, not an interrupt.</b> The engine polls a
 *       {@link Cancellation} between splits and rows, so a cancelled job stops at a row boundary
 *       and the partial artifact is a well-formed prefix rather than a truncated record.
 *   <li><b>The concurrent-job cap is enforced on submit, not by queueing forever.</b> The contract
 *       has a {@code 429 JobCapExceeded} response precisely so a user learns immediately that the
 *       slots are full, instead of watching a job sit in {@code QUEUED} with no explanation.
 * </ul>
 */
@Service
public class JobService {

  private static final Logger LOG = LoggerFactory.getLogger(JobService.class);

  /** Retained log lines per job. Bounded: a long unload is chatty and this lives in memory. */
  static final int LOG_BUFFER = 2000;

  /** Progress is published at most this often - the contract says roughly 1/s. */
  static final long PROGRESS_INTERVAL_MILLIS = 1000;

  /** Value of {@code cassyx_job.engine} for rows the DSBulk half of the substrate owns. */
  static final String DSBULK_ENGINE = "DSBULK";

  private final JobRepository repository;
  private final DsbulkJobEventStream events;
  private final DsbulkJobService dsbulk;
  private final SessionRegistry sessions;
  private final ExecutorService executor;
  private final ObjectMapper json;
  private final Path artifactRoot;
  private final Clock clock;
  private final int maxConcurrent;

  private final Map<String, AtomicBoolean> cancellations = new ConcurrentHashMap<>();
  private final Map<String, Future<?>> inFlight = new ConcurrentHashMap<>();
  private final Map<String, Deque<JobLogLine>> logs = new ConcurrentHashMap<>();

  public JobService(
      JobRepository repository,
      DsbulkJobEventStream events,
      DsbulkJobService dsbulkJobs,
      SessionRegistry sessionRegistry,
      ExecutorService nativeJobExecutor,
      ObjectMapper json,
      Path nativeJobArtifactRoot,
      Clock clock,
      @Value("${cassyx.jobs.max-concurrent:4}") int maxConcurrent) {
    this.repository = repository;
    this.events = events;
    this.dsbulk = dsbulkJobs;
    this.sessions = sessionRegistry;
    this.executor = nativeJobExecutor;
    this.json = json;
    this.artifactRoot = nativeJobArtifactRoot;
    this.clock = clock;
    this.maxConcurrent = Math.max(1, maxConcurrent);
  }

  /* ------------------------------------------------------------------------------ submit */

  /**
   * Creates a {@code QUEUED} unload job and hands it to the bounded executor.
   *
   * @throws JobCapExceededException when every concurrent slot is in use (contract: 429)
   */
  public Job submitUnload(String connectionId, UnloadJobRequest request) {
    validate(request);
    if (inFlight.size() >= maxConcurrent) {
      throw new JobCapExceededException(inFlight.size(), maxConcurrent);
    }

    String jobId = UUID.randomUUID().toString();
    Instant now = clock.instant();
    repository.insert(
        jobId,
        "UNLOAD",
        connectionId,
        request.keyspace(),
        request.table(),
        request.name(),
        now,
        null);
    cancellations.put(jobId, new AtomicBoolean());
    publishStatus(jobId, "QUEUED", null, "Job accepted.");

    inFlight.put(jobId, executor.submit(() -> execute(jobId, connectionId, request)));
    return repository.find(jobId).orElseThrow(() -> new JobNotFoundException(jobId));
  }

  /**
   * The request must name a target.
   *
   * <p>Checked here rather than left to the engine: an unload with neither table nor query would
   * otherwise fail deep inside a worker thread and surface as a mysterious FAILED job instead of a
   * 400 the caller can act on.
   */
  static void validate(UnloadJobRequest request) {
    if (request == null) {
      throw new IllegalArgumentException("An unload job needs a request body.");
    }
    boolean hasTable = notBlank(request.keyspace()) && notBlank(request.table());
    if (!hasTable && !notBlank(request.query())) {
      throw new IllegalArgumentException(
          "An unload job needs either keyspace + table, or a query.");
    }
    if (request.sink() == null || !notBlank(request.sink().type())) {
      throw new IllegalArgumentException("An unload job needs a sink with a type.");
    }
    // Fails fast on an unknown format or an unusable sink instead of after the scan has already
    // run: an S3 sink with no bucket must be a 400 now, not a FAILED job in ten minutes.
    Encoder.forFormat(formatId(request.format()));
    targetFor(request.sink());
  }

  /* ----------------------------------------------------------------------------- execute */

  private void execute(String jobId, String connectionId, UnloadJobRequest request) {
    Instant startedAt = clock.instant();
    repository.markRunning(jobId, startedAt);
    publishStatus(jobId, "RUNNING", "QUEUED", "Starting the native token-range unload.");

    AtomicBoolean cancelled = cancellations.computeIfAbsent(jobId, key -> new AtomicBoolean());
    Cancellation cancellation = Cancellation.of(cancelled);
    String artifactPath = null;

    try {
      CqlSession session = sessions.session(connectionId);
      UnloadEngine engine = BulkFactory.unloadEngine();
      UnloadRequest unload = toUnloadRequest(request);

      ScanStrategy strategy = engine.strategyFor(session, unload);
      log(jobId, "INFO", "Scan strategy: " + strategy
          + (strategy == ScanStrategy.PAGING
              ? " (this cluster has no token-range scan; falling back to plain paging)"
              : " with a target of " + unload.splits() + " splits"));

      UnloadResult result;
      if ("DOWNLOAD".equals(sinkType(request.sink()))) {
        Path file = artifactFile(jobId, request);
        artifactPath = file.toString();
        // Straight into the file: the artifact is never assembled in memory first.
        try (OutputStream out = Files.newOutputStream(file)) {
          result = engine.unloadTo(session, unload, out, listener(jobId, startedAt), cancellation);
        }
      } else {
        result = engine.unload(session, unload, listener(jobId, startedAt), cancellation);
        artifactPath = volumeArtifactPath(request, result);
      }

      result.warnings().forEach(warning -> log(jobId, "WARN", warning));
      succeed(jobId, request, result, startedAt, artifactPath);
    } catch (RuntimeException | IOException e) {
      if (cancelled.get()) {
        // The engine reports cancellation as a failure, correctly - it refuses to call a partial
        // export a success. At this layer we know the user asked for it, so it is CANCELLED.
        cancel(jobId, startedAt);
      } else {
        fail(jobId, e, startedAt, artifactPath);
      }
    } finally {
      inFlight.remove(jobId);
    }
  }

  private void succeed(
      String jobId,
      UnloadJobRequest request,
      UnloadResult result,
      Instant startedAt,
      String artifactPath) {
    Instant finishedAt = clock.instant();
    long durationMillis = finishedAt.toEpochMilli() - startedAt.toEpochMilli();
    repository.updateProgress(
        jobId, result.rowsWritten(), result.splitsCompleted(), result.splitsCompleted());
    repository.markFinished(
        jobId, "SUCCEEDED", finishedAt, result.rowsWritten(), null, artifactPath,
        settingsDocument(request, result));

    log(jobId, "INFO",
        "Unloaded " + result.rowsWritten() + " row(s) from " + result.splitsCompleted()
            + " split(s) in " + durationMillis + " ms.");
    publishStatus(jobId, "SUCCEEDED", "RUNNING", null);

    Job job = repository.find(jobId).orElse(null);
    Map<String, Object> completed = new LinkedHashMap<>();
    completed.put("jobId", jobId);
    completed.put("status", "SUCCEEDED");
    completed.put("rowsProcessed", result.rowsWritten());
    completed.put("durationMillis", durationMillis);
    List<JobDtos.JobArtifact> artifacts = job == null ? List.of() : job.artifacts();
    completed.put("artifacts", artifacts);
    completed.put("artifact", artifacts.isEmpty() ? null : artifacts.get(0));
    events.complete(jobId, completed);
  }

  private void fail(String jobId, Exception e, Instant startedAt, String artifactPath) {
    LOG.warn("Native unload job {} failed", jobId, e);
    Instant finishedAt = clock.instant();
    String detail = safeMessage(e);
    repository.markFinished(jobId, "FAILED", finishedAt, 0, detail, artifactPath, null);
    log(jobId, "ERROR", detail);
    publishStatus(jobId, "FAILED", "RUNNING", detail);
    events.complete(
        jobId,
        Map.of(
            "jobId", jobId,
            "status", "FAILED",
            "durationMillis", finishedAt.toEpochMilli() - startedAt.toEpochMilli(),
            "error", problem(detail)));
  }

  private void cancel(String jobId, Instant startedAt) {
    Instant finishedAt = clock.instant();
    repository.markFinished(
        jobId, "CANCELLED", finishedAt, 0, "Cancelled by the user.", null, null);
    log(jobId, "WARN", "Cancelled by the user.");
    publishStatus(jobId, "CANCELLED", "RUNNING", "Cancelled by the user.");
    events.complete(
        jobId,
        Map.of(
            "jobId", jobId,
            "status", "CANCELLED",
            "durationMillis", finishedAt.toEpochMilli() - startedAt.toEpochMilli()));
  }

  /* ------------------------------------------------------------------------ cancel/delete */

  /**
   * Requests cancellation of a running job, whichever engine is running it.
   *
   * <p><b>Routes on the row's engine, and that routing is the whole point.</b> A {@code DSBULK} job
   * is a child process; this service's {@link AtomicBoolean} means nothing to it. Before the
   * delegation existed, {@code POST /api/jobs/{id}/cancel} set a flag nobody read and answered 202,
   * so the UI showed the job stopped while DSBulk carried on writing to the user's cluster. A cancel
   * that reports success without stopping anything is worse than one that fails loudly.
   *
   * @return {@code true} if some engine owns the job and has signalled it
   */
  public boolean requestCancel(String jobId) {
    if (DSBULK_ENGINE.equalsIgnoreCase(engineOf(jobId)) && dsbulk.cancel(jobId)) {
      return true;
    }
    AtomicBoolean flag = cancellations.get(jobId);
    if (flag == null) {
      return cancelOrphan(jobId);
    }
    flag.set(true);
    Future<?> future = inFlight.get(jobId);
    if (future == null) {
      // Queued but never started: no worker will ever observe the flag, so finish it here.
      cancel(jobId, clock.instant());
    }
    return true;
  }

  /**
   * Finishes a non-terminal row that no engine is running - typically one left {@code RUNNING} by a
   * restart that killed its worker.
   *
   * <p>The contract says the job reaches {@code CANCELLED} shortly after a cancel request. Nothing
   * else is ever going to move this row, so leaving it {@code RUNNING} forever while answering 202
   * would be the same lie in a different place.
   */
  private boolean cancelOrphan(String jobId) {
    Job job = repository.find(jobId).orElse(null);
    if (job == null || JobController.isTerminal(job.status())) {
      return false;
    }
    LOG.warn("Job {} is {} but no engine is running it; recording it CANCELLED", jobId, job.status());
    cancel(jobId, clock.instant());
    return true;
  }

  /** The {@code engine} column of the job row, or {@code null} when there is no such row. */
  private String engineOf(String jobId) {
    return repository.findRow(jobId).map(row -> JobRepository.string(row, "engine")).orElse(null);
  }

  /** Drops the retained logs, the artifact directory and the replay buffer for a deleted job. */
  public void forget(String jobId) {
    cancellations.remove(jobId);
    logs.remove(jobId);
    events.forget(jobId);
    deleteRecursively(artifactRoot.resolve(jobId));
  }

  private static void deleteRecursively(Path path) {
    if (!Files.exists(path)) {
      return;
    }
    try (java.util.stream.Stream<Path> walk = Files.walk(path)) {
      walk.sorted(java.util.Comparator.reverseOrder())
          .forEach(
              entry -> {
                try {
                  Files.deleteIfExists(entry);
                } catch (IOException e) {
                  LOG.debug("Cannot delete {}: {}", entry, e.toString());
                }
              });
    } catch (IOException e) {
      LOG.debug("Cannot clean the artifact directory {}: {}", path, e.toString());
    }
  }

  /* -------------------------------------------------------------------------------- logs */

  /** Retained log lines for a job, oldest first. */
  public List<JobLogLine> logs(String jobId) {
    Deque<JobLogLine> buffer = logs.get(jobId);
    if (buffer == null) {
      return List.of();
    }
    synchronized (buffer) {
      return List.copyOf(buffer);
    }
  }

  /** Appends a retained log line and publishes it as a named {@code log} SSE event. */
  void log(String jobId, String level, String message) {
    JobLogLine line = new JobLogLine(clock.instant().toString(), level, message, "ENGINE");
    Deque<JobLogLine> buffer = logs.computeIfAbsent(jobId, key -> new ArrayDeque<>());
    synchronized (buffer) {
      buffer.addLast(line);
      while (buffer.size() > LOG_BUFFER) {
        buffer.removeFirst();
      }
    }
    events.publish(
        jobId,
        "log",
        Map.of(
            "jobId", jobId,
            "level", level,
            "message", message,
            "at", line.at(),
            "source", "ENGINE"));
  }

  /* --------------------------------------------------------------------------- artifacts */

  /** The retained artifact of a {@code DOWNLOAD} job, if it exists on disk. */
  public Optional<Path> artifact(String jobId) {
    return repository
        .findRow(jobId)
        .map(row -> JobRepository.string(row, "artifact_path"))
        .filter(path -> path != null && !path.isBlank())
        .map(Path::of)
        .filter(Files::isRegularFile);
  }

  private Path artifactFile(String jobId, UnloadJobRequest request) throws IOException {
    Path directory = artifactRoot.resolve(jobId);
    Files.createDirectories(directory);
    return directory.resolve(fileName(request));
  }

  /**
   * The artifact file name.
   *
   * <p>Sanitised unconditionally: {@code fileNamePattern} is caller-supplied, and an unsanitised
   * {@code ../../etc/passwd} is a write-anywhere primitive.
   */
  static String fileName(UnloadJobRequest request) {
    String pattern = request.sink() == null ? null : request.sink().fileNamePattern();
    String extension = Encoder.forFormat(formatId(request.format())).fileExtension();
    String base;
    if (notBlank(pattern)) {
      base = pattern.replace("%d", "1");
    } else if (notBlank(request.table())) {
      base = request.table() + "." + extension;
    } else {
      base = "export." + extension;
    }
    return safeFileName(base);
  }

  static String safeFileName(String name) {
    if (name == null || name.isBlank()) {
      return "export.dat";
    }
    String base = name.replace('\\', '/');
    base = base.substring(base.lastIndexOf('/') + 1);
    base = base.replaceAll("[^A-Za-z0-9._-]", "_");
    return base.isBlank() || ".".equals(base) || "..".equals(base) ? "export.dat" : base;
  }

  private static String volumeArtifactPath(UnloadJobRequest request, UnloadResult result) {
    if (!"VOLUME_PATH".equals(sinkType(request.sink())) || result.artifacts().isEmpty()) {
      return null;
    }
    return Path.of(targetFor(request.sink())).resolve(result.artifacts().get(0)).toString();
  }

  /* ----------------------------------------------------------------------------- mapping */

  /** Maps the contract's request onto the engine's plain-Java {@link UnloadRequest}. */
  UnloadRequest toUnloadRequest(UnloadJobRequest request) {
    Map<String, String> options = new LinkedHashMap<>();
    if (notBlank(request.query())) {
      options.put("query", request.query());
      // A custom SELECT cannot be split by token range - it may already carry a WHERE clause.
      options.put("scanStrategy", ScanStrategy.PAGING.name());
    }
    if (notBlank(request.consistency())) {
      options.put("consistency", request.consistency());
    }
    UnloadSink sink = request.sink();
    if (sink != null && notBlank(sink.fileNamePattern())) {
      options.put("fileName", fileName(request));
    }
    return new UnloadRequest(
        notBlank(request.keyspace()) ? request.keyspace() : "system",
        notBlank(request.table()) ? request.table() : "local",
        request.columns(),
        formatId(request.format()),
        targetFor(sink),
        UnloadRequest.DEFAULT_SPLITS,
        0,
        options);
  }

  /** {@code ExportFormat} is upper-case on the wire; the {@code Encoder} SPI keys are lower-case. */
  static String formatId(String format) {
    return format == null || format.isBlank() ? "csv" : format.trim().toLowerCase(Locale.ROOT);
  }

  static String sinkType(UnloadSink sink) {
    String type = sink == null ? null : sink.type();
    String normalised = type == null ? "DOWNLOAD" : type.trim().toUpperCase(Locale.ROOT);
    return switch (normalised) {
      case "DOWNLOAD", "VOLUME_PATH", "S3" -> normalised;
      default -> throw new IllegalArgumentException(
          "Unknown sink type '" + type + "'; expected DOWNLOAD, VOLUME_PATH or S3.");
    };
  }

  /** The engine-level target URI for a sink. */
  static String targetFor(UnloadSink sink) {
    return switch (sinkType(sink)) {
      case "S3" -> requireValue(sink.s3Uri(), "An S3 sink needs an s3Uri.");
      case "VOLUME_PATH" -> requireValue(sink.path(), "A VOLUME_PATH sink needs a path.");
      // DOWNLOAD writes into the job's own artifact directory; the engine is handed the stream
      // directly, so the target is only ever used for naming.
      default -> "/out";
    };
  }

  private static String requireValue(String value, String message) {
    if (!notBlank(value)) {
      throw new IllegalArgumentException(message);
    }
    return value;
  }

  /* ---------------------------------------------------------------------------- progress */

  /**
   * Progress bridge: engine ticks to named SSE {@code progress} events plus a persisted heartbeat.
   *
   * <p>Throttled to ~1/s. A native unload over 10 000 splits ticks far faster than a browser can
   * render, and an unthrottled stream is one nobody can read and a database nobody can keep up with.
   */
  private io.cassyx.bulk.api.ProgressListener listener(String jobId, Instant startedAt) {
    AtomicLong lastPublish = new AtomicLong();
    AtomicInteger lastSplits = new AtomicInteger();
    return progress -> {
      long now = System.currentTimeMillis();
      long previous = lastPublish.get();
      if (now - previous < PROGRESS_INTERVAL_MILLIS || !lastPublish.compareAndSet(previous, now)) {
        return;
      }
      lastSplits.set(progress.splitsCompleted());
      repository.updateProgress(
          jobId, progress.rowsProcessed(), progress.splitsCompleted(), progress.splitsTotal());
      events.publish(jobId, "progress", progressPayload(jobId, progress, startedAt));
    };
  }

  Map<String, Object> progressPayload(String jobId, JobProgress progress, Instant startedAt) {
    long elapsed = clock.instant().toEpochMilli() - startedAt.toEpochMilli();
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("jobId", jobId);
    payload.put("rowsProcessed", progress.rowsProcessed());
    payload.put("splitsCompleted", progress.splitsCompleted());
    payload.put("splitsTotal", progress.splitsTotal());
    payload.put("elapsedMillis", elapsed);
    payload.put("rowsPerSecond", elapsed > 0 ? progress.rowsProcessed() * 1000 / elapsed : 0);
    payload.put("failures", 0);
    payload.put("currentPhase", progress.message());
    if (progress.splitsTotal() > 0) {
      double percent = 100.0 * progress.splitsCompleted() / progress.splitsTotal();
      payload.put("percent", Math.min(100.0, percent));
      if (progress.splitsCompleted() > 0 && elapsed > 0) {
        long remaining = (long) progress.splitsTotal() - progress.splitsCompleted();
        payload.put("etaMillis", remaining * elapsed / progress.splitsCompleted());
      }
    }
    return payload;
  }

  private void publishStatus(String jobId, String status, String previous, String message) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("jobId", jobId);
    payload.put("status", status);
    if (previous != null) {
      payload.put("previousStatus", previous);
    }
    payload.put("at", clock.instant().toString());
    if (notBlank(message)) {
      payload.put("message", message);
    }
    events.publish(jobId, "status", payload);
  }

  /* ----------------------------------------------------------------------------- helpers */

  /** Everything needed to reproduce the job, persisted alongside it. */
  String settingsDocument(UnloadJobRequest request, UnloadResult result) {
    Map<String, Object> document = new LinkedHashMap<>();
    if (notBlank(request.name())) {
      document.put("name", request.name());
    }
    document.put("format", formatId(request.format()));
    document.put("sinkType", sinkType(request.sink()));
    document.put("splitsCompleted", result.splitsCompleted());
    document.put("warnings", result.warnings());
    try {
      return json.writeValueAsString(document);
    } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
      LOG.warn("Cannot serialise the job settings document: {}", e.toString());
      return null;
    }
  }

  private static Map<String, Object> problem(String detail) {
    return Map.of(
        "type", "https://cassyx.dev/problems/job-failed",
        "title", "Job failed",
        "status", 500,
        "detail", detail);
  }

  /** Error text safe to store and return: a message, never a credential or a stack trace. */
  static String safeMessage(Exception e) {
    String message = e.getMessage();
    return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
  }

  static boolean notBlank(String value) {
    return value != null && !value.isBlank();
  }

  /** Thrown when the concurrent-job cap is reached. Contract: {@code 429 JobCapExceeded}. */
  public static class JobCapExceededException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public JobCapExceededException(int inUse, int cap) {
      super(inUse + " of " + cap + " concurrent job slots are in use.");
    }
  }

  /** Thrown when a job id does not exist. Contract: {@code 404}. */
  public static class JobNotFoundException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public JobNotFoundException(String jobId) {
      super("No job with id '" + jobId + "'.");
    }
  }

  /** Thrown when an operation is illegal for the job's current lifecycle state. Contract: 409. */
  public static class JobStateException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public JobStateException(String message) {
      super(message);
    }
  }
}
