package io.cassyx.core.api.astra;

import java.nio.file.Path;

/**
 * Resolves a {@link io.cassyx.core.api.ScbAcquisitionMode#PATH} bundle location on the SERVER host.
 *
 * <p>An unrestricted server-side path parameter is an arbitrary-file-read primitive, so
 * implementations canonicalize the candidate, reject anything outside the configured allow-list
 * root ({@code CASSYX_SCB_PATH_ROOT}, default {@code /etc/cassyx/scb}), and verify the file is a
 * readable zip carrying the expected bundle entries before a connection is attempted - a wrong
 * path must fail with a clear message, not a TLS error (plan section 3).
 */
public interface ScbPathResolver {

  /** Default allow-list root. */
  String DEFAULT_ROOT = "/etc/cassyx/scb";

  /** Environment variable holding the allow-list root. */
  String ROOT_ENV_VAR = "CASSYX_SCB_PATH_ROOT";

  /**
   * @param candidate a path, absolute or relative to the allow-list root
   * @return the canonical, validated path
   * @throws ScbPathException if the path escapes the root, is missing/unreadable, or is not a
   *     secure connect bundle
   */
  Path resolve(String candidate);
}
