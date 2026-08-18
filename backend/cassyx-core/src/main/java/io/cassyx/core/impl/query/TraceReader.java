package io.cassyx.core.impl.query;

import com.datastax.oss.driver.api.core.cql.ExecutionInfo;
import com.datastax.oss.driver.api.core.cql.TraceEvent;
import io.cassyx.core.api.query.QueryTrace;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the full {@code system_traces.sessions} + {@code system_traces.events} timeline.
 *
 * <p>Plan section 5.1 singles tracing out as one of the few places {@code cqlsh} still beats every
 * GUI, so we render every event with its source node, thread and elapsed microseconds rather than
 * collapsing the trace to a total duration. The driver polls for the trace, which lands in
 * {@code system_traces} slightly after the query returns - which is why this is read on demand from
 * a separate endpoint rather than inline with the result.
 */
final class TraceReader {

  private TraceReader() {}

  static QueryTrace read(ExecutionInfo info) {
    com.datastax.oss.driver.api.core.cql.QueryTrace trace = info.getQueryTrace();
    List<QueryTrace.Event> events = new ArrayList<>();
    for (TraceEvent event : trace.getEvents()) {
      events.add(
          new QueryTrace.Event(
              event.getActivity(),
              event.getSourceAddress() == null ? null : event.getSourceAddress().toString(),
              event.getSourceElapsedMicros(),
              event.getThreadName(),
              Instant.ofEpochMilli(event.getTimestamp())));
    }
    return new QueryTrace(
        trace.getTracingId().toString(),
        trace.getRequestType(),
        trace.getCoordinatorAddress() == null ? null : trace.getCoordinatorAddress().toString(),
        trace.getDurationMicros(),
        Instant.ofEpochMilli(trace.getStartedAt()),
        trace.getParameters(),
        events);
  }
}
