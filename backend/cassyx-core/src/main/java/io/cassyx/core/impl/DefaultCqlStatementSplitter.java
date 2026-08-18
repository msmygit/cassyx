package io.cassyx.core.impl;

import io.cassyx.core.api.CqlStatement;
import io.cassyx.core.api.CqlStatementSplitter;
import java.util.ArrayList;
import java.util.List;

/**
 * Semicolon splitter that understands CQL lexical structure: single-quoted strings (with
 * {@code ''} escapes), double-quoted identifiers, dollar-quoted UDF bodies ({@code $$...$$}),
 * line comments ({@code --} and {@code //}) and block comments.
 *
 * <p>Plan section 5.1: a naive {@code split(";")} corrupts string literals and UDF bodies.
 */
public final class DefaultCqlStatementSplitter implements CqlStatementSplitter {

  @Override
  public List<CqlStatement> split(String script) {
    List<CqlStatement> statements = new ArrayList<>();
    if (script == null || script.isBlank()) {
      return statements;
    }
    int length = script.length();
    int start = 0;
    int i = 0;
    while (i < length) {
      char c = script.charAt(i);
      if (c == '\'') {
        i = skipSingleQuoted(script, i);
      } else if (c == '"') {
        i = skipDoubleQuoted(script, i);
      } else if (c == '$' && i + 1 < length && script.charAt(i + 1) == '$') {
        i = skipDollarQuoted(script, i);
      } else if (isLineCommentStart(script, i)) {
        i = skipToLineEnd(script, i);
      } else if (c == '/' && i + 1 < length && script.charAt(i + 1) == '*') {
        i = skipBlockComment(script, i);
      } else if (c == ';') {
        addStatement(statements, script, start, i);
        i++;
        start = i;
      } else {
        i++;
      }
    }
    addStatement(statements, script, start, length);
    return statements;
  }

  private static void addStatement(List<CqlStatement> out, String script, int start, int end) {
    String raw = script.substring(start, end);
    String trimmed = raw.trim();
    if (trimmed.isEmpty()) {
      return;
    }
    int offset = start + raw.indexOf(trimmed.charAt(0));
    out.add(new CqlStatement(trimmed, offset, offset + trimmed.length()));
  }

  private static boolean isLineCommentStart(String s, int i) {
    if (i + 1 >= s.length()) {
      return false;
    }
    char a = s.charAt(i);
    char b = s.charAt(i + 1);
    return (a == '-' && b == '-') || (a == '/' && b == '/');
  }

  private static int skipToLineEnd(String s, int i) {
    int idx = s.indexOf('\n', i);
    return idx < 0 ? s.length() : idx + 1;
  }

  private static int skipBlockComment(String s, int i) {
    int idx = s.indexOf("*/", i + 2);
    return idx < 0 ? s.length() : idx + 2;
  }

  private static int skipSingleQuoted(String s, int i) {
    int j = i + 1;
    while (j < s.length()) {
      if (s.charAt(j) == '\'') {
        if (j + 1 < s.length() && s.charAt(j + 1) == '\'') {
          j += 2;
          continue;
        }
        return j + 1;
      }
      j++;
    }
    return s.length();
  }

  private static int skipDoubleQuoted(String s, int i) {
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
    return s.length();
  }

  private static int skipDollarQuoted(String s, int i) {
    int idx = s.indexOf("$$", i + 2);
    return idx < 0 ? s.length() : idx + 2;
  }
}
