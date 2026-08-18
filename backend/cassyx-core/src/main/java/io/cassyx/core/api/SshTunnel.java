package io.cassyx.core.api;

/**
 * An established local port forward. Closing it tears the forward down and disconnects the SSH
 * session, so it is tied to the {@link com.datastax.oss.driver.api.core.CqlSession}'s lifetime by
 * whoever opened it.
 */
public interface SshTunnel extends AutoCloseable {

  /** The local port the driver should point its contact points at. */
  int localPort();

  /** {@code 127.0.0.1:<localPort>} - the contact point that replaces the real one. */
  default String localContactPoint() {
    return "127.0.0.1:" + localPort();
  }

  boolean isOpen();

  /** Idempotent; never throws. Tunnel teardown must not be able to fail a disconnect. */
  @Override
  void close();
}
