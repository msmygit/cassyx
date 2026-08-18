package io.cassyx.migrate.api;

import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

/**
 * SPI for import origins: CSV, Excel, MySQL, SQL Server (plan sections 2.1 and 8).
 * {@link ServiceLoader}-discovered by {@link #id()}.
 */
public interface ImportSource {

  /** Stable id, e.g. {@code csv}, {@code excel}, {@code mysql}, {@code sqlserver}. */
  String id();

  /** Human label for the UI picker. */
  String displayName();

  /** Column names in source order, used to build the column-mapping UI. */
  List<String> columns(ImportRequest request) throws IOException;

  /**
   * Streams source rows. Implementations must stream, never materialise the whole source - a
   * dry-run preview just takes the first N (plan section 8).
   */
  ImportCursor open(ImportRequest request) throws IOException;

  /** Streaming cursor over source rows. */
  interface ImportCursor extends Iterator<Map<String, Object>>, AutoCloseable {

    @Override
    void close() throws IOException;
  }

  static ImportSource forId(String id) {
    for (ImportSource source : ServiceLoader.load(ImportSource.class)) {
      if (source.id().equalsIgnoreCase(id)) {
        return source;
      }
    }
    throw new IllegalArgumentException("No ImportSource registered for id '" + id + "'");
  }

  static List<ImportSource> available() {
    List<ImportSource> sources = new java.util.ArrayList<>();
    ServiceLoader.load(ImportSource.class).forEach(sources::add);
    return List.copyOf(sources);
  }
}
