package io.cassyx.core.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.ProtocolVersion;
import com.datastax.oss.driver.api.core.cql.ColumnDefinitions;
import com.datastax.oss.driver.api.core.cql.ResultSet;
import com.datastax.oss.driver.api.core.cql.Row;
import io.cassyx.core.api.Capability;
import io.cassyx.core.api.CassyxCoreException;
import io.cassyx.core.api.ClusterFlavor;
import io.cassyx.core.api.ConnectionNotOpenException;
import io.cassyx.core.api.ConnectionSpec;
import io.cassyx.core.api.ManagedSessionRegistry;
import io.cassyx.core.api.SessionFactory;
import io.cassyx.core.api.SessionHandle;
import io.cassyx.core.api.SshTunnel;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The shared seam of plan section 3: one session per connection, evicted when idle. */
class CachingSessionRegistryTest {

  private static final String ID = "8f2b1c6e-2a55-4f47-9f2a-4c1c3f0d9a11";

  private final ConnectionSpec spec =
      ConnectionSpec.cassandra("local-dev", List.of("127.0.0.1:9042"), "datacenter1");

  /* ------------------------------------------------------------------ opening */

  @Test
  void opensProbesAndServesTheSession() {
    CqlSession session = fakeSession("5.0.2");
    CachingSessionRegistry registry = registry(spec -> session);

    SessionHandle handle = registry.open(ID, spec);

    assertThat(handle.connectionId()).isEqualTo(ID);
    assertThat(handle.connectionName()).isEqualTo("local-dev");
    assertThat(handle.sessionId()).startsWith("sess_");
    assertThat(registry.session(ID)).isSameAs(session);
    assertThat(registry.isConnected(ID)).isTrue();
    assertThat(registry.capabilities(ID).supports(Capability.SAI)).isTrue();
    assertThat(registry.capabilities(ID).flavor()).isEqualTo(ClusterFlavor.CASSANDRA);
    registry.close();
  }

  @Test
  @DisplayName("ONE session per connection: a second connect returns the first, it does not rebuild")
  void openIsIdempotent() {
    AtomicInteger built = new AtomicInteger();
    CqlSession session = fakeSession("5.0.2");
    CachingSessionRegistry registry =
        registry(
            spec -> {
              built.incrementAndGet();
              return session;
            });

    SessionHandle first = registry.open(ID, spec);
    SessionHandle second = registry.open(ID, spec);

    assertThat(built).hasValue(1);
    assertThat(second.sessionId()).isEqualTo(first.sessionId());
    registry.close();
  }

  @Test
  @DisplayName("two simultaneous Connect clicks build ONE session, not two")
  void openIsSafeUnderConcurrency() throws Exception {
    AtomicInteger built = new AtomicInteger();
    CqlSession session = fakeSession("5.0.2");
    CachingSessionRegistry registry =
        registry(
            spec -> {
              built.incrementAndGet();
              try {
                Thread.sleep(50);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
              return session;
            });

    int threads = 8;
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      for (int i = 0; i < threads; i++) {
        pool.submit(
            () -> {
              start.await();
              return registry.open(ID, this.spec);
            });
      }
      start.countDown();
      pool.shutdown();
      assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
    } finally {
      pool.shutdownNow();
    }

    assertThat(built).hasValue(1);
    registry.close();
  }

  @Test
  @DisplayName("an idempotent re-connect closes the redundant tunnel rather than leaking it")
  void closesTheRedundantTunnelOnAnIdempotentOpen() {
    CqlSession session = fakeSession("5.0.2");
    CachingSessionRegistry registry = registry(spec -> session);
    registry.open(ID, spec, new FakeTunnel(15000));
    FakeTunnel redundant = new FakeTunnel(15001);

    registry.open(ID, spec, redundant);

    assertThat(redundant.closed).isTrue();
    registry.close();
  }

  @Test
  void aFailedConnectClosesTheTunnelAndRegistersNothing() {
    FakeTunnel tunnel = new FakeTunnel(15002);
    CachingSessionRegistry registry =
        registry(
            spec -> {
              throw new CassyxCoreException("Could not reach any contact point");
            });

    assertThatThrownBy(() -> registry.open(ID, spec, tunnel))
        .isInstanceOf(CassyxCoreException.class)
        .hasMessageContaining("contact point");
    assertThat(tunnel.closed).isTrue();
    assertThat(registry.isConnected(ID)).isFalse();
    registry.close();
  }

