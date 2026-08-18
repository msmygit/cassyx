package io.cassyx.core.api.astra;

import java.util.List;
import java.util.Locale;

/**
 * The selection algorithm of plan section 3.1, kept as a pure function so it is unit-testable with
 * no network and reusable by any {@link AstraDevOpsClient} implementation.
 *
 * <pre>
 *   region present -&gt; first element whose region equals it, case-insensitively
 *   region absent  -&gt; first element of the array
 *   then: DEFAULT -&gt; element.downloadURL
 *         CUSTOM  -&gt; the customDomainBundles entry whose domain matches
 * </pre>
 */
public final class SecureBundleSelection {

  private SecureBundleSelection() {}

  /** @throws AstraDevOpsException when nothing matches; the message never contains credentials */
  public static SecureBundleEndpoint selectEndpoint(
      List<SecureBundleEndpoint> endpoints, ScbSelector selector) {
    if (endpoints == null || endpoints.isEmpty()) {
      throw new AstraDevOpsException(
          "Astra returned no secure connect bundle endpoints for this database");
    }
    if (selector.region() == null) {
      return endpoints.get(0);
    }
    String wanted = selector.region().toLowerCase(Locale.ROOT);
    return endpoints.stream()
        .filter(e -> e.region() != null && e.region().toLowerCase(Locale.ROOT).equals(wanted))
        .findFirst()
        .orElseThrow(
            () ->
                new AstraDevOpsException(
                    "No secure connect bundle for region '"
                        + selector.region()
                        + "'; available regions: "
                        + endpoints.stream().map(SecureBundleEndpoint::region).toList()));
  }

  /** Resolves the download URL for the selected endpoint. */
  public static String selectDownloadUrl(
      List<SecureBundleEndpoint> endpoints, ScbSelector selector) {
    SecureBundleEndpoint endpoint = selectEndpoint(endpoints, selector);
    return switch (selector.scbType()) {
      case DEFAULT -> {
        if (endpoint.downloadUrl() == null || endpoint.downloadUrl().isBlank()) {
          throw new AstraDevOpsException(
              "Astra returned no downloadURL for region '" + endpoint.region() + "'");
        }
        yield endpoint.downloadUrl();
      }
      case CUSTOM -> {
        String wanted = selector.domain().toLowerCase(Locale.ROOT);
        yield endpoint.customDomainBundles().stream()
            .filter(b -> b.domain() != null && b.domain().toLowerCase(Locale.ROOT).equals(wanted))
            .map(CustomDomainBundle::downloadUrl)
            .findFirst()
            .orElseThrow(
                () ->
                    new AstraDevOpsException(
                        "No custom domain bundle for domain '"
                            + selector.domain()
                            + "' in region '"
                            + endpoint.region()
                            + "'; available domains: "
                            + endpoint.customDomainBundles().stream()
                                .map(CustomDomainBundle::domain)
                                .toList()));
      }
    };
  }
}
