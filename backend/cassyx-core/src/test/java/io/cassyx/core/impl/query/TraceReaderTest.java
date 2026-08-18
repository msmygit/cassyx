package io.cassyx.core.impl.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.datastax.oss.driver.api.core.cql.ExecutionInfo;
import com.datastax.oss.driver.api.core.cql.TraceEvent;
import io.cassyx.core.api.query.QueryTrace;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TraceReaderTest {

  @Test
  @DisplayName("The whole system_traces timeline is read, not just the total duration")
  void readsEveryEventWithItsSourceAndElapsedTime() {
    com.datastax.oss.driver.api.core.cql.QueryTrace driverTrace =
        mock(com.datastax.oss.driver.api.core.cql.QueryTrace.class);
    when(driverTrace.getTracingId())
        .thenReturn(java.util.UUID.fromString("7f4c2b91-1d5e-11f0-9c3d-0242ac120002"));
    when(driverTrace.getRequestType()).thenReturn("Execute CQL3 query");
    when(driverTrace.getCoordinatorAddress()).thenReturn(new InetSocketAddress("127.0.0.1", 9042));
    when(driverTrace.getDurationMicros()).thenReturn(41230);
    when(driverTrace.getStartedAt()).thenReturn(1755424262115L);
    when(driverTrace.getParameters()).thenReturn(Map.of("page_size", "500"));

    TraceEvent first = event("Parsing SELECT * FROM demo.users", "127.0.0.1", 132);
    TraceEvent second = event("Preparing statement", "127.0.0.1", 240);
    when(driverTrace.getEvents()).thenReturn(List.of(first, second));

    ExecutionInfo info = mock(ExecutionInfo.class);
    when(info.getQueryTrace()).thenReturn(driverTrace);

    QueryTrace trace = TraceReader.read(info);

    assertThat(trace.tracingId()).isEqualTo("7f4c2b91-1d5e-11f0-9c3d-0242ac120002");
    assertThat(trace.requestType()).isEqualTo("Execute CQL3 query");
    assertThat(trace.coordinator()).contains("127.0.0.1");
    assertThat(trace.durationMicros()).isEqualTo(41230);
    assertThat(trace.startedAt()).isEqualTo(Instant.ofEpochMilli(1755424262115L));
    assertThat(trace.parameters()).containsEntry("page_size", "500");
    assertThat(trace.events()).hasSize(2);
    assertThat(trace.events().get(0).activity()).isEqualTo("Parsing SELECT * FROM demo.users");
    assertThat(trace.events().get(0).sourceElapsedMicros()).isEqualTo(132);
    assertThat(trace.events().get(0).threadName()).isEqualTo("Native-Transport-Requests-1");
    assertThat(trace.events().get(1).sourceElapsedMicros()).isEqualTo(240);
  }

  @Test
  void toleratesAnEventWithNoSourceAddress() {
    com.datastax.oss.driver.api.core.cql.QueryTrace driverTrace =
        mock(com.datastax.oss.driver.api.core.cql.QueryTrace.class);
    when(driverTrace.getTracingId()).thenReturn(java.util.UUID.randomUUID());
    when(driverTrace.getRequestType()).thenReturn("Execute CQL3 query");
    when(driverTrace.getCoordinatorAddress()).thenReturn(null);
    when(driverTrace.getParameters()).thenReturn(Map.of());

    TraceEvent orphan = mock(TraceEvent.class);
    when(orphan.getActivity()).thenReturn("Timed out");
    when(orphan.getSourceAddress()).thenReturn(null);
    when(orphan.getSourceElapsedMicros()).thenReturn(0);
    when(orphan.getThreadName()).thenReturn(null);
    when(orphan.getTimestamp()).thenReturn(0L);
    when(driverTrace.getEvents()).thenReturn(List.of(orphan));

    ExecutionInfo info = mock(ExecutionInfo.class);
    when(info.getQueryTrace()).thenReturn(driverTrace);

    QueryTrace trace = TraceReader.read(info);

    assertThat(trace.coordinator()).isNull();
    assertThat(trace.events()).singleElement().satisfies(e -> assertThat(e.source()).isNull());
  }

  private static TraceEvent event(String activity, String source, int elapsedMicros) {
    TraceEvent event = mock(TraceEvent.class);
    when(event.getActivity()).thenReturn(activity);
    when(event.getSourceAddress()).thenReturn(new InetSocketAddress(source, 9042));
    when(event.getSourceElapsedMicros()).thenReturn(elapsedMicros);
    when(event.getThreadName()).thenReturn("Native-Transport-Requests-1");
    when(event.getTimestamp()).thenReturn(1755424262115L);
    return event;
  }
}