  @Test
  @DisplayName("a session we cannot probe still connects - degraded, not failed")
  void survivesAFailingProbe() {
    CqlSession session = mock(CqlSession.class, RETURNS_DEEP_STUBS);
    when(session.isClosed()).thenReturn(false);
    when(session.execute(anyString())).thenThrow(new IllegalStateException("no"));
    when(session.getMetadata().getNodes()).thenThrow(new IllegalStateException("no"));
    when(session.getContext().getProtocolVersion()).thenThrow(new IllegalStateException("no"));
    CachingSessionRegistry registry = registry(spec -> session);

    SessionHandle handle = registry.open(ID, spec);

    assertThat(handle).isNotNull();
    assertThat(registry.session(ID)).isSameAs(session);
    registry.close();
  }

  /* ------------------------------------------------------------------ lookups */

  @Test
  void unknownConnectionsAreNotConnectedRatherThanAnError() {
    CachingSessionRegistry registry = registry(spec -> fakeSession("5.0.2"));

    assertThat(registry.isConnected("nope")).isFalse();
    assertThat(registry.isConnected(null)).isFalse();
    assertThat(registry.handle("nope")).isEmpty();
    assertThat(registry.probe("nope")).isEmpty();
    registry.close();
  }

  @Test
  @DisplayName("asking for a session that is not open throws ConnectionNotOpen, never opens one")
  void sessionThrowsWhenNotConnected() {
    CachingSessionRegistry registry = registry(spec -> fakeSession("5.0.2"));

    assertThatThrownBy(() -> registry.session(ID))
        .isInstanceOf(ConnectionNotOpenException.class)
        .isInstanceOf(CassyxCoreException.class)
        .hasMessageContaining("Connect it first");
    assertThatThrownBy(() -> registry.capabilities(ID))
        .isInstanceOf(ConnectionNotOpenException.class);
    registry.close();
  }

  @Test
  void treatsADriverClosedSessionAsDisconnected() {
    CqlSession session = fakeSession("5.0.2");
    CachingSessionRegistry registry = registry(spec -> session);
    registry.open(ID, spec);

    when(session.isClosed()).thenReturn(true);

    assertThat(registry.isConnected(ID)).isFalse();
    assertThat(registry.handle(ID)).isEmpty();
    assertThatThrownBy(() -> registry.session(ID)).isInstanceOf(ConnectionNotOpenException.class);
    registry.close();
  }

  @Test
  void listsEveryLiveSession() {
    CachingSessionRegistry registry = registry(spec -> fakeSession("5.0.2"));
    registry.open("a", ConnectionSpec.cassandra("a", List.of("h:9042"), "dc1"));
    registry.open("b", ConnectionSpec.cassandra("b", List.of("h:9042"), "dc1"));

    assertThat(registry.handles()).extracting(SessionHandle::connectionId).containsExactlyInAnyOrder("a", "b");
    assertThat(registry.size()).isEqualTo(2);
    registry.close();
  }

  /* ------------------------------------------------------------------ closing */

  @Test
  void closingTearsDownTheSessionAndTheTunnel() {
    CqlSession session = fakeSession("5.0.2");
    FakeTunnel tunnel = new FakeTunnel(15003);
    CachingSessionRegistry registry = registry(spec -> session);
    registry.open(ID, spec, tunnel);

    assertThat(registry.close(ID)).isTrue();

    verify(session).close();
    assertThat(tunnel.closed).isTrue();
    assertThat(registry.isConnected(ID)).isFalse();
  }

  @Test
  @DisplayName("disconnecting something already disconnected is a no-op, not an error")
  void closingAnUnknownConnectionIsFalseNotAnException() {
    CachingSessionRegistry registry = registry(spec -> fakeSession("5.0.2"));

    assertThat(registry.close(ID)).isFalse();
    assertThat(registry.close(null)).isFalse();
    registry.close();
  }

  @Test
  void closingTheRegistryClosesEverySession() {
    CqlSession one = fakeSession("5.0.2");
    CqlSession two = fakeSession("5.0.2");
    AtomicInteger call = new AtomicInteger();
    CachingSessionRegistry registry =
        registry(spec -> call.getAndIncrement() == 0 ? one : two);
    registry.open("a", ConnectionSpec.cassandra("a", List.of("h:9042"), "dc1"));
    registry.open("b", ConnectionSpec.cassandra("b", List.of("h:9042"), "dc1"));

    registry.close();

    verify(one).close();
    verify(two).close();
    assertThat(registry.handles()).isEmpty();
    assertThatThrownBy(() -> registry.open("c", spec)).isInstanceOf(CassyxCoreException.class);
  }

