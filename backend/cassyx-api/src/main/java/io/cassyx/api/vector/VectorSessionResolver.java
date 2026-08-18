package io.cassyx.api.vector;

import com.datastax.oss.driver.api.core.CqlSession;
import java.util.function.Function;

/**
 * Resolves a connection id to a live {@link CqlSession}.
 *
 * <p><b>Why this exists.</b> cassyx-vector takes a {@code CqlSession} directly - the plan section
 * 2.1 rule that every module is usable with nothing but a session. The session <i>registry</i>
 * (keyed by {@code (userId, connectionId)}, with idle eviction) is workstream A's, and this
 * controller must boot and be testable whether or not it exists yet. So the controller depends on
 * this one-method seam, and {@link VectorConfiguration} supplies a fallback that fails legibly.
 *
 * <p><b>Wiring note for workstream A:</b> publish a bean of this type (or of
 * {@code Function<String, CqlSession>}) delegating to the registry, and the fallback backs off
 * automatically - it is {@code @ConditionalOnMissingBean}.
 */
@FunctionalInterface
public interface VectorSessionResolver {

  /**
   * @throws NoLiveSessionException when the connection has no live session
   */
  CqlSession resolve(String connectionId);

  /** Adapts the plain {@code Function} form workstream A may publish instead. */
  static VectorSessionResolver of(Function<String, CqlSession> function) {
    return connectionId -> {
      CqlSession session = function.apply(connectionId);
      if (session == null) {
        throw new NoLiveSessionException(connectionId);
      }
      return session;
    };
  }

  /** Maps to the contract's {@code NotConnected} 409 problem. */
  class NoLiveSessionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public NoLiveSessionException(String connectionId) {
      super("Connection \"" + connectionId + "\" has no live session. Connect it first.");
    }

    public NoLiveSessionException(String connectionId, String detail) {
      super("Connection \"" + connectionId + "\" has no live session. " + detail);
    }
  }
}
