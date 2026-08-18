package io.cassyx.core.api.query;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The {@code system_traces.sessions} + {@code system_traces.events} timeline for a traced query.
 *
 * <p>Plan section 5.1 calls tracing out as one of the few places {@code cqlsh} still beats every
 * GUI, so the whole event list is rendered, not just the total duration.
 */
public record QueryTrace(
    String tracingId,
    String requestType,
    String coordinator,
    long durationMicros,
    Instant startedAt,
    Map<String, String> parameters,
    List<Event> events) {

  public QueryTrace {
    parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
    events = events == null ? List.of() : List.copyOf(events);
  }

  /** One row of {@code system_traces.events}. */
  public record Event(
      String activity, String source, long sourceElapsedMicros, String threadName, Instant timestamp) {}
}
