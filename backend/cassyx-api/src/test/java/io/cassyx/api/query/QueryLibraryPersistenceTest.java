package io.cassyx.api.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.server.ResponseStatusException;

/** Query history and saved scripts against the real H2 baseline schema. */
@SpringBootTest
@TestPropertySource(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:cassyx-query-library;DB_CLOSE_DELAY=-1",
      "cassyx.license.enforce=false"
    })
class QueryLibraryPersistenceTest {

  @Autowired private QueryHistoryRepository history;
  @Autowired private SavedScriptRepository scripts;
  @Autowired private JdbcTemplate jdbc;

  private QueryLibraryController controller;

  @BeforeEach
  void setUp() {
    jdbc.update("DELETE FROM cassyx_query_history");
    jdbc.update("DELETE FROM cassyx_saved_script");
    jdbc.update("DELETE FROM cassyx_script_folder");
    controller = new QueryLibraryController(history, scripts);
  }

  @Test
  void recordsAndFiltersHistory() {
    history.record(null, "demo", "SELECT * FROM demo.users", "LOCAL_ONE", 34, 10, true, null, null);
    history.record(null, "demo", "INSERT INTO demo.users (id) VALUES (1)", "LOCAL_QUORUM", 9, 0, true, null, null);
    history.record(null, "demo", "SELECT bad", null, 1, 0, false, "syntax error", null);

    assertThat(controller.listQueryHistory(null, null, 50, 0).total()).isEqualTo(3);
    var filtered = controller.listQueryHistory(null, "select", 50, 0);
    assertThat(filtered.items()).hasSize(2);
    assertThat(filtered.items()).anySatisfy(entry -> {
      assertThat(entry.success()).isFalse();
      assertThat(entry.errorMessage()).isEqualTo("syntax error");
    });

    assertThat(controller.listQueryHistory(null, null, 1, 0).items()).hasSize(1);
    assertThat(controller.listQueryHistory(null, null, 1, 2).items()).hasSize(1);
  }

  @Test
  @DisplayName("A history row for an unsaved connection is kept, not dropped on the FK")
  void historySurvivesAnUnknownConnectionId() {
    history.record(
        "00000000-0000-0000-0000-000000000000", null, "SELECT 1", null, 1, 1, true, null, null);

    var page = controller.listQueryHistory(null, null, 50, 0);
    assertThat(page.items()).singleElement().satisfies(
        entry -> assertThat(entry.connectionId()).isNull());
  }

  @Test
  void clearsHistory() {
    history.record(null, null, "SELECT 1", null, 1, 1, true, null, null);
    controller.clearQueryHistory(null);
    assertThat(controller.listQueryHistory(null, null, 50, 0).total()).isZero();

    history.record(null, null, "SELECT 1", null, 1, 1, true, null, null);
    controller.clearQueryHistory("00000000-0000-0000-0000-000000000000");
    assertThat(controller.listQueryHistory(null, null, 50, 0).total()).isEqualTo(1);
  }

  @Test
  void rejectsOutOfRangePagingParameters() {
    assertThatThrownBy(() -> controller.listQueryHistory(null, null, 0, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> controller.listQueryHistory(null, null, 501, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> controller.listQueryHistory(null, null, 50, -1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void createsReadsUpdatesAndDeletesSavedScriptsInFolders() {
    var created =
        controller
            .createSavedScript(
                new QueryDtos.SavedScriptRequest(
                    "Daily signups", "SELECT count(*) FROM demo.users", "/reports", true, null, "desc"))
            .getBody();

    assertThat(created).isNotNull();
    assertThat(created.folder()).isEqualTo("/reports");
    assertThat(created.favourite()).isTrue();
    assertThat(created.description()).isEqualTo("desc");

    assertThat(controller.listSavedScripts("/reports")).hasSize(1);
    assertThat(controller.listSavedScripts("/nowhere")).isEmpty();
    assertThat(controller.listSavedScripts(null)).hasSize(1);
    assertThat(controller.getSavedScript(created.id()).name()).isEqualTo("Daily signups");

    var updated =
        controller.updateSavedScript(
            created.id(),
            new QueryDtos.SavedScriptRequest("Renamed", "SELECT 1", "/reports", false, null, null));
    assertThat(updated.name()).isEqualTo("Renamed");
    assertThat(updated.favourite()).isFalse();

    // The folder row is reused rather than duplicated.
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cassyx_script_folder", Integer.class))
        .isEqualTo(1);

    assertThat(controller.deleteSavedScript(created.id()).getStatusCode().value()).isEqualTo(204);
    assertThat(controller.listSavedScripts(null)).isEmpty();
  }

  @Test
  void unknownScriptsAre404() {
    assertThatThrownBy(() -> controller.getSavedScript("nope"))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(
            () ->
                controller.updateSavedScript(
                    "nope", new QueryDtos.SavedScriptRequest("n", "SELECT 1", null, null, null, null)))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> controller.deleteSavedScript("nope"))
        .isInstanceOf(ResponseStatusException.class);
  }
}
