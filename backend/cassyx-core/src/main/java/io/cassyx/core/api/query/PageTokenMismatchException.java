package io.cassyx.core.api.query;

import io.cassyx.core.api.CassyxCoreException;

/**
 * A page token was presented against a result handle it does not belong to. Surfaces as {@code 409}
 * so a client that muddles two open result sets fails loudly instead of silently paging the wrong
 * one.
 */
public class PageTokenMismatchException extends CassyxCoreException {

  private static final long serialVersionUID = 1L;

  public PageTokenMismatchException(String message) {
    super(message);
  }
}
