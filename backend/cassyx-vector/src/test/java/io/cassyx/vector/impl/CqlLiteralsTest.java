package io.cassyx.vector.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.cassyx.vector.api.VectorException;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The escaping layer under every generated statement.
 *
 * <p>A GUI that lets a user name a column {@code "my col"} and then pastes that name into CQL
 * unquoted produces either a syntax error or, worse, a statement that means something else.
 */
class CqlLiteralsTest {

  @Test
  void leavesPlainIdentifiersUnquotedAndQuotesTheRest() {
    assertThat(CqlLiterals.identifier("embedding")).isEqualTo("embedding");
    assertThat(CqlLiterals.identifier("doc_id_2")).isEqualTo("doc_id_2");
    assertThat(CqlLiterals.identifier("UserEvents")).isEqualTo("\"UserEvents\"");
    assertThat(CqlLiterals.identifier("2fast")).isEqualTo("\"2fast\"");
    assertThat(CqlLiterals.identifier(" spaced name ")).isEqualTo("\"spaced name\"");
    assertThat(CqlLiterals.qualified("demo", "Docs")).isEqualTo("demo.\"Docs\"");
  }

  @Test
  void refusesIdentifiersThatCannotSurviveQuoting() {
    assertThatThrownBy(() -> CqlLiterals.identifier("bad\"name"))
        .isInstanceOf(VectorException.class)
        .hasMessageContaining("Illegal CQL identifier");
    assertThatThrownBy(() -> CqlLiterals.identifier("drop\ntable"))
        .isInstanceOf(VectorException.class);
    assertThatThrownBy(() -> CqlLiterals.identifier(null)).isInstanceOf(VectorException.class);
    assertThatThrownBy(() -> CqlLiterals.identifier("  ")).isInstanceOf(VectorException.class);
  }

  @Test
  void understandsCollectionIndexTargets() {
    assertThat(CqlLiterals.indexTarget("tags")).isEqualTo("tags");
    assertThat(CqlLiterals.indexTarget("values(tags)")).isEqualTo("values(tags)");
    assertThat(CqlLiterals.indexTarget("KEYS( Attrs )")).isEqualTo("keys(\"Attrs\")");
    assertThat(CqlLiterals.indexTarget("entries(meta)")).isEqualTo("entries(meta)");
    assertThat(CqlLiterals.indexTarget("full(frozen_list)")).isEqualTo("full(frozen_list)");
    assertThatThrownBy(() -> CqlLiterals.indexTarget(" "))
        .isInstanceOf(VectorException.class);
  }

  @Test
  void escapesStringLiterals() {
    assertThat(CqlLiterals.stringLiteral("it's")).isEqualTo("'it''s'");
    assertThat(CqlLiterals.literal("electronics'; DROP TABLE demo.docs --"))
        .isEqualTo("'electronics''; DROP TABLE demo.docs --'");
  }

  @Test
  void rendersEveryPredicateValueType() {
    UUID id = UUID.fromString("7c1a2b3d-4e5f-4061-8273-849506172839");

    assertThat(CqlLiterals.literal(null)).isEqualTo("NULL");
    assertThat(CqlLiterals.literal(true)).isEqualTo("true");
    assertThat(CqlLiterals.literal(42)).isEqualTo("42");
    assertThat(CqlLiterals.literal(1.5d)).isEqualTo("1.5");
    assertThat(CqlLiterals.literal(id)).isEqualTo("'" + id + "'");
    assertThat(CqlLiterals.literal(Instant.parse("2026-08-17T11:00:00Z")))
        .isEqualTo("'2026-08-17T11:00:00Z'");
    assertThat(CqlLiterals.literal(LocalDate.parse("2026-08-17"))).isEqualTo("'2026-08-17'");
    assertThat(CqlLiterals.literal(LocalTime.parse("11:00"))).isEqualTo("'11:00'");
    assertThat(CqlLiterals.literal(List.of("a", "b"))).isEqualTo("{'a', 'b'}");

    Map<String, Object> map = new LinkedHashMap<>();
    map.put("k", 1);
    assertThat(CqlLiterals.literal(map)).isEqualTo("{'k': 1}");
  }

  @Test
  void rendersInetWithoutTheJavaHostnamePrefix() throws Exception {
    // InetAddress.toString() is "hostname/1.2.3.4"; getHostAddress() is what CQL wants. This
    // asserts we do not accidentally emit the slash form.
    assertThat(CqlLiterals.literal(InetAddress.getByName("127.0.0.1"))).isEqualTo("'127.0.0.1'");
  }

  @Test
  void rejectsBlobPredicatesRatherThanGuessing() {
    assertThatThrownBy(() -> CqlLiterals.literal(ByteBuffer.allocate(4)))
        .isInstanceOf(VectorException.class)
        .hasMessageContaining("Blob");
  }

  @Test
  void inListIsParenthesisedNotASetLiteral() {
    assertThat(CqlLiterals.inList(List.of("a", "b"))).isEqualTo("('a', 'b')");
    assertThatThrownBy(() -> CqlLiterals.inList(List.of()))
        .isInstanceOf(VectorException.class);
    assertThatThrownBy(() -> CqlLiterals.inList(null)).isInstanceOf(VectorException.class);
  }
}
