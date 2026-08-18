package io.cassyx.api.connections;

import io.cassyx.core.api.CoreFactory;
import io.cassyx.core.api.ManagedSessionRegistry;
import io.cassyx.core.api.SecretCipher;
import io.cassyx.core.api.SessionFactory;
import io.cassyx.core.api.SessionRegistry;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Bean wiring for workstream A.
 *
 * <p>Everything here comes from {@link CoreFactory} - no {@code io.cassyx.core.impl} import, which
 * is the modularity contract of plan section 2.1 and is ArchUnit-enforced.
 */
@Configuration(proxyBeanMethods = false)
public class ConnectionsConfiguration {

  private static final Logger LOG = LoggerFactory.getLogger(ConnectionsConfiguration.class);

  /**
   * The AES-256-GCM cipher for everything stored at rest.
   *
   * <p>Deliberately fatal when {@code CASSYX_SECRET_KEY} is unset: cassyx stores cluster passwords,
   * Astra tokens and secure connect bundles, and a "no key configured" fallback would either write
   * them in plaintext or lose every stored credential on restart. Failing at startup, with the
   * command to generate a key, is the honest option.
   */
  @Bean
  public SecretCipher secretCipher(@Value("${cassyx.secret-key:}") String configured) {
    if (configured != null && !configured.isBlank()) {
      return CoreFactory.secretCipher(configured);
    }
    return CoreFactory.secretCipher();
  }

  /**
   * The session factory used by the registry. The bundle resolver reads whatever
   * {@link SecureBundleHolder} put there for the connect currently in flight.
   */
  @Bean
  @Primary
  public SessionFactory connectionSessionFactory(SecureBundleHolder holder) {
    return CoreFactory.sessionFactory(holder.resolver());
  }

  /**
   * ONE registry per process. Two would mean two {@code CqlSession}s per connection and double the
   * connection pools against every cluster.
   */
  @Bean(destroyMethod = "close")
  public ManagedSessionRegistry sessionRegistry(
      SessionFactory sessionFactory,
      @Value("${cassyx.sessions.idle-timeout-seconds:1800}") long idleTimeoutSeconds) {
    LOG.info("Session idle-eviction TTL: {}s", idleTimeoutSeconds);
    return CoreFactory.sessionRegistry(sessionFactory, Duration.ofSeconds(idleTimeoutSeconds));
  }

  /**
   * The read-only view every other workstream injects. Exposed as a separate bean so a feature
   * cannot accidentally take the managed interface and close a session somebody else is querying.
   */
  @Bean
  public SessionRegistry readOnlySessionRegistry(ManagedSessionRegistry registry) {
    return registry;
  }
}
