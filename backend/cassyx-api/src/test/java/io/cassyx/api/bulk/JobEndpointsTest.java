package io.cassyx.api.bulk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.datastax.oss.driver.api.core.CqlSession;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.cassyx.bulk.api.dsbulk.DsbulkJobSpec;
import io.cassyx.bulk.api.dsbulk.DsbulkListener;
import io.cassyx.bulk.api.dsbulk.DsbulkOperation;
import io.cassyx.bulk.api.dsbulk.DsbulkPlan;
import io.cassyx.bulk.api.dsbulk.DsbulkResult;
import io.cassyx.bulk.api.dsbulk.DsbulkRunner;
import io.cassyx.core.api.ClusterCapabilities;
import io.cassyx.core.api.ConnectionNotOpenException;
import io.cassyx.core.api.SessionRegistry;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * The job substrate's endpoints over a real servlet stack and a real database (plan section 5.5).
 *
 * <p>What this proves beyond the JSON shapes:
 *
 * <ul>
 *   <li>the SSE stream really emits <b>named</b> frames, asserted on the raw response bytes rather
 *       than on the publisher's intent;
 *   <li>the artifact download streams and honours {@code Range};
 *   <li>the lifecycle guards (409 on a live job, 404 on an unknown one) behave as the contract says.
 * </ul>
 *
 * <p><b>Standalone MockMvc, not {@code @SpringBootTest}, deliberately.</b> Eight workstreams are
 * landing beans into one application context in parallel; booting the whole context here would make
 * this suite red whenever any unrelated workstream is mid-edit, which tells us nothing about the job
 * substrate. The controllers are constructed directly against a real Flyway-migrated H2, so the
 * database, the JSON mapping, the SSE emitter and the streaming response are all genuine - only the
 * component scan is skipped. Full-context wiring is covered by the application smoke test.
 */
class JobEndpointsTest {

  private static final String CONNECTION = "11111111-2222-3333-4444-555555555555";

  private static org.springframework.jdbc.datasource.embedded.EmbeddedDatabase database;
  private static JdbcTemplate jdbc;

  @TempDir static Path artifactRoot;

  private MockMvc mvc;
  private JobRepository repository;
  private JobService jobs;
  private DsbulkJobService dsbulkJobs;
  private SleepingRunner runner;
  private DsbulkJobEventStream events;

  @BeforeAll
  static void startDatabase() {
    database =
        new EmbeddedDatabaseBuilder()
            .setType(EmbeddedDatabaseType.H2)
            .setName("cassyx-jobs-" + UUID.randomUUID())
            .build();
    migrate(database);
    jdbc = new JdbcTemplate(database);
  }

  /** Runs the real Flyway migrations, so the baseline {@code cassyx_job} table is the real one. */
  private static void migrate(DataSource dataSource) {
    org.flywaydb.core.Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration")
        .load()
        .migrate();
  }

  @AfterAll
  static void stopDatabase() {
    if (database != null) {
      database.shutdown();
    }
  }

  @BeforeEach
  void setUp() {
    jdbc.update("DELETE FROM cassyx_job");
    // cassyx_job.connection_id is a real foreign key, so the parent row has to exist.
    jdbc.update("DELETE FROM cassyx_connection WHERE id = ?", CONNECTION);
    jdbc.update(
        "INSERT INTO cassyx_connection (id, name, mode, created_at, updated_at) "
            + "VALUES (?, ?, 'CASSANDRA', ?, ?)",
        CONNECTION,
        "test-" + UUID.randomUUID(),
        Timestamp.from(Instant.now()),
        Timestamp.from(Instant.now()));
    ObjectMapper mapper = new ObjectMapper();
    repository = new JobRepository(jdbc, mapper);
    events = new DsbulkJobEventStream();
    runner = new SleepingRunner();
    dsbulkJobs =
        new DsbulkJobService(
            new DsbulkJobRepository(jdbc),
            events,
            runner,
            Executors.newSingleThreadExecutor(),
            new DisconnectedSessions(),
            mapper,
            artifactRoot,
            Clock.systemUTC());
    jobs =
        new JobService(
            repository,
            events,
            dsbulkJobs,
            new DisconnectedSessions(),
            Executors.newSingleThreadExecutor(),
            mapper,
            artifactRoot,
            Clock.systemUTC(),
            4);
    JobController controller = new JobController(repository, jobs, events);
    mvc =
        MockMvcBuilders.standaloneSetup(controller, new UnloadJobController(jobs))
            .setControllerAdvice(new JobProblemAdvice())
            .build();
  }

