package io.cassyx.api.schema;

import io.cassyx.core.api.schema.DdlExecutor;
import io.cassyx.core.api.schema.DdlGenerator;
import io.cassyx.core.api.schema.RoleReader;
import io.cassyx.core.api.schema.SchemaFactory;
import io.cassyx.core.api.schema.SchemaReader;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wiring for the schema and DDL surface (plan section 4).
 *
 * <p>Every bean comes from {@code SchemaFactory}, never from an {@code io.cassyx.core.impl}
 * package - the modularity contract of plan section 2.1, ArchUnit-enforced. Keeping this
 * configuration inside the schema package rather than in the shared composition root means the
 * workstream owns its own wiring and cannot collide with another workstream's edits.
 */
@Configuration(proxyBeanMethods = false)
public class SchemaConfiguration {

  @Bean
  public SchemaReader schemaReader() {
    return SchemaFactory.reader();
  }

  @Bean
  public DdlGenerator ddlGenerator() {
    return SchemaFactory.ddlGenerator();
  }

  @Bean
  public DdlExecutor ddlExecutor() {
    return SchemaFactory.ddlExecutor();
  }

  @Bean
  public RoleReader roleReader() {
    return SchemaFactory.roleReader();
  }

  /*
   * There is deliberately no TableStatisticsStore bean here any more.
   *
   * The in-memory one this configuration used to publish was never written to by anything, so the
   * STATISTICS tab 404'd forever; and even once written to it would have lost every snapshot on
   * restart while the job row that produced it survived. The durable, job-row-backed store in
   * io.cassyx.api.bulk.JobRowTableStatisticsStore is the single bean now. Publishing a second one
   * here would make the injection ambiguous and fail the context at start-up - which is the
   * intended outcome if someone reintroduces the placeholder.
   */
}
