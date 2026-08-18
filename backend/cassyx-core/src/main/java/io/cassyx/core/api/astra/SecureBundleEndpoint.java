package io.cassyx.core.api.astra;

import java.util.List;

/**
 * One datacenter node of the {@code POST /v2/databases/{id}/secureBundleURL?all=true} JSON array.
 *
 * @param downloadUrl the "default" bundle URL; pre-signed, so the GET carries no auth header
 */
public record SecureBundleEndpoint(
    String region, String downloadUrl, List<CustomDomainBundle> customDomainBundles) {

  public SecureBundleEndpoint {
    customDomainBundles =
        customDomainBundles == null ? List.of() : List.copyOf(customDomainBundles);
  }
}
