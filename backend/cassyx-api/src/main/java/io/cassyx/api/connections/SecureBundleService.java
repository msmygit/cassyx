package io.cassyx.api.connections;

import io.cassyx.api.connections.dto.AstraBundleDatacenterView;
import io.cassyx.api.connections.dto.AstraBundleDownloadRequest;
import io.cassyx.api.connections.dto.AstraDatabaseView;
import io.cassyx.api.connections.dto.ScbMode;
import io.cassyx.api.connections.dto.ScbType;
import io.cassyx.core.api.CoreFactory;
import io.cassyx.core.api.Secret;
import io.cassyx.core.api.SecretCipher;
import io.cassyx.core.api.astra.AstraDevOpsClient;
import io.cassyx.core.api.astra.AstraDevOpsException;
import io.cassyx.core.api.astra.ScbPathException;
import io.cassyx.core.api.astra.ScbPathResolver;
import io.cassyx.core.api.astra.ScbSelector;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * All three secure connect bundle acquisition modes of plan section 3.1.
 *
 * <p>Whichever mode produced it, a bundle ends up in exactly one place: encrypted in H2 alongside
 * the connection, materialised to a short-lived temp file only while a session is being built. That
 * is the fix for the prior art, which took the bundle as a local filesystem path and thereby forced
 * the backend and the bundle onto the same host.
 *
 * <p>The Astra token is write-only throughout: it arrives in a header or a body, is used for one
 * DevOps call, and appears in no response and no log line - including the error paths, which is
 * exactly where tokens leak in practice.
 */
@Service
public class SecureBundleService {

  private static final Logger LOG = LoggerFactory.getLogger(SecureBundleService.class);

  /** Bundles are ~12 KB; anything near this is not a secure connect bundle. */
  static final long MAX_BUNDLE_BYTES = 5L * 1024 * 1024;

  private final SecretCipher cipher;
  private final ScbPathResolver pathResolver;
  private final Clock clock;
  private final Function<Secret, AstraDevOpsClient> devOpsClientFactory;

  @Autowired
  public SecureBundleService(SecretCipher cipher, ScbPathResolver pathResolver) {
    this(cipher, pathResolver, Clock.systemUTC(), CoreFactory::astraDevOpsClient);
  }

  SecureBundleService(
      SecretCipher cipher,
      ScbPathResolver pathResolver,
      Clock clock,
      Function<Secret, AstraDevOpsClient> devOpsClientFactory) {
    this.cipher = cipher;
    this.pathResolver = pathResolver;
    this.clock = clock;
    this.devOpsClientFactory = devOpsClientFactory;
  }

  /* ------------------------------------------------------------------ DevOps proxy */

  /** The database picker, so no user ever types a database UUID (plan section 3.1, deviation 3). */
  public List<AstraDatabaseView> listDatabases(String astraToken) {
    return devOpsClientFactory.apply(Secret.of(astraToken)).listDatabases().stream()
        .map(AstraDatabaseView::from)
        .toList();
  }

  /** One entry per datacenter; populates the region and custom-domain dropdowns. */
  public List<AstraBundleDatacenterView> listBundles(String astraToken, String databaseId) {
    return devOpsClientFactory.apply(Secret.of(astraToken)).secureBundleEndpoints(databaseId).stream()
        .map(AstraBundleDatacenterView::from)
        .toList();
  }

  /**
   * Resolves, downloads server-side, validates and stores the bundle encrypted against the
   * connection.
   *
   * <p>Returns metadata only - never the bytes. The cache key is
   * {@code (databaseId, region, scbType, domain)}; {@code force} is the explicit "re-download"
   * action that exists because Astra rotates bundles and a stale one fails as an opaque TLS error
   * rather than an obvious one.
   */
  public ConnectionRow download(
      ConnectionRow row, String databaseId, AstraBundleDownloadRequest request) {
    ScbSelector selector =
        request.scbTypeOrDefault() == ScbType.CUSTOM
            ? ScbSelector.customDomain(request.region(), request.domain())
            : ScbSelector.defaultBundleIn(request.region());
    String cacheKey = selector.cacheKey(databaseId);

    if (!request.isForced()
        && row.scbBundle() != null
        && cacheKey.equals(row.scbCacheKey())) {
      LOG.debug("Reusing the cached secure connect bundle for connection {}", row.id());
      return row;
    }

    Path target = null;
    try {
      target = createTempBundleFile("cassyx-scb-download");
      devOpsClientFactory
          .apply(Secret.of(request.astraToken()))
          .downloadBundle(databaseId, selector, target);
      // Validate BEFORE storing: a bad bundle caught here is a clear 422, and the same bundle
      // caught at connect time is an unexplained TLS handshake failure.
      CoreFactory.verifySecureConnectBundle(target);
      byte[] bytes = Files.readAllBytes(target);
      store(
          row,
          bytes,
          "secure-connect-" + databaseId + ".zip",
          ScbMode.AUTO_DOWNLOAD,
          cacheKey);
      row.astraDatabaseId(databaseId)
          .scbRegion(blankToNull(request.region()))
          .scbType(request.scbTypeOrDefault().name())
          .scbCustomDomain(blankToNull(request.domain()));
      return row;
    } catch (IOException e) {
      throw new UncheckedIOException("Could not store the downloaded secure connect bundle", e);
    } finally {
      deleteQuietly(target);
    }
  }

