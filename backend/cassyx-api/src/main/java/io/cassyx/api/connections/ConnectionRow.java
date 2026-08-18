package io.cassyx.api.connections;

import io.cassyx.core.api.EncryptedValue;
import java.time.Instant;

/**
 * One row of {@code cassyx_connection}, ciphertext and all.
 *
 * <p>A mutable class rather than a record on purpose: it has ~50 fields, a third of which are
 * optional, and the update path mutates a handful of them while preserving the rest. A 50-component
 * record would make every call site a positional puzzle and every "preserve the stored secret"
 * branch a full re-listing of every other field.
 *
 * <p>Every {@link EncryptedValue} here is AES-256-GCM ciphertext plus its own nonce. Nothing in this
 * class is ever serialised to a client; {@link ConnectionMapper} builds the response, and it copies
 * fields across explicitly so adding a secret column cannot silently leak it.
 */
public final class ConnectionRow {

  private String id;
  private String name;
  private String mode;
  private String description;
  private String contactPoints;
  private String localDatacenter;
  private String protocolVersionName;
  private String username;
  private EncryptedValue password;
  private String defaultKeyspace;
  private Integer requestTimeoutMillis;
  private String tags;
  private String advancedConfig;

  private String astraDatabaseId;
  private String astraDatabaseName;
  private EncryptedValue astraToken;
  private String scbAcquisitionMode;
  private String scbRegion;
  private String scbType;
  private String scbCustomDomain;
  private String scbPath;
  private EncryptedValue scbBundle;
  private Instant scbBundleFetchedAt;
  private String scbFileName;
  private Long scbSizeBytes;
  private String scbSha256;
  private String scbSource;
  private String scbCacheKey;
  private boolean scbValidated;

  private boolean sslEnabled;
  private boolean sslHostnameValidation = true;
  private String sslCipherSuites;
  private EncryptedValue truststore;
  private EncryptedValue truststorePassword;
  private String truststoreFileName;
  private String truststoreType;
  private EncryptedValue keystore;
  private EncryptedValue keystorePassword;
  private String keystoreFileName;
  private String keystoreType;

  private boolean sshEnabled;
  private String sshHost;
  private Integer sshPort;
  private String sshUsername;
  private EncryptedValue sshPassword;
  private EncryptedValue sshPrivateKey;
  private EncryptedValue sshPrivateKeyPassphrase;
  private Integer sshLocalPort;
  private String sshRemoteHost;
  private Integer sshRemotePort;
  private boolean sshStrictHostKeyChecking = true;
  private String sshKnownHostsEntry;

  private Instant createdAt;
  private Instant updatedAt;
  private Instant lastConnectedAt;

  public String id() {
    return id;
  }

  public ConnectionRow id(String value) {
    this.id = value;
    return this;
  }

  public String name() {
    return name;
  }

  public ConnectionRow name(String value) {
    this.name = value;
    return this;
  }

  public String mode() {
    return mode;
  }

  public ConnectionRow mode(String value) {
    this.mode = value;
    return this;
  }

  public String description() {
    return description;
  }

  public ConnectionRow description(String value) {
    this.description = value;
    return this;
  }

  public String contactPoints() {
    return contactPoints;
  }

  public ConnectionRow contactPoints(String value) {
    this.contactPoints = value;
    return this;
  }

  public String localDatacenter() {
    return localDatacenter;
  }

  public ConnectionRow localDatacenter(String value) {
    this.localDatacenter = value;
    return this;
  }

  public String protocolVersionName() {
    return protocolVersionName;
  }

  public ConnectionRow protocolVersionName(String value) {
    this.protocolVersionName = value;
    return this;
  }

  public String username() {
    return username;
  }

  public ConnectionRow username(String value) {
    this.username = value;
    return this;
  }

  public EncryptedValue password() {
    return password;
  }

  public ConnectionRow password(EncryptedValue value) {
    this.password = value;
    return this;
  }

  public String defaultKeyspace() {
    return defaultKeyspace;
  }

  public ConnectionRow defaultKeyspace(String value) {
    this.defaultKeyspace = value;
    return this;
  }

  public Integer requestTimeoutMillis() {
    return requestTimeoutMillis;
  }

