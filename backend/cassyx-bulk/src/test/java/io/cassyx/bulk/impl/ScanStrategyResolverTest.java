package io.cassyx.bulk.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.cassyx.bulk.api.ScanStrategy;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The plan section 7.1 fallback decision.
 *
 * <p>Amazon Keyspaces speaks CQL and looks like Cassandra to the driver but has no token ring. The
 * capability matrix says degrade to plain paging there, not error - so this table is the difference
 * between "export is slower on Keyspaces" and "export is broken on Keyspaces".
 */
class ScanStrategyResolverTest {

  private static final List<String> CASSANDRA = List.of("/10.0.0.1:9042", "/10.0.0.2:9042");

  private static final List<String> KEYSPACES =
      List.of("cassandra.eu-west-1.amazonaws.com/3.5.6.7:9142");

  @Test
  void picksTheFastPathOnARealRing() {
    assertThat(ScanStrategyResolver.resolve(256, CASSANDRA, null))
        .isEqualTo(ScanStrategy.TOKEN_RANGE);
  }

  @Test
  @DisplayName("no token ranges means no token ring - page instead")
  void fallsBackWhenTheTokenMapIsEmpty() {
    assertThat(ScanStrategyResolver.resolve(0, CASSANDRA, null)).isEqualTo(ScanStrategy.PAGING);
  }

  @Test
  @DisplayName("Keyspaces falls back even if it reports token ranges")
  void fallsBackOnKeyspaces() {
    assertThat(ScanStrategyResolver.resolve(256, KEYSPACES, null)).isEqualTo(ScanStrategy.PAGING);
  }

  @Test
  void anExplicitPagingOverrideWins() {
    assertThat(ScanStrategyResolver.resolve(256, CASSANDRA, "paging"))
        .isEqualTo(ScanStrategy.PAGING);
    assertThat(ScanStrategyResolver.resolve(256, CASSANDRA, " TOKEN_RANGE "))
        .isEqualTo(ScanStrategy.TOKEN_RANGE);
  }

  @Test
  @DisplayName("asking for the fast path cannot conjure a token ring")
  void anExplicitFastPathOverrideStillDegrades() {
    assertThat(ScanStrategyResolver.resolve(0, CASSANDRA, "TOKEN_RANGE"))
        .isEqualTo(ScanStrategy.PAGING);
    assertThat(ScanStrategyResolver.resolve(256, KEYSPACES, "TOKEN_RANGE"))
        .isEqualTo(ScanStrategy.PAGING);
  }

  @Test
  void blankOverridesAreIgnored() {
    assertThat(ScanStrategyResolver.resolve(256, CASSANDRA, "   "))
        .isEqualTo(ScanStrategy.TOKEN_RANGE);
  }

  @Test
  void rejectsAnUnknownOverride() {
    assertThatThrownBy(() -> ScanStrategyResolver.resolve(256, CASSANDRA, "turbo"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("turbo");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "cassandra.us-east-2.amazonaws.com:9142",
        "CASSANDRA.EU-WEST-1.AMAZONAWS.COM:9142",
        "cassandra.ap-south-1.example:9142"
      })
  void recognisesKeyspacesEndpoints(String endpoint) {
    assertThat(ScanStrategyResolver.isKeyspaces(List.of(endpoint))).isTrue();
  }

  @Test
  void nullAndMissingEndpointsAreNotKeyspaces() {
    assertThat(ScanStrategyResolver.isKeyspaces(null)).isFalse();
    assertThat(ScanStrategyResolver.isKeyspaces(List.of())).isFalse();
    assertThat(ScanStrategyResolver.isKeyspaces(Arrays.asList((String) null))).isFalse();
    assertThat(ScanStrategyResolver.isKeyspaces(CASSANDRA)).isFalse();
  }
}
