package io.cassyx.core.api;

import java.time.Duration;
import java.util.Objects;

/**
 * A single statement execution request.
 *
 * @param cql the statement
 * @param fetchSize page size; defaults to 500 (plan section 5.1)
 * @param pagingState opaque continuation token from a previous {@link QueryResultPage}, or null
 * @param consistency statement-level consistency level name, or null for the driver default
 * @param tracing enable {@code TRACING ON} equivalent
 * @param timeout per-query timeout, or null for the driver default
 */
public record QueryRequest(
    String cql,
    int fetchSize,
    String pagingState,
    String consistency,
    boolean tracing,
    Duration timeout) {

  public static final int DEFAULT_FETCH_SIZE = 500;

  public QueryRequest {
    Objects.requireNonNull(cql, "cql");
    if (fetchSize <= 0) {
      fetchSize = DEFAULT_FETCH_SIZE;
    }
  }

  public static QueryRequest of(String cql) {
    return new QueryRequest(cql, DEFAULT_FETCH_SIZE, null, null, false, null);
  }
}