  public ConnectionRow requestTimeoutMillis(Integer value) {
    this.requestTimeoutMillis = value;
    return this;
  }

  public String tags() {
    return tags;
  }

  public ConnectionRow tags(String value) {
    this.tags = value;
    return this;
  }

  public String advancedConfig() {
    return advancedConfig;
  }

  public ConnectionRow advancedConfig(String value) {
    this.advancedConfig = value;
    return this;
  }

  public String astraDatabaseId() {
    return astraDatabaseId;
  }

  public ConnectionRow astraDatabaseId(String value) {
    this.astraDatabaseId = value;
    return this;
  }

  public String astraDatabaseName() {
    return astraDatabaseName;
  }

  public ConnectionRow astraDatabaseName(String value) {
    this.astraDatabaseName = value;
    return this;
  }

  public EncryptedValue astraToken() {
    return astraToken;
  }

  public ConnectionRow astraToken(EncryptedValue value) {
    this.astraToken = value;
    return this;
  }

  public String scbAcquisitionMode() {
    return scbAcquisitionMode;
  }

  public ConnectionRow scbAcquisitionMode(String value) {
    this.scbAcquisitionMode = value;
    return this;
  }

  public String scbRegion() {
    return scbRegion;
  }

  public ConnectionRow scbRegion(String value) {
    this.scbRegion = value;
    return this;
  }

  public String scbType() {
    return scbType;
  }

  public ConnectionRow scbType(String value) {
    this.scbType = value;
    return this;
  }

  public String scbCustomDomain() {
    return scbCustomDomain;
  }

  public ConnectionRow scbCustomDomain(String value) {
    this.scbCustomDomain = value;
    return this;
  }

  public String scbPath() {
    return scbPath;
  }

  public ConnectionRow scbPath(String value) {
    this.scbPath = value;
    return this;
  }

  public EncryptedValue scbBundle() {
    return scbBundle;
  }

  public ConnectionRow scbBundle(EncryptedValue value) {
    this.scbBundle = value;
    return this;
  }

  public Instant scbBundleFetchedAt() {
    return scbBundleFetchedAt;
  }

  public ConnectionRow scbBundleFetchedAt(Instant value) {
    this.scbBundleFetchedAt = value;
    return this;
  }

  public String scbFileName() {
    return scbFileName;
  }

  public ConnectionRow scbFileName(String value) {
    this.scbFileName = value;
    return this;
  }

  public Long scbSizeBytes() {
    return scbSizeBytes;
  }

  public ConnectionRow scbSizeBytes(Long value) {
    this.scbSizeBytes = value;
    return this;
  }

  public String scbSha256() {
    return scbSha256;
  }

  public ConnectionRow scbSha256(String value) {
    this.scbSha256 = value;
    return this;
  }

  public String scbSource() {
    return scbSource;
  }

  public ConnectionRow scbSource(String value) {
    this.scbSource = value;
    return this;
  }

  public String scbCacheKey() {
    return scbCacheKey;
  }

  public ConnectionRow scbCacheKey(String value) {
    this.scbCacheKey = value;
    return this;
  }

  public boolean scbValidated() {
    return scbValidated;
  }

  public ConnectionRow scbValidated(boolean value) {
    this.scbValidated = value;
    return this;
  }

  public boolean sslEnabled() {
    return sslEnabled;
  }

  public ConnectionRow sslEnabled(boolean value) {
    this.sslEnabled = value;
    return this;
  }

  public boolean sslHostnameValidation() {
    return sslHostnameValidation;
  }

  public ConnectionRow sslHostnameValidation(boolean value) {
    this.sslHostnameValidation = value;
    return this;
  }

  public String sslCipherSuites() {
    return sslCipherSuites;
  }

  public ConnectionRow sslCipherSuites(String value) {
    this.sslCipherSuites = value;
    return this;
  }

  public EncryptedValue truststore() {
    return truststore;
  }

  public ConnectionRow truststore(EncryptedValue value) {
    this.truststore = value;
    return this;
  }

  public EncryptedValue truststorePassword() {
    return truststorePassword;
  }

  public ConnectionRow truststorePassword(EncryptedValue value) {
    this.truststorePassword = value;
    return this;
  }

