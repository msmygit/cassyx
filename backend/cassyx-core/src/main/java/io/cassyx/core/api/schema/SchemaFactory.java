package io.cassyx.core.api.schema;

import io.cassyx.core.api.CqlStatementSplitter;
import io.cassyx.core.impl.schema.CqlDdlGenerator;
import io.cassyx.core.impl.schema.InMemoryTableStatisticsStore;
import io.cassyx.core.impl.schema.MetadataSchemaReader;
import io.cassyx.core.impl.schema.SessionDdlExecutor;
import io.cassyx.core.impl.schema.SystemAuthRoleReader;

/**
 * Composition entry point for the schema and DDL surface (plan section 4).
 *
 * <p>Usable with nothing but a {@code CqlSession} - no Spring, no web layer (plan section 2.1):
 *
 * <pre>{@code
 * SchemaReader reader = SchemaFactory.reader();
 * DdlGenerator ddl = SchemaFactory.ddlGenerator();
 * DdlPreview preview = ddl.createTable("demo", tableDefinition);
 * System.out.println(preview.cql());          // ALWAYS show this before executing
 * SchemaFactory.ddlExecutor().execute(session, preview, true);
 * }</pre>
 */
public final class SchemaFactory {

  private SchemaFactory() {}

  public static SchemaReader reader() {
    return new MetadataSchemaReader();
  }

  public static DdlGenerator ddlGenerator() {
    return new CqlDdlGenerator();
  }

  public static DdlExecutor ddlExecutor() {
    return new SessionDdlExecutor();
  }

  /** @param splitter the CQL lexer used to carve a reviewed script into statements */
  public static DdlExecutor ddlExecutor(CqlStatementSplitter splitter) {
    return new SessionDdlExecutor(splitter);
  }

  public static RoleReader roleReader() {
    return new SystemAuthRoleReader();
  }

  /** In-memory store; workstream E replaces it with the job-backed implementation. */
  public static TableStatisticsStore tableStatisticsStore() {
    return new InMemoryTableStatisticsStore();
  }
}