  /* ------------------------------------------------------------------ eviction */

  @Test
  @DisplayName("eviction is by IDLENESS: an untouched session is closed after the TTL")
  void evictsIdleSessions() {
    MutableClock clock = new MutableClock(Instant.parse("2026-08-17T10:00:00Z"));
    CqlSession session = fakeSession("5.0.2");
    CachingSessionRegistry registry =
        new CachingSessionRegistry(spec -> session, Duration.ofMinutes(30), clock, false);
    registry.open(ID, spec);

    clock.advance(Duration.ofMinutes(29));
    assertThat(registry.evictIdle()).isZero();
    assertThat(registry.isConnected(ID)).isTrue();

    clock.advance(Duration.ofMinutes(2));
    assertThat(registry.evictIdle()).isEqualTo(1);
    assertThat(registry.isConnected(ID)).isFalse();
    verify(session).close();
  }

  @Test
  @DisplayName("a session in continuous use is never evicted under the user")
  void touchingResetsTheIdleTimer() {
    MutableClock clock = new MutableClock(Instant.parse("2026-08-17T10:00:00Z"));
    CqlSession session = fakeSession("5.0.2");
    CachingSessionRegistry registry =
        new CachingSessionRegistry(spec -> session, Duration.ofMinutes(30), clock, false);
    registry.open(ID, spec);

    for (int i = 0; i < 10; i++) {
      clock.advance(Duration.ofMinutes(20));
      registry.session(ID);
      assertThat(registry.evictIdle()).isZero();
    }

    assertThat(registry.isConnected(ID)).isTrue();
    verify(session, never()).close();
    registry.close();
  }

  @Test
  void reportsTheHandleExpiryTheUiCountsDownTo() {
    MutableClock clock = new MutableClock(Instant.parse("2026-08-17T10:00:00Z"));
    CachingSessionRegistry registry =
        new CachingSessionRegistry(
            spec -> fakeSession("5.0.2"), Duration.ofMinutes(30), clock, false);

    SessionHandle handle = registry.open(ID, spec);

    assertThat(handle.connectedAt()).isEqualTo(Instant.parse("2026-08-17T10:00:00Z"));
    assertThat(handle.expiresAt()).isEqualTo(Instant.parse("2026-08-17T10:30:00Z"));
    assertThat(registry.idleTimeout()).isEqualTo(Duration.ofMinutes(30));
    registry.close();
  }

  @Test
  void aZeroTtlDisablesEviction() {
    MutableClock clock = new MutableClock(Instant.parse("2026-08-17T10:00:00Z"));
    CachingSessionRegistry registry =
        new CachingSessionRegistry(spec -> fakeSession("5.0.2"), Duration.ZERO, clock, false);
    registry.open(ID, spec);

    clock.advance(Duration.ofDays(7));

    assertThat(registry.evictIdle()).isZero();
    assertThat(registry.isConnected(ID)).isTrue();
    registry.close();
  }

  @Test
  void evictsASessionTheDriverClosedUnderneathUs() {
    MutableClock clock = new MutableClock(Instant.parse("2026-08-17T10:00:00Z"));
    CqlSession session = fakeSession("5.0.2");
    CachingSessionRegistry registry =
        new CachingSessionRegistry(spec -> session, Duration.ofMinutes(30), clock, false);
    registry.open(ID, spec);

    when(session.isClosed()).thenReturn(true);

    assertThat(registry.evictIdle()).isEqualTo(1);
  }

  /* ------------------------------------------------------------------ re-probe */

  @Test
  void reprobeRefreshesTheCachedCapabilities() {
    CqlSession session = fakeSession("4.1.3");
    CachingSessionRegistry registry = registry(spec -> session);
    registry.open(ID, spec);
    assertThat(registry.capabilities(ID).supports(Capability.SAI)).isFalse();

    // The operator upgraded the cluster; a re-probe must see it.
    stubRelease(session, "5.0.2");
    assertThat(registry.reprobe(ID).supports(Capability.SAI)).isTrue();
    assertThat(registry.capabilities(ID).supports(Capability.SAI)).isTrue();
    registry.close();
  }

