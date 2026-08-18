package io.cassyx.api.connections.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.cassyx.core.api.astra.CustomDomainBundle;
import io.cassyx.core.api.astra.SecureBundleEndpoint;
import java.util.List;

/**
 * One element of the {@code secureBundleURL} response - one node per datacenter. Populates the UI's
 * region dropdown, so region is never free text.
 *
 * <p>{@code downloadURL} keeps the DevOps API's own spelling because the contract does; these URLs
 * are short-lived pre-signed HTTPS links that carry no auth header, and the actual download happens
 * server-side.
 */
public record AstraBundleDatacenterView(
    String region,
    @JsonProperty("downloadURL") String downloadUrl,
    String datacenterId,
    List<AstraCustomDomainBundleView> customDomainBundles) {

  public AstraBundleDatacenterView {
    customDomainBundles = customDomainBundles == null ? List.of() : List.copyOf(customDomainBundles);
  }

  public static AstraBundleDatacenterView from(SecureBundleEndpoint endpoint) {
    List<AstraCustomDomainBundleView> custom =
        endpoint.customDomainBundles().stream()
            .map(AstraBundleDatacenterView::toView)
            .toList();
    return new AstraBundleDatacenterView(
        endpoint.region(), endpoint.downloadUrl(), null, custom);
  }

  private static AstraCustomDomainBundleView toView(CustomDomainBundle bundle) {
    return new AstraCustomDomainBundleView(bundle.domain(), bundle.downloadUrl());
  }
}
