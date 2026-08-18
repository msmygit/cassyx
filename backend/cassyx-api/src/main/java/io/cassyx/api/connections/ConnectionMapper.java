package io.cassyx.api.connections;

import io.cassyx.api.connections.dto.AstraInfo;
import io.cassyx.api.connections.dto.ConnectionMode;
import io.cassyx.api.connections.dto.ConnectionRequest;
import io.cassyx.api.connections.dto.ConnectionResponse;
import io.cassyx.api.connections.dto.ContactPoint;
import io.cassyx.api.connections.dto.ScbMode;
import io.cassyx.api.connections.dto.ScbType;
import io.cassyx.api.connections.dto.SecureConnectBundleInfo;
import io.cassyx.api.connections.dto.SshTunnelInfo;
import io.cassyx.api.connections.dto.SslInfo;
import io.cassyx.core.api.AstraConnection;
import io.cassyx.core.api.ClusterFlavor;
import io.cassyx.core.api.ConnectionSpec;
import io.cassyx.core.api.ScbAcquisitionMode;
import io.cassyx.core.api.Secret;
import io.cassyx.core.api.SecretCipher;
import io.cassyx.core.api.SshTunnelSpec;
import io.cassyx.core.api.SslSpec;
import io.cassyx.core.api.astra.ScbSelector;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * Translates between the wire contract, the stored row, and the core {@link ConnectionSpec}.
 *
 * <p>The response direction is written out field by field on purpose. A reflective or
 * "copy everything" mapper would mean that adding a credential column to {@link ConnectionRow}
 * silently adds it to {@link ConnectionResponse} - and the contract's central rule is that no
 * secret value ever appears in a response. Here, leaking one takes a deliberate edit to this file.
 */
@Component
public class ConnectionMapper {

  private static final String LIST_SEPARATOR = ",";

  private final SecretCipher cipher;

  public ConnectionMapper(SecretCipher cipher) {
    this.cipher = cipher;
  }

  /* ------------------------------------------------------------------ row -> response */

  /** @param connected whether the session registry currently holds a live session for this row */
  public ConnectionResponse toResponse(ConnectionRow row, boolean connected) {
    return new ConnectionResponse(
        row.id(),
        row.name(),
        mode(row),
        row.description(),
        parseContactPoints(row.contactPoints()),
        row.localDatacenter(),
        row.username(),
        row.password() != null,
        row.protocolVersionName(),
        row.defaultKeyspace(),
        row.requestTimeoutMillis(),
        row.advancedConfig() != null && !row.advancedConfig().isBlank(),
        astraInfo(row),
        sshInfo(row),
        sslInfo(row),
        splitList(row.tags()),
        connected,
        row.createdAt(),
        row.updatedAt(),
        row.lastConnectedAt());
  }

  private static ConnectionMode mode(ConnectionRow row) {
    try {
      return ConnectionMode.valueOf(row.mode());
    } catch (IllegalArgumentException | NullPointerException e) {
      return ConnectionMode.CASSANDRA;
    }
  }

  private static AstraInfo astraInfo(ConnectionRow row) {
    if (mode(row) != ConnectionMode.ASTRA) {
      return null;
    }
    return new AstraInfo(
        row.astraToken() != null,
        scbMode(row.scbAcquisitionMode()),
        row.astraDatabaseId(),
        row.astraDatabaseName(),
        row.scbRegion(),
        scbType(row.scbType()),
        row.scbCustomDomain(),
        row.scbPath(),
        bundleInfo(row));
  }

  private static SecureConnectBundleInfo bundleInfo(ConnectionRow row) {
    if (row.scbBundle() == null) {
      return null;
    }
    return new SecureConnectBundleInfo(
        row.scbFileName(),
        row.scbSizeBytes() == null ? 0L : row.scbSizeBytes(),
        row.scbSha256(),
        row.scbBundleFetchedAt(),
        scbMode(row.scbSource()),
        row.scbRegion(),
        scbType(row.scbType()),
        row.scbCustomDomain(),
        row.scbCacheKey(),
        row.scbValidated());
  }

  private static SshTunnelInfo sshInfo(ConnectionRow row) {
    if (!row.sshEnabled()) {
      return SshTunnelInfo.disabled();
    }
    return new SshTunnelInfo(
        true,
        row.sshHost(),
        row.sshPort(),
        row.sshUsername(),
        row.sshPassword() != null,
        row.sshPrivateKey() != null,
        row.sshRemoteHost(),
        row.sshRemotePort(),
        row.sshStrictHostKeyChecking());
  }

