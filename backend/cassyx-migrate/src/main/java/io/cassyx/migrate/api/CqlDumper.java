package io.cassyx.migrate.api;

import com.datastax.oss.driver.api.core.CqlSession;
import java.nio.file.Path;
import java.util.List;

/** Keyspace backup: schema DDL plus data, to one file or a directory tree (plan section 8). */
public interface CqlDumper {

  /**
   * @param mode schema only, data only, or both
   * @param tables table selection; empty means every table in the keyspace
   */
  Path dump(CqlSession session, String keyspace, List<String> tables, DumpMode mode, Path target);

  /** Replays a dump with progress. */
  void restore(CqlSession session, Path dump, String targetKeyspace);

  enum DumpMode {
    SCHEMA_ONLY,
    DATA_ONLY,
    SCHEMA_AND_DATA
  }
}
