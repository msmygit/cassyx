package io.cassyx.core.api;

import com.datastax.oss.driver.api.core.CqlSession;

/**
 * Builds {@link CqlSession} instances from a {@link ConnectionSpec}.
 *
 * <p>Sessions are expensive and thread-safe: one per connection, never one per request (plan
 * section 3).
 */
public interface SessionFactory {

  /**
   * Opens a new session. The caller owns the returned session and must close it.
   *
   * @throws CassyxCoreException if the cluster cannot be reached or the spec is invalid
   */
  CqlSession open(ConnectionSpec spec);
}
