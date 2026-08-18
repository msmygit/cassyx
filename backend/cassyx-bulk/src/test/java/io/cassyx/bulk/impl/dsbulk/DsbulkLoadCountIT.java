package io.cassyx.bulk.impl.dsbulk;

import static org.assertj.core.api.Assertions.assertThat;

import io.cassyx.bulk.api.dsbulk.DsbulkFactory;
import io.cassyx.bulk.api.dsbulk.DsbulkJobSpec;
import io.cassyx.bulk.api.dsbulk.DsbulkListener;
import io.cassyx.bulk.api.dsbulk.DsbulkOperation;
import io.cassyx.bulk.api.dsbulk.DsbulkPlan;
import io.cassyx.bulk.api.dsbulk.DsbulkProbe;
import io.cassyx.bulk.api.dsbulk.DsbulkResult;
import io.cassyx.bulk.api.dsbulk.DsbulkRunner;
import io.cassyx.core.testsupport.CassandraSingleton;
import io.cassyx.core.testsupport.IntegrationTestBase;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A REAL DSBulk load and a REAL DSBulk count, in a real child process, against the shared
 * Testcontainers Cassandra 5.x singleton (plan section 11.2).
 *
 * <p>These are the tests that prove the integration actually works end to end: the generated HOCON
 * is one DSBulk accepts, the {@code -f} file is found, the argv parses, the workflows resolve
 * through ServiceLoader, the exit status means what we think it means, and the count report parses
 * back into the Statistics model.
 *
 * <p><b>Skipped unless a DSBulk distribution is present.</b> The distribution ships in the runtime
 * Docker image, not in the Maven build image, so this suite runs in CI and in the app image and
 * skips on a developer machine that has not set {@code DSBULK_HOME}. A skip says so out loud rather
 * than passing vacuously.
 */
class DsbulkLoadCountIT extends IntegrationTestBase {

  private static final String KEYSPACE = "demo";
  private static final String TABLE = "dsbulk_it_users";

  @TempDir Path jobDirectory;

  @BeforeAll
  static void requireADsbulkDistribution() {
    Assumptions.assumeTrue(
        DsbulkFactory.distribution().isComplete(),
        "No DSBulk distribution found; set DSBULK_HOME to the unpacked dsbulk-<version>.tar.gz");
  }

  /** Contact point and datacenter of the shared container, injected into the child process. */
  private static Map<String, String> clusterOverrides() {
    InetSocketAddress contactPoint = CassandraSingleton.contactPoint();
    Map<String, String> overrides = new LinkedHashMap<>();
    overrides.put("driver.basic.contactPoints",
        "[\"" + contactPoint.getHostString() + ":" + contactPoint.getPort() + "\"]");
    overrides.put("driver.basic.loadBalancingPolicy.localDatacenter", CassandraSingleton.LOCAL_DATACENTER);
    // A single-node container cannot satisfy LOCAL_QUORUM at RF 1 any better than LOCAL_ONE, but
    // being explicit keeps the test independent of the derived default.
    overrides.put("driver.basic.requestConsistency", "LOCAL_ONE");
    return overrides;
  }

  private static void createTable() {
    ensureKeyspace(KEYSPACE);
    session().execute("DROP TABLE IF EXISTS " + KEYSPACE + "." + TABLE);
    session().execute(
        "CREATE TABLE " + KEYSPACE + "." + TABLE + " (id int PRIMARY KEY, email text, tag text)");
  }

  private static long rowCount() {
    return session().execute("SELECT COUNT(*) FROM " + KEYSPACE + "." + TABLE).one().getLong(0);
  }

  private DsbulkResult run(DsbulkJobSpec spec, Path directory) {
    DsbulkRunner runner = DsbulkFactory.runner(DsbulkFactory.distribution(), "1g");
    DsbulkPlan plan = DsbulkFactory.plan(spec, DsbulkProbe.UNKNOWN, directory, null);
    return runner.run(plan, directory, Map.of(), DsbulkListener.noop());
  }

  @Test
  @DisplayName("a real DSBulk load writes real rows into the seeded demo keyspace")
  void realLoad() throws IOException {
    createTable();
    Path source = jobDirectory.resolve("users.csv");
    Files.writeString(source, """
        id,email,tag
        1,ada@example.com,alpha
        2,grace@example.com,beta
        3,alan@example.com,gamma
        """);

    Map<String, String> overrides = clusterOverrides();
    DsbulkJobSpec spec = new DsbulkJobSpec(DsbulkOperation.LOAD, KEYSPACE, TABLE, null, "csv",
        source.toString(), null, false, null, 10, overrides);

    Path directory = jobDirectory.resolve("load");
    DsbulkResult result = run(spec, directory);

    assertThat(result.succeeded())
        .as("DSBulk load exit %s: %s", result.exitCode(), result.failureMessage())
        .isTrue();
    assertThat(rowCount()).isEqualTo(3);
    // The generated configuration is retained as a reproducible artifact.
    assertThat(directory.resolve("dsbulk.conf")).exists();
    assertThat(Files.readString(directory.resolve("dsbulk.conf")))
        .contains("dsbulk.schema.keyspace = \"" + KEYSPACE + "\"");
  }

  @Test
  @DisplayName("a real DSBulk count produces the total, the per-replica and the per-range statistics")
  void realCount() throws IOException {
    createTable();
    for (int i = 1; i <= 7; i++) {
      session().execute("INSERT INTO " + KEYSPACE + "." + TABLE + " (id, email) VALUES (?, ?)",
          i, "user" + i + "@example.com");
    }

    DsbulkJobSpec spec = new DsbulkJobSpec(DsbulkOperation.COUNT, KEYSPACE, TABLE, null, "csv", null,
        null, false, java.util.List.of("global", "ranges", "hosts", "partitions"), 5, clusterOverrides());

    Path directory = jobDirectory.resolve("count");
    DsbulkResult result = run(spec, directory);

    assertThat(result.succeeded())
        .as("DSBulk count exit %s: %s", result.exitCode(), result.failureMessage())
        .isTrue();
    assertThat(result.countReport().totalRows()).isEqualTo(7);
    assertThat(result.countReport().perReplica()).isNotEmpty();
    assertThat(result.countReport().perTokenRange()).isNotEmpty();
    assertThat(result.countReport().largestPartitions()).isNotEmpty();
    // Tokens survive as strings; a Murmur3 token does not fit a JavaScript number.
    assertThat(result.countReport().perTokenRange().get(0).start()).isNotBlank();
    assertThat(result.rowsProcessed()).isEqualTo(7);
  }

  @Test
  @DisplayName("the shipped distribution really does carry all three ServiceLoader workflows")
  void distributionIsComplete() {
    assertThat(DsbulkFactory.distribution().workflows())
        .containsExactlyInAnyOrder("dsbulk-workflow-load", "dsbulk-workflow-unload", "dsbulk-workflow-count");
  }
}
