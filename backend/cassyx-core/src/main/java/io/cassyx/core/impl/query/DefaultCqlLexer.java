package io.cassyx.core.impl.query;

import io.cassyx.core.api.query.CqlLexer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Hand-written CQL lexer. One pass, no regex, no backtracking.
 *
 * <p>It exists so that NOTHING in cassyx ever calls {@code split(";")}. These all contain semicolons
 * and all break a naive splitter:
 *
 * <pre>{@code
 * SELECT * FROM t WHERE s = 'a;b';
 * CREATE FUNCTION f(a int) ... LANGUAGE java AS 'return a; ';
 * CREATE FUNCTION g(a int) ... AS $$ return a; $$;
 * -- a comment with ; in it
 * CREATE TABLE "weird;name" (id int PRIMARY KEY);
 * }</pre>
 */
public final class DefaultCqlLexer implements CqlLexer {

  /**
   * CQL reserved and non-reserved keywords worth highlighting. Not exhaustive by design - an unknown
   * word simply lexes as an identifier, which is the safe direction.
   */
  private static final Set<String> KEYWORDS =
      Set.of(
          "ADD", "AGGREGATE", "ALL", "ALLOW", "ALTER", "AND", "ANN", "APPLY", "AS", "ASC",
          "ASCII", "AUTHORIZE", "BATCH", "BEGIN", "BIGINT", "BLOB", "BOOLEAN", "BY", "CALLED",
          "CAST", "CLUSTERING", "COLUMNFAMILY", "COMPACT", "CONTAINS", "COUNT", "COUNTER",
          "CREATE", "CUSTOM", "DATE", "DECIMAL", "DEFAULT", "DELETE", "DESC", "DESCRIBE",
          "DISTINCT", "DOUBLE", "DROP", "DURATION", "ENTRIES", "EXISTS", "FILTERING", "FINALFUNC",
          "FLOAT", "FROM", "FROZEN", "FULL", "FUNCTION", "GRANT", "GROUP", "IF", "IN", "INDEX",
          "INET", "INFINITY", "INITCOND", "INPUT", "INSERT", "INT", "INTO", "IS", "JSON", "KEY",
          "KEYS", "KEYSPACE", "LANGUAGE", "LIKE", "LIMIT", "LIST", "LOGGED", "MAP", "MATERIALIZED",
          "MBEAN", "MODIFY", "NAN", "NOLOGIN", "NORECURSIVE", "NOSUPERUSER", "NOT", "NULL", "OF",
          "ON", "OPTIONS", "OR", "ORDER", "PARTITION", "PASSWORD", "PER", "PERMISSION",
          "PERMISSIONS", "PRIMARY", "RENAME", "REPLACE", "RESTRICT", "RETURNS", "REVOKE", "ROLE",
          "ROLES", "SELECT", "SET", "SFUNC", "SMALLINT", "STATIC", "STORAGE", "STYPE",
          "SUPERUSER", "TABLE", "TEXT", "TIME", "TIMESTAMP", "TIMEUUID", "TINYINT", "TO", "TOKEN",
          "TRIGGER", "TRUNCATE", "TTL", "TUPLE", "TYPE", "UNLOGGED", "UPDATE", "USE", "USER",
          "USERS", "USING", "UUID", "VALUES", "VARCHAR", "VARINT", "VECTOR", "VIEW", "WHERE",
          "WITH", "WRITETIME");

  private static final String OPERATOR_CHARS = "=<>!+-*/%|&^~";
  private static final String PUNCTUATION_CHARS = "(),;{}[].";

  @Override
  public Result lex(String cql) {
    List<Token> tokens = new ArrayList<>();
    List<Problem> errors = new ArrayList<>();
    if (cql == null) {
      return new Result(List.of(new Token(TokenType.EOF, "", 0, 0, 1, 1)), List.of());
    }

    LineIndex lines = new LineIndex(cql);
    int i = 0;
    int length = cql.length();

    while (i < length) {
      char c = cql.charAt(i);
      int start = i;

      if (Character.isWhitespace(c)) {
        while (i < length && Character.isWhitespace(cql.charAt(i))) {
          i++;
        }
        add(tokens, lines, TokenType.WHITESPACE, cql, start, i);
      } else if (isLineCommentStart(cql, i)) {
        int nl = cql.indexOf('\n', i);
        i = nl < 0 ? length : nl;
        add(tokens, lines, TokenType.COMMENT, cql, start, i);
      } else if (c == '/' && i + 1 < length && cql.charAt(i + 1) == '*') {
        int end = cql.indexOf("*/", i + 2);
        if (end < 0) {
          errors.add(problem(lines, "Unterminated block comment.", start));
          i = length;
        } else {
          i = end + 2;
        }
        add(tokens, lines, TokenType.COMMENT, cql, start, i);
      } else if (c == '\'') {
        i = scanSingleQuoted(cql, i, errors, lines);
        add(tokens, lines, TokenType.STRING, cql, start, i);
      } else if (c == '$' && i + 1 < length && cql.charAt(i + 1) == '$') {
        int end = cql.indexOf("$$", i + 2);
        if (end < 0) {
          errors.add(problem(lines, "Unterminated dollar-quoted string.", start));
          i = length;
        } else {
          i = end + 2;
        }
        add(tokens, lines, TokenType.STRING, cql, start, i);
      } else if (c == '"') {
        i = scanDoubleQuoted(cql, i, errors, lines);
        add(tokens, lines, TokenType.QUOTED_IDENTIFIER, cql, start, i);
      } else if (c == '?') {
        i++;
        add(tokens, lines, TokenType.BIND_MARKER, cql, start, i);
      } else if (c == ':' && i + 1 < length && isIdentifierStart(cql.charAt(i + 1))) {
        i += 2;
        while (i < length && isIdentifierPart(cql.charAt(i))) {
          i++;
        }
        add(tokens, lines, TokenType.BIND_MARKER, cql, start, i);
      } else if (Character.isDigit(c) || (c == '.' && i + 1 < length && Character.isDigit(cql.charAt(i + 1)))) {
        i = scanNumber(cql, i);
        add(tokens, lines, TokenType.NUMBER, cql, start, i);
      } else if (isIdentifierStart(c)) {
        while (i < length && isIdentifierPart(cql.charAt(i))) {
          i++;
        }
        String text = cql.substring(start, i);
        TokenType type =
            KEYWORDS.contains(text.toUpperCase(Locale.ROOT)) ? TokenType.KEYWORD : TokenType.IDENTIFIER;
        add(tokens, lines, type, cql, start, i);
      } else if (PUNCTUATION_CHARS.indexOf(c) >= 0) {
        i++;
        add(tokens, lines, TokenType.PUNCTUATION, cql, start, i);
      } else if (OPERATOR_CHARS.indexOf(c) >= 0) {
        i++;
        while (i < length && OPERATOR_CHARS.indexOf(cql.charAt(i)) >= 0) {
          i++;
        }
        add(tokens, lines, TokenType.OPERATOR, cql, start, i);
      } else {
        // Anything else (including a stray ':') is still a token; the parser is Cassandra's job.
        i++;
        add(tokens, lines, TokenType.OPERATOR, cql, start, i);
      }
    }

    tokens.add(new Token(TokenType.EOF, "", length, length, lines.line(length), lines.column(length)));
    return new Result(tokens, errors);
  }

