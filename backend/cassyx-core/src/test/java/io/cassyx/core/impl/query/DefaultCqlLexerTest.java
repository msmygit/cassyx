package io.cassyx.core.impl.query;

import static org.assertj.core.api.Assertions.assertThat;

import io.cassyx.core.api.query.CqlLexer;
import io.cassyx.core.api.query.CqlLexer.TokenType;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DefaultCqlLexerTest {

  private final CqlLexer lexer = new DefaultCqlLexer();

  private List<CqlLexer.Token> significant(String cql) {
    return lexer.lex(cql).tokens().stream().filter(CqlLexer.Token::isSignificant).toList();
  }

  @Test
  void classifiesKeywordsIdentifiersAndPunctuation() {
    List<CqlLexer.Token> tokens = significant("SELECT id FROM demo.people;");

    assertThat(tokens)
        .extracting(CqlLexer.Token::type)
        .containsExactly(
            TokenType.KEYWORD,
            TokenType.IDENTIFIER,
            TokenType.KEYWORD,
            TokenType.IDENTIFIER,
            TokenType.PUNCTUATION,
            TokenType.IDENTIFIER,
            TokenType.PUNCTUATION);
  }

  @Test
  @DisplayName("A semicolon inside a string literal is part of the string, not a terminator")
  void semicolonInsideStringIsOneToken() {
    List<CqlLexer.Token> tokens = significant("SELECT * FROM t WHERE a = 'x;y'");

    assertThat(tokens).last().satisfies(t -> {
      assertThat(t.type()).isEqualTo(TokenType.STRING);
      assertThat(t.text()).isEqualTo("'x;y'");
    });
  }

  @Test
  void handlesDoubledQuoteEscapes() {
    List<CqlLexer.Token> tokens = significant("SELECT 'it''s;fine'");

    assertThat(tokens).last().extracting(CqlLexer.Token::text).isEqualTo("'it''s;fine'");
  }

  @Test
  void handlesQuotedIdentifiersIncludingDoubledQuotes() {
    List<CqlLexer.Token> tokens = significant("SELECT \"we\"\"ird;col\" FROM t");

    assertThat(tokens.get(1).type()).isEqualTo(TokenType.QUOTED_IDENTIFIER);
    assertThat(tokens.get(1).text()).isEqualTo("\"we\"\"ird;col\"");
  }

  @Test
  void handlesDollarQuotedFunctionBodies() {
    List<CqlLexer.Token> tokens = significant("AS $$ int x = 1; return x; $$");

    assertThat(tokens).last().satisfies(t -> {
      assertThat(t.type()).isEqualTo(TokenType.STRING);
      assertThat(t.text()).contains("return x;");
    });
  }

  @Test
  void recognisesBothCommentStyles() {
    CqlLexer.Result result = lexer.lex("-- a; b\n// c; d\n/* e; f */ SELECT 1");

    assertThat(result.tokens()).filteredOn(t -> t.type() == TokenType.COMMENT).hasSize(3);
    assertThat(result.errors()).isEmpty();
  }

  @Test
  void recognisesBindMarkers() {
    List<CqlLexer.Token> tokens = significant("WHERE a = ? AND b = :name");

    assertThat(tokens).filteredOn(t -> t.type() == TokenType.BIND_MARKER)
        .extracting(CqlLexer.Token::text)
        .containsExactly("?", ":name");
  }

  @Test
  void recognisesNumbersIncludingHexAndExponents() {
    List<CqlLexer.Token> tokens = significant("1 2.5 3e10 0xdeadBEEF .5");

    assertThat(tokens).extracting(CqlLexer.Token::text)
        .containsExactly("1", "2.5", "3e10", "0xdeadBEEF", ".5");
    assertThat(tokens).allSatisfy(t -> assertThat(t.type()).isEqualTo(TokenType.NUMBER));
  }

  @Test
  void reportsUnterminatedConstructsRatherThanThrowing() {
    assertThat(lexer.lex("SELECT 'oops").errors())
        .singleElement()
        .extracting(CqlLexer.Problem::message)
        .isEqualTo("Unterminated string literal.");
    assertThat(lexer.lex("SELECT \"oops").errors()).hasSize(1);
    assertThat(lexer.lex("/* oops").errors()).hasSize(1);
    assertThat(lexer.lex("AS $$ oops").errors()).hasSize(1);
  }

  @Test
  void reportsOneBasedLineAndColumn() {
    CqlLexer.Result result = lexer.lex("SELECT 1;\n  SELECT 2;");

    CqlLexer.Token second =
        result.tokens().stream()
            .filter(t -> t.type() == TokenType.KEYWORD)
            .skip(1)
            .findFirst()
            .orElseThrow();
    assertThat(second.line()).isEqualTo(2);
    assertThat(second.column()).isEqualTo(3);
  }

  @Test
  void alwaysEmitsEofAndToleratesNull() {
    assertThat(lexer.lex(null).tokens()).extracting(CqlLexer.Token::type).containsExactly(TokenType.EOF);
    assertThat(lexer.lex("").tokens()).extracting(CqlLexer.Token::type).containsExactly(TokenType.EOF);
  }

  @Test
  void lexesOperatorsAndStrayCharacters() {
    List<CqlLexer.Token> tokens = significant("a >= 1 @ b");

    assertThat(tokens).extracting(CqlLexer.Token::text).contains(">=", "@");
  }

  @Test
  void resultReportsValidity() {
    assertThat(lexer.lex("SELECT 1").isValid()).isTrue();
    assertThat(lexer.lex("SELECT 'x").isValid()).isFalse();
  }
}
