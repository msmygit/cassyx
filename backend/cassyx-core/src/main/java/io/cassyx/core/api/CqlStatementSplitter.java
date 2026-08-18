package io.cassyx.core.api;

import java.util.List;

/**
 * Splits a multi-statement CQL script into individual statements.
 *
 * <p>A real lexer, not {@code split(";")}: string literals, quoted identifiers, comments and UDF
 * bodies all contain semicolons (plan section 5.1).
 */
public interface CqlStatementSplitter {

  List<CqlStatement> split(String script);
}
