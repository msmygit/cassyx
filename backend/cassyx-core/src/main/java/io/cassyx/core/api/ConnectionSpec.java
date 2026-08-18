package io.cassyx.core.api;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable description of how to reach a CQL cluster (plan section 3).
 *
 * <p>One of three shapes, matching the three connection modes:
 *
 * <ul>
 *   <li><b>Cassandra / DSE</b> - {@code contactPoints} plus {@code localDatacenter} and optional
 *       credentials
 *   <li><b>Astra</b> - {@code astra}, whose secure connect bundle is resolved by the session
 *       factory's bundle resolver
 *   <li><b>Advanced</b> - {@code advancedConfig}, raw HOCON handed to the driver untouched
 * </ul>
 *
 * <p>{@code ssh} and {@code ssl} are orthogonal to all three. Secrets are held here only in transit;
 * persistence encrypts them (AES-256-GCM, see {@link SecretCipher}) and the API layer never returns
 * them.
 *
 * <p>Use {@link #builder(String)} rather than the canonical constructor - there are enough optional
 * components that positional construction is a readability trap.
 */
public record ConnectionSpec(
    String name,
    List<String> contactPoints,
    String localDatacenter,
    String username,
    Secret password,
    String protocolVersion,
    AstraConnection astra,
    String advancedConfig,
    String defaultKeyspace,
    Duration requestTimeout,
    SshTunnelSpec ssh,
    SslSpec ssl) {

  public ConnectionSpec {
    Objects.requireNonNull(name, "name");
    contactPoints = contactPoints == null ? List.of() : List.copyOf(contactPoints);
    password = password == null ? Secret.empty() : password;
    ssl = ssl == null ? SslSpec.disabled() : ssl;
    protocolVersion = blankToNull(protocolVersion);
    advancedConfig = blankToNull(advancedConfig);
    defaultKeyspace = blankToNull(defaultKeyspace);
    localDatacenter = blankToNull(localDatacenter);
    username = blankToNull(username);
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  /** The presence flag the API exposes instead of the value (plan section 2.3). */
  public boolean hasPassword() {
    return password.isPresent();
  }

  public static ConnectionSpec cassandra(
      String name, List<String> contactPoints, String localDatacenter) {
    return builder(name).contactPoints(contactPoints).localDatacenter(localDatacenter).build();
  }

  public static ConnectionSpec astra(String name, AstraConnection astra) {
    return builder(name).astra(astra).build();
  }

  public static ConnectionSpec advanced(String name, String hocon) {
    return builder(name).advancedConfig(hocon).build();
  }

  public Optional<AstraConnection> astraConnection() {
    return Optional.ofNullable(astra);
  }

  public boolean isAstra() {
    return astra != null;
  }

  public boolean isAdvanced() {
    return astra == null && advancedConfig != null;
  }

  public Optional<SshTunnelSpec> sshTunnel() {
    return Optional.ofNullable(ssh);
  }

  /** A copy whose contact points point at a local SSH forward instead of at the cluster. */
  public ConnectionSpec withContactPoints(List<String> replacement) {
    return new ConnectionSpec(
        name,
        replacement,
        localDatacenter,
        username,
        password,
        protocolVersion,
        astra,
        advancedConfig,
        defaultKeyspace,
        requestTimeout,
        ssh,
        ssl);
  }

  public static Builder builder(String name) {
    return new Builder(name);
  }

  /** Never expose credentials through toString - this object ends up in logs and job records. */
  @Override
  public String toString() {
    return "ConnectionSpec[name="
        + name
        + ", contactPoints="
        + contactPoints
        + ", localDatacenter="
        + localDatacenter
        + ", username="
        + (username == null ? "<none>" : "<set>")
        + ", password="
        + password
        + ", astra="
        + (astra == null ? "<none>" : astra)
        + ", advancedConfig="
        + (advancedConfig == null ? "<none>" : "<set>")
        + ", ssh="
        + (ssh == null ? "<none>" : ssh)
        + ", ssl="
        + ssl
        + "]";
  }

  /** Fluent construction; every setter is optional except the name passed to {@link #builder}. */
  public static final class Builder {

    private final String name;
    private List<String> contactPoints = List.of();
    private String localDatacenter;
    private String username;
    private Secret password = Secret.empty();
    private String protocolVersion;
    private AstraConnection astra;
    private String advancedConfig;
    private String defaultKeyspace;
    private Duration requestTimeout;
    private SshTunnelSpec ssh;
    private SslSpec ssl = SslSpec.disabled();

    private Builder(String name) {
      this.name = Objects.requireNonNull(name, "name");
    }

    public Builder contactPoints(List<String> value) {
      this.contactPoints = value == null ? List.of() : List.copyOf(value);
      return this;
    }

    public Builder localDatacenter(String value) {
      this.localDatacenter = value;
      return this;
    }

    public Builder credentials(String user, Secret secret) {
      this.username = user;
      this.password = secret == null ? Secret.empty() : secret;
      return this;
    }

    public Builder protocolVersion(String value) {
      this.protocolVersion = value;
      return this;
    }

    public Builder astra(AstraConnection value) {
      this.astra = value;
      return this;
    }

    public Builder advancedConfig(String value) {
      this.advancedConfig = value;
      return this;
    }

    public Builder defaultKeyspace(String value) {
      this.defaultKeyspace = value;
      return this;
    }

    public Builder requestTimeout(Duration value) {
      this.requestTimeout = value;
      return this;
    }

    public Builder ssh(SshTunnelSpec value) {
      this.ssh = value;
      return this;
    }

    public Builder ssl(SslSpec value) {
      this.ssl = value == null ? SslSpec.disabled() : value;
      return this;
    }

    public ConnectionSpec build() {
      return new ConnectionSpec(
          name,
          contactPoints,
          localDatacenter,
          username,
          password,
          protocolVersion,
          astra,
          advancedConfig,
          defaultKeyspace,
          requestTimeout,
          ssh,
          ssl);
    }
  }
}