  private static SslInfo sslInfo(ConnectionRow row) {
    return new SslInfo(
        row.sslEnabled(),
        row.sslHostnameValidation(),
        row.truststore() != null,
        row.truststoreFileName(),
        row.truststorePassword() != null,
        row.keystore() != null,
        row.keystoreFileName(),
        row.keystorePassword() != null,
        splitList(row.sslCipherSuites()));
  }

  /* ------------------------------------------------------------------ request -> row */

  /**
   * Applies a request onto a row.
   *
   * <p>Secret handling follows the contract exactly: <b>null preserves</b> the stored value and
   * <b>empty string clears</b> it. Without that asymmetry the UI could not render an edit form,
   * because it never receives the secret to send back.
   *
   * @param existing the stored row on update, or a fresh row on create
   */
  public ConnectionRow apply(ConnectionRequest request, ConnectionRow existing) {
    validate(request);
    ConnectionRow row = existing;
    row.name(request.name().trim())
        .mode(request.mode().name())
        .description(blankToNull(request.description()))
        .contactPoints(joinContactPoints(request.contactPoints()))
        .localDatacenter(blankToNull(request.localDatacenter()))
        .protocolVersionName(blankToNull(request.protocolVersion()))
        .username(blankToNull(request.username()))
        .defaultKeyspace(blankToNull(request.defaultKeyspace()))
        .requestTimeoutMillis(request.requestTimeoutMillis())
        .tags(joinList(request.tags()));

    row.password(applySecret(request.password(), row::password));

    if (request.advancedConfig() != null) {
      row.advancedConfig(request.advancedConfig().isEmpty() ? null : request.advancedConfig());
    }

    applyAstra(request, row);
    applySsh(request, row);
    applySsl(request, row);
    return row;
  }

  private void applyAstra(ConnectionRequest request, ConnectionRow row) {
    if (request.mode() != ConnectionMode.ASTRA) {
      return;
    }
    var astra = request.astra();
    if (astra == null) {
      throw new ConnectionValidationException("astra", "Astra settings are required in ASTRA mode");
    }
    row.astraToken(applySecret(astra.astraToken(), row::astraToken))
        .astraDatabaseId(blankToNull(astra.databaseId()))
        .scbAcquisitionMode(astra.scbModeOrDefault().name())
        .scbRegion(blankToNull(astra.region()))
        .scbType(astra.scbTypeOrDefault().name())
        .scbCustomDomain(blankToNull(astra.domain()))
        .scbPath(blankToNull(astra.scbPath()));
  }

  private void applySsh(ConnectionRequest request, ConnectionRow row) {
    var ssh = request.ssh();
    if (ssh == null) {
      return;
    }
    row.sshEnabled(ssh.isEnabled())
        .sshHost(blankToNull(ssh.host()))
        .sshPort(ssh.port())
        .sshUsername(blankToNull(ssh.username()))
        .sshLocalPort(ssh.localPort())
        .sshRemoteHost(blankToNull(ssh.remoteHost()))
        .sshRemotePort(ssh.remotePort())
        .sshStrictHostKeyChecking(ssh.strictHostKeyCheckingOrDefault())
        .sshKnownHostsEntry(blankToNull(ssh.knownHostsEntry()));
    row.sshPassword(applySecret(ssh.password(), row::sshPassword));
    row.sshPrivateKey(applySecret(ssh.privateKey(), row::sshPrivateKey));
    row.sshPrivateKeyPassphrase(
        applySecret(ssh.privateKeyPassphrase(), row::sshPrivateKeyPassphrase));
  }

  private void applySsl(ConnectionRequest request, ConnectionRow row) {
    var ssl = request.ssl();
    if (ssl == null) {
      return;
    }
    row.sslEnabled(ssl.isEnabled())
        .sslHostnameValidation(ssl.hostnameValidationOrDefault())
        .sslCipherSuites(joinList(ssl.cipherSuites()));
    row.truststorePassword(applySecret(ssl.truststorePassword(), row::truststorePassword));
    row.keystorePassword(applySecret(ssl.keystorePassword(), row::keystorePassword));
  }

  /** null preserves, "" clears, anything else replaces. */
  private io.cassyx.core.api.EncryptedValue applySecret(
      String supplied, Supplier<io.cassyx.core.api.EncryptedValue> stored) {
    if (supplied == null) {
      return stored.get();
    }
    return supplied.isEmpty() ? null : cipher.encryptString(supplied);
  }

  /* ------------------------------------------------------------------ row -> spec */

