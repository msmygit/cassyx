package io.cassyx.api.connections;

/**
 * The request is well-formed JSON but does not describe a usable connection - an Astra mode with no
 * token, a {@code custom} bundle type with no domain, a {@code PATH} mode with no path.
 *
 * <p>Carries the offending field so the response can populate {@code Problem.errors[]} and the form
 * can highlight the right input rather than showing a banner.
 */
public class ConnectionValidationException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final String field;

  public ConnectionValidationException(String field, String message) {
    super(message);
    this.field = field;
  }

  public String field() {
    return field;
  }
}