  private static boolean isLineCommentStart(String s, int i) {
    if (i + 1 >= s.length()) {
      return false;
    }
    char a = s.charAt(i);
    char b = s.charAt(i + 1);
    return (a == '-' && b == '-') || (a == '/' && b == '/');
  }

  private static int scanSingleQuoted(String s, int i, List<Problem> errors, LineIndex lines) {
    int j = i + 1;
    while (j < s.length()) {
      if (s.charAt(j) == '\'') {
        // '' is an escaped quote, not a terminator.
        if (j + 1 < s.length() && s.charAt(j + 1) == '\'') {
          j += 2;
          continue;
        }
        return j + 1;
      }
      j++;
    }
    errors.add(problem(lines, "Unterminated string literal.", i));
    return s.length();
  }

  private static int scanDoubleQuoted(String s, int i, List<Problem> errors, LineIndex lines) {
    int j = i + 1;
    while (j < s.length()) {
      if (s.charAt(j) == '"') {
        if (j + 1 < s.length() && s.charAt(j + 1) == '"') {
          j += 2;
          continue;
        }
        return j + 1;
      }
      j++;
    }
    errors.add(problem(lines, "Unterminated quoted identifier.", i));
    return s.length();
  }

  private static int scanNumber(String s, int i) {
    int j = i;
    if (s.charAt(j) == '0' && j + 1 < s.length() && (s.charAt(j + 1) == 'x' || s.charAt(j + 1) == 'X')) {
      j += 2;
      while (j < s.length() && isHex(s.charAt(j))) {
        j++;
      }
      return j;
    }
    boolean seenDot = false;
    boolean seenExponent = false;
    while (j < s.length()) {
      char c = s.charAt(j);
      if (Character.isDigit(c)) {
        j++;
      } else if (c == '.' && !seenDot && !seenExponent) {
        seenDot = true;
        j++;
      } else if ((c == 'e' || c == 'E') && !seenExponent && j + 1 < s.length()) {
        char next = s.charAt(j + 1);
        if (Character.isDigit(next) || next == '+' || next == '-') {
          seenExponent = true;
          j += 2;
        } else {
          break;
        }
      } else {
        break;
      }
    }
    return j;
  }

  private static boolean isHex(char c) {
    return Character.isDigit(c) || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
  }

  private static boolean isIdentifierStart(char c) {
    return Character.isLetter(c) || c == '_';
  }

  private static boolean isIdentifierPart(char c) {
    return Character.isLetterOrDigit(c) || c == '_';
  }

  private static void add(
      List<Token> tokens, LineIndex lines, TokenType type, String cql, int start, int end) {
    tokens.add(
        new Token(type, cql.substring(start, end), start, end, lines.line(start), lines.column(start)));
  }

  private static Problem problem(LineIndex lines, String message, int offset) {
    return new Problem(message, offset, lines.line(offset));
  }

  /** Offset to (line, column), 1-based, computed once per script rather than per token. */
  static final class LineIndex {

    private final int[] lineStarts;

    LineIndex(String text) {
      List<Integer> starts = new ArrayList<>();
      starts.add(0);
      for (int i = 0; i < text.length(); i++) {
        if (text.charAt(i) == '\n') {
          starts.add(i + 1);
        }
      }
      lineStarts = starts.stream().mapToInt(Integer::intValue).toArray();
    }

    int line(int offset) {
      return indexOf(offset) + 1;
    }

    int column(int offset) {
      return offset - lineStarts[indexOf(offset)] + 1;
    }

    private int indexOf(int offset) {
      int lo = 0;
      int hi = lineStarts.length - 1;
      while (lo < hi) {
        int mid = (lo + hi + 1) >>> 1;
        if (lineStarts[mid] <= offset) {
          lo = mid;
        } else {
          hi = mid - 1;
        }
      }
      return lo;
    }
  }
}
