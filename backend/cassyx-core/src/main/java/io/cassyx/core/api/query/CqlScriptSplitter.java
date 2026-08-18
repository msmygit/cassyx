package io.cassyx.core.api.query;

import java.util.List;

/**
 * Splits a multi-statement CQL script into statements, using {@link CqlLexer} rather than
 * {@code split(";")} (plan section 5.1).
 *
 * <p>Every slice carries its source offsets so the editor can implement "execute all", "execute the
 * statement under the cursor" and "execute the selection" from one server-side answer.
 */
public interface CqlScriptSplitter {

  /**
   * @param cql the whole script
   * @param cursorOffset caret position, or {@code null}; the containing slice is flagged
   *     {@link Slice#underCursor()}
   */
  Result split(String cql, Integer cursorOffset);

  default Result split(String cql) {
    return split(cql, null);
  }

  /** Coarse classification, used for routing and for history badges. */
  enum Kind {
    SELECT,
    INSERT,
    UPDATE,
    DELETE,
    BATCH,
    DDL,
    USE,
    TRUNCATE,
    GRANT,
    REVOKE,
    OTHER
  }

  /** @param startLine 1-based line of {@code startOffset} */
  record Slice(
      int index,
      String cql,
      int startOffset,
      int endOffset,
      int startLine,
      Kind kind,
      boolean underCursor) {

    public boolean contains(int offset) {
      return offset >= startOffset && offset <= endOffset;
    }
  }

  record Result(List<Slice> statements, List<CqlLexer.Problem> errors) {

    public Result {
      statements = statements == null ? List.of() : List.copyOf(statements);
      errors = errors == null ? List.of() : List.copyOf(errors);
    }
  }
}
