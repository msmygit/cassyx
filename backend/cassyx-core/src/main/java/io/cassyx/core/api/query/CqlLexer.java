package io.cassyx.core.api.query;

import java.util.List;

/**
 * A real CQL lexer (plan section 5.1).
 *
 * <p>This exists because {@code split(";")} is wrong: string literals, quoted identifiers, block and
 * line comments and dollar-quoted UDF bodies all legitimately contain semicolons. Everything that
 * needs to understand statement boundaries - the splitter, "run statement under cursor", the editor
 * syntax service - goes through this one implementation.
 */
public interface CqlLexer {

  Result lex(String cql);

  /** Mirrors the contract's {@code CqlToken.type} enum. */
  enum TokenType {
    KEYWORD,
    IDENTIFIER,
    QUOTED_IDENTIFIER,
    STRING,
    NUMBER,
    OPERATOR,
    PUNCTUATION,
    COMMENT,
    WHITESPACE,
    BIND_MARKER,
    EOF
  }

  /**
   * @param line 1-based line of {@code startOffset}
   * @param column 1-based column of {@code startOffset}
   */
  record Token(TokenType type, String text, int startOffset, int endOffset, int line, int column) {

    public boolean isSignificant() {
      return type != TokenType.WHITESPACE && type != TokenType.COMMENT && type != TokenType.EOF;
    }
  }

  /** A lexical error, e.g. an unterminated string literal. Mirrors {@code CqlSyntaxProblem}. */
  record Problem(String message, int offset, int line) {}

  record Result(List<Token> tokens, List<Problem> errors) {

    public Result {
      tokens = tokens == null ? List.of() : List.copyOf(tokens);
      errors = errors == null ? List.of() : List.copyOf(errors);
    }

    public boolean isValid() {
      return errors.isEmpty();
    }
  }
}
