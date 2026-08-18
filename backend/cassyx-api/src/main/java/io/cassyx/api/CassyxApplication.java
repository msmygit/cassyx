package io.cassyx.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.cassandra.CassandraAutoConfiguration;

/**
 * cassyx backend entry point.
 *
 * <p>This module is the ONLY one allowed to depend on Spring (plan section 2.1): every lower module
 * is plain Java with constructor injection, and this module supplies the {@code @Bean} wiring. The
 * rule is enforced by {@code ModularityArchitectureTest}, which fails the build on any
 * {@code org.springframework} import below {@code cassyx-api}.
 */
// Spring Boot's Cassandra auto-configuration would build ONE global CqlSession against
// localhost:9042 at startup, which is both wrong for a multi-connection tool and a startup
// failure when no cluster is there. cassyx owns session lifecycle itself: a SessionRegistry keyed
// by (userId, connectionId) with idle eviction (plan section 3).
@SpringBootApplication(exclude = CassandraAutoConfiguration.class)
public class CassyxApplication {

  public static void main(String[] args) {
    SpringApplication.run(CassyxApplication.class, args);
  }
}
