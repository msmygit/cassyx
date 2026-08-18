package io.cassyx.core.impl;

import static org.assertj.core.api.Assertions.assertThat;

import io.cassyx.core.api.Capability;
import io.cassyx.core.api.CapabilityStatus;
import io.cassyx.core.api.CapabilitySupport;
import io.cassyx.core.api.ClusterFlavor;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/** The plan section 7.1 matrix, asserted cell by cell. */
class CapabilityMatrixTest {

  private static CapabilitySupport support(
      ClusterFlavor flavor, String version, String dseVersion, Capability capability) {
    return CapabilityMatrix.forCluster(flavor, version, dseVersion).get(capability).support();
  }

  @ParameterizedTest
  @EnumSource(ClusterFlavor.class)
  @DisplayName("every flavour answers for every capability - no silent gaps in the matrix")
  void coversEveryCapabilityForEveryFlavour(ClusterFlavor flavor) {
    Map<Capability, CapabilityStatus> matrix = CapabilityMatrix.forCluster(flavor, "5.0.2", "6.8.35");

    assertThat(matrix).containsOnlyKeys(Capability.values());
  }

  @ParameterizedTest
  @EnumSource(ClusterFlavor.class)
  @DisplayName("anything not fully supported explains itself - that text is the UI tooltip")
  void everyNonSupportedCellCarriesAReason(ClusterFlavor flavor) {
    CapabilityMatrix.forCluster(flavor, "3.11.14", "6.7.0")
        .values()
        .forEach(
            status -> {
              if (status.support() != CapabilitySupport.SUPPORTED) {
                assertThat(status.reason())
                    .as("%s / %s needs a reason", flavor, status.capability())
                    .isNotBlank();
              }
            });
  }

  @Nested
  class ApacheCassandra {

    @ParameterizedTest
    @ValueSource(strings = {"5.0.0", "5.0.2", "6.0.0"})
    void fiveHasSaiAndVectors(String version) {
      assertThat(support(ClusterFlavor.CASSANDRA, version, null, Capability.SAI))
          .isEqualTo(CapabilitySupport.SUPPORTED);
      assertThat(support(ClusterFlavor.CASSANDRA, version, null, Capability.VECTOR_ANN))
          .isEqualTo(CapabilitySupport.SUPPORTED);
      assertThat(support(ClusterFlavor.CASSANDRA, version, null, Capability.MATERIALIZED_VIEWS))
          .isEqualTo(CapabilitySupport.SUPPORTED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"2.1.22", "3.11.14", "4.0.11", "4.1.3"})
    void beforeFiveHasNeither(String version) {
      assertThat(support(ClusterFlavor.CASSANDRA, version, null, Capability.SAI))
          .isEqualTo(CapabilitySupport.UNSUPPORTED);
      assertThat(support(ClusterFlavor.CASSANDRA, version, null, Capability.VECTOR_ANN))
          .isEqualTo(CapabilitySupport.UNSUPPORTED);
    }

    @Test
    @DisplayName("materialized views are PARTIAL, not SUPPORTED, before 5.0 - they are experimental")
    void materializedViewsAreExperimentalBeforeFive() {
      assertThat(support(ClusterFlavor.CASSANDRA, "4.1.3", null, Capability.MATERIALIZED_VIEWS))
          .isEqualTo(CapabilitySupport.PARTIAL);
    }

    @Test
    void virtualTablesArriveInFour() {
      assertThat(support(ClusterFlavor.CASSANDRA, "3.11.14", null, Capability.VIRTUAL_TABLES))
          .isEqualTo(CapabilitySupport.UNSUPPORTED);
      assertThat(support(ClusterFlavor.CASSANDRA, "4.0.11", null, Capability.VIRTUAL_TABLES))
          .isEqualTo(CapabilitySupport.SUPPORTED);
    }

    @Test
    void hasTokenRangeScanTruncateUdfAndRoles() {
      assertThat(support(ClusterFlavor.CASSANDRA, "4.1.3", null, Capability.TOKEN_RANGE_SCAN))
          .isEqualTo(CapabilitySupport.SUPPORTED);
      assertThat(support(ClusterFlavor.CASSANDRA, "4.1.3", null, Capability.TRUNCATE))
          .isEqualTo(CapabilitySupport.SUPPORTED);
      assertThat(support(ClusterFlavor.CASSANDRA, "4.1.3", null, Capability.UDF_UDA))
          .isEqualTo(CapabilitySupport.SUPPORTED);
      assertThat(support(ClusterFlavor.CASSANDRA, "4.1.3", null, Capability.ROLES_PERMISSIONS))
          .isEqualTo(CapabilitySupport.SUPPORTED);
      assertThat(support(ClusterFlavor.CASSANDRA, "4.1.3", null, Capability.TRACING))
          .isEqualTo(CapabilitySupport.SUPPORTED);
    }

