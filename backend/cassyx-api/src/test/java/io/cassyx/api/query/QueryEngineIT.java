package io.cassyx.api.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.datastax.oss.driver.api.core.CqlSession;
import io.cassyx.core.api.query.BatchSpec;
import io.cassyx.core.api.query.EditabilityVerdict;
import io.cassyx.core.api.query.IncompletePrimaryKeyException;
import io.cassyx.core.api.query.QueryFactory;
import io.cassyx.core.api.query.QueryService;
import io.cassyx.core.api.query.QuerySpec;
import io.cassyx.core.api.query.ResultPage;
import io.cassyx.core.api.query.RowDeleteSpec;
import io.cassyx.core.api.query.RowInsertSpec;
import io.cassyx.core.api.query.RowMutationOutcome;
import io.cassyx.core.api.query.RowMutationService;
import io.cassyx.core.api.query.RowUpdateSpec;
import io.cassyx.core.api.query.StatementGenerationSpec;
import io.cassyx.core.testsupport.IntegrationTestBase;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The query engine against real Cassandra 5.x (the shared Testcontainers singleton, plan §11.2).
 *
 * <p>The headline test is {@link #pagesThroughEveryRowExactlyOnce()}: page a table larger than any
 * single page and assert every row is seen exactly once. Gaps and duplicates are the failure modes
 * of a paging implementation that mixes up its cursors, and they are silent — the result simply has
 * the wrong rows in it.
 */
class QueryEngineIT extends IntegrationTestBase {

  private static final String KEYSPACE = "cassyx_query_it";
  private static final int TOTAL_ROWS = 10_500;

  private static final QueryService QUERIES = QueryFactory.queryService();
  private static final RowMutationService ROWS = QueryFactory.rowMutationService();

  @BeforeAll
  static void seed() {
    if (!Boolean.getBoolean(ENABLED_PROPERTY)) {
      return;
    }
    CqlSession session = session();
    ensureKeyspace(KEYSPACE);
    session.execute(
        "CREATE TABLE IF NOT EXISTS " + KEYSPACE + ".paged (bucket int, seq int, payload text,"
            + " PRIMARY KEY (bucket, seq))");
    session.execute(
        "CREATE TABLE IF NOT EXISTS " + KEYSPACE + ".users (user_id uuid, created_at timestamp,"
            + " email text, logins bigint, PRIMARY KEY (user_id, created_at))");
    session.execute("TRUNCATE " + KEYSPACE + ".paged");

    var insert = session.prepare("INSERT INTO " + KEYSPACE + ".paged (bucket, seq, payload) VALUES (?,?,?)");
    for (int i = 0; i < TOTAL_ROWS; i++) {
      // Four partitions so paging crosses partition boundaries, which is where a naive cursor
      // implementation goes wrong.
      session.execute(insert.bind(i % 4, i, "row-" + i));
    }
  }

  @Test
  @DisplayName("Paging a >10k-row table yields every row exactly once - no gaps, no duplicates")
  void pagesThroughEveryRowExactlyOnce() {
    ResultPage page =
        QUERIES.execute(
            session(),
            new QuerySpec(
                "SELECT bucket, seq, payload FROM " + KEYSPACE + ".paged",
                null, null, null, "LOCAL_ONE", null, 500, null, false, true, null));

    Set<Integer> seen = new HashSet<>();
    List<Integer> duplicates = new ArrayList<>();
    int pages = 0;

    while (true) {
      pages++;
      for (Map<String, Object> row : page.rows()) {
        Integer seq = (Integer) row.get("seq");
        if (!seen.add(seq)) {
          duplicates.add(seq);
        }
      }
      if (page.nextPageToken() == null) {
        break;
      }
      page = QUERIES.nextPage(page.resultHandle(), page.nextPageToken(), null);
    }

    assertThat(duplicates).as("rows returned more than once").isEmpty();
    assertThat(seen).as("every row seen exactly once").hasSize(TOTAL_ROWS);
    for (int i = 0; i < TOTAL_ROWS; i++) {
      assertThat(seen).as("row %s must not be skipped", i).contains(i);
    }
    assertThat(pages).isGreaterThan(TOTAL_ROWS / 500);
  }

  @Test
  @DisplayName("previous-page replays a retained token and returns the SAME rows as the first visit")
  void previousPageReturnsTheSameRows() {
    ResultPage first =
        QUERIES.execute(session(), new QuerySpec(
            "SELECT bucket, seq FROM " + KEYSPACE + ".paged",
            null, null, null, null, null, 200, null, false, true, null));

    ResultPage second = QUERIES.nextPage(first.resultHandle(), first.nextPageToken(), null);
    List<Object> secondSeqs = second.rows().stream().map(row -> row.get("seq")).toList();
    ResultPage third = QUERIES.nextPage(second.resultHandle(), second.nextPageToken(), null);

    ResultPage backToSecond =
        QUERIES.previousPage(third.resultHandle(), third.previousPageToken(), null);

    assertThat(backToSecond.pageNumber()).isEqualTo(2);
    assertThat(backToSecond.rows().stream().map(row -> row.get("seq")).toList())
        .isEqualTo(secondSeqs);

    ResultPage backToFirst =
        QUERIES.previousPage(backToSecond.resultHandle(), backToSecond.previousPageToken(), null);
    assertThat(backToFirst.pageNumber()).isEqualTo(1);
    assertThat(backToFirst.previousPageToken()).isNull();
    assertThat(backToFirst.rows().stream().map(row -> row.get("seq")).toList())
        .isEqualTo(first.rows().stream().map(row -> row.get("seq")).toList());
  }

  @Test
  void reportsResultSetStateAndReleasesTheHandle() {
    ResultPage page =
        QUERIES.execute(session(), QuerySpec.of("SELECT bucket, seq FROM " + KEYSPACE + ".paged"));

    assertThat(QUERIES.resultSetInfo(page.resultHandle()).hasMorePages()).isTrue();
    QUERIES.closeResultSet(page.resultHandle());

    assertThatThrownBy(() -> QUERIES.resultSetInfo(page.resultHandle()))
        .isInstanceOf(io.cassyx.core.api.query.ResultHandleExpiredException.class);
  }

  @Test
  @DisplayName("A bigint leaves the server as a STRING, because JSON numbers cannot hold one")
  void bigintsTransportAsStrings() {
    UUID id = UUID.randomUUID();
    session()
        .execute(
            "INSERT INTO " + KEYSPACE + ".users (user_id, created_at, logins)"
                + " VALUES (" + id + ", '2026-08-17T10:00:00Z', 9007199254740993)");

    ResultPage page =
        QUERIES.execute(
            session(),
            QuerySpec.of("SELECT logins FROM " + KEYSPACE + ".users WHERE user_id = " + id));

    assertThat(page.rows()).singleElement().satisfies(
        row -> assertThat(row.get("logins")).isEqualTo("9007199254740993"));
  }

  @Test
  @DisplayName("[applied] is surfaced distinctly from the row data")
  void surfacesLwtApplied() {
    UUID id = UUID.randomUUID();
    String cql =
        "INSERT INTO " + KEYSPACE + ".users (user_id, created_at, email)"
            + " VALUES (" + id + ", '2026-08-17T10:00:00Z', 'a@b.c') IF NOT EXISTS";

    assertThat(QUERIES.execute(session(), QuerySpec.of(cql)).applied()).isTrue();
    assertThat(QUERIES.execute(session(), QuerySpec.of(cql)).applied()).isFalse();
  }

  @Test
  void tracingProducesAFullSystemTracesTimeline() {
    ResultPage page =
        QUERIES.execute(
            session(),
            new QuerySpec(
                "SELECT bucket, seq FROM " + KEYSPACE + ".paged LIMIT 1",
                null, null, null, null, null, 10, null, true, true, null));

    assertThat(page.tracingId()).isNotNull();
    assertThat(QUERIES.trace(page.queryId()))
        .hasValueSatisfying(
            trace -> {
              assertThat(trace.requestType()).isNotBlank();
              assertThat(trace.events()).isNotEmpty();
              assertThat(trace.events().get(0).activity()).isNotBlank();
            });
  }

  @Test
  void batchesReportWhetherTheySpanPartitions() {
    String cql = "INSERT INTO " + KEYSPACE + ".paged (bucket, seq, payload) VALUES (?,?,?)";

    var singlePartition =
        QUERIES.executeBatch(
            session(),
            new BatchSpec(
                BatchSpec.Kind.UNLOGGED,
                List.of(
                    new BatchSpec.Statement(cql, List.of(9, 900001, "a"), null),
                    new BatchSpec.Statement(cql, List.of(9, 900002, "b"), null)),
                null, null, null, null, false));
    assertThat(singlePartition.executed()).isTrue();
    assertThat(singlePartition.spansMultiplePartitions()).isFalse();
    assertThat(singlePartition.distinctPartitions()).isEqualTo(1);

    var multiPartition =
        QUERIES.executeBatch(
            session(),
            new BatchSpec(
                BatchSpec.Kind.LOGGED,
                List.of(
                    new BatchSpec.Statement(cql, List.of(10, 900003, "a"), null),
                    new BatchSpec.Statement(cql, List.of(11, 900004, "b"), null)),
                null, null, null, null, true));
    assertThat(multiPartition.executed()).isFalse();
    assertThat(multiPartition.spansMultiplePartitions()).isTrue();
    assertThat(multiPartition.distinctPartitions()).isEqualTo(2);
    assertThat(multiPartition.assembledCql()).startsWith("BEGIN BATCH").endsWith("APPLY BATCH;");
  }

  /* ---------------------------------------------------------------------------- row CRUD */

  @Test
  @DisplayName("A result set missing a key column is refused for editing, naming the column")
  void editabilityFollowsTheProjectedPrimaryKey() {
    EditabilityVerdict partial =
        ROWS.editability(session(), KEYSPACE, "users", List.of("user_id", "email"));
    assertThat(partial.editable()).isFalse();
    assertThat(partial.missingKeyColumns()).containsExactly("created_at");
    assertThat(partial.reason()).contains("created_at");

    assertThat(ROWS.editability(session(), KEYSPACE, "users", List.of("user_id", "created_at")).editable())
        .isTrue();
  }

  @Test
  void insertsUpdatesAndDeletesARowWithTtlAndTimestamp() {
    UUID id = UUID.randomUUID();
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("user_id", id.toString());
    values.put("created_at", "2026-08-17T10:00:00Z");
    values.put("email", "ops@example.com");
    values.put("logins", "9007199254740993");

    RowMutationOutcome inserted =
        ROWS.insert(session(), KEYSPACE, "users",
            new RowInsertSpec(values, 3600, null, false, "LOCAL_QUORUM", null, false));
    assertThat(inserted.executed()).isTrue();
    assertThat(inserted.cql()).contains("USING TTL 3600");

    Map<String, Object> key = new LinkedHashMap<>();
    key.put("user_id", id.toString());
    key.put("created_at", "2026-08-17T10:00:00Z");

    RowMutationOutcome updated =
        ROWS.update(session(), KEYSPACE, "users",
            new RowUpdateSpec(key, Map.of("email", "new@example.com"), null, null, null, false,
                null, null, false));
    assertThat(updated.executed()).isTrue();

    ResultPage after =
        QUERIES.execute(session(), QuerySpec.of(
            "SELECT email, logins FROM " + KEYSPACE + ".users WHERE user_id = " + id));
    assertThat(after.rows()).singleElement().satisfies(row -> {
      assertThat(row.get("email")).isEqualTo("new@example.com");
      assertThat(row.get("logins")).isEqualTo("9007199254740993");
    });

    RowMutationOutcome deleted =
        ROWS.delete(session(), KEYSPACE, "users",
            new RowDeleteSpec(key, List.of(), null, false, null, null, false));
    assertThat(deleted.executed()).isTrue();
    assertThat(QUERIES.execute(session(), QuerySpec.of(
        "SELECT email FROM " + KEYSPACE + ".users WHERE user_id = " + id)).rows()).isEmpty();
  }

  @Test
  void refusesAMutationWithoutTheCompletePrimaryKey() {
    assertThatThrownBy(
            () ->
                ROWS.update(
                    session(),
                    KEYSPACE,
                    "users",
                    new RowUpdateSpec(
                        Map.of("user_id", UUID.randomUUID().toString()),
                        Map.of("email", "a@b.c"),
                        null, null, null, false, null, null, false)))
        .isInstanceOf(IncompletePrimaryKeyException.class)
        .hasMessageContaining("created_at");
  }

  @Test
  void generatedStatementsExecuteAsWritten() {
    UUID id = UUID.randomUUID();
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("user_id", id.toString());
    row.put("created_at", "2026-08-17T11:00:00Z");
    row.put("email", "gen@example.com");

    var generated =
        ROWS.generate(session(), KEYSPACE, "users",
            new StatementGenerationSpec(
                StatementGenerationSpec.Kind.INSERT, List.of(row), List.of(), null, null,
                false, false, true));

    assertThat(generated.statements()).hasSize(1);
    session().execute(generated.statements().get(0));

    assertThat(QUERIES.execute(session(), QuerySpec.of(
        "SELECT email FROM " + KEYSPACE + ".users WHERE user_id = " + id)).rows())
        .singleElement()
        .satisfies(r -> assertThat(r.get("email")).isEqualTo("gen@example.com"));
  }
}
