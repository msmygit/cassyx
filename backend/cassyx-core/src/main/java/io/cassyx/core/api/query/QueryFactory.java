package io.cassyx.core.api.query;

import io.cassyx.core.impl.query.DefaultCqlLexer;
import io.cassyx.core.impl.query.DefaultCqlValueCodec;
import io.cassyx.core.impl.query.DefaultRowMutationService;
import io.cassyx.core.impl.query.LexingScriptSplitter;
import io.cassyx.core.impl.query.ResultSetCache;
import io.cassyx.core.impl.query.VirtualThreadQueryService;
import java.time.Clock;
import java.time.Duration;

/**
 * Composition entry point for the query engine.
 *
 * <p>This is the single seam between {@code io.cassyx.core.impl.query} and everything else: no
 * sibling module - including {@code cassyx-api} - may import an {@code impl} package (plan section
 * 2.1, ArchUnit-enforced), so they come here instead.
 *
 * <pre>{@code
 * QueryService queries = QueryFactory.queryService();
 * ResultPage page = queries.execute(session, QuerySpec.of("SELECT * FROM demo.users"));
 * while (page.nextPageToken() != null) {
 *   page = queries.nextPage(page.resultHandle(), page.nextPageToken(), null);
 * }
 * }</pre>
 */
public final class QueryFactory {

  private QueryFactory() {}

  public static CqlLexer lexer() {
    return new DefaultCqlLexer();
  }

  public static CqlScriptSplitter scriptSplitter() {
    return new LexingScriptSplitter();
  }

  public static CqlValueCodec valueCodec() {
    return new DefaultCqlValueCodec();
  }

  /** Query service with the default 10-minute idle TTL on result handles. */
  public static QueryService queryService() {
    return new VirtualThreadQueryService();
  }

  /**
   * @param resultHandleTtl idle TTL for cached result sets and their retained paging tokens
   */
  public static QueryService queryService(Duration resultHandleTtl) {
    return new VirtualThreadQueryService(
        new DefaultCqlValueCodec(), new ResultSetCache(resultHandleTtl, Clock.systemUTC()));
  }

  public static RowMutationService rowMutationService() {
    return new DefaultRowMutationService();
  }
}
