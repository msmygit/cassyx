package io.cassyx.api.smoke;

import static org.assertj.core.api.Assertions.assertThat;

import io.cassyx.core.api.CoreFactory;
import io.cassyx.core.api.KeyspaceSummary;
import io.cassyx.core.testsupport.IntegrationTestBase;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Reference integration test: shows Phase 1 agents the ONE pattern to follow - extend
 * {@link IntegrationTestBase} and use the shared Cassandra 5.x container (plan section 11.2).
 * Never start your own container.
 *
 * <p>Runs under Failsafe ({@code *IT}) with {@code -Dcassyx.it=true}; skipped otherwise so a
 * Docker-less {@code mvn test} still passes.
 */
class SharedCassandraIT extends IntegrationTestBase {

  @Test
  void readsSchemaFromTheSharedContainer() {
    ensureKeyspace("cassyx_smoke");

    List<KeyspaceSummary> keyspaces = CoreFactory.schemaCatalog().keyspaces(session(), false);

    assertThat(keyspaces).extracting(KeyspaceSummary::name).contains("cassyx_smoke");
  }
}