  /* ------------------------------------------------------------------ storage */

  /**
   * Encrypts and stores an uploaded or downloaded bundle, after proving it really is one.
   *
   * @throws ScbPathException if the bytes are not a readable zip with the expected entries
   */
  public ConnectionRow store(
      ConnectionRow row, byte[] bytes, String fileName, ScbMode source, String cacheKey) {
    if (bytes == null || bytes.length == 0) {
      throw new ScbPathException("The uploaded secure connect bundle is empty");
    }
    if (bytes.length > MAX_BUNDLE_BYTES) {
      throw new ScbPathException(
          "That file is "
              + (bytes.length / 1024)
              + " KB. An Astra secure connect bundle is a few tens of kilobytes - this is probably "
              + "the wrong file.");
    }
    Path temp = null;
    try {
      temp = createTempBundleFile("cassyx-scb-verify");
      Files.write(temp, bytes);
      CoreFactory.verifySecureConnectBundle(temp);
    } catch (IOException e) {
      throw new UncheckedIOException("Could not verify the secure connect bundle", e);
    } finally {
      deleteQuietly(temp);
    }

    return row.scbBundle(cipher.encrypt(bytes))
        .scbBundleFetchedAt(clock.instant())
        .scbFileName(fileName)
        .scbSizeBytes((long) bytes.length)
        .scbSha256(sha256(bytes))
        .scbSource(source.name())
        .scbCacheKey(cacheKey)
        .scbValidated(true);
  }

  /** Forgets the stored bundle; the connection keeps its other settings. */
  public ConnectionRow remove(ConnectionRow row) {
    return row.scbBundle(null)
        .scbBundleFetchedAt(null)
        .scbFileName(null)
        .scbSizeBytes(null)
        .scbSha256(null)
        .scbSource(null)
        .scbCacheKey(null)
        .scbValidated(false);
  }

  /* ------------------------------------------------------------------ materialisation */

  /**
   * Produces the local file the driver's {@code withCloudSecureConnectBundle} needs.
   *
   * <p>{@code PATH} mode resolves against the {@code CASSYX_SCB_PATH_ROOT} allow-list - an
   * unrestricted server-side path parameter is an arbitrary-file-read primitive, so that check is
   * part of the contract rather than a nicety. Every other mode decrypts the stored bytes into a
   * temp file created with owner-only permissions, which the caller deletes as soon as the session
   * is built.
   *
   * @return the file, or null when this connection needs no bundle
   */
  public Path materialize(ConnectionRow row) {
    if (ConnectionMapper.scbMode(row.scbAcquisitionMode()) == ScbMode.PATH && row.scbBundle() == null) {
      return pathResolver.resolve(row.scbPath());
    }
    if (row.scbBundle() == null) {
      return null;
    }
    try {
      Path file = createTempBundleFile("cassyx-scb");
      Files.write(file, cipher.decrypt(row.scbBundle()));
      return file;
    } catch (IOException e) {
      throw new UncheckedIOException("Could not materialise the secure connect bundle", e);
    }
  }

  /**
   * Deletes a file produced by {@link #materialize}, unless it is the user's own mounted bundle.
   *
   * <p>A decrypted bundle on disk is a plaintext credential; leaving it there would undo the point
   * of encrypting it in the first place.
   */
  public void discard(ConnectionRow row, Path file) {
    if (file == null) {
      return;
    }
    if (ConnectionMapper.scbMode(row.scbAcquisitionMode()) == ScbMode.PATH
        && row.scbBundle() == null) {
      // Not ours - it is the operator's mounted volume or secret.
      return;
    }
    deleteQuietly(file);
  }

  /* ------------------------------------------------------------------ helpers */

  private static Path createTempBundleFile(String prefix) throws IOException {
    Path file;
    try {
      // Owner-only from creation, not chmod-ed afterwards: there must be no window in which the
      // decrypted bundle is world-readable.
      Set<PosixFilePermission> ownerOnly = PosixFilePermissions.fromString("rw-------");
      file = Files.createTempFile(prefix, ".zip", PosixFilePermissions.asFileAttribute(ownerOnly));
    } catch (UnsupportedOperationException e) {
      // Non-POSIX filesystem (Windows); fall back and tighten afterwards.
      file = Files.createTempFile(prefix, ".zip");
      file.toFile().setReadable(false, false);
      file.toFile().setReadable(true, true);
      file.toFile().setWritable(false, false);
      file.toFile().setWritable(true, true);
    }
    file.toFile().deleteOnExit();
    return file;
  }

  private static void deleteQuietly(Path file) {
    if (file == null) {
      return;
    }
    try {
      Files.deleteIfExists(file);
    } catch (IOException e) {
      LOG.warn("Could not delete the temporary secure connect bundle {}", file);
    }
  }

  static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required by every JVM", e);
    }
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  /** Re-exported so the controller can answer 502 with the DevOps client's actionable message. */
  public static boolean isUnreachable(AstraDevOpsException e) {
    return e.statusCode() == 0;
  }
}
