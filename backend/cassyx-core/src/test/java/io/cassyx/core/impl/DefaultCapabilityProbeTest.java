package io.cassyx.core.impl;

import static org.assertj.core.api.Assertions.assertThat;

import io.cassyx.core.api.Capability;
import io.cassyx.core.api.CapabilityProbe;
import io.cassyx.core.api.CoreFactory;
import org.junit.jupiter.api.Test;

class DefaultCapabilityProbeTest {

  @Test
  void isDiscoverableViaServiceLoader() {
    assertThat(CoreFactory.capabilityProbes())
        .extracting(CapabilityProbe::id)
        .contains("apache-cassandra");
  }

  @Test
  void gatesVectorAndSaiOnCassandra5() {
    assertThat(DefaultCapabilityProbe.forVersion("5.0.2"))
        .contains(Capability.SAI, Capability.VECTOR_ANN);
    assertThat(DefaultCapabilityProbe.forVersion("4.1.3"))
        .doesNotContain(Capability.SAI, Capability.VECTOR_ANN)
        .contains(Capability.TOKEN_RANGE_SCAN);
    assertThat(DefaultCapabilityProbe.forVersion("not-a-version"))
        .doesNotContain(Capability.VECTOR_ANN);
  }
}
