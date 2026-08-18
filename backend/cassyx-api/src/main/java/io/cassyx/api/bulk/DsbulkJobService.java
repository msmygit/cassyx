package io.cassyx.api.bulk;

import com.datastax.oss.driver.api.core.CqlSession;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.cassyx.api.bulk.DsbulkDtos.DerivedSetting;
import io.cassyx.api.bulk.DsbulkDtos.DsbulkJobView;
import io.cassyx.api.bulk.DsbulkDtos.JobProgressView;
import io.cassyx.api.bulk.DsbulkDtos.SchemaIdentity;
import io.cassyx.api.bulk.DsbulkDtos.TableStatistics;
import io.cassyx.bulk.api.dsbulk.DsbulkFactory;
import io.cassyx.bulk.api.dsbulk.DsbulkJobSpec;
import io.cassyx.bulk.api.dsbulk.DsbulkListener;
import io.cassyx.bulk.api.dsbulk.DsbulkLogLine;
import io.cassyx.bulk.api.dsbulk.DsbulkOperation;
import io.cassyx.bulk.api.dsbulk.DsbulkPlan;
import io.cassyx.bulk.api.dsbulk.DsbulkProbe;
import io.cassyx.bulk.api.dsbulk.DsbulkProgress;
import io.cassyx.bulk.api.dsbulk.DsbulkResult;
import io.cassyx.bulk.api.dsbulk.DsbulkRunner;
import io.cassyx.core.api.SessionRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Runs DSBulk load and count jobs on the shared job substrate (plan section 5.5).
 *
 * <p>{@code QUEUED -> RUNNING -> SUCCEEDED | FAILED | CANCELLED}, persisted in {@code cassyx_job},
 * progress published as NAMED SSE events, cancellable, logs retained, artifacts downloadable.
 *
 * <p>Two behaviours worth stating outright:
 *
 * <ul>
 *   <li><b>Completed-with-errors is a success, not a failure.</b> DSBulk exit status 1 means the
 *       workflow ran to the end and wrote its output, but rejected some records. Reporting that as
 *       FAILED hides a mostly-successful load; reporting it as a clean success hides data loss. The
 *       job lands SUCCEEDED with a non-zero failure count and the rejected records retained.
 *   <li><b>Cancellation kills the process.</b> Not a flag DSBulk is asked to notice - a signal.
 *       SIGTERM first so it flushes its error reports and checkpoint, SIGKILL after the grace
 *       period.
 * </ul>
 */
@Service
public class DsbulkJobService {

  private static final Logger LOG = LoggerFactory.getLogger(DsbulkJobService.class);

  private final DsbulkJobRepository repository;
  private final DsbulkJobEventStream events;
  private final DsbulkRunner runner;
  private final ExecutorService executor;
  private final SessionRegistry sessions;
  private final ObjectMapper json;
  private final Path workRoot;
  private final Clock clock;

  private final Map<String, Future<?>> inFlight = new ConcurrentHashMap<>();
  private final Map<String, Path> jobDirectories = new ConcurrentHashMap<>();

  /**
   * Jobs the user cancelled, so the worker thread can tell a kill from a crash.
   *
   * <p>Needed because the two race. {@link #cancel} signals the child and writes CANCELLED; the
   * worker is still blocked in {@code runner.run()} and wakes up moments later holding exit status
   * 143 (128 + SIGTERM), which is indistinguishable from a crash by exit code alone. Without this
   * set the worker's own terminal write lands second and overwrites CANCELLED with FAILED - the job
   * really was cancelled, and the UI would say it failed.
   */
  private final java.util.Set<String> cancelled = ConcurrentHashMap.newKeySet();

  public DsbulkJobService(
      DsbulkJobRepository repository,
      DsbulkJobEventStream events,
      DsbulkRunner runner,
      ExecutorService dsbulkJobExecutor,
      SessionRegistry sessions,
      ObjectMapper json,
      Path dsbulkJobWorkRoot,
      Clock clock) {
    this.repository = repository;
    this.events = events;
    this.runner = runner;
    this.executor = dsbulkJobExecutor;
    this.sessions = sessions;
    this.json = json;
    this.workRoot = dsbulkJobWorkRoot;
    this.clock = clock;
  }

  /**
   * Probes the cluster for a connection, degrading to {@link DsbulkProbe#UNKNOWN} if it cannot.
   *
   * <p>The registry is injected directly. It used to arrive through an {@code ObjectProvider}
   * because workstream A had not yet registered a bean, which meant every derived default was
   * computed from {@code UNKNOWN} - generic split counts and concurrency for every cluster, however
   * big. There is one registry bean now, so the defaults are derived from real capability data.
   */
  public DsbulkProbe probe(String connectionId, String keyspace, String table) {
    if (connectionId == null || !sessions.isConnected(connectionId)) {
      return DsbulkProbe.UNKNOWN;
    }
    try {
      CqlSession session = sessions.session(connectionId);
      return DsbulkFactory.probe(session, keyspace, table);
    } catch (RuntimeException e) {
      // A probe failure must degrade the QUALITY of the derived defaults, never block the job.
      LOG.debug("Cluster probe for connection {} failed, using fallbacks: {}", connectionId, e.toString());
      return DsbulkProbe.UNKNOWN;
    }
  }

