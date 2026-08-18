package io.cassyx.api.connections.dto;

/**
 * Astra-specific write model (plan sections 3 and 3.1).
 *
 * <p>The token is a full-privilege credential: write-only, encrypted at rest, masked in the UI and
 * never logged - including on DevOps API error paths, which is exactly where tokens usually leak.
 *
 * @param region optional, matched case-insensitively; ORTHOGONAL to {@code scbType}
 * @param domain required if and only if {@code scbType} is {@code custom}
 * @param scbPath required for {@code PATH} mode; resolved on the BACKEND host against
 *     {@code CASSYX_SCB_PATH_ROOT}
 */
public record AstraSettings(
    String astraToken,
    ScbMode scbMode,
    String databaseId,
    String region,
    ScbType scbType,
    String domain,
    String scbPath) {

  public ScbMode scbModeOrDefault() {
    return scbMode == null ? ScbMode.AUTO_DOWNLOAD : scbMode;
  }

  public ScbType scbTypeOrDefault() {
    return scbType == null ? ScbType.DEFAULT : scbType;
  }
}
