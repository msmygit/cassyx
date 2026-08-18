package io.cassyx.api.connections.dto;

import io.cassyx.core.api.ScbAcquisitionMode;

/**
 * How the Astra secure connect bundle is acquired - the contract's {@code ScbMode} (plan section 3).
 *
 * <p>A separate enum from {@link ScbAcquisitionMode} only because the wire contract and the core
 * library must be free to move independently; {@link #toCore()} is the single conversion point.
 */
public enum ScbMode {
  /** Default: token only, fetched through the DevOps API. No UUID typing, no file hunting. */
  AUTO_DOWNLOAD,
  /** Air-gapped, restricted egress, or a hand-issued bundle. Multipart upload, stored encrypted. */
  UPLOAD,
  /** Docker / Kubernetes volume or secret mounts. Resolved on the SERVER, under the allow-list. */
  PATH;

  public ScbAcquisitionMode toCore() {
    return switch (this) {
      case AUTO_DOWNLOAD -> ScbAcquisitionMode.AUTO_DOWNLOAD;
      case UPLOAD -> ScbAcquisitionMode.UPLOAD;
      case PATH -> ScbAcquisitionMode.PATH;
    };
  }

  public static ScbMode fromCore(ScbAcquisitionMode mode) {
    if (mode == null) {
      return AUTO_DOWNLOAD;
    }
    return switch (mode) {
      case AUTO_DOWNLOAD -> AUTO_DOWNLOAD;
      case UPLOAD -> UPLOAD;
      case PATH -> PATH;
    };
  }
}
