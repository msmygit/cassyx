package io.cassyx.api.vector;

import com.datastax.oss.driver.api.core.CqlSession;
import io.cassyx.vector.api.VectorFactory;
import io.cassyx.vector.api.VectorService;
import java.util.function.Function;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Bean wiring for the vector workstream (plan section 6).
 *
 * <p>{@code AnnQueryBuilder} and {@code SaiIndexManager} beans already come from
 * {@code CassyxCoreConfiguration}; they are deliberately not redeclared here, because two beans of
 * the same type would make injection ambiguous.
 */
@Configuration(proxyBeanMethods = false)
public class VectorConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public VectorService vectorService() {
    return VectorFactory.vectorService();
  }

  /**
   * Fallback session resolver.
   *
   * <p>The real one belongs to workstream A's session registry. Until that bean exists, this one
   * fails with the contract's {@code NotConnected} problem rather than an
   * {@code UnsatisfiedDependencyException} at startup - a missing registry must not stop the whole
   * application from booting, or nothing else can be exercised either.
   *
   * <p>It also adapts a plain {@code Function<String, CqlSession>} if that is the shape workstream
   * A publishes, so the two can meet without either side changing.
   */
  @Bean
  @ConditionalOnMissingBean(VectorSessionResolver.class)
  public VectorSessionResolver vectorSessionResolver(
      ObjectProvider<Function<String, CqlSession>> sessionLookup) {
    Function<String, CqlSession> lookup = sessionLookup.getIfAvailable();
    if (lookup != null) {
      return VectorSessionResolver.of(lookup);
    }
    return connectionId -> {
      throw new VectorSessionResolver.NoLiveSessionException(
          connectionId, "No session registry is wired into this build yet.");
    };
  }
}
