package io.cassyx.core.api;

import io.cassyx.core.api.astra.ScbSelector;
import java.util.Objects;

/**
 * Astra DB connection settings (plan section 3 / 3.1).
 *
 * @param token the {@code AstraCS:...} token. Write-only, encrypted at rest, masked in the UI and
 *     NEVER logged - see {@link io.cassyx.core.api.astra.AstraDevOpsClient}.
 * @param databaseId the Astra database UUID; required for {@link ScbAcquisitionMode#AUTO_DOWNLOAD}
 * @param acquisitionMode how the secure connect bundle is obtained
 * @param selector region / bundle-type selection for AUTO_DOWNLOAD
 * @param bundlePath server-side path for {@link ScbAcquisitionMode#PATH}; resolved against
 *     {@code CASSYX_SCB_PATH_ROOT}
 * @param uploadedBundleId storage handle for {@link ScbAcquisitionMode#UPLOAD}
 */
public record AstraConnection(
    Secret token,
    String databaseId,
    ScbAcquisitionMode acquisitionMode,
    ScbSelector selector,
    String bundlePath,
    String uploadedBundleId) {

  public AstraConnection {
    Objects.requireNonNull(token, "token");
    if (token.isEmpty()) {
      throw new IllegalArgumentException("Astra token is required");
    }
    acquisitionMode = acquisitionMode == null ? ScbAcquisitionMode.AUTO_DOWNLOAD : acquisitionMode;
    selector = selector == null ? ScbSelector.defaultBundle() : selector;
    if (acquisitionMode == ScbAcquisitionMode.AUTO_DOWNLOAD && isBlank(databaseId)) {
      throw new IllegalArgumentException("databaseId is required for AUTO_DOWNLOAD");
    }
    if (acquisitionMode == ScbAcquisitionMode.PATH && isBlank(bundlePath)) {
      throw new IllegalArgumentException("bundlePath is required for PATH");
    }
    if (acquisitionMode == ScbAcquisitionMode.UPLOAD && isBlank(uploadedBundleId)) {
      throw new IllegalArgumentException("uploadedBundleId is required for UPLOAD");
    }
  }

  private static boolean isBlank(String s) {
    return s == null || s.isBlank();
  }

  /** Redacts the token - this record is embedded in {@link ConnectionSpec#toString()}. */
  @Override
  public String toString() {
    return "AstraConnection[databaseId="
        + databaseId
        + ", acquisitionMode="
        + acquisitionMode
        + ", selector="
        + selector
        + ", token=<redacted>]";
  }
}
