package io.cassyx.core.api.astra;

import java.nio.file.Path;
import java.util.List;

/**
 * Client for the Astra DevOps API (plan section 3.1). Plain Java over {@code java.net.http}; no
 * Spring, no web layer.
 *
 * <p>Given only an {@code AstraCS:...} token this enumerates databases and downloads secure connect
 * bundles, which removes the worst part of connecting to Astra: hunting for a bundle in the console
 * and typing a database UUID.
 *
 * <p><b>Security contract:</b> implementations must never log the token, on any path, including
 * error paths. {@code AstraDevOpsClientTokenLoggingTest} in this module enforces that.
 */
public interface AstraDevOpsClient {

  /** Astra DevOps API base URL. */
  String DEFAULT_BASE_URL = "https://api.astra.datastax.com";

  /** {@code GET /v2/databases} - powers the database picker (id, name, status, regions). */
  List<AstraDatabase> listDatabases();

  /**
   * {@code POST /v2/databases/{databaseId}/secureBundleURL?all=true} - one element per datacenter.
   * Also populates the region dropdown, so region is never free text.
   */
  List<SecureBundleEndpoint> secureBundleEndpoints(String databaseId);

  /**
   * Resolves the download URL for {@code selector} without fetching the bundle body.
   *
   * @throws AstraDevOpsException if no element matches the requested region or custom domain
   */
  String resolveBundleUrl(String databaseId, ScbSelector selector);

  /**
   * Downloads the bundle zip to {@code target} and returns it. The download URL is pre-signed, so
   * no {@code Authorization} header is sent on this request.
   */
  Path downloadBundle(String databaseId, ScbSelector selector, Path target);
}