  /**
   * A registry with no live session - the realistic state of a fresh install, and the one that
   * proves a failed job still reports its full named lifecycle instead of hanging.
   */
  private static final class DisconnectedSessions implements SessionRegistry {
    @Override
    public CqlSession session(String connectionId) {
      throw new ConnectionNotOpenException(connectionId);
    }

    @Override
    public boolean isConnected(String connectionId) {
      return false;
    }

    @Override
    public ClusterCapabilities capabilities(String connectionId) {
      throw new ConnectionNotOpenException(connectionId);
    }
  }

  /**
   * A DSBulk runner that starts a REAL long-running child process and blocks until it dies.
   *
   * <p>Not a mock, and that is the point of the test it serves. The bug being guarded against is a
   * cancel that returns success while a process keeps running; a mocked runner whose {@code cancel}
   * returns {@code true} would report exactly the same success and prove nothing. This one exposes
   * the actual {@link Process}, so the test can ask the operating system whether it is still there.
   */
  private static final class SleepingRunner implements DsbulkRunner {

    private final java.util.concurrent.atomic.AtomicReference<Process> process =
        new java.util.concurrent.atomic.AtomicReference<>();
    private final CountDownLatch started = new CountDownLatch(1);
    private volatile Path directory;

    @Override
    public DsbulkResult run(
        DsbulkPlan plan, Path jobDirectory, Map<String, String> secrets, DsbulkListener listener) {
      this.directory = jobDirectory.toAbsolutePath().normalize();
      try {
        Process child =
            new ProcessBuilder("sh", "-c", "exec sleep 120")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        process.set(child);
        started.countDown();
        int exit = child.waitFor();
        return new DsbulkResult(exit, 0, 0, java.time.Duration.ofMillis(1), jobDirectory,
            java.util.List.of(), io.cassyx.bulk.api.dsbulk.DsbulkCountReport.EMPTY, "killed");
      } catch (java.io.IOException e) {
        throw new IllegalStateException(e);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }
    }

    @Override
    public boolean cancel(Path jobDirectory) {
      Process child = process.get();
      if (child == null
          || !child.isAlive()
          || !jobDirectory.toAbsolutePath().normalize().equals(directory)) {
        return false;
      }
      child.destroy();
      return true;
    }
  }

  /** Seeds a job row directly, so the read side is testable without running an engine. */
  private String seed(String type, String status, String keyspace, String table, String artifact) {
    String id = UUID.randomUUID().toString();
    jdbc.update(
        "INSERT INTO cassyx_job (id, type, status, connection_id, keyspace_name, table_name, "
            + "engine, settings_json, rows_processed, splits_total, splits_completed, "
            + "artifact_path, created_at, started_at, finished_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, 'NATIVE', ?, ?, ?, ?, ?, ?, ?, ?)",
        id,
        type,
        status,
        CONNECTION,
        keyspace,
        table,
        "{\"name\":\"" + type + " " + keyspace + "." + table + "\"}",
        4_210_000L,
        10_000,
        4_103,
        artifact,
        Timestamp.from(Instant.now()),
        Timestamp.from(Instant.now()),
        JobController.isTerminal(status) ? Timestamp.from(Instant.now()) : null);
    return id;
  }

  /* --------------------------------------------------------------------------------- list */

