package io.cassyx.api.query;

import io.cassyx.core.api.query.CqlLexer;
import io.cassyx.core.api.query.CqlScriptSplitter;
import io.cassyx.core.api.query.CqlValueCodec;
import io.cassyx.core.api.query.QueryFactory;
import io.cassyx.core.api.query.QueryService;
import io.cassyx.core.api.query.RowMutationService;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Wiring for the query engine and the data grid (plan sections 5.1 and 7).
 *
 * <p>Every bean comes from {@code io.cassyx.core.api.query.QueryFactory} - never from an
 * {@code impl} package, which ArchUnit enforces (plan section 2.1).
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class QueryModuleConfiguration {

  private static final Logger LOG = LoggerFactory.getLogger(QueryModuleConfiguration.class);

  /**
   * Result handles are cached server-side with an idle TTL; the contract documents 10 minutes as the
   * default and {@code 404 ResultHandleExpired} as what a client sees afterwards.
   */
  @Bean
  public QueryService queryService(
      @Value("${cassyx.query.result-handle-ttl:PT10M}") Duration resultHandleTtl) {
    LOG.info("Query result handles expire after {} idle", resultHandleTtl);
    return QueryFactory.queryService(resultHandleTtl);
  }

  @Bean
  public RowMutationService rowMutationService() {
    return QueryFactory.rowMutationService();
  }

  @Bean
  public CqlScriptSplitter cqlScriptSplitter() {
    return QueryFactory.scriptSplitter();
  }

  @Bean
  public CqlLexer cqlLexer() {
    return QueryFactory.lexer();
  }

  @Bean
  public CqlValueCodec cqlValueCodec() {
    return QueryFactory.valueCodec();
  }

  /** Releases idle-expired result handles so a forgotten browser tab does not pin paging state. */
  @Bean
  public ResultSetSweeper resultSetSweeper(QueryService queries) {
    return new ResultSetSweeper(queries);
  }

  // There was a @ConditionalOnMissingBean SessionRegistry fallback here that failed every lookup
  // with 409 NotConnected. It existed only because workstream A had not yet registered a real
  // registry. It is gone now that ConnectionsConfiguration publishes exactly one
  // ManagedSessionRegistry (which IS a SessionRegistry), and its removal is not cosmetic: with the
  // fallback in place a bean-ordering accident could have silently wired the query and data
  // endpoints to a registry that reports "not connected" for a cluster that is, in fact, connected.
  // Do NOT add a second SessionRegistry bean - two make every injection point ambiguous and broke
  // three workstreams' Spring contexts the last time it happened.

  /** Scheduled sweep of expired result handles. */
  public static final class ResultSetSweeper {

    private final QueryService queries;

    ResultSetSweeper(QueryService queries) {
      this.queries = queries;
    }

    @Scheduled(fixedDelayString = "${cassyx.query.sweep-interval-ms:60000}")
    public void sweep() {
      int released = queries.sweepExpiredResultSets();
      if (released > 0) {
        LOG.debug("Released {} expired result handle(s)", released);
      }
    }
  }
}
