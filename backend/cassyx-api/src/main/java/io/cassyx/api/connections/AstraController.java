package io.cassyx.api.connections;

import io.cassyx.api.connections.dto.AstraBundleDatacenterView;
import io.cassyx.api.connections.dto.AstraBundleDownloadRequest;
import io.cassyx.api.connections.dto.AstraDatabaseView;
import io.cassyx.api.connections.dto.ScbMode;
import io.cassyx.api.connections.dto.SecureConnectBundleInfo;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Astra DevOps helpers of plan section 3.1 - the endpoints that make {@code AUTO_DOWNLOAD} the
 * default rather than an aspiration.
 *
 * <p><b>The token is in a header or a body, never a query parameter.</b> Query strings land in
 * access logs, proxy logs, browser history and {@code Referer} headers, and an Astra application
 * token is a full-privilege credential. It is also never persisted by the two proxy calls and never
 * echoed back - including in error responses, which is precisely where tokens leak in practice.
 */
@RestController
public class AstraController {

  private final SecureBundleService bundles;
  private final ConnectionService connections;

  public AstraController(SecureBundleService bundles, ConnectionService connections) {
    this.bundles = bundles;
    this.connections = connections;
  }

  /** The database picker: name, id, status and regions, so no UUID is ever typed. */
  @GetMapping("/api/astra/databases")
  public List<AstraDatabaseView> listAstraDatabases(
      @RequestHeader("X-Astra-Token") String astraToken) {
    return bundles.listDatabases(astraToken);
  }

  /** One entry per datacenter; feeds the region and custom-domain dropdowns. */
  @GetMapping("/api/astra/databases/{databaseId}/bundles")
  public List<AstraBundleDatacenterView> listAstraBundles(
      @PathVariable String databaseId, @RequestHeader("X-Astra-Token") String astraToken) {
    return bundles.listBundles(astraToken, databaseId);
  }

  /**
   * Downloads the bundle server-side, validates it, and stores it encrypted against the connection.
   *
   * <p>Returns metadata only - never the bytes, and never the temp path they were written through.
   */
  @PostMapping("/api/astra/databases/{databaseId}/bundle/download")
  public SecureConnectBundleInfo downloadAstraBundle(
      @PathVariable String databaseId, @Valid @RequestBody AstraBundleDownloadRequest request) {
    ConnectionRow row = connections.require(request.connectionId());
    bundles.download(row, databaseId, request);
    row.scbAcquisitionMode(ScbMode.AUTO_DOWNLOAD.name());
    connections.save(row);
    return new SecureConnectBundleInfo(
        row.scbFileName(),
        row.scbSizeBytes() == null ? 0L : row.scbSizeBytes(),
        row.scbSha256(),
        row.scbBundleFetchedAt(),
        ScbMode.AUTO_DOWNLOAD,
        row.scbRegion(),
        ConnectionMapper.scbType(row.scbType()),
        row.scbCustomDomain(),
        row.scbCacheKey(),
        row.scbValidated());
  }
}
