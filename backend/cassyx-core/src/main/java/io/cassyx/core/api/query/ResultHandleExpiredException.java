package io.cassyx.core.api.query;

import io.cassyx.core.api.CassyxCoreException;

/**
 * The result handle is unknown or has passed its idle TTL (default 10 minutes). Surfaces as
 * {@code 404 ResultHandleExpired} - the client re-runs the query rather than retrying the page.
 */
public class ResultHandleExpiredException extends CassyxCoreException {

  private static final long serialVersionUID = 1L;

  private final String resultHandle;

  public ResultHandleExpiredException(String resultHandle, String message) {
    super(message);
    this.resultHandle = resultHandle;
  }

  public String resultHandle() {
    return resultHandle;
  }
}