  public String truststoreFileName() {
    return truststoreFileName;
  }

  public ConnectionRow truststoreFileName(String value) {
    this.truststoreFileName = value;
    return this;
  }

  public String truststoreType() {
    return truststoreType;
  }

  public ConnectionRow truststoreType(String value) {
    this.truststoreType = value;
    return this;
  }

  public EncryptedValue keystore() {
    return keystore;
  }

  public ConnectionRow keystore(EncryptedValue value) {
    this.keystore = value;
    return this;
  }

  public EncryptedValue keystorePassword() {
    return keystorePassword;
  }

  public ConnectionRow keystorePassword(EncryptedValue value) {
    this.keystorePassword = value;
    return this;
  }

  public String keystoreFileName() {
    return keystoreFileName;
  }

  public ConnectionRow keystoreFileName(String value) {
    this.keystoreFileName = value;
    return this;
  }

  public String keystoreType() {
    return keystoreType;
  }

  public ConnectionRow keystoreType(String value) {
    this.keystoreType = value;
    return this;
  }

  public boolean sshEnabled() {
    return sshEnabled;
  }

  public ConnectionRow sshEnabled(boolean value) {
    this.sshEnabled = value;
    return this;
  }

  public String sshHost() {
    return sshHost;
  }

  public ConnectionRow sshHost(String value) {
    this.sshHost = value;
    return this;
  }

  public Integer sshPort() {
    return sshPort;
  }

  public ConnectionRow sshPort(Integer value) {
    this.sshPort = value;
    return this;
  }

  public String sshUsername() {
    return sshUsername;
  }

  public ConnectionRow sshUsername(String value) {
    this.sshUsername = value;
    return this;
  }

  public EncryptedValue sshPassword() {
    return sshPassword;
  }

  public ConnectionRow sshPassword(EncryptedValue value) {
    this.sshPassword = value;
    return this;
  }

  public EncryptedValue sshPrivateKey() {
    return sshPrivateKey;
  }

  public ConnectionRow sshPrivateKey(EncryptedValue value) {
    this.sshPrivateKey = value;
    return this;
  }

  public EncryptedValue sshPrivateKeyPassphrase() {
    return sshPrivateKeyPassphrase;
  }

  public ConnectionRow sshPrivateKeyPassphrase(EncryptedValue value) {
    this.sshPrivateKeyPassphrase = value;
    return this;
  }

  public Integer sshLocalPort() {
    return sshLocalPort;
  }

  public ConnectionRow sshLocalPort(Integer value) {
    this.sshLocalPort = value;
    return this;
  }

  public String sshRemoteHost() {
    return sshRemoteHost;
  }

  public ConnectionRow sshRemoteHost(String value) {
    this.sshRemoteHost = value;
    return this;
  }

  public Integer sshRemotePort() {
    return sshRemotePort;
  }

  public ConnectionRow sshRemotePort(Integer value) {
    this.sshRemotePort = value;
    return this;
  }

  public boolean sshStrictHostKeyChecking() {
    return sshStrictHostKeyChecking;
  }

  public ConnectionRow sshStrictHostKeyChecking(boolean value) {
    this.sshStrictHostKeyChecking = value;
    return this;
  }

  public String sshKnownHostsEntry() {
    return sshKnownHostsEntry;
  }

  public ConnectionRow sshKnownHostsEntry(String value) {
    this.sshKnownHostsEntry = value;
    return this;
  }

  public Instant createdAt() {
    return createdAt;
  }

  public ConnectionRow createdAt(Instant value) {
    this.createdAt = value;
    return this;
  }

  public Instant updatedAt() {
    return updatedAt;
  }

  public ConnectionRow updatedAt(Instant value) {
    this.updatedAt = value;
    return this;
  }

  public Instant lastConnectedAt() {
    return lastConnectedAt;
  }

  public ConnectionRow lastConnectedAt(Instant value) {
    this.lastConnectedAt = value;
    return this;
  }

  /** Renders identity only. A row is full of ciphertext and belongs in no log line. */
  @Override
  public String toString() {
    return "ConnectionRow[id=" + id + ", name=" + name + ", mode=" + mode + "]";
  }
}
