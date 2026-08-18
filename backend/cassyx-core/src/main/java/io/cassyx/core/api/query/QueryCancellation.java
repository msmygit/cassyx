package io.cassyx.core.api.query;

/** Outcome of cancelling an in-flight query. Contract: {@code QueryCancellationResult}. */
public record QueryCancellation(String queryId, boolean cancelled, State state, String message) {

  public enum State {
    CANCELLED,
    ALREADY_COMPLETED,
    NOT_FOUND
  }

  public static QueryCancellation notFound(String queryId) {
    return new QueryCancellation(
        queryId, false, State.NOT_FOUND, "No in-flight query with this id.");
  }
}
