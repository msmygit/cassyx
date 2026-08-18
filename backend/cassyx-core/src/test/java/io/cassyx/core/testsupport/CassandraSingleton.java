package io.cassyx.core.testsupport;

import com.datastax.oss.driver.api.core.CqlSession;
import java.net.InetSocketAddress;
import org.testcontainers.containers.CassandraContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * ONE Cassandra 5.x container for the WHOLE integration suite (plan section 11.2).
 *
 * <p>Starting a container per test class is the usual reason Cassandra suites become unusably slow,
 * so this class deliberately does <b>not</b> use {@code @Testcontainers} / {@code @Container}
 * lifecycle management. The container is started lazily on first use and left running; Ryuk reaps
 * it when the JVM exits.
 *
 * <p>Integration tests across every module extend {@link IntegrationTestBase} (or call
 * {@link #session()} directly):
 *
 * <pre>{@code
 * class MyIT extends IntegrationTestBase {
 *   @Test
 *   void createsKeyspace() {
 *     session().execute("CREATE KEYSPACE IF NOT EXISTS demo WITH replication = "
 *         + "{'class':'SimpleStrategy','replication_factor':1}");
 *   }
 * }
 * }</pre>
 *
 * <p>Every test must namespace its own keyspace(s); the container is shared state.
 */
public final class CassandraSingleton {

  /** Cassandra 5.x - required for vector / SAI (plan section 10, item 4). */
  public static final DockerImageName IMAGE = DockerImageName.parse("cassandra:5.0");

  public static final String LOCAL_DATACENTER = "datacenter1";

  private static volatile CassandraContainer<?> container;
  private static volatile CqlSession session;

  private CassandraSingleton() {}

  /** Starts the shared container on first call; subsequent calls reuse it. */
  @SuppressWarnings("resource")
  public static CassandraContainer<?> container() {
    CassandraContainer<?> local = container;
    if (local == null) {
      synchronized (CassandraSingleton.class) {
        local = container;
        if (local == null) {
          local =
              new CassandraContainer<>(IMAGE)
                  .withEnv("CASSANDRA_DC", LOCAL_DATACENTER)
                  .withEnv("HEAP_NEWSIZE", "128M")
                  .withEnv("MAX_HEAP_SIZE", "1024M");
          local.start();
          container = local;
        }
      }
    }
    return local;
  }

  /** A session shared by the whole suite. Do not close it - it outlives individual tests. */
  public static CqlSession session() {
    CqlSession local = session;
    if (local == null) {
      synchronized (CassandraSingleton.class) {
        local = session;
        if (local == null) {
          CassandraContainer<?> c = container();
          local =
              CqlSession.builder()
                  .addContactPoint(
                      new InetSocketAddress(c.getHost(), c.getMappedPort(9042)))
                  .withLocalDatacenter(LOCAL_DATACENTER)
                  .build();
          session = local;
          Runtime.getRuntime()
              .addShutdownHook(new Thread(CassandraSingleton::closeSessionQuietly));
        }
      }
    }
    return local;
  }

  /** Contact point of the shared container, for code that builds its own session. */
  public static InetSocketAddress contactPoint() {
    CassandraContainer<?> c = container();
    return new InetSocketAddress(c.getHost(), c.getMappedPort(9042));
  }

  private static void closeSessionQuietly() {
    CqlSession local = session;
    if (local != null) {
      try {
        local.close();
      } catch (RuntimeException ignored) {
        // shutting down anyway
      }
    }
  }
}
