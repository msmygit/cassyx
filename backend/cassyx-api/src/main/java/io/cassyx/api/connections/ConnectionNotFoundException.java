package io.cassyx.api.connections;

/**
 * No such saved connection.
 *
 * <p>Distinct from {@link io.cassyx.core.api.ConnectionNotOpenException}: "no such connection" is a
 * 404 and a dead end, "not connected" is a 409 with a Connect button. Collapsing the two would make
 * the UI offer to connect something that does not exist.
 */
public class ConnectionNotFoundException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final String connectionId;

  public ConnectionNotFoundException(String connectionId) {
    super("No connection " + connectionId);
    this.connectionId = connectionId;
  }

  public String connectionId() {
    return connectionId;
  }
}
