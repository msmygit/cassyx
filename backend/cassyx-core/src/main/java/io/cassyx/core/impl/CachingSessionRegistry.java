package io.cassyx.core.impl;

import com.datastax.oss.driver.api.core.CqlSession;
import io.cassyx.core.api.CapabilityProbe;
import io.cassyx.core.api.CassyxCoreException;
import io.cassyx.core.api.ClusterCapabilities;
import io.cassyx.core.api.ClusterFlavor;
import io.cassyx.core.api.ClusterProbeResult;
import io.cassyx.core.api.ConnectionNotOpenException;
import io.cassyx.core.api.ConnectionSpec;
import io.cassyx.core.api.CoreFactory;
import io.cassyx.core.api.ManagedSessionRegistry;
import io.cassyx.core.api.SessionFactory;
import io.cassyx.core.api.SessionHandle;
import io.cassyx.core.api.SshTunnel;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link ManagedSessionRegistry} backed by a concurrent map with idle eviction (plan section 3).
 *
 * <p>Design points worth stating, because each is a bug somebody would otherwise reintroduce:
 *
 * <ul>
 *   <li><b>One session per connection.</b> {@link #open} is idempotent and uses
 *       {@code computeIfAbsent}-style locking per connection id, so two simultaneous Connect clicks
 *       build one session, not two. Building two would double every connection pool against the
 *       cluster and leak one of them.
 *   <li><b>Eviction is by idleness, not age.</b> A session in continuous use is never closed under
 *       the user; one nobody has touched for the TTL is. {@link #session} bumps the timestamp, so
 *       the TTL measures "unused", which is what the plan means by idle.
 *   <li><b>Closing tears down everything.</b> The SSH tunnel and any materialized temp files
 *       (the decrypted secure connect bundle) are closed with the session. A leaked tunnel is an
 *       open hole into the customer's network; a leaked bundle is a plaintext credential on disk.
 *   <li><b>Nothing here logs a credential.</b> {@link ConnectionSpec#toString()} redacts, and the
 *       only identifiers logged are the connection id and name.
 * </ul>
 */
public final class CachingSessionRegistry implements ManagedSessionRegistry {

  private static final Logger LOG = LoggerFactory.getLogger(CachingSessionRegistry.class);

  private final SessionFactory sessionFactory;
  private final Duration idleTimeout;
  private final Clock clock;
  private final Map<String, Entry> entries = new ConcurrentHashMap<>();
  private final Map<String, Object> locks = new ConcurrentHashMap<>();
  private final ScheduledExecutorService evictor;
  private final AtomicLong sessionCounter = new AtomicLong();
  private volatile boolean closed;

  public CachingSessionRegistry(SessionFactory sessionFactory) {
    this(sessionFactory, Duration.ofSeconds(DEFAULT_IDLE_TIMEOUT_SECONDS), Clock.systemUTC(), true);
  }

  public CachingSessionRegistry(SessionFactory sessionFactory, Duration idleTimeout) {
    this(sessionFactory, idleTimeout, Clock.systemUTC(), true);
  }

  /**
   * @param idleTimeout {@link Duration#ZERO} or negative disables eviction entirely, which is what
   *     you want in a test that controls the clock itself
   * @param sweep whether to run the background sweeper; tests drive {@link #evictIdle()} by hand
   */
  public CachingSessionRegistry(
      SessionFactory sessionFactory, Duration idleTimeout, Clock clock, boolean sweep) {
    this.sessionFactory = Objects.requireNonNull(sessionFactory, "sessionFactory");
    this.idleTimeout =
        idleTimeout == null ? Duration.ofSeconds(DEFAULT_IDLE_TIMEOUT_SECONDS) : idleTimeout;
    this.clock = clock == null ? Clock.systemUTC() : clock;
    if (sweep && !this.idleTimeout.isZero() && !this.idleTimeout.isNegative()) {
      this.evictor =
          Executors.newSingleThreadScheduledExecutor(
              runnable -> {
                Thread thread = new Thread(runnable, "cassyx-session-evictor");
                thread.setDaemon(true);
                return thread;
              });
      long periodSeconds = Math.max(30, this.idleTimeout.toSeconds() / 4);
      this.evictor.scheduleWithFixedDelay(
          this::evictIdleQuietly, periodSeconds, periodSeconds, TimeUnit.SECONDS);
    } else {
      this.evictor = null;
    }
  }

  @Override
  public CqlSession session(String connectionId) {
    return entry(connectionId).touch(clock.instant()).session;
  }

  @Override
  public boolean isConnected(String connectionId) {
    Entry entry = connectionId == null ? null : entries.get(connectionId);
    return entry != null && !entry.session.isClosed();
  }

  @Override
  public ClusterCapabilities capabilities(String connectionId) {
    return entry(connectionId).probe.capabilities();
  }

  @Override
  public SessionHandle open(String connectionId, ConnectionSpec spec, SshTunnel tunnel) {
    Objects.requireNonNull(connectionId, "connectionId");
    Objects.requireNonNull(spec, "spec");
    if (closed) {
      throw new CassyxCoreException("The session registry is shut down");
    }

    // Idempotent by contract: POST /connect on an already-connected connection returns the
    // existing state. Check outside the lock first so the common case costs a map read.
    Entry existing = entries.get(connectionId);
    if (existing != null && !existing.session.isClosed()) {
      closeQuietly(tunnel);
      return existing.touch(clock.instant()).handle(connectionId, idleTimeout);
    }

    synchronized (lockFor(connectionId)) {
      existing = entries.get(connectionId);
      if (existing != null && !existing.session.isClosed()) {
        closeQuietly(tunnel);
        return existing.touch(clock.instant()).handle(connectionId, idleTimeout);
      }
      if (existing != null) {
        // Stale entry for a session the driver closed underneath us.
        existing.shutdown();
        entries.remove(connectionId);
      }

      CqlSession session;
      try {
        session = sessionFactory.open(spec);
      } catch (RuntimeException e) {
        closeQuietly(tunnel);
        throw e;
      }

      ClusterProbeResult probe;
      try {
        probe = runProbe(session, spec);
      } catch (RuntimeException e) {
        // A session we cannot probe is still a working session; degrade rather than fail the
        // connect. The UI shows "unknown" capabilities, which is honest.
        LOG.warn(
            "Capability probe failed for connection '{}' ({}); continuing with UNKNOWN capabilities",
            spec.name(),
            e.getClass().getSimpleName());
        probe = ClusterProbeResult.unknown();
      }

      Instant now = clock.instant();
      Entry entry =
          new Entry(
              session,
              probe,
              tunnel,
              spec.name(),
              "sess_" + sessionCounter.incrementAndGet(),
              now);
      entries.put(connectionId, entry);
      LOG.info(
          "Opened session {} for connection '{}' ({})",
          entry.sessionId,
          spec.name(),
          probe.flavor());
      return entry.handle(connectionId, idleTimeout);
    }
  }

  private ClusterProbeResult runProbe(CqlSession session, ConnectionSpec spec) {
    ClusterFlavor hint = spec.isAstra() ? ClusterFlavor.ASTRA : null;
    for (CapabilityProbe probe : CoreFactory.capabilityProbes()) {
      Optional<ClusterProbeResult> result = probe.probeCluster(session, hint);
      if (result.isPresent()) {
        return result.get();
      }
    }
    return new DefaultCapabilityProbe(clock)
        .probeCluster(session, hint)
        .orElseGet(ClusterProbeResult::unknown);
  }

  @Override
  public boolean close(String connectionId) {
    if (connectionId == null) {
      return false;
    }
    Entry entry = entries.remove(connectionId);
    if (entry == null) {
      return false;
    }
    entry.shutdown();
    LOG.info("Closed session {} for connection '{}'", entry.sessionId, entry.connectionName);
    return true;
  }

  @Override
  public Optional<SessionHandle> handle(String connectionId) {
    Entry entry = connectionId == null ? null : entries.get(connectionId);
    return entry == null || entry.session.isClosed()
        ? Optional.empty()
        : Optional.of(entry.handle(connectionId, idleTimeout));
  }

  @Override
  public List<SessionHandle> handles() {
    List<SessionHandle> handles = new ArrayList<>();
    entries.forEach(
        (id, entry) -> {
          if (!entry.session.isClosed()) {
            handles.add(entry.handle(id, idleTimeout));
          }
        });
    handles.sort(Comparator.comparing(SessionHandle::connectedAt));
    return List.copyOf(handles);
  }

  @Override
  public Optional<ClusterProbeResult> probe(String connectionId) {
    Entry entry = connectionId == null ? null : entries.get(connectionId);
    return entry == null ? Optional.empty() : Optional.of(entry.probe);
  }

  @Override
  public ClusterProbeResult reprobe(String connectionId) {
    Entry entry = entry(connectionId);
    ClusterProbeResult refreshed =
        new DefaultCapabilityProbe(clock)
            .probeCluster(entry.session, entry.probe.flavor())
            .orElseGet(ClusterProbeResult::unknown);
    entry.probe = refreshed;
    return refreshed;
  }

  @Override
  public Duration idleTimeout() {
    return idleTimeout;
  }

  /**
   * Closes every session that has not been touched within the TTL.
   *
   * @return how many were evicted. Package-visible and deterministic so the eviction rule can be
   *     tested against a fixed clock instead of by sleeping.
   */
  public int evictIdle() {
    if (idleTimeout.isZero() || idleTimeout.isNegative()) {
      return 0;
    }
    Instant cutoff = clock.instant().minus(idleTimeout);
    int evicted = 0;
    for (Map.Entry<String, Entry> mapEntry : List.copyOf(entries.entrySet())) {
      Entry entry = mapEntry.getValue();
      if (entry.lastAccess.isBefore(cutoff) || entry.session.isClosed()) {
        if (entries.remove(mapEntry.getKey(), entry)) {
          entry.shutdown();
          evicted++;
          LOG.info(
              "Evicted idle session {} for connection '{}' after {}",
              entry.sessionId,
              entry.connectionName,
              idleTimeout);
        }
      }
    }
    return evicted;
  }

  private void evictIdleQuietly() {
    try {
      evictIdle();
    } catch (RuntimeException e) {
      LOG.warn("Session eviction sweep failed: {}", e.getClass().getSimpleName());
    }
  }

  @Override
  public void close() {
    closed = true;
    if (evictor != null) {
      evictor.shutdownNow();
    }
    for (String id : List.copyOf(entries.keySet())) {
      close(id);
    }
  }

  /** Live session count, for {@code ServiceHealth.liveSessions}. */
  public int size() {
    return entries.size();
  }

  private Entry entry(String connectionId) {
    Entry entry = connectionId == null ? null : entries.get(connectionId);
    if (entry == null || entry.session.isClosed()) {
      throw new ConnectionNotOpenException(String.valueOf(connectionId));
    }
    return entry;
  }

  /**
   * Per-connection lock, so opening connection A never blocks opening connection B - which matters
   * because opening takes seconds. Deliberately not {@code String.intern()}: interned strings are
   * shared with every other user of that literal in the JVM.
   */
  private Object lockFor(String connectionId) {
    return locks.computeIfAbsent(connectionId, id -> new Object());
  }

  private static void closeQuietly(SshTunnel tunnel) {
    if (tunnel != null) {
      tunnel.close();
    }
  }

  /** Mutable only in {@code lastAccess} and {@code probe}; everything else is final. */
  private static final class Entry {

    private final CqlSession session;
    private final SshTunnel tunnel;
    private final String connectionName;
    private final String sessionId;
    private final Instant connectedAt;
    private volatile ClusterProbeResult probe;
    private volatile Instant lastAccess;

    private Entry(
        CqlSession session,
        ClusterProbeResult probe,
        SshTunnel tunnel,
        String connectionName,
        String sessionId,
        Instant now) {
      this.session = session;
      this.probe = probe;
      this.tunnel = tunnel;
      this.connectionName = connectionName;
      this.sessionId = sessionId;
      this.connectedAt = now;
      this.lastAccess = now;
    }

    private Entry touch(Instant now) {
      this.lastAccess = now;
      return this;
    }

    private SessionHandle handle(String connectionId, Duration idleTimeout) {
      return new SessionHandle(
          connectionId,
          connectionName,
          sessionId,
          connectedAt,
          lastAccess,
          lastAccess.plus(idleTimeout),
          tunnel != null && tunnel.isOpen(),
          probe);
    }

    private void shutdown() {
      // Order matters: the session first, so in-flight requests fail fast against a closing pool
      // rather than against a tunnel that has already vanished.
      try {
        session.close();
      } catch (RuntimeException e) {
        LOG.debug("Ignoring error while closing session {}", sessionId, e);
      }
      if (tunnel != null) {
        tunnel.close();
      }
    }
  }
}
