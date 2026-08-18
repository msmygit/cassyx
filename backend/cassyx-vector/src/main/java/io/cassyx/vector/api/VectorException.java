package io.cassyx.vector.api;

/** Anything cassyx-vector rejects before it reaches the cluster. Maps to HTTP 400 in the API. */
public class VectorException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public VectorException(String message) {
    super(message);
  }

  public VectorException(String message, Throwable cause) {
    super(message, cause);
  }
}
