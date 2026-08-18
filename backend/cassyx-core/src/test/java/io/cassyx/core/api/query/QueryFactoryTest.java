package io.cassyx.core.api.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link QueryFactory} is the ONLY seam between {@code io.cassyx.core.impl.query} and the rest of
 * the product (plan section 2.1). If it stops handing out working instances, cassyx-api has no
 * legal way to reach the engine at all.
 */
class QueryFactoryTest {

  @Test
  @DisplayName("Every engine component is reachable without importing an impl package")
  void buildsEveryComponent() {
    assertThat(QueryFactory.lexer()).isNotNull();
    assertThat(QueryFactory.scriptSplitter()).isNotNull();
    assertThat(QueryFactory.valueCodec()).isNotNull();
    assertThat(QueryFactory.rowMutationService()).isNotNull();

    try (AutoCloseable service = (AutoCloseable) QueryFactory.queryService()) {
      assertThat(service).isNotNull();
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }

  @Test
  void theResultHandleTtlIsConfigurable() {
    QueryService service = QueryFactory.queryService(Duration.ofMinutes(1));

    assertThat(service.sweepExpiredResultSets()).isZero();
  }

  @Test
  void theFactoryIsNotInstantiable() {
    assertThat(QueryFactory.class.getDeclaredConstructors()).hasSize(1);
  }

  @Test
  void theLexerAndSplitterAgreeOnStatementBoundaries() {
    String script = "SELECT * FROM t WHERE a = 'x;y'; SELECT 1;";

    assertThat(QueryFactory.scriptSplitter().split(script).statements()).hasSize(2);
    assertThat(QueryFactory.lexer().lex(script).isValid()).isTrue();
  }
}
