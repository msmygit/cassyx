package io.cassyx.api.connections.dto;

/** Response-side view of the SSH tunnel - no secret material, ever. */
public record SshTunnelInfo(
    boolean enabled,
    String host,
    Integer port,
    String username,
    boolean hasPassword,
    boolean hasPrivateKey,
    String remoteHost,
    Integer remotePort,
    boolean strictHostKeyChecking) {

  public static SshTunnelInfo disabled() {
    return new SshTunnelInfo(false, null, null, null, false, false, null, null, true);
  }
}
