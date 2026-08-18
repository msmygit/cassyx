package io.cassyx.core.api.astra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Plan section 3.1 - selection algorithm and the deviations from the reference implementation. */
class SecureBundleSelectionTest {

  private static final List<SecureBundleEndpoint> ENDPOINTS =
      List.of(
          new SecureBundleEndpoint(
              "us-east1",
              "https://example.invalid/us-east1.zip",
              List.of(new CustomDomainBundle("db.example.com", "https://example.invalid/cd1.zip"))),
          new SecureBundleEndpoint(
              "eu-west1", "https://example.invalid/eu-west1.zip", List.of()));

  @Test
  void takesFirstElementWhenNoRegionRequested() {
    assertThat(SecureBundleSelection.selectDownloadUrl(ENDPOINTS, ScbSelector.defaultBundle()))
        .isEqualTo("https://example.invalid/us-east1.zip");
  }

  @Test
  void matchesRegionCaseInsensitively() {
    assertThat(
            SecureBundleSelection.selectDownloadUrl(
                ENDPOINTS, ScbSelector.defaultBundleIn("EU-West1")))
        .isEqualTo("https://example.invalid/eu-west1.zip");
  }

  @Test
  void regionAndTypeAreOrthogonal() {
    // The reference implementation's three-valued type enum would have made this impossible:
    // a custom-domain bundle in a specific region.
    assertThat(
            SecureBundleSelection.selectDownloadUrl(
                ENDPOINTS, ScbSelector.customDomain("us-east1", "DB.example.com")))
        .isEqualTo("https://example.invalid/cd1.zip");
  }

  @Test
  void failsClearlyWhenRegionOrDomainIsUnknown() {
    assertThatThrownBy(
            () ->
                SecureBundleSelection.selectDownloadUrl(
                    ENDPOINTS, ScbSelector.defaultBundleIn("ap-south1")))
        .isInstanceOf(AstraDevOpsException.class)
        .hasMessageContaining("ap-south1")
        .hasMessageContaining("us-east1");

    assertThatThrownBy(
            () ->
                SecureBundleSelection.selectDownloadUrl(
                    ENDPOINTS, ScbSelector.customDomain("eu-west1", "nope.example.com")))
        .isInstanceOf(AstraDevOpsException.class)
        .hasMessageContaining("nope.example.com");
  }

  @Test
  void failsOnEmptyResponse() {
    assertThatThrownBy(
            () -> SecureBundleSelection.selectEndpoint(List.of(), ScbSelector.defaultBundle()))
        .isInstanceOf(AstraDevOpsException.class);
    assertThatThrownBy(
            () -> SecureBundleSelection.selectEndpoint(null, ScbSelector.defaultBundle()))
        .isInstanceOf(AstraDevOpsException.class);
  }

  @Test
  void failsWhenSelectedEndpointHasNoDefaultUrl() {
    List<SecureBundleEndpoint> broken =
        List.of(new SecureBundleEndpoint("us-east1", null, List.of()));

    assertThatThrownBy(
            () -> SecureBundleSelection.selectDownloadUrl(broken, ScbSelector.defaultBundle()))
        .isInstanceOf(AstraDevOpsException.class)
        .hasMessageContaining("downloadURL");
  }
}