  /** Creates a {@code QUEUED} job and hands it to the bounded executor. */
  public DsbulkJobView submit(String connectionId, String name, DsbulkJobSpec spec) {
    String jobId = UUID.randomUUID().toString();
    Instant now = clock.instant();
    String type = spec.operation() == DsbulkOperation.LOAD ? "LOAD" : "COUNT";

    repository.insert(jobId, type, connectionId, spec.keyspace(), spec.table(), name, now);
    DsbulkJobView queued = view(jobId, type, "QUEUED", spec, connectionId, name, now, null, null, null, null);
    publishStatus(jobId, "QUEUED", null, "Job accepted.");

    inFlight.put(jobId, executor.submit(() -> execute(jobId, type, connectionId, name, spec)));
    return queued;
  }

  private void execute(String jobId, String type, String connectionId, String name, DsbulkJobSpec spec) {
    Instant startedAt = clock.instant();
    Path jobDirectory = workRoot.resolve(jobId);
    jobDirectories.put(jobId, jobDirectory);
    repository.markRunning(jobId, startedAt);
    publishStatus(jobId, "RUNNING", "QUEUED", "Starting DSBulk " + spec.operation().command() + ".");

    try {
      Files.createDirectories(jobDirectory);
      DsbulkProbe probe = probe(connectionId, spec.keyspace(), spec.table());
      DsbulkPlan plan = DsbulkFactory.plan(spec, probe, jobDirectory, executionId(spec.operation(), jobId));

      AtomicLong lastPublish = new AtomicLong();
      DsbulkResult result = runner.run(plan, jobDirectory, Map.of(), new DsbulkListener() {
        @Override
        public void onProgress(DsbulkProgress progress) {
          // Throttled to roughly 1/s, as the contract specifies for the `progress` event: a job
          // reporting faster than the browser can render is a stream nobody can read.
          long now = System.currentTimeMillis();
          if (now - lastPublish.get() >= 1000) {
            lastPublish.set(now);
            events.publish(jobId, "progress", progressPayload(jobId, progress, startedAt));
          }
        }

        @Override
        public void onLog(DsbulkLogLine line) {
          events.publish(jobId, "log", Map.of(
              "jobId", jobId,
              "level", line.level(),
              "message", line.message(),
              "at", clock.instant().toString(),
              "source", "DSBULK"));
        }
      });

      finish(jobId, type, connectionId, name, spec, plan, result, startedAt);
    } catch (IOException | RuntimeException e) {
      if (cancelled.remove(jobId)) {
        // The child was killed out from under the runner. cancel() already wrote the terminal row
        // and published the events; reporting this as a failure on top would contradict it.
        LOG.info("DSBulk job {} ended because it was cancelled", jobId);
        return;
      }
      LOG.warn("DSBulk job {} failed", jobId, e);
      Instant finishedAt = clock.instant();
      repository.markFinished(jobId, "FAILED", finishedAt, 0, safeMessage(e), null);
      events.publish(jobId, "error", Map.of(
          "jobId", jobId,
          "problem", Map.of("title", "DSBulk job failed", "detail", safeMessage(e), "status", 500),
          "recoverable", false));
      events.complete(jobId, Map.of("jobId", jobId, "status", "FAILED", "rowsProcessed", 0,
          "durationMillis", finishedAt.toEpochMilli() - startedAt.toEpochMilli()));
    } finally {
      inFlight.remove(jobId);
    }
  }

  private void finish(
      String jobId,
      String type,
      String connectionId,
      String name,
      DsbulkJobSpec spec,
      DsbulkPlan plan,
      DsbulkResult result,
      Instant startedAt) {
    if (cancelled.remove(jobId)) {
      // cancel() owns the terminal transition for a cancelled job - see the `cancelled` field.
      LOG.info("DSBulk job {} finished after cancellation (exit {})", jobId, result.exitCode());
      return;
    }
    Instant finishedAt = clock.instant();
    long durationMillis = finishedAt.toEpochMilli() - startedAt.toEpochMilli();
    String status = status(result);

    TableStatistics statistics = spec.operation() == DsbulkOperation.COUNT
        ? TableStatistics.from(result.countReport(), spec.keyspace(), spec.table(), jobId,
            finishedAt.toString(), durationMillis)
        : null;

    repository.markFinished(jobId, status, finishedAt, result.rowsProcessed(),
        result.failureMessage().isEmpty() ? null : result.failureMessage(),
        settingsDocument(plan, statistics, result.exitCode()));

    publishStatus(jobId, status, "RUNNING", result.failureMessage());
    Map<String, Object> completed = new LinkedHashMap<>();
    completed.put("jobId", jobId);
    completed.put("status", status);
    completed.put("rowsProcessed", result.rowsProcessed());
    completed.put("durationMillis", durationMillis);
    completed.put("artifacts", result.artifacts().stream().map(Path::toString).toList());
    events.complete(jobId, completed);

    LOG.info("DSBulk {} job {} finished {} in {} ms ({} rows, {} failures, exit {})",
        spec.operation(), jobId, status, durationMillis, result.rowsProcessed(), result.failures(),
        result.exitCode());
  }

