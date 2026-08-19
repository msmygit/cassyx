package io.cassyx.api.bulk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.datastax.oss.driver.api.core.CqlSession;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.cassyx.api.schema.DdlService;
import io.cassyx.api.schema.SchemaProblemAdvice;
import io.cassyx.api.schema.SchemaSessions;
import io.cassyx.api.schema.TableController;
import io.cassyx.bulk.api.dsbulk.DsbulkCountReport;
import io.cassyx.bulk.api.dsbulk.DsbulkListener;
import io.cassyx.bulk.api.dsbulk.DsbulkPlan;
import io.cassyx.bulk.api.dsbulk.DsbulkResult;
import io.cassyx.bulk.api.dsbulk.DsbulkRunner;
import io.cassyx.core.api.ClusterCapabilities;
import io.cassyx.core.api.SessionRegistry;
import io.cassyx.core.api.schema.PrimaryKeyDefinition;
import io.cassyx.core.api.schema.SchemaIdentity;
import io.cassyx.core.api.schema.SchemaReader;
import io.cassyx.core.api.schema.TableDetail;
import io.cassyx.core.api.schema.TableInfo;
import io.cassyx.core.api.schema.TableStatistics;
import io.cassyx.core.api.schema.TableStatisticsStore;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * The statistics round trip: a COUNT job finishes, and the Statistics tab can see it.
 *
 * <p>This test exists because that link was missing for an entire workstream and nothing noticed.
 * Every piece was present and individually tested - the DSBulk count workflow, the output parser,
 * the job service, the store interface, the controller, the whole React tab - and the result still
 * never reached the user, because the job service wrote the snapshot into the job row's settings
 * document and no one ever wrote to the store the controller reads. Unit tests on both halves pass
 * happily either way. Only a test that goes in one end and out the other can fail.
 *
 * <p>So the assertion is deliberately end to end: POST the count job, let it finish, then GET the
 * statistics endpoint and the table info panel over a real servlet stack and a real database.
 */
class StatisticsRoundTripTest {

  private static final String CONNECTION = "22222222-3333-4444-5555-666666666666";
  private static final String BASE = "/api/connections/" + CONNECTION;

  /** More ranges than the cap, so truncation is exercised rather than described. */
  private static final int REPORTED_RANGES = 640;

  private static org.springframework.jdbc.datasource.embedded.EmbeddedDatabase database;
  private static JdbcTemplate jdbc;

  @TempDir static Path workRoot;

  private MockMvc mvc;
  private ObjectMapper mapper;
  private DsbulkJobRepository jobRepository;
  private TableStatisticsStore store;
  private SchemaReader schemaReader;

  @BeforeAll
  static void startDatabase() {
    database =
        new EmbeddedDatabaseBuilder()
            .setType(EmbeddedDatabaseType.H2)
            .setName("cassyx-statistics-" + UUID.randomUUID())
            .build();
    migrate(database);
    jdbc = new JdbcTemplate(database);
  }

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
    jdbc.update("DELETE FROM cassyx_connection WHERE id = ?", CONNECTION);
    jdbc.update(
        "INSERT INTO cassyx_connection (id, name, mode, created_at, updated_at) "
            + "VALUES (?, ?, 'CASSANDRA', ?, ?)",
        CONNECTION,
        "test-" + UUID.randomUUID(),
        Timestamp.from(Instant.now()),
        Timestamp.from(Instant.now()));

    mapper = new ObjectMapper();
    mapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
    mapper.disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    jobRepository = new DsbulkJobRepository(jdbc);
    store = new JobRowTableStatisticsStore(jobRepository, mapper);

    SessionRegistry sessions = new SingleSessionRegistry();
    DsbulkJobService dsbulkJobs =
        new DsbulkJobService(
            jobRepository,
            new DsbulkJobEventStream(),
            new CountingRunner(),
            Executors.newSingleThreadExecutor(),
            sessions,
            mapper,
            workRoot,
            Clock.systemUTC(),
            store);

    schemaReader = mock(SchemaReader.class);
    when(schemaReader.table(any(), eq("demo"), eq("users"))).thenReturn(tableDetail());
    when(schemaReader.tableInfo(any(), eq("demo"), eq("users"), org.mockito.ArgumentMatchers.anyBoolean()))
        .thenAnswer(call -> new TableInfo(
            SchemaIdentity.table("demo", "users"),
            List.of(), List.of(), null, "CREATE TABLE demo.users (...);", List.of(),
            call.getArgument(3)));

    SchemaSessions schemaSessions = new SchemaSessions(sessions);
    TableController tables = new TableController(
        schemaReader,
        schemaSessions,
        new DdlService(null, null, schemaSessions, mapper),
        store);

