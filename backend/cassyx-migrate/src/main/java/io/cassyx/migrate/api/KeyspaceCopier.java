package io.cassyx.migrate.api;

import com.datastax.oss.driver.api.core.CqlSession;
import java.util.List;
import java.util.Map;

/**
 * Streaming cluster-to-cluster keyspace copy (plan section 8). Never buffers to disk.
 *
 * @see #copy(CqlSession, CqlSession, String, String, List, Map)
 */
public interface KeyspaceCopier {

  /**
   * @param replicationRemap source DC name to target DC name; source DC names rarely match
   */
  CopyReport copy(
      CqlSession source,
      CqlSession target,
      String sourceKeyspace,
      String targetKeyspace,
      List<String> tables,
      Map<String, String> replicationRemap);

  record CopyReport(long rowsCopied, List<String> tablesCopied, List<String> warnings) {

    public CopyReport {
      tablesCopied = tablesCopied == null ? List.of() : List.copyOf(tablesCopied);
      warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
  }
}