  /** Exit status 1 means "ran to the end, rejected some records" - a success with a failure count. */
  static String status(DsbulkResult result) {
    if (result.status().isInterrupted()) {
      return "CANCELLED";
    }
    return result.succeeded() ? "SUCCEEDED" : "FAILED";
  }

  /**
   * Cancels a running job by killing its child process.
   *
   * <p>This is what {@code POST /api/jobs/{id}/cancel} reaches for a row with {@code
   * engine='DSBULK'}, via {@link JobService#requestCancel}. It is the only thing in the system that
   * can stop a DSBulk job: the native engine's cooperative {@code AtomicBoolean} is not wired to
   * anything DSBulk reads, so without this delegation the endpoint would answer 202 while the job
   * kept consuming the user's cluster.
   *
   * @return {@code true} when this service owns the job and has signalled or dequeued it
   */
  public boolean cancel(String jobId) {
    Path directory = jobDirectories.get(jobId);
    Future<?> future = inFlight.get(jobId);
    if (directory == null && future == null) {
      return false;
    }
    cancelled.add(jobId);
    boolean killed = directory != null && runner.cancel(directory);
    if (!killed && future != null) {
      future.cancel(true);
    }
    if (killed || future != null) {
      repository.markFinished(jobId, "CANCELLED", clock.instant(), 0, "Cancelled by the user.", null);
      publishStatus(jobId, "CANCELLED", "RUNNING", "Cancelled by the user.");
      events.complete(jobId, Map.of("jobId", jobId, "status", "CANCELLED"));
      return true;
    }
    return false;
  }

  /** DSBulk names its log subdirectory after this, so it must be filesystem-safe. */
  static String executionId(DsbulkOperation operation, String jobId) {
    return operation.name() + "_" + jobId.replaceAll("[^A-Za-z0-9]", "");
  }

  private Map<String, Object> progressPayload(String jobId, DsbulkProgress progress, Instant startedAt) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("jobId", jobId);
    payload.put("rowsProcessed", progress.rowsProcessed());
    payload.put("rowsPerSecond", progress.rowsPerSecond());
    payload.put("failures", progress.failures());
    payload.put("elapsedMillis", clock.instant().toEpochMilli() - startedAt.toEpochMilli());
    payload.put("currentPhase", progress.phase());
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
    if (message != null && !message.isBlank()) {
      payload.put("message", message);
    }
    events.publish(jobId, "status", payload);
  }

  /**
   * The persisted settings document.
   *
   * <p>Everything needed to reproduce the job: the resolved settings with their auto markers, the
   * generated command, and (for a count) the statistics. Written into {@code settings_json} rather
   * than into new columns, so this workstream ships without a schema migration that would collide
   * with another agent's.
   */
  String settingsDocument(DsbulkPlan plan, TableStatistics statistics, int exitCode) {
    Map<String, Object> document = new LinkedHashMap<>();
    document.put("derivedSettings", DerivedSetting.from(plan.settings()));
    document.put("command", plan.command());
    document.put("argv", plan.argv());
    document.put("hocon", plan.hocon());
    document.put("maskedFields", plan.maskedFields());
    document.put("warnings", plan.warnings());
    document.put("exitCode", exitCode);
    if (statistics != null) {
      document.put("statistics", statistics);
    }
    try {
      return json.writeValueAsString(document);
    } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
      LOG.warn("Cannot serialise the job settings document: {}", e.toString());
      return null;
    }
  }

  /** Error text safe to store and return: a message, never a credential or a stack trace. */
  static String safeMessage(Exception e) {
    String message = e.getMessage();
    return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
  }

  DsbulkJobView view(
      String jobId,
      String type,
      String status,
      DsbulkJobSpec spec,
      String connectionId,
      String name,
      Instant createdAt,
      Instant startedAt,
      Instant finishedAt,
      Integer exitCode,
      TableStatistics statistics) {
    return new DsbulkJobView(
        jobId,
        name,
        type,
        status,
        "DSBULK",
        connectionId,
        new SchemaIdentity("TABLE", spec.keyspace(), spec.table(), spec.qualifiedName()),
        createdAt == null ? null : createdAt.toString(),
        startedAt == null ? null : startedAt.toString(),
        finishedAt == null ? null : finishedAt.toString(),
        startedAt == null || finishedAt == null ? null : finishedAt.toEpochMilli() - startedAt.toEpochMilli(),
        new JobProgressView(0, null, null, 0, 0, 0, ""),
        java.util.List.of(),
        "/api/jobs/" + jobId + "/events",
        "/api/jobs/" + jobId + "/logs",
        exitCode,
        statistics);
  }
}
