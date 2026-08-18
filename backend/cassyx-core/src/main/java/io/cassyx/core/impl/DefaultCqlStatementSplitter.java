package io.cassyx.core.impl;

import io.cassyx.core.api.CqlStatement;
import io.cassyx.core.api.CqlStatementSplitter;
import io.cassyx.core.api.query.CqlScriptSplitter;
import io.cassyx.core.impl.query.LexingScriptSplitter;
import java.util.List;

/**
 * The narrow {@link CqlStatementSplitter} view onto the real lexer.
 *
 * <p>Originally a hand-rolled character scanner; it now delegates to {@link LexingScriptSplitter} so
 * there is exactly ONE implementation of "where does a CQL statement end" in the product. Two
 * splitters that disagree is worse than one that is slightly wrong, because the editor would
 * highlight one thing and the server would execute another.
 *
 * <p>The richer {@link CqlScriptSplitter} view - statement kind, line numbers, under-cursor flag,
 * lexical errors - is what the query API uses; this one is kept for callers that only want the text.
 */
public final class DefaultCqlStatementSplitter implements CqlStatementSplitter {

  private final CqlScriptSplitter delegate = new LexingScriptSplitter();

  @Override
  public List<CqlStatement> split(String script) {
    return delegate.split(script, null).statements().stream()
        .map(s -> new CqlStatement(s.cql(), s.startOffset(), s.endOffset()))
        .toList();
  }
}
