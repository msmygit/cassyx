package io.cassyx.core.impl.query;

import io.cassyx.core.api.query.CqlLexer;
import io.cassyx.core.api.query.CqlScriptSplitter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Statement splitter driven by {@link DefaultCqlLexer}.
 *
 * <p>Two behaviours a naive splitter gets wrong:
 *
 * <ul>
 *   <li>Semicolons inside string literals, quoted identifiers, comments and dollar-quoted UDF bodies
 *       are not statement terminators. The lexer has already classified them, so they cannot be.
 *   <li>{@code BEGIN BATCH ... APPLY BATCH;} is ONE statement even though its inner statements are
 *       semicolon-separated. Splitting it produces three fragments, none of which parse.
 * </ul>
 */
public final class LexingScriptSplitter implements CqlScriptSplitter {

  private final CqlLexer lexer;

  public LexingScriptSplitter() {
    this(new DefaultCqlLexer());
  }

  public LexingScriptSplitter(CqlLexer lexer) {
    this.lexer = lexer;
  }

  @Override
  public Result split(String cql, Integer cursorOffset) {
    if (cql == null || cql.isBlank()) {
      return new Result(List.of(), List.of());
    }

    CqlLexer.Result lexed = lexer.lex(cql);
    List<CqlLexer.Token> tokens = lexed.tokens().stream().filter(CqlLexer.Token::isSignificant).toList();

    // Spans start where the PREVIOUS terminator ended, not at the first significant token, so that
    // a comment preceding a statement stays attached to it: the editor then highlights exactly the
    // text it sends, and the server hands the comment to Cassandra, which ignores it.
    List<int[]> spans = new ArrayList<>();
    int segStart = 0;
    boolean started = false;
    boolean inBatch = false;
    boolean batchClosed = false;

    for (int i = 0; i < tokens.size(); i++) {
      CqlLexer.Token token = tokens.get(i);
      boolean semicolon = token.type() == CqlLexer.TokenType.PUNCTUATION && ";".equals(token.text());

      if (!started && !semicolon) {
        started = true;
        inBatch = isKeyword(token, "BEGIN");
        batchClosed = false;
      }

      if (inBatch && !batchClosed && isKeyword(token, "APPLY")) {
        CqlLexer.Token next = i + 1 < tokens.size() ? tokens.get(i + 1) : null;
        if (next != null && isKeyword(next, "BATCH")) {
          batchClosed = true;
        }
      }

      if (semicolon && (!inBatch || batchClosed)) {
        spans.add(new int[] {segStart, token.startOffset()});
        segStart = token.endOffset();
        started = false;
        inBatch = false;
        batchClosed = false;
      }
    }

    if (segStart < cql.length()) {
      spans.add(new int[] {segStart, cql.length()});
    }

    DefaultCqlLexer.LineIndex lines = new DefaultCqlLexer.LineIndex(cql);
    List<Slice> slices = new ArrayList<>();
    for (int[] span : spans) {
      String raw = cql.substring(span[0], span[1]);
      String trimmed = raw.strip();
      if (trimmed.isEmpty()) {
        continue;
      }
      int offset = span[0] + raw.indexOf(trimmed.charAt(0));
      int end = offset + trimmed.length();
      boolean underCursor = cursorOffset != null && cursorOffset >= offset && cursorOffset <= end;
      slices.add(
          new Slice(
              slices.size(), trimmed, offset, end, lines.line(offset), classify(trimmed), underCursor));
    }

    // A caret in trailing whitespace still belongs to the statement before it, which is what a user
    // pressing "run statement under cursor" at the end of a script means.
    if (cursorOffset != null && slices.stream().noneMatch(Slice::underCursor) && !slices.isEmpty()) {
      int best = 0;
      for (int i = 0; i < slices.size(); i++) {
        if (slices.get(i).startOffset() <= cursorOffset) {
          best = i;
        }
      }
      Slice s = slices.get(best);
      slices.set(
          best,
          new Slice(s.index(), s.cql(), s.startOffset(), s.endOffset(), s.startLine(), s.kind(), true));
    }

    return new Result(slices, lexed.errors());
  }

  private static boolean isKeyword(CqlLexer.Token token, String word) {
    return token.type() == CqlLexer.TokenType.KEYWORD && token.text().equalsIgnoreCase(word);
  }

  /**
   * Coarse classification from the leading keyword, skipping any leading comment - which is why this
   * goes back through the lexer rather than reading the first word of the raw text.
   */
  Kind classify(String statement) {
    String first =
        lexer.lex(statement).tokens().stream()
            .filter(CqlLexer.Token::isSignificant)
            .findFirst()
            .map(t -> t.text().toUpperCase(Locale.ROOT))
            .orElse("");
    return switch (first) {
      case "SELECT" -> Kind.SELECT;
      case "INSERT" -> Kind.INSERT;
      case "UPDATE" -> Kind.UPDATE;
      case "DELETE" -> Kind.DELETE;
      case "BEGIN" -> Kind.BATCH;
      case "USE" -> Kind.USE;
      case "TRUNCATE" -> Kind.TRUNCATE;
      case "GRANT" -> Kind.GRANT;
      case "REVOKE" -> Kind.REVOKE;
      case "CREATE", "ALTER", "DROP" -> Kind.DDL;
      default -> Kind.OTHER;
    };
  }
}
