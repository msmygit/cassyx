package io.cassyx.bulk.api.dsbulk;

/** Anything that goes wrong planning or running a DSBulk job. Never carries credentials. */
public class DsbulkException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public DsbulkException(String message) {
    super(message);
  }

  public DsbulkException(String message, Throwable cause) {
    super(message, cause);
  }
}