    @Test
    void neverClaimsDseSearch() {
      assertThat(support(ClusterFlavor.CASSANDRA, "5.0.2", null, Capability.DSE_SEARCH))
          .isEqualTo(CapabilitySupport.UNSUPPORTED);
    }

    @Test
    @DisplayName("the reason names the version actually reported, so it is actionable")
    void explainsWhichVersionItSaw() {
      assertThat(
              CapabilityMatrix.forCluster(ClusterFlavor.CASSANDRA, "4.1.3", null)
                  .get(Capability.SAI)
                  .reason())
          .contains("4.1.3");
    }

    @Test
    void anUnreadableVersionFailsClosedRatherThanUnlockingFeatures() {
      assertThat(support(ClusterFlavor.CASSANDRA, null, null, Capability.SAI))
          .isEqualTo(CapabilitySupport.UNSUPPORTED);
      assertThat(support(ClusterFlavor.CASSANDRA, "not-a-version", null, Capability.VECTOR_ANN))
          .isEqualTo(CapabilitySupport.UNSUPPORTED);
    }
  }

  @Nested
  class Dse {

    @Test
    void saiArrivesIn68() {
      assertThat(support(ClusterFlavor.DSE, "4.0.0", "6.7.11", Capability.SAI))
          .isEqualTo(CapabilitySupport.UNSUPPORTED);
      assertThat(support(ClusterFlavor.DSE, "4.0.0", "6.8.35", Capability.SAI))
          .isEqualTo(CapabilitySupport.SUPPORTED);
      assertThat(support(ClusterFlavor.DSE, "4.0.0", "6.9.0", Capability.SAI))
          .isEqualTo(CapabilitySupport.SUPPORTED);
    }

    @Test
    void hasSearchButNoVectors() {
      assertThat(support(ClusterFlavor.DSE, "4.0.0", "6.8.35", Capability.DSE_SEARCH))
          .isEqualTo(CapabilitySupport.SUPPORTED);
      assertThat(support(ClusterFlavor.DSE, "4.0.0", "6.8.35", Capability.VECTOR_ANN))
          .isEqualTo(CapabilitySupport.UNSUPPORTED);
    }
  }

  @Nested
  class Astra {

    @Test
    void hasSaiAndVectors() {
      assertThat(support(ClusterFlavor.ASTRA, "4.0.0.6816", null, Capability.SAI))
          .isEqualTo(CapabilitySupport.SUPPORTED);
      assertThat(support(ClusterFlavor.ASTRA, "4.0.0.6816", null, Capability.VECTOR_ANN))
          .isEqualTo(CapabilitySupport.SUPPORTED);
    }

    @Test
    void hasNoMaterializedViewsOrUdfs() {
      assertThat(support(ClusterFlavor.ASTRA, "4.0.0.6816", null, Capability.MATERIALIZED_VIEWS))
          .isEqualTo(CapabilitySupport.UNSUPPORTED);
      assertThat(support(ClusterFlavor.ASTRA, "4.0.0.6816", null, Capability.UDF_UDA))
          .isEqualTo(CapabilitySupport.UNSUPPORTED);
    }

    @Test
    @DisplayName("roles are PARTIAL on Astra - shown with a caveat, not hidden")
    void rolesArePartial() {
      assertThat(support(ClusterFlavor.ASTRA, "4.0.0.6816", null, Capability.ROLES_PERMISSIONS))
          .isEqualTo(CapabilitySupport.PARTIAL);
    }

    @Test
    void keepsTokenRangeScan() {
      assertThat(support(ClusterFlavor.ASTRA, "4.0.0.6816", null, Capability.TOKEN_RANGE_SCAN))
          .isEqualTo(CapabilitySupport.SUPPORTED);
    }
  }

  @Nested
  class AmazonKeyspaces {

    @Test
    @DisplayName("NO token-range scan - the single most consequential cell in the matrix")
    void hasNoTokenRangeScan() {
      assertThat(support(ClusterFlavor.AMAZON_KEYSPACES, "3.11.2", null, Capability.TOKEN_RANGE_SCAN))
          .isEqualTo(CapabilitySupport.UNSUPPORTED);
      assertThat(
              CapabilityMatrix.forCluster(ClusterFlavor.AMAZON_KEYSPACES, "3.11.2", null)
                  .get(Capability.TOKEN_RANGE_SCAN)
                  .reason())
          .contains("plain driver paging");
    }

