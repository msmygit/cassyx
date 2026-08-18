package io.cassyx.core.api;

/** How a secure connect bundle is obtained (plan section 3, "SCB acquisition modes"). */
public enum ScbAcquisitionMode {
  /** Default: fetch from the Astra DevOps API given only the token (plan section 3.1). */
  AUTO_DOWNLOAD,
  /** Multipart upload from the browser; stored encrypted. For air-gapped / restricted egress. */
  UPLOAD,
  /**
   * Server-side filesystem path, for Docker/K8s volume or secret mounts. Resolved against the
   * {@code CASSYX_SCB_PATH_ROOT} allow-list root - an unrestricted path is an arbitrary-file-read
   * primitive.
   */
  PATH
}
