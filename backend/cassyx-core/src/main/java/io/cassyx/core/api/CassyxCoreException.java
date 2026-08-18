package io.cassyx.core.api;

/** Unchecked failure raised by cassyx-core. Messages are safe to surface to users. */
public class CassyxCoreException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public CassyxCoreException(String message) {
    super(message);
  }

  public CassyxCoreException(String message, Throwable cause) {
    super(message, cause);
  }
}
