package io.cassyx.core.api.query;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One interactive statement execution (plan section 5.1). Mirrors the contract's
 * {@code QueryRequest}.
 *
 * @param cql a single statement; multi-statement scripts go through {@link CqlScriptSplitter} first
 * @param positionalValues wire-encoded bind values for {@code ?} markers
 * @param namedValues wire-encoded bind values for {@code :name} markers
 * @param keyspace keyspace to run in when the statement is not fully qualified
 * @param consistency statement consistency level name, or null for the driver default
 * @param serialConsistency Paxos-phase consistency for LWT statements
 * @param fetchSize page size; {@value #DEFAULT_FETCH_SIZE} by default
 * @param timeout per-query timeout, or null for the driver default
 * @param tracing {@code setTracing(true)} - the {@code TRACING ON} equivalent
 * @param idempotent marks the statement safe for speculative execution and retries
 * @param queryId caller-supplied execution id so the UI can cancel a query it has not yet got a
 *     response for; null means the server allocates one
 */
public record QuerySpec(
    String cql,
    List<Object> positionalValues,
    Map<String, Object> namedValues,
    String keyspace,
    String consistency,
    String serialConsistency,
    int fetchSize,
    Duration timeout,
    boolean tracing,
    boolean idempotent,
    String queryId) {

  /** Plan section 5.1: fetch size default 500. */
  public static final int DEFAULT_FETCH_SIZE = 500;

  public static final int MAX_FETCH_SIZE = 10_000;

  public QuerySpec {
    Objects.requireNonNull(cql, "cql");
    positionalValues = positionalValues == null ? List.of() : List.copyOf(positionalValues);
    namedValues = namedValues == null ? Map.of() : Map.copyOf(namedValues);
    if (fetchSize <= 0) {
      fetchSize = DEFAULT_FETCH_SIZE;
    }
    if (fetchSize > MAX_FETCH_SIZE) {
      fetchSize = MAX_FETCH_SIZE;
    }
  }

  public static QuerySpec of(String cql) {
    return new QuerySpec(cql, null, null, null, null, null, DEFAULT_FETCH_SIZE, null, false, false, null);
  }

  public boolean hasBindValues() {
    return !positionalValues.isEmpty() || !namedValues.isEmpty();
  }
}
