package io.cassyx.api.schema;

import io.cassyx.core.api.schema.DdlExecutor;
import io.cassyx.core.api.schema.DdlGenerator;
import io.cassyx.core.api.schema.RoleReader;
import io.cassyx.core.api.schema.SchemaFactory;
import io.cassyx.core.api.schema.SchemaReader;
import io.cassyx.core.api.schema.TableStatisticsStore;
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

  /**
   * Statistics snapshots produced by the COUNT job (plan section 5.4).
   *
   * <p>Empty until workstream E writes to it, which is precisely the contract's "no statistics
   * computed yet" 404 on the STATISTICS tab. When workstream E supplies a durable store it
   * replaces this bean.
   */
  @Bean
  public TableStatisticsStore tableStatisticsStore() {
    return SchemaFactory.tableStatisticsStore();
  }
}
