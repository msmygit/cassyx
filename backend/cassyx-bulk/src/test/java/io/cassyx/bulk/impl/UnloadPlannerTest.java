package io.cassyx.bulk.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The generated CQL: quoting, and the bound asymmetry that decides whether rows get duplicated. */
class UnloadPlannerTest {

  @Test
  @DisplayName("token bounds are start-exclusive and end-inclusive")
  void tokenRangeQueryUsesExclusiveStartAndInclusiveEnd() {
    String cql =
        UnloadPlanner.tokenRangeQuery("demo", "users", List.of("id", "email"), List.of("id"));

    // > and <= must not both become >= or <=. Two inclusive bounds duplicate every boundary
    // partition across adjacent splits; two exclusive bounds drop it. Both are silent.
    assertThat(cql)
        .isEqualTo(
            "SELECT id, email FROM demo.users WHERE token(id) > ? AND token(id) <= ?");
  }

  @Test
  @DisplayName("composite partition keys produce a single multi-column token() call")
  void tokenRangeQuerySupportsCompositePartitionKeys() {
    String cql =
        UnloadPlanner.tokenRangeQuery(
            "demo", "events", List.of("a"), List.of("tenant", "bucket"));

    assertThat(cql)
        .contains("token(tenant, bucket) > ?")
        .contains("token(tenant, bucket) <= ?");
  }

  @Test
  void tokenRangeQueryRejectsATableWithoutAPartitionKey() {
    assertThatThrownBy(() -> UnloadPlanner.tokenRangeQuery("demo", "users", List.of(), List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("no partition key");
  }

  @Test
  @DisplayName("case-sensitive identifiers keep their case and their quotes")
  void quotesCaseSensitiveIdentifiers() {
    assertThat(UnloadPlanner.quote("users")).isEqualTo("users");
    assertThat(UnloadPlanner.quote("UserEvents")).isEqualTo("\"UserEvents\"");
    assertThat(UnloadPlanner.qualify("demo", "UserEvents")).isEqualTo("demo.\"UserEvents\"");
    assertThat(UnloadPlanner.quote("select")).isEqualTo("\"select\"");
  }

  @Test
  void fullScanQueryProjectsStarWhenNoColumnsAreRequested() {
    assertThat(UnloadPlanner.fullScanQuery("demo", "users", List.of()))
        .isEqualTo("SELECT * FROM demo.users");
    assertThat(UnloadPlanner.fullScanQuery("demo", "users", null))
        .isEqualTo("SELECT * FROM demo.users");
  }

  @Test
  @DisplayName("an empty projection means every column, in schema order")
  void resolveColumnsDefaultsToTheWholeTable() {
    List<String> table = List.of("id", "email", "created_at");
    assertThat(UnloadPlanner.resolveColumns(table, List.of())).isEqualTo(table);
    assertThat(UnloadPlanner.resolveColumns(table, null)).isEqualTo(table);
  }

  @Test
  void resolveColumnsPreservesTheRequestedOrder() {
    assertThat(UnloadPlanner.resolveColumns(List.of("id", "email"), List.of("email", "id")))
        .containsExactly("email", "id");
  }

  @Test
  @DisplayName("an unknown column fails fast rather than exporting a column of nulls")
  void resolveColumnsRejectsUnknownColumns() {
    assertThatThrownBy(() -> UnloadPlanner.resolveColumns(List.of("id"), List.of("nope")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("nope");
  }

  @Test
  void splitsForFallsBackToOneSplitPerRange() {
    assertThat(UnloadPlanner.splitsFor(10_000, 16)).isEqualTo(10_000);
    assertThat(UnloadPlanner.splitsFor(0, 16)).isEqualTo(16);
    assertThat(UnloadPlanner.splitsFor(0, 0)).isEqualTo(1);
  }
}
