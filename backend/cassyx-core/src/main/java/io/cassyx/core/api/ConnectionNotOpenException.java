package io.cassyx.core.api;

/**
 * No live session exists for a connection.
 *
 * <p>Maps to the contract's {@code 409 NotConnected} problem response, whose whole point is that
 * "not connected" is a distinct, recoverable state from "no such connection" (404) - the UI offers
 * a Connect button for one and an error for the other.
 */
public class ConnectionNotOpenException extends CassyxCoreException {

  private static final long serialVersionUID = 1L;

  private final String connectionId;

  public ConnectionNotOpenException(String connectionId) {
    super("Connection '" + connectionId + "' has no live session. Connect it first.");
    this.connectionId = connectionId;
  }

  public String connectionId() {
    return connectionId;
  }
}