  /**
   * Decrypts the stored credentials into the core spec used to build a session.
   *
   * <p>This is the ONE place plaintext credentials exist in the API module, and only for as long as
   * it takes the driver to build a session. {@link ConnectionSpec#toString()} redacts, so the spec
   * itself is safe to carry into logs and job records.
   */
  public ConnectionSpec toSpec(ConnectionRow row) {
    ConnectionSpec.Builder builder =
        ConnectionSpec.builder(row.name())
            .contactPoints(
                parseContactPoints(row.contactPoints()).stream()
                    .map(ContactPoint::toHostPort)
                    .toList())
            .localDatacenter(row.localDatacenter())
            .protocolVersion(row.protocolVersionName())
            .defaultKeyspace(row.defaultKeyspace())
            .credentials(row.username(), decrypt(row.password()))
            .advancedConfig(row.advancedConfig());

    if (row.requestTimeoutMillis() != null && row.requestTimeoutMillis() > 0) {
      builder.requestTimeout(Duration.ofMillis(row.requestTimeoutMillis()));
    }
    if (ConnectionMode.ASTRA.name().equals(row.mode())) {
      builder.astra(toAstraConnection(row));
    }
    if (row.sslEnabled()) {
      builder.ssl(
          new SslSpec(
              true,
              row.sslHostnameValidation(),
              decryptBytes(row.truststore()),
              decrypt(row.truststorePassword()),
              decryptBytes(row.keystore()),
              decrypt(row.keystorePassword()),
              splitList(row.sslCipherSuites())));
    }
    if (row.sshEnabled()) {
      builder.ssh(toSshSpec(row));
    }
    return builder.build();
  }

  private AstraConnection toAstraConnection(ConnectionRow row) {
    Secret token = decrypt(row.astraToken());
    if (token.isEmpty()) {
      throw new ConnectionValidationException(
          "astra.astraToken", "This Astra connection has no stored token; re-enter it to connect.");
    }
    ScbAcquisitionMode mode = scbMode(row.scbAcquisitionMode()).toCore();
    ScbType type = scbType(row.scbType());
    ScbSelector selector =
        type == ScbType.CUSTOM
            ? ScbSelector.customDomain(row.scbRegion(), row.scbCustomDomain())
            : ScbSelector.defaultBundleIn(row.scbRegion());
    // A stored bundle satisfies AUTO_DOWNLOAD and UPLOAD alike, so the record's own per-mode
    // requirements are relaxed to "we have something to connect with".
    String uploadedBundleId = row.scbBundle() == null ? null : row.id();
    if (mode == ScbAcquisitionMode.UPLOAD && uploadedBundleId == null) {
      throw new ConnectionValidationException(
          "astra.scbMode",
          "No secure connect bundle has been uploaded for this connection yet.");
    }
    return new AstraConnection(
        token,
        row.astraDatabaseId(),
        mode,
        selector,
        row.scbPath(),
        uploadedBundleId);
  }

  private SshTunnelSpec toSshSpec(ConnectionRow row) {
    try {
      return new SshTunnelSpec(
          row.sshHost(),
          row.sshPort() == null ? 0 : row.sshPort(),
          row.sshUsername(),
          decrypt(row.sshPassword()),
          decrypt(row.sshPrivateKey()),
          decrypt(row.sshPrivateKeyPassphrase()),
          row.sshLocalPort() == null ? 0 : row.sshLocalPort(),
          row.sshRemoteHost(),
          row.sshRemotePort() == null ? 0 : row.sshRemotePort(),
          row.sshStrictHostKeyChecking(),
          row.sshKnownHostsEntry());
    } catch (IllegalArgumentException e) {
      throw new ConnectionValidationException("ssh", e.getMessage());
    }
  }

  /** The probe hint: an Astra connection is Astra even though it looks like Cassandra over CQL. */
  public ClusterFlavor flavorHint(ConnectionRow row) {
    ConnectionMode mode = mode(row);
    return switch (mode) {
      case ASTRA -> ClusterFlavor.ASTRA;
      case DSE -> ClusterFlavor.DSE;
      case CASSANDRA, ADVANCED -> null;
    };
  }

  public Secret decrypt(io.cassyx.core.api.EncryptedValue value) {
    return value == null ? Secret.empty() : cipher.decryptSecret(value);
  }

  public byte[] decryptBytes(io.cassyx.core.api.EncryptedValue value) {
    return value == null ? null : cipher.decrypt(value);
  }

  /* ------------------------------------------------------------------ validation */

