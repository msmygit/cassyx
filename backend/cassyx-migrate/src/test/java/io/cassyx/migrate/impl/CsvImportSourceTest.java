package io.cassyx.migrate.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.cassyx.migrate.api.ImportRequest;
import io.cassyx.migrate.api.ImportSource;
import io.cassyx.migrate.api.MigrateFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CsvImportSourceTest {

  @TempDir Path tmp;

  private Path csv() throws IOException {
    Path file = tmp.resolve("people.csv");
    Files.writeString(file, "id,name\n1,\"Ada, L\"\n2,\"say \"\"hi\"\"\"\n3,Grace\n");
    return file;
  }

  @Test
  void isDiscoveredViaServiceLoader() {
    assertThat(MigrateFactory.importSource("CSV")).isInstanceOf(CsvImportSource.class);
    assertThat(MigrateFactory.importSources()).extracting(ImportSource::id).contains("csv");
    assertThatThrownBy(() -> MigrateFactory.importSource("mysql"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void readsHeaderAndStreamsRows() throws Exception {
    ImportRequest request = ImportRequest.of("csv", csv().toString());
    ImportSource source = MigrateFactory.importSource("csv");

    assertThat(source.columns(request)).containsExactly("id", "name");

    List<Map<String, Object>> rows = new ArrayList<>();
    try (ImportSource.ImportCursor cursor = source.open(request)) {
      cursor.forEachRemaining(rows::add);
    }

    assertThat(rows).hasSize(3);
    assertThat(rows.get(0)).containsEntry("name", "Ada, L");
    assertThat(rows.get(1)).containsEntry("name", "say \"hi\"");
  }

  @Test
  void dryRunStopsAfterNRows() throws Exception {
    ImportRequest request =
        new ImportRequest("csv", csv().toString(), "ks", "t", Map.of(), Map.of(), 2);
    assertThat(request.isDryRun()).isTrue();

    List<Map<String, Object>> rows = new ArrayList<>();
    try (ImportSource.ImportCursor cursor = MigrateFactory.importSource("csv").open(request)) {
      cursor.forEachRemaining(rows::add);
    }

    assertThat(rows).hasSize(2);
  }
}
