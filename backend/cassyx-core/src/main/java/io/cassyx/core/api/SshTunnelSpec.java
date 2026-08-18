package io.cassyx.core.api;

import java.util.Objects;

/**
 * A local port forward to establish <b>before</b> the {@link com.datastax.oss.driver.api.core.CqlSession}
 * is built (plan section 3, NoSQL Manager parity).
 *
 * <p>The session's contact points then point at {@code 127.0.0.1:<localPort>} rather than at the
 * cluster, which is the whole trick: the driver needs no SSH awareness at all.
 *
 * @param localPort {@code 0} lets the OS pick a free port, which is what you want - a fixed local
 *     port collides the moment a user opens two tunnelled connections
 * @param strictHostKeyChecking when true, {@code knownHostsEntry} must be supplied and is the only
 *     key accepted. Defaulting this to false would make every tunnel trivially
 *     man-in-the-middleable, which rather defeats the point of tunnelling.
 */
public record SshTunnelSpec(
    String host,
    int port,
    String username,
    Secret password,
    Secret privateKey,
    Secret privateKeyPassphrase,
    int localPort,
    String remoteHost,
    int remotePort,
    boolean strictHostKeyChecking,
    String knownHostsEntry) {

  public static final int DEFAULT_SSH_PORT = 22;
  public static final int DEFAULT_REMOTE_PORT = 9042;

  public SshTunnelSpec {
    if (isBlank(host)) {
      throw new IllegalArgumentException("SSH tunnel host is required");
    }
    if (isBlank(username)) {
      throw new IllegalArgumentException("SSH tunnel username is required");
    }
    port = port <= 0 ? DEFAULT_SSH_PORT : port;
    remotePort = remotePort <= 0 ? DEFAULT_REMOTE_PORT : remotePort;
    localPort = Math.max(localPort, 0);
    password = password == null ? Secret.empty() : password;
    privateKey = privateKey == null ? Secret.empty() : privateKey;
    privateKeyPassphrase = privateKeyPassphrase == null ? Secret.empty() : privateKeyPassphrase;
    remoteHost = isBlank(remoteHost) ? "127.0.0.1" : remoteHost.trim();
    knownHostsEntry = isBlank(knownHostsEntry) ? null : knownHostsEntry.trim();
    if (password.isEmpty() && privateKey.isEmpty()) {
      throw new IllegalArgumentException(
          "SSH tunnel needs either a password or a private key");
    }
    if (strictHostKeyChecking && knownHostsEntry == null) {
      throw new IllegalArgumentException(
          "strictHostKeyChecking requires a pinned host key (knownHostsEntry). Paste the bastion's "
              + "public key line, or disable strict checking if you accept the risk.");
    }
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  public boolean hasPassword() {
    return password.isPresent();
  }

  public boolean hasPrivateKey() {
    return privateKey.isPresent();
  }

  /** Redacts every credential - this record ends up in {@link ConnectionSpec#toString()}. */
  @Override
  public String toString() {
    return "SshTunnelSpec[host="
        + host
        + ":"
        + port
        + ", username="
        + username
        + ", remote="
        + remoteHost
        + ":"
        + remotePort
        + ", hasPassword="
        + hasPassword()
        + ", hasPrivateKey="
        + hasPrivateKey()
        + ", strictHostKeyChecking="
        + strictHostKeyChecking
        + "]";
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof SshTunnelSpec other
        && port == other.port
        && localPort == other.localPort
        && remotePort == other.remotePort
        && strictHostKeyChecking == other.strictHostKeyChecking
        && Objects.equals(host, other.host)
        && Objects.equals(username, other.username)
        && Objects.equals(remoteHost, other.remoteHost)
        && Objects.equals(knownHostsEntry, other.knownHostsEntry)
        && Objects.equals(password, other.password)
        && Objects.equals(privateKey, other.privateKey)
        && Objects.equals(privateKeyPassphrase, other.privateKeyPassphrase);
  }

  @Override
  public int hashCode() {
    return Objects.hash(host, port, username, remoteHost, remotePort, localPort, strictHostKeyChecking);
  }
}
