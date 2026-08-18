package io.cassyx.bulk.api;

/** Unchecked failure raised by the bulk engines. */
public class BulkException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public BulkException(String message) {
    super(message);
  }

  public BulkException(String message, Throwable cause) {
    super(message, cause);
  }
}
