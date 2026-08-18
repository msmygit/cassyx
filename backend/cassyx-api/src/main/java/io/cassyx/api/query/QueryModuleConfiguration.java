package io.cassyx.api.query;

import io.cassyx.core.api.ClusterCapabilities;
import io.cassyx.core.api.ConnectionNotOpenException;
import io.cassyx.core.api.SessionRegistry;
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
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
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

  /**
   * Fallback so the query and data endpoints are wired even before workstream A's real registry
   * lands. It fails every lookup with the contract's {@code 409 NotConnected}, which is the honest
   * answer for a deployment with no session management: {@code @ConditionalOnMissingBean} means the
   * real implementation always wins.
   */
  @Bean
  @ConditionalOnMissingBean(SessionRegistry.class)
  public SessionRegistry unavailableSessionRegistry() {
    LOG.warn("No SessionRegistry bean found; query and data endpoints will report 409 Not connected");
    return new SessionRegistry() {

      @Override
      public com.datastax.oss.driver.api.core.CqlSession session(String connectionId) {
        throw new ConnectionNotOpenException(connectionId);
      }

      @Override
      public boolean isConnected(String connectionId) {
        return false;
      }

      @Override
      public ClusterCapabilities capabilities(String connectionId) {
        throw new ConnectionNotOpenException(connectionId);
      }
    };
  }

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
