package io.cassyx.api.connections.dto;

/**
 * Write model for the SSH tunnel (plan section 3). Every credential here is write-only and appears
 * in no response; {@link SshTunnelInfo} reports presence flags instead.
 */
public record SshTunnelConfig(
    Boolean enabled,
    String host,
    Integer port,
    String username,
    String password,
    String privateKey,
    String privateKeyPassphrase,
    Integer localPort,
    String remoteHost,
    Integer remotePort,
    Boolean strictHostKeyChecking,
    String knownHostsEntry) {

  public boolean isEnabled() {
    return Boolean.TRUE.equals(enabled);
  }

  /** Defaults to true: a tunnel that accepts any host key is not a security control. */
  public boolean strictHostKeyCheckingOrDefault() {
    return !Boolean.FALSE.equals(strictHostKeyChecking);
  }

  public static SshTunnelConfig disabled() {
    return new SshTunnelConfig(
        false, null, null, null, null, null, null, null, null, null, true, null);
  }
}
