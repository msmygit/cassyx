package io.cassyx.core.testsupport;

import com.datastax.oss.driver.api.core.CqlSession;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;

/**
 * Base class for every integration test in every module (plan section 11.2). Extending it gives you
 * the single shared Cassandra 5.x container - never start your own.
 *
 * <p>Integration tests are opt-in via {@code -Dcassyx.it=true} so that a plain {@code mvn verify} on
 * a machine without Docker still runs the fast suite. The guard is an assumption rather than
 * {@code @EnabledIfSystemProperty}, because that annotation is not inherited by subclasses and
 * would silently do nothing here.
 */
public abstract class IntegrationTestBase {

  /** System property that enables the integration suite. */
  public static final String ENABLED_PROPERTY = "cassyx.it";

  @BeforeAll
  static void requireIntegrationTestsEnabled() {
    Assumptions.assumeTrue(
        Boolean.getBoolean(ENABLED_PROPERTY),
        "Integration tests are disabled; run with -D" + ENABLED_PROPERTY + "=true (needs Docker)");
  }

  protected static CqlSession session() {
    return CassandraSingleton.session();
  }

  /** Creates a keyspace with RF 1 if absent and returns its name. */
  protected static String ensureKeyspace(String name) {
    session()
        .execute(
            "CREATE KEYSPACE IF NOT EXISTS "
                + name
                + " WITH replication = {'class':'SimpleStrategy','replication_factor':1}");
    return name;
  }
}