  @Test
  @DisplayName("listJobs pages, filters and returns newest first across BOTH engines")
  void listAndFilter() throws Exception {
    seed("UNLOAD", "SUCCEEDED", "demo", "users", null);
    seed("LOAD", "FAILED", "demo", "orders", null);
    String running = seed("UNLOAD", "RUNNING", "demo", "sensor_readings", null);

    mvc.perform(get("/api/jobs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(3))
        .andExpect(jsonPath("$.limit").value(50))
        .andExpect(jsonPath("$.offset").value(0))
        .andExpect(jsonPath("$.items.length()").value(3));

    mvc.perform(get("/api/jobs").param("status", "RUNNING"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.items[0].id").value(running));

    // style: form, explode: false - one comma-joined parameter, not a repeated one.
    mvc.perform(get("/api/jobs").param("status", "RUNNING,FAILED"))
        .andExpect(jsonPath("$.total").value(2));

    mvc.perform(get("/api/jobs").param("type", "LOAD"))
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.items[0].type").value("LOAD"));

    mvc.perform(get("/api/jobs").param("connectionId", UUID.randomUUID().toString()))
        .andExpect(jsonPath("$.total").value(0));

    mvc.perform(get("/api/jobs").param("limit", "2").param("offset", "2"))
        .andExpect(jsonPath("$.limit").value(2))
        .andExpect(jsonPath("$.offset").value(2))
        .andExpect(jsonPath("$.items.length()").value(1));

    // The contract caps limit at 500; a client asking for more gets the cap, not a 500.
    mvc.perform(get("/api/jobs").param("limit", "100000"))
        .andExpect(jsonPath("$.limit").value(500));
  }

  /* ------------------------------------------------------------------------------ get/CRUD */

  @Test
  @DisplayName("getJob returns the contract's Job; an unknown id is an RFC 9457 404")
  void getAndNotFound() throws Exception {
    String id = seed("UNLOAD", "RUNNING", "demo", "users", null);

    mvc.perform(get("/api/jobs/{id}", id))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(id))
        .andExpect(jsonPath("$.type").value("UNLOAD"))
        .andExpect(jsonPath("$.status").value("RUNNING"))
        .andExpect(jsonPath("$.engine").value("NATIVE"))
        .andExpect(jsonPath("$.identity.qualifiedName").value("demo.users"))
        .andExpect(jsonPath("$.progress.splitsTotal").value(10000))
        .andExpect(jsonPath("$.eventsUrl").value("/api/jobs/" + id + "/events"));

    mvc.perform(get("/api/jobs/{id}", UUID.randomUUID()))
        .andExpect(status().isNotFound())
        .andExpect(header().string("Content-Type", "application/problem+json"))
        .andExpect(jsonPath("$.title").value("Job not found"));
  }

  @Test
  @DisplayName("a live job cannot be deleted - cancel it first")
  void deleteRequiresATerminalState() throws Exception {
    String running = seed("UNLOAD", "RUNNING", "demo", "users", null);
    mvc.perform(delete("/api/jobs/{id}", running))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.title").value("Job state conflict"));

    String done = seed("UNLOAD", "SUCCEEDED", "demo", "users", null);
    mvc.perform(delete("/api/jobs/{id}", done)).andExpect(status().isNoContent());
    mvc.perform(get("/api/jobs/{id}", done)).andExpect(status().isNotFound());

    mvc.perform(delete("/api/jobs/{id}", UUID.randomUUID())).andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("cancelling a finished job is a 409, not a silent no-op")
  void cancelGuards() throws Exception {
    String done = seed("UNLOAD", "SUCCEEDED", "demo", "users", null);
    mvc.perform(post("/api/jobs/{id}/cancel", done)).andExpect(status().isConflict());

    mvc.perform(post("/api/jobs/{id}/cancel", UUID.randomUUID())).andExpect(status().isNotFound());

    String running = seed("UNLOAD", "RUNNING", "demo", "users", null);
    mvc.perform(post("/api/jobs/{id}/cancel", running))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.id").value(running));
  }

  @Test
  @DisplayName("cancelling a DSBULK job kills the process and lands the row in CANCELLED")
  void cancelReachesTheDsbulkEngine() throws Exception {
    DsbulkJobSpec spec = DsbulkJobSpec.table(
        DsbulkOperation.LOAD, "demo", "users", "csv", artifactRoot.resolve("in.csv").toString());
    String id = dsbulkJobs.submit(CONNECTION, "Load demo.users", spec).id();

    assertThat(runner.started.await(20, TimeUnit.SECONDS)).as("the child process started").isTrue();
    Process child = runner.process.get();
    assertThat(child.isAlive()).isTrue();

    // THE regression. Before JobService routed on the row's engine, this returned 202 with the job
    // still RUNNING and `sleep` still on the process table - the UI said stopped, the cluster did
    // not. The engine column is what makes the routing correct, so assert it is really DSBULK.
    assertThat(repository.find(id)).get().extracting(io.cassyx.api.bulk.JobDtos.Job::engine)
        .isEqualTo("DSBULK");

    mvc.perform(post("/api/jobs/{id}/cancel", id)).andExpect(status().isAccepted());

    assertThat(child.waitFor(20, TimeUnit.SECONDS)).as("the child process is gone").isTrue();
    assertThat(child.isAlive()).isFalse();
    assertThat(awaitTerminal(id)).isEqualTo("CANCELLED");

    // The worker thread wakes up afterwards holding exit 143 and must not relabel this a failure.
    Thread.sleep(300);
    assertThat(repository.find(id)).get()
        .extracting(io.cassyx.api.bulk.JobDtos.Job::status).isEqualTo("CANCELLED");
    assertThat(events.snapshot(id)).extracting(DsbulkJobEventStream.Event::name)
        .contains("status", "completed");
  }

  @Test
  @DisplayName("a RUNNING row no engine owns is cancelled outright, not answered 202 and left running")
  void cancelOfAnOrphanedRowIsHonest() throws Exception {
    String orphan = seed("UNLOAD", "RUNNING", "demo", "users", null);
    mvc.perform(post("/api/jobs/{id}/cancel", orphan)).andExpect(status().isAccepted());
    assertThat(repository.find(orphan)).get()
        .extracting(io.cassyx.api.bulk.JobDtos.Job::status).isEqualTo("CANCELLED");
  }

  /* ---------------------------------------------------------------------------------- SSE */

  @Test
  @DisplayName("the SSE stream emits NAMED frames - status/progress/log/completed - with ids")
  void sseFramesAreNamed() throws Exception {
    String id = seed("UNLOAD", "RUNNING", "demo", "users", null);

    MvcResult result =
        mvc.perform(get("/api/jobs/{id}/events", id))
            .andExpect(status().isOk())
            .andExpect(request().asyncStarted())
            .andReturn();

    events.publish(id, "status", Map.of("jobId", id, "status", "RUNNING"));
    events.publish(id, "progress", Map.of("jobId", id, "rowsProcessed", 4_210_000));
    events.publish(id, "log", Map.of("jobId", id, "level", "INFO", "message", "Unloading."));
    events.complete(id, Map.of("jobId", id, "status", "SUCCEEDED"));

    String stream = result.getResponse().getContentAsString(StandardCharsets.UTF_8);

    // THE contract point: an EventSource with only an onmessage handler receives nothing at all
    // from a named stream, so a plain-message stream would make every job appear to hang forever.
    assertThat(stream).contains("event:status");
    assertThat(stream).contains("event:progress");
    assertThat(stream).contains("event:log");
    assertThat(stream).contains("event:completed");
    // Monotonic ids are what make a Last-Event-ID reconnect resume without a gap.
    assertThat(stream).contains("id:");
    assertThat(stream).contains("\"rowsProcessed\":4210000");

    mvc.perform(get("/api/jobs/{id}/events", UUID.randomUUID()))
        .andExpect(status().isNotFound());
  }

  /* --------------------------------------------------------------------------------- logs */

  @Test
  @DisplayName("retained logs are returned, tailed and level-filtered")
  void logs() throws Exception {
    String id = seed("UNLOAD", "RUNNING", "demo", "users", null);
    jobs.log(id, "INFO", "Scan strategy: TOKEN_RANGE with a target of 10000 splits");
    jobs.log(id, "WARN", "Token-range scan unavailable; fell back to paging.");
    jobs.log(id, "ERROR", "Split failed.");

    mvc.perform(get("/api/jobs/{id}/logs", id))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.jobId").value(id))
        .andExpect(jsonPath("$.lines.length()").value(3))
        .andExpect(jsonPath("$.totalLines").value(3))
        .andExpect(jsonPath("$.truncated").value(false))
        .andExpect(jsonPath("$.lines[0].source").value("ENGINE"));

    mvc.perform(get("/api/jobs/{id}/logs", id).param("level", "WARN"))
        .andExpect(jsonPath("$.lines.length()").value(2));

    mvc.perform(get("/api/jobs/{id}/logs", id).param("tail", "1"))
        .andExpect(jsonPath("$.lines.length()").value(1))
        .andExpect(jsonPath("$.truncated").value(true))
        .andExpect(jsonPath("$.lines[0].level").value("ERROR"));

    mvc.perform(get("/api/jobs/{id}/logs", UUID.randomUUID())).andExpect(status().isNotFound());
  }

  /* ----------------------------------------------------------------------------- artifact */

  @Test
  @DisplayName("the artifact streams with a filename, and Range yields a 206 window")
  void artifactDownload() throws Exception {
    Path file = Files.createTempFile("cassyx-artifact", ".csv");
    Files.writeString(file, "id,email\n1,ada@example.com\n2,alan@example.com\n");
    String id = seed("UNLOAD", "SUCCEEDED", "demo", "users", file.toString());

    // StreamingResponseBody is an ASYNC return type: the bytes only exist after the async dispatch.
    // That is the point of the endpoint - nothing is materialised before the response starts.
    MvcResult started =
        mvc.perform(get("/api/jobs/{id}/artifact", id))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", "text/csv"))
            .andExpect(header().string("Accept-Ranges", "bytes"))
            .andExpect(
                header()
                    .string(
                        "Content-Disposition",
                        "attachment; filename=\"" + file.getFileName() + "\""))
            .andReturn();
    byte[] whole =
        mvc.perform(asyncDispatch(started)).andReturn().getResponse().getContentAsByteArray();
    assertThat(new String(whole, StandardCharsets.UTF_8)).isEqualTo(Files.readString(file));

    // Range support is what makes an interrupted multi-gigabyte download resumable.
    MvcResult partial =
        mvc.perform(get("/api/jobs/{id}/artifact", id).header("Range", "bytes=0-7"))
            .andExpect(status().isPartialContent())
            .andExpect(header().string("Content-Range", "bytes 0-7/" + Files.size(file)))
            .andReturn();
    assertThat(
            mvc.perform(asyncDispatch(partial))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8))
        .isEqualTo("id,email");

    Files.deleteIfExists(file);
  }

  @Test
  @DisplayName("the artifact is a 409 before the job succeeds and a 404 when there is none")
  void artifactGuards() throws Exception {
    String running = seed("UNLOAD", "RUNNING", "demo", "users", null);
    mvc.perform(get("/api/jobs/{id}/artifact", running))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.title").value("Job state conflict"));

    String noArtifact = seed("COUNT", "SUCCEEDED", "demo", "users", null);
    mvc.perform(get("/api/jobs/{id}/artifact", noArtifact)).andExpect(status().isNotFound());

    mvc.perform(get("/api/jobs/{id}/artifact", UUID.randomUUID())).andExpect(status().isNotFound());
  }

  /* ------------------------------------------------------------------------------- unload */

  @Test
  @DisplayName("createUnloadJob validates before it queues anything")
  void unloadValidation() throws Exception {
    mvc.perform(
            post("/api/connections/{id}/jobs/unload", CONNECTION)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"format\":\"CSV\",\"sink\":{\"type\":\"DOWNLOAD\"}}"))
        .andExpect(status().isBadRequest())
        .andExpect(header().string("Content-Type", "application/problem+json"));

    mvc.perform(
            post("/api/connections/{id}/jobs/unload", CONNECTION)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"keyspace\":\"demo\",\"table\":\"users\",\"format\":\"PROTOBUF\","
                        + "\"sink\":{\"type\":\"DOWNLOAD\"}}"))
        .andExpect(status().isBadRequest());

    mvc.perform(
            post("/api/connections/{id}/jobs/unload", CONNECTION)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"keyspace\":\"demo\",\"table\":\"users\",\"format\":\"CSV\","
                        + "\"sink\":{\"type\":\"S3\"}}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("an unload against a disconnected connection queues, then lands FAILED with events")
  void unloadWithoutASessionFails() throws Exception {
    String body =
        mvc.perform(
                post("/api/connections/{id}/jobs/unload", CONNECTION)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"name\":\"Export demo.users\",\"keyspace\":\"demo\",\"table\":\"users\","
                            + "\"format\":\"CSV\",\"engine\":\"NATIVE\","
                            + "\"sink\":{\"type\":\"DOWNLOAD\",\"fileNamePattern\":\"users-%d.csv\"}}"))
            .andExpect(status().isAccepted())
            .andExpect(header().exists("Location"))
            .andExpect(jsonPath("$.status").value("QUEUED"))
            .andExpect(jsonPath("$.type").value("UNLOAD"))
            .andExpect(jsonPath("$.engine").value("NATIVE"))
            .andExpect(jsonPath("$.name").value("Export demo.users"))
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);

    String id = body.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");

    // The worker runs on the bounded executor; the terminal state is what matters, not the timing.
    String status = awaitTerminal(id);
    assertThat(status).isEqualTo("FAILED");

    // A failed job still reports the full named lifecycle, so the UI never shows a stuck spinner.
    assertThat(events.snapshot(id))
        .extracting(DsbulkJobEventStream.Event::name)
        .contains("status", "completed");

    mvc.perform(get("/api/jobs/{id}", id))
        .andExpect(jsonPath("$.status").value("FAILED"))
        .andExpect(jsonPath("$.error.status").value(500))
        .andExpect(jsonPath("$.artifacts.length()").value(0));
  }

  private String awaitTerminal(String id) throws InterruptedException {
    for (int i = 0; i < 100; i++) {
      String status =
          repository.find(id).map(io.cassyx.api.bulk.JobDtos.Job::status).orElse("QUEUED");
      if (JobController.isTerminal(status)) {
        return status;
      }
      Thread.sleep(50);
    }
    return repository.find(id).map(io.cassyx.api.bulk.JobDtos.Job::status).orElse("QUEUED");
  }
}
