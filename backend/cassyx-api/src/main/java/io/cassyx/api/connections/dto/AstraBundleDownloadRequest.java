package io.cassyx.api.connections.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Selection uses TWO ORTHOGONAL inputs: {@code region} (optional) and {@code scbType}
 * ({@code default} | {@code custom}). See {@link ScbType} for why there is no third value.
 *
 * @param astraToken write-only. In the body rather than a header because this call also persists
 *     the association; it is still never in a URL or query string.
 * @param force bypasses the {@code (databaseId, region, scbType, domain)} cache - the explicit
 *     "re-download bundle" action, needed because Astra rotates bundles and a stale one surfaces as
 *     a confusing TLS failure rather than an obvious error
 */
public record AstraBundleDownloadRequest(
    @NotBlank String connectionId,
    @NotBlank @Pattern(regexp = "^AstraCS:.+$", message = "must be an AstraCS: token")
        String astraToken,
    String region,
    ScbType scbType,
    String domain,
    Boolean force) {

  public boolean isForced() {
    return Boolean.TRUE.equals(force);
  }

  public ScbType scbTypeOrDefault() {
    return scbType == null ? ScbType.DEFAULT : scbType;
  }

  @Override
  public String toString() {
    return "AstraBundleDownloadRequest[connectionId="
        + connectionId
        + ", region="
        + region
        + ", scbType="
        + scbType
        + ", astraToken=<redacted>]";
  }
}
