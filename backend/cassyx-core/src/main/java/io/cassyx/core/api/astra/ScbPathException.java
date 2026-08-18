package io.cassyx.core.api.astra;

/** Rejection of a server-side secure connect bundle path. */
public class ScbPathException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public ScbPathException(String message) {
    super(message);
  }

  public ScbPathException(String message, Throwable cause) {
    super(message, cause);
  }
}
