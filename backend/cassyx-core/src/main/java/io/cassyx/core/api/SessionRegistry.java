package io.cassyx.core.api;

import com.datastax.oss.driver.api.core.CqlSession;

/**
 * Live sessions, keyed by saved-connection id.
 *
 * <p>SHARED SEAM - owned and implemented by workstream A (plan section 3), consumed by every
 * feature workstream. Schema browsing, query execution, bulk unload and vector/ANN all need a live
 * session for a connection and none of them should know how it was built: contact points versus an
 * Astra secure connect bundle, whether an SSH tunnel had to come up first, or where the credentials
 * were decrypted from.
 *
 * <p><b>One session per connection, never one per request.</b> {@link CqlSession} is expensive to
 * build (it opens pooled connections to every node and subscribes to schema events) and is fully
 * thread-safe, so it is cached and shared. Implementations evict on idleness rather than on age, so
 * a session nobody has touched for the configured TTL is closed while one in continuous use is not.
 *
 * <p>This interface is deliberately tiny and read-only. Opening and closing sessions is
 * {@link ManagedSessionRegistry}, which only the connections layer holds; everyone else takes a
 * {@code SessionRegistry} and therefore cannot close a session another feature is mid-query on.
 */
public interface SessionRegistry {

  /** Default idle-eviction TTL in seconds (plan section 3: "idle-eviction TTL, default 30 min"). */
  int DEFAULT_IDLE_TIMEOUT_SECONDS = 1800;

  /**
   * The live session for a connection, marking it as recently used so the idle timer restarts.
   *
   * @return the live session for the connection
   * @throws ConnectionNotOpenException (a {@link CassyxCoreException}) if the connection has no
   *     live session. Callers must not silently open one: opening takes seconds, needs credentials,
   *     and may need an SSH tunnel.
   */
  CqlSession session(String connectionId);

  /** Cheap check that never throws; drives the UI's connected indicator. */
  boolean isConnected(String connectionId);

  /**
   * Capabilities detected at connect time (plan section 7.1), cached for the session's lifetime.
   *
   * @throws ConnectionNotOpenException if the connection has no live session
   */
  ClusterCapabilities capabilities(String connectionId);
}