    @Test
    void hasNoTruncateSaiVectorsViewsOrUdfs() {
      assertThat(support(ClusterFlavor.AMAZON_KEYSPACES, "3.11.2", null, Capability.TRUNCATE))
          .isEqualTo(CapabilitySupport.UNSUPPORTED);
      assertThat(support(ClusterFlavor.AMAZON_KEYSPACES, "3.11.2", null, Capability.SAI))
          .isEqualTo(CapabilitySupport.UNSUPPORTED);
      assertThat(support(ClusterFlavor.AMAZON_KEYSPACES, "3.11.2", null, Capability.VECTOR_ANN))
          .isEqualTo(CapabilitySupport.UNSUPPORTED);
      assertThat(support(ClusterFlavor.AMAZON_KEYSPACES, "3.11.2", null, Capability.MATERIALIZED_VIEWS))
          .isEqualTo(CapabilitySupport.UNSUPPORTED);
      assertThat(support(ClusterFlavor.AMAZON_KEYSPACES, "3.11.2", null, Capability.UDF_UDA))
          .isEqualTo(CapabilitySupport.UNSUPPORTED);
      assertThat(support(ClusterFlavor.AMAZON_KEYSPACES, "3.11.2", null, Capability.COUNTERS))
          .isEqualTo(CapabilitySupport.UNSUPPORTED);
    }

    @Test
    void authorisesThroughIam() {
      assertThat(support(ClusterFlavor.AMAZON_KEYSPACES, "3.11.2", null, Capability.ROLES_PERMISSIONS))
          .isEqualTo(CapabilitySupport.PARTIAL);
      assertThat(
              CapabilityMatrix.forCluster(ClusterFlavor.AMAZON_KEYSPACES, "3.11.2", null)
                  .get(Capability.ROLES_PERMISSIONS)
                  .reason())
          .contains("IAM");
    }

    @Test
    void stillSupportsLwt() {
      assertThat(support(ClusterFlavor.AMAZON_KEYSPACES, "3.11.2", null, Capability.LWT))
          .isEqualTo(CapabilitySupport.SUPPORTED);
    }
  }

  @Nested
  class Scylla {

    @Test
    void hasViewsButNoSaiOrVectors() {
      assertThat(support(ClusterFlavor.SCYLLA, "3.0.8", null, Capability.MATERIALIZED_VIEWS))
          .isEqualTo(CapabilitySupport.SUPPORTED);
      assertThat(support(ClusterFlavor.SCYLLA, "3.0.8", null, Capability.SAI))
          .isEqualTo(CapabilitySupport.UNSUPPORTED);
      assertThat(support(ClusterFlavor.SCYLLA, "3.0.8", null, Capability.VECTOR_ANN))
          .isEqualTo(CapabilitySupport.UNSUPPORTED);
    }

    @Test
    void udfsArePartialBecauseTheyAreLuaOnly() {
      assertThat(support(ClusterFlavor.SCYLLA, "5.2.0", null, Capability.UDF_UDA))
          .isEqualTo(CapabilitySupport.PARTIAL);
    }

    @Test
    void virtualTablesDependOnTheRelease() {
      assertThat(support(ClusterFlavor.SCYLLA, "4.6.0", null, Capability.VIRTUAL_TABLES))
          .isEqualTo(CapabilitySupport.UNSUPPORTED);
      assertThat(support(ClusterFlavor.SCYLLA, "5.2.0", null, Capability.VIRTUAL_TABLES))
          .isEqualTo(CapabilitySupport.PARTIAL);
    }

    @Test
    void keepsTokenRangeScan() {
      assertThat(support(ClusterFlavor.SCYLLA, "5.2.0", null, Capability.TOKEN_RANGE_SCAN))
          .isEqualTo(CapabilitySupport.SUPPORTED);
    }
  }

  @Nested
  class Unrecognised {

    @Test
    @DisplayName("an unknown cluster reports UNKNOWN, not UNSUPPORTED - a different UI message")
    void everythingIsUnknown() {
      Map<Capability, CapabilityStatus> matrix = CapabilityMatrix.forCluster(null, null, null);

      assertThat(matrix.get(Capability.SAI).support()).isEqualTo(CapabilitySupport.UNKNOWN);
      assertThat(matrix.get(Capability.VECTOR_ANN).support()).isEqualTo(CapabilitySupport.UNKNOWN);
    }

    @Test
    @DisplayName("token-range scan is assumed available - guessing PLAIN_PAGING would be a 10x tax")
    void assumesTokenRangeScan() {
      assertThat(support(ClusterFlavor.UNKNOWN, null, null, Capability.TOKEN_RANGE_SCAN))
          .isEqualTo(CapabilitySupport.PARTIAL);
    }
  }
}
