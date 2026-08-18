package io.cassyx.api.query;

/**
 * A statement the cluster rejected, carrying the offending CQL so the problem document can echo it.
 *
 * <p>The contract's {@code CqlError} response includes {@code cql} and {@code cqlErrorClass}; the
 * driver exception knows neither which statement text the user typed nor how it was framed, so the
 * controller wraps at the point where both are still in scope.
 */
public class CqlExecutionException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final String cql;

  public CqlExecutionException(String cql, Throwable cause) {
    super(cause.getMessage(), cause);
    this.cql = cql;
  }

  public String cql() {
    return cql;
  }

  /** The driver exception class, for precise client-side handling. */
  public String errorClass() {
    return getCause() == null ? null : getCause().getClass().getName();
  }
}
