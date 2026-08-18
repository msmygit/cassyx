package io.cassyx.core.impl.query;

import static org.assertj.core.api.Assertions.assertThat;

import io.cassyx.core.api.query.CqlScriptSplitter;
import io.cassyx.core.api.query.CqlScriptSplitter.Kind;
import io.cassyx.core.api.query.CqlScriptSplitter.Slice;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The "never {@code split(";")}" tests of plan section 5.1. Every case here is a script that a naive
 * splitter mangles.
 */
class LexingScriptSplitterTest {

  private final CqlScriptSplitter splitter = new LexingScriptSplitter();

  private List<Slice> split(String cql) {
    return splitter.split(cql).statements();
  }

  @Test
  void splitsPlainStatements() {
    assertThat(split("SELECT 1; SELECT 2;"))
        .extracting(Slice::cql)
        .containsExactly("SELECT 1", "SELECT 2");
  }

  @Test
  @DisplayName("split(\";\") would break: string literal containing a semicolon")
  void doesNotSplitInsideStringLiteral() {
    String script = "SELECT * FROM demo.users WHERE email = 'a;b'; SELECT 1;";

    assertThat(split(script))
        .extracting(Slice::cql)
        .containsExactly("SELECT * FROM demo.users WHERE email = 'a;b'", "SELECT 1");
    // The naive alternative, for contrast:
    assertThat(script.split(";")).hasSize(3);
  }

  @Test
  @DisplayName("split(\";\") would break: UDF body containing semicolons")
  void doesNotSplitInsideFunctionBody() {
    String script =
        "CREATE FUNCTION demo.f(a int) RETURNS NULL ON NULL INPUT RETURNS int"
            + " LANGUAGE java AS 'return a + 1;';\nSELECT 2;";

    List<Slice> statements = split(script);

    assertThat(statements).hasSize(2);
    assertThat(statements.get(0).cql()).endsWith("AS 'return a + 1;'");
    assertThat(statements.get(0).kind()).isEqualTo(Kind.DDL);
    assertThat(script.split(";")).hasSize(3);
  }

  @Test
  void doesNotSplitInsideDollarQuotedBody() {
    List<Slice> statements =
        split("CREATE FUNCTION f() ... AS $$ int x = 1; return x; $$; SELECT 2;");

    assertThat(statements).hasSize(2);
    assertThat(statements.get(0).cql()).contains("return x;");
  }

  @Test
  void doesNotSplitInsideCommentsOrQuotedIdentifiers() {
    String script =
        "-- drop it; really\nINSERT INTO \"My;Table\" (a) VALUES ('it''s; fine');\n"
            + "/* block ; comment */ SELECT 1;";

    List<Slice> statements = split(script);

    assertThat(statements).hasSize(2);
    assertThat(statements.get(0).cql()).contains("\"My;Table\"").contains("it''s; fine");
    assertThat(statements.get(1).cql()).isEqualTo("/* block ; comment */ SELECT 1");
  }

  @Test
  @DisplayName("BEGIN BATCH ... APPLY BATCH is ONE statement despite its inner semicolons")
  void keepsBatchTogether() {
    String script =
        "BEGIN BATCH\n"
            + "  INSERT INTO demo.users (id) VALUES (1);\n"
            + "  INSERT INTO demo.users (id) VALUES (2);\n"
            + "APPLY BATCH;\n"
            + "SELECT 1;";

    List<Slice> statements = split(script);

    assertThat(statements).hasSize(2);
    assertThat(statements.get(0).kind()).isEqualTo(Kind.BATCH);
    assertThat(statements.get(0).cql()).startsWith("BEGIN BATCH").endsWith("APPLY BATCH");
    assertThat(statements.get(1).cql()).isEqualTo("SELECT 1");
  }

  @Test
  void classifiesStatementKinds() {
    assertThat(split("SELECT 1").get(0).kind()).isEqualTo(Kind.SELECT);
    assertThat(split("insert into t (a) values (1)").get(0).kind()).isEqualTo(Kind.INSERT);
    assertThat(split("UPDATE t SET a = 1 WHERE b = 2").get(0).kind()).isEqualTo(Kind.UPDATE);
    assertThat(split("DELETE FROM t WHERE b = 2").get(0).kind()).isEqualTo(Kind.DELETE);
    assertThat(split("USE demo").get(0).kind()).isEqualTo(Kind.USE);
    assertThat(split("TRUNCATE demo.users").get(0).kind()).isEqualTo(Kind.TRUNCATE);
    assertThat(split("GRANT SELECT ON demo.users TO r").get(0).kind()).isEqualTo(Kind.GRANT);
    assertThat(split("REVOKE SELECT ON demo.users FROM r").get(0).kind()).isEqualTo(Kind.REVOKE);
    assertThat(split("ALTER TABLE t ADD c int").get(0).kind()).isEqualTo(Kind.DDL);
    assertThat(split("LIST ROLES").get(0).kind()).isEqualTo(Kind.OTHER);
  }

  @Test
  void classificationIgnoresLeadingComments() {
    assertThat(split("/* select this */ INSERT INTO t (a) VALUES (1)").get(0).kind())
        .isEqualTo(Kind.INSERT);
  }

  @Test
  void offsetsMapBackToTheEditorSelection() {
    String script = "SELECT 1;\nSELECT 2;";

    List<Slice> statements = split(script);

    assertThat(script.substring(statements.get(1).startOffset(), statements.get(1).endOffset()))
        .isEqualTo("SELECT 2");
    assertThat(statements.get(1).startLine()).isEqualTo(2);
    assertThat(statements.get(0).contains(3)).isTrue();
    assertThat(statements.get(0).contains(15)).isFalse();
  }

  @Test
  void flagsTheStatementUnderTheCursor() {
    String script = "SELECT 1;\nSELECT 2;";

    List<Slice> statements = splitter.split(script, script.indexOf("SELECT 2") + 3).statements();

    assertThat(statements).filteredOn(Slice::underCursor).extracting(Slice::cql).containsExactly("SELECT 2");
  }

  @Test
  void aCursorInTrailingWhitespaceBelongsToThePrecedingStatement() {
    String script = "SELECT 1;\nSELECT 2;\n\n";

    List<Slice> statements = splitter.split(script, script.length()).statements();

    assertThat(statements).filteredOn(Slice::underCursor).extracting(Slice::cql).containsExactly("SELECT 2");
  }

  @Test
  void surfacesLexicalErrors() {
    CqlScriptSplitter.Result result = splitter.split("SELECT 'unterminated", null);

    assertThat(result.errors()).hasSize(1);
  }

  @Test
  void toleratesBlankAndDegenerateInput() {
    assertThat(split(null)).isEmpty();
    assertThat(split("   \n  ")).isEmpty();
    assertThat(split(";;;")).isEmpty();
    assertThat(split("SELECT 1")).hasSize(1);
    assertThat(splitter.split("", 0).statements()).isEmpty();
  }
}
