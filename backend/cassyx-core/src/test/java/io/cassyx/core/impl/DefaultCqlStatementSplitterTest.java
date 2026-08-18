package io.cassyx.core.impl;

import static org.assertj.core.api.Assertions.assertThat;

import io.cassyx.core.api.CqlStatement;
import io.cassyx.core.api.CqlStatementSplitter;
import java.util.List;
import org.junit.jupiter.api.Test;

class DefaultCqlStatementSplitterTest {

  private final CqlStatementSplitter splitter = new DefaultCqlStatementSplitter();

  @Test
  void splitsPlainStatements() {
    List<CqlStatement> statements = splitter.split("SELECT 1; SELECT 2;");

    assertThat(statements).extracting(CqlStatement::text).containsExactly("SELECT 1", "SELECT 2");
  }

  @Test
  void doesNotSplitInsideStringLiterals() {
    List<CqlStatement> statements =
        splitter.split("INSERT INTO t (a) VALUES ('a;b'); SELECT * FROM t;");

    assertThat(statements).hasSize(2);
    assertThat(statements.get(0).text()).isEqualTo("INSERT INTO t (a) VALUES ('a;b')");
  }

  @Test
  void handlesEscapedQuotesQuotedIdentifiersAndComments() {
    String script =
        "-- drop it; really\n"
            + "INSERT INTO \"My;Table\" (a) VALUES ('it''s; fine');\n"
            + "/* block ; comment */ SELECT 1;";

    List<CqlStatement> statements = splitter.split(script);

    assertThat(statements).hasSize(2);
    assertThat(statements.get(0).text()).contains("it''s; fine");
    // Comments stay attached to the statement they precede: the editor highlights exactly what
    // it executes, and the server ignores them.
    assertThat(statements.get(1).text()).isEqualTo("/* block ; comment */ SELECT 1");
  }

  @Test
  void doesNotSplitInsideDollarQuotedFunctionBody() {
    String script =
        "CREATE FUNCTION f(i int) RETURNS int LANGUAGE java AS $$ int x = 1; return x; $$;"
            + " SELECT 2;";

    List<CqlStatement> statements = splitter.split(script);

    assertThat(statements).hasSize(2);
    assertThat(statements.get(0).text()).contains("return x;");
  }

  @Test
  void reportsOffsetsSoEditorCanFindStatementUnderCursor() {
    String script = "SELECT 1;\nSELECT 2;";

    List<CqlStatement> statements = splitter.split(script);

    assertThat(statements.get(1).contains(script.indexOf("SELECT 2") + 2)).isTrue();
    assertThat(statements.get(0).contains(script.indexOf("SELECT 2") + 2)).isFalse();
  }

  @Test
  void toleratesBlankAndTrailingInput() {
    assertThat(splitter.split(null)).isEmpty();
    assertThat(splitter.split("   \n  ")).isEmpty();
    assertThat(splitter.split("SELECT 1")).hasSize(1);
    assertThat(splitter.split(";;;")).isEmpty();
  }
}