  @Test
  void reprobeThrowsWhenNotConnected() {
    CachingSessionRegistry registry = registry(spec -> fakeSession("5.0.2"));

    assertThatThrownBy(() -> registry.reprobe(ID)).isInstanceOf(ConnectionNotOpenException.class);
    registry.close();
  }

  @Test
  void defaultTtlIsThirtyMinutes() {
    try (ManagedSessionRegistry registry =
        new CachingSessionRegistry(spec -> fakeSession("5.0.2"))) {
      assertThat(registry.idleTimeout()).isEqualTo(Duration.ofMinutes(30));
    }
  }

  @Test
  void rejectsNullArguments() {
    CachingSessionRegistry registry = registry(spec -> fakeSession("5.0.2"));

    assertThatThrownBy(() -> registry.open(null, spec)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> registry.open(ID, null)).isInstanceOf(NullPointerException.class);
    registry.close();
  }

  @Test
  void closingTheRegistryTwiceIsSafe() {
    CachingSessionRegistry registry = registry(spec -> fakeSession("5.0.2"));
    registry.open(ID, spec);

    registry.close();
    registry.close();

    assertThat(registry.handles()).isEmpty();
  }

  @Test
  void aBackgroundSweeperIsStartedForTheDefaultConfiguration() {
    // Exercises the sweeping constructor path; the sweep itself is asserted deterministically
    // through evictIdle() above rather than by sleeping.
    try (ManagedSessionRegistry registry =
        new CachingSessionRegistry(spec -> fakeSession("5.0.2"), Duration.ofMinutes(1))) {
      assertThat(registry.idleTimeout()).isEqualTo(Duration.ofMinutes(1));
    }
  }

  @Test
  void countsOpensForTheSameSessionFactoryOnce() {
    AtomicInteger built = new AtomicInteger();
    CachingSessionRegistry registry =
        registry(
            spec -> {
              built.incrementAndGet();
              return fakeSession("5.0.2");
            });
    registry.open(ID, spec);
    registry.close(ID);
    registry.open(ID, spec);

    assertThat(built).hasValue(2);
    registry.close();
  }

  /* ------------------------------------------------------------------ fixtures */

  private static CachingSessionRegistry registry(SessionFactory factory) {
    return new CachingSessionRegistry(factory, Duration.ofMinutes(30), Clock.systemUTC(), false);
  }

  private static CqlSession fakeSession(String releaseVersion) {
    CqlSession session = mock(CqlSession.class, RETURNS_DEEP_STUBS);
    when(session.isClosed()).thenReturn(false);
    when(session.getMetadata().getNodes()).thenReturn(Map.of());
    when(session.getMetadata().getKeyspaces()).thenReturn(Map.of());
    when(session.getContext().getProtocolVersion()).thenReturn(ProtocolVersion.V5);
    stubRelease(session, releaseVersion);
    return session;
  }

  private static void stubRelease(CqlSession session, String releaseVersion) {
    when(session.execute(anyString()))
        .thenAnswer(
            invocation -> {
              String cql = invocation.getArgument(0);
              if (cql.contains("system.versions")) {
                throw new IllegalStateException("unconfigured table versions");
              }
              ColumnDefinitions definitions = mock(ColumnDefinitions.class);
              when(definitions.contains(anyString()))
                  .thenAnswer(i -> "release_version".equals(i.getArgument(0)));
              Row row = mock(Row.class);
              when(row.getColumnDefinitions()).thenReturn(definitions);
              when(row.getString(anyString()))
                  .thenAnswer(
                      i -> "release_version".equals(i.getArgument(0)) ? releaseVersion : null);
              ResultSet resultSet = mock(ResultSet.class);
              when(resultSet.one()).thenReturn(row);
              return resultSet;
            });
  }

  private static final class FakeTunnel implements SshTunnel {

    private final int port;
    private boolean closed;

    private FakeTunnel(int port) {
      this.port = port;
    }

    @Override
    public int localPort() {
      return port;
    }

    @Override
    public boolean isOpen() {
      return !closed;
    }

    @Override
    public void close() {
      closed = true;
    }
  }

  /** A clock the test moves by hand, so idle eviction is asserted without sleeping. */
  private static final class MutableClock extends Clock {

    private Instant now;

    private MutableClock(Instant now) {
      this.now = now;
    }

    private void advance(Duration by) {
      now = now.plus(by);
    }

    @Override
    public ZoneOffset getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
