package io.cassyx.api.connections;

/** The unique index on {@code cassyx_connection.name} rejected the row - a 409, not a 500. */
public class DuplicateConnectionNameException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final String name;

  public DuplicateConnectionNameException(String name, Throwable cause) {
    super("A connection named '" + name + "' already exists", cause);
    this.name = name;
  }

  public String connectionName() {
    return name;
  }
}
