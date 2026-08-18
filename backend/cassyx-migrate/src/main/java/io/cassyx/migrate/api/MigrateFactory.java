package io.cassyx.migrate.api;

import java.util.List;

/**
 * Composition entry point of cassyx-migrate (plan section 2.1).
 *
 * <pre>{@code
 * ImportSource csv = MigrateFactory.importSource("csv");
 * ImportRequest request = ImportRequest.of("csv", "/data/people.csv");
 * try (ImportSource.ImportCursor cursor = csv.open(request)) {
 *   while (cursor.hasNext()) {
 *     System.out.println(cursor.next());
 *   }
 * }
 * }</pre>
 */
public final class MigrateFactory {

  private MigrateFactory() {}

  public static ImportSource importSource(String id) {
    return ImportSource.forId(id);
  }

  public static List<ImportSource> importSources() {
    return ImportSource.available();
  }
}
