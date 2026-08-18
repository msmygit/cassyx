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
   * The AES-256-GCM cipher for everything stored at rest, keyed from {@code CASSYX_SECRET_KEY}
   * (Spring's relaxed binding also accepts {@code cassyx.secret-key}).
   *
   * <p>With no key configured the app still boots - health, licensing and every feature that does
   * not touch a credential keep working - but {@link UnconfiguredSecretCipher} refuses to encrypt
   * or decrypt anything, with the command that fixes it. What it never does is fall back to
   * plaintext storage of cluster passwords and Astra tokens.
   */
  @Bean
  public SecretCipher secretCipher(@Value("${cassyx.secret-key:}") String configured) {
    if (configured != null && !configured.isBlank()) {
      return CoreFactory.secretCipher(configured);
    }
    LOG.warn(UnconfiguredSecretCipher.MESSAGE);
    return new UnconfiguredSecretCipher();
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
   * connection pools against every cluster - which is also why there is deliberately no second,
   * read-only {@link SessionRegistry} bean: it would be the same object under two names and make
   * every injection point ambiguous.
   *
   * <p>The read-only/managed split is enforced at the type level instead. A feature that declares a
   * {@link SessionRegistry} dependency gets this bean but cannot see {@code open} or {@code close},
   * so it cannot shut down a session another feature is mid-query on.
   */
  @Bean(destroyMethod = "close")
  public ManagedSessionRegistry sessionRegistry(
      SessionFactory sessionFactory,
      @Value("${cassyx.sessions.idle-timeout-seconds:1800}") long idleTimeoutSeconds) {
    LOG.info("Session idle-eviction TTL: {}s", idleTimeoutSeconds);
    return CoreFactory.sessionRegistry(sessionFactory, Duration.ofSeconds(idleTimeoutSeconds));
  }
}
