package io.cassyx.core.api.astra;

/**
 * DevOps API failure.
 *
 * <p>Implementations must never place the Astra token (nor the {@code Authorization} header) in
 * the message or cause - error paths are exactly where tokens leak (plan section 3.1, Security).
 */
public class AstraDevOpsException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final int statusCode;

  public AstraDevOpsException(String message) {
    this(message, 0, null);
  }

  public AstraDevOpsException(String message, Throwable cause) {
    this(message, 0, cause);
  }

  public AstraDevOpsException(String message, int statusCode, Throwable cause) {
    super(message, cause);
    this.statusCode = statusCode;
  }

  /** HTTP status that caused this failure, or 0 if the failure was not an HTTP response. */
  public int statusCode() {
    return statusCode;
  }
}
