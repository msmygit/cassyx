package io.cassyx.core.api;

import com.datastax.oss.driver.api.core.CqlSession;
import java.util.List;

/**
 * Read-only view over the driver's live schema metadata. Never polls {@code system_schema} - the
 * driver maintains an event-driven cache (plan section 4).
 */
public interface SchemaCatalog {

  List<KeyspaceSummary> keyspaces(CqlSession session, boolean includeSystem);

  List<TableSummary> tables(CqlSession session, String keyspace);
}