  /**
   * Mode-dependent requirements the JSON schema cannot express.
   *
   * <p>Each failure names the field so the form can highlight it; a banner saying "invalid
   * connection" on a nine-field form is not a usable error.
   */
  public void validate(ConnectionRequest request) {
    switch (request.mode()) {
      case CASSANDRA, DSE -> {
        if (request.contactPoints().isEmpty()) {
          throw new ConnectionValidationException(
              "contactPoints", "at least one contact point is required");
        }
        if (isBlank(request.localDatacenter())) {
          throw new ConnectionValidationException(
              "localDatacenter",
              "the local datacenter is required - the driver's load-balancing policy has no "
                  + "safe default for it");
        }
      }
      case ADVANCED -> {
        if (isBlank(request.advancedConfig())) {
          throw new ConnectionValidationException(
              "advancedConfig", "an application.conf (HOCON) document is required in ADVANCED mode");
        }
      }
      case ASTRA -> validateAstra(request);
      default ->
          throw new ConnectionValidationException("mode", "Unsupported mode " + request.mode());
    }
  }

  private void validateAstra(ConnectionRequest request) {
    var astra = request.astra();
    if (astra == null) {
      throw new ConnectionValidationException("astra", "Astra settings are required in ASTRA mode");
    }
    if (astra.astraToken() != null
        && !astra.astraToken().isEmpty()
        && !astra.astraToken().startsWith("AstraCS:")) {
      throw new ConnectionValidationException(
          "astra.astraToken", "an Astra application token starts with 'AstraCS:'");
    }
    if (astra.scbTypeOrDefault() == ScbType.CUSTOM && isBlank(astra.domain())) {
      throw new ConnectionValidationException(
          "astra.domain", "domain is required when scbType is 'custom'");
    }
    if (astra.scbTypeOrDefault() == ScbType.DEFAULT && !isBlank(astra.domain())) {
      throw new ConnectionValidationException(
          "astra.domain", "domain must not be set when scbType is 'default'");
    }
    switch (astra.scbModeOrDefault()) {
      case AUTO_DOWNLOAD -> {
        if (isBlank(astra.databaseId())) {
          throw new ConnectionValidationException(
              "astra.databaseId", "databaseId is required for AUTO_DOWNLOAD - pick one from the list");
        }
      }
      case PATH -> {
        if (isBlank(astra.scbPath())) {
          throw new ConnectionValidationException(
              "astra.scbPath", "scbPath is required for PATH mode, and resolves on the SERVER host");
        }
      }
      case UPLOAD -> {
        // The bundle arrives through its own multipart endpoint, so nothing is required here.
      }
      default ->
          throw new ConnectionValidationException(
              "astra.scbMode", "Unsupported scbMode " + astra.scbModeOrDefault());
    }
  }

  /* ------------------------------------------------------------------ small helpers */

  static List<ContactPoint> parseContactPoints(String stored) {
    if (stored == null || stored.isBlank()) {
      return List.of();
    }
    return Arrays.stream(stored.split("[,\\s]+"))
        .map(String::trim)
        .filter(entry -> !entry.isEmpty())
        .map(ContactPoint::parse)
        .toList();
  }

  static String joinContactPoints(List<ContactPoint> contactPoints) {
    if (contactPoints == null || contactPoints.isEmpty()) {
      return null;
    }
    return String.join(
        LIST_SEPARATOR, contactPoints.stream().map(ContactPoint::toHostPort).toList());
  }

  static List<String> splitList(String stored) {
    if (stored == null || stored.isBlank()) {
      return List.of();
    }
    return Arrays.stream(stored.split(LIST_SEPARATOR))
        .map(String::trim)
        .filter(entry -> !entry.isEmpty())
        .toList();
  }

  static String joinList(List<String> values) {
    return values == null || values.isEmpty() ? null : String.join(LIST_SEPARATOR, values);
  }

  static ScbMode scbMode(String stored) {
    if (stored == null || stored.isBlank()) {
      return ScbMode.AUTO_DOWNLOAD;
    }
    try {
      return ScbMode.valueOf(stored.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      return ScbMode.AUTO_DOWNLOAD;
    }
  }

  static ScbType scbType(String stored) {
    if (stored == null || stored.isBlank()) {
      return ScbType.DEFAULT;
    }
    return ScbType.CUSTOM.name().equalsIgnoreCase(stored.trim())
            || ScbType.CUSTOM.wireName().equalsIgnoreCase(stored.trim())
        ? ScbType.CUSTOM
        : ScbType.DEFAULT;
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