    mvc = MockMvcBuilders.standaloneSetup(new CountJobController(dsbulkJobs), tables)
        .setControllerAdvice(new JobProblemAdvice(), new SchemaProblemAdvice())
        .build();
  }

  @Test
  @DisplayName("a finished COUNT job is visible on the statistics endpoint and the info panel")
  void countJobReachesTheStatisticsTab() throws Exception {
    // Before: the contract's 404, and an info panel that says there is nothing to show.
    mvc.perform(get(BASE + "/keyspaces/demo/tables/users/statistics")).andExpect(status().isNotFound());
    mvc.perform(get(BASE + "/keyspaces/demo/tables/users/info"))
        .andExpect(jsonPath("$.statisticsAvailable").value(false));

    mvc.perform(post(BASE + "/jobs/count")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"keyspace":"demo","table":"users","modes":["global","ranges","hosts","partitions"],
                 "topPartitions":10}
                """))
        .andExpect(status().isAccepted());

    awaitTerminal();

    mvc.perform(get(BASE + "/keyspaces/demo/tables/users/statistics"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalRows").value(1_000_000))
        .andExpect(jsonPath("$.identity.qualifiedName").value("demo.users"))
        // DSBulk reports no total partition count, so the field is absent rather than a fabricated
        // echo of the top-N cap.
        .andExpect(jsonPath("$.partitionCount").doesNotExist())
        .andExpect(jsonPath("$.largestPartitions[0].rows").value(9_000))
        // Murmur3's minimum token must survive as text - it does not round-trip a JS number.
        .andExpect(jsonPath("$.perTokenRange[0].start").value("-9223372036854775808"))
        .andExpect(jsonPath("$.perTokenRangeTruncated").value(true))
        .andExpect(jsonPath("$.perTokenRangeReported").value(REPORTED_RANGES))
        .andExpect(jsonPath("$.perTokenRange.length()")
            .value(DsbulkDtos.TableStatistics.MAX_DETAIL_ROWS))
        .andExpect(jsonPath("$.perReplicaTruncated").value(false));

    mvc.perform(get(BASE + "/keyspaces/demo/tables/users/info"))
        .andExpect(jsonPath("$.statisticsAvailable").value(true));
  }

  @Test
  @DisplayName("the snapshot survives a restart, because the job row is the source of truth")
  void snapshotOutlivesTheProcessCache() throws Exception {
    mvc.perform(post(BASE + "/jobs/count")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"keyspace\":\"demo\",\"table\":\"users\",\"modes\":[\"global\"]}"))
        .andExpect(status().isAccepted());
    awaitTerminal();

    // A brand-new store: no cache, nothing carried over. This is what the next boot sees, and it is
    // exactly the case the in-memory placeholder got wrong - the snapshot was lost while the job
    // row that produced it sat in the database.
    TableStatisticsStore afterRestart = new JobRowTableStatisticsStore(jobRepository, mapper);
    Optional<TableStatistics> found = afterRestart.find(CONNECTION, "demo", "users");

    assertThat(found).isPresent();
    assertThat(found.get().totalRows()).isEqualTo(1_000_000L);
    assertThat(found.get().partitionCount()).isNull();
    assertThat(found.get().computedAt()).isNotNull();
    assertThat(found.get().perTokenRange()).hasSize(DsbulkDtos.TableStatistics.MAX_DETAIL_ROWS);
    assertThat(found.get().perTokenRangeTruncated()).isTrue();
  }

  /** Polls the job row rather than sleeping a fixed interval. */
  private void awaitTerminal() throws InterruptedException {
    for (int attempt = 0; attempt < 200; attempt++) {
      List<Map<String, Object>> rows =
          jdbc.queryForList("SELECT status FROM cassyx_job WHERE type = 'COUNT'");
      if (!rows.isEmpty() && !"QUEUED".equals(rows.get(0).get("STATUS"))
          && !"RUNNING".equals(rows.get(0).get("STATUS"))) {
        return;
      }
      Thread.sleep(25);
    }
    throw new AssertionError("The count job never reached a terminal state.");
  }

  private static TableDetail tableDetail() {
    return new TableDetail(
        SchemaIdentity.table("demo", "users"),
        "users",
        "demo",
        List.of(),
        new PrimaryKeyDefinition(
            List.of("user_id"),
            List.of(new io.cassyx.core.api.schema.ClusteringKeyColumn("created_at", null))),
        null,
        List.of(),
        List.of(),
        false,
        false,
        false,
        false);
  }

  /** A runner that returns a plausible count report without starting anything. */
  private static final class CountingRunner implements DsbulkRunner {

    @Override
    public DsbulkResult run(
        DsbulkPlan plan, Path jobDirectory, Map<String, String> secrets, DsbulkListener listener) {
      return new DsbulkResult(0, 1_000_000, 0, Duration.ofSeconds(4), jobDirectory, List.of(),
          report(), "");
    }

    @Override
    public boolean cancel(Path jobDirectory) {
      return false;
    }

    private static DsbulkCountReport report() {
      List<DsbulkCountReport.RangeCount> ranges = new ArrayList<>(REPORTED_RANGES);
      // The first range starts at Murmur3's minimum token, which is the value that silently breaks
      // if anything on the path treats a token as a number.
      ranges.add(new DsbulkCountReport.RangeCount(
          String.valueOf(Long.MIN_VALUE), "-9000000000000000000", REPORTED_RANGES));
      for (int i = 1; i < REPORTED_RANGES; i++) {
        ranges.add(new DsbulkCountReport.RangeCount(
            Long.toString(-9_000_000_000_000_000_000L + i), Long.toString(i), REPORTED_RANGES - i));
      }
      return new DsbulkCountReport(
          1_000_000,
          List.of(
              new DsbulkCountReport.ReplicaCount("10.0.0.1:9042", 500_000),
              new DsbulkCountReport.ReplicaCount("10.0.0.2:9042", 500_000)),
          ranges,
          List.of(new DsbulkCountReport.PartitionCount("hot-key", 9_000)));
    }
  }

  /** Connected, with a session that is never actually queried on this path. */
  private static final class SingleSessionRegistry implements SessionRegistry {

    private final CqlSession session = mock(CqlSession.class);

    @Override
    public CqlSession session(String connectionId) {
      return session;
    }

    @Override
    public boolean isConnected(String connectionId) {
      return CONNECTION.equals(connectionId);
    }

    @Override
    public ClusterCapabilities capabilities(String connectionId) {
      return ClusterCapabilities.unknown();
    }
  }
}
