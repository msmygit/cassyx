package io.cassyx.api.schema;

import com.datastax.oss.driver.api.core.CqlSession;
import io.cassyx.core.api.Capability;
import io.cassyx.core.api.CassyxCoreException;
import io.cassyx.core.api.ClusterCapabilities;
import io.cassyx.core.api.SessionRegistry;
import io.cassyx.core.api.schema.UnsupportedCapabilityException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Resolves a live session and its capabilities for a connection id.
 *
 * <p>Held through an {@link ObjectProvider} on purpose. {@link SessionRegistry} is workstream A's
 * seam; taking it optionally means the schema endpoints compile, start and answer "not connected"
 * legibly whether or not the registry bean is present yet, instead of failing context startup for
 * every other workstream.
 */
@Component
public class SchemaSessions {

  private final ObjectProvider<SessionRegistry> registry;

  public SchemaSessions(ObjectProvider<SessionRegistry> registry) {
    this.registry = registry;
  }

  /**
   * @throws NotConnectedException mapped to the contract's 409 when there is no live session
   */
  public CqlSession session(String connectionId) {
    SessionRegistry sessions = require(connectionId);
    try {
      return sessions.session(connectionId);
    } catch (CassyxCoreException e) {
      throw new NotConnectedException(connectionId, e);
    }
  }

  /** Capabilities of the connected cluster, or an "unknown" set when detection is unavailable. */
  public ClusterCapabilities capabilities(String connectionId) {
    SessionRegistry sessions = require(connectionId);
    try {
      ClusterCapabilities capabilities = sessions.capabilities(connectionId);
      return capabilities == null ? ClusterCapabilities.unknown() : capabilities;
    } catch (CassyxCoreException e) {
      throw new NotConnectedException(connectionId, e);
    }
  }

  /**
   * Feature gate (plan section 7.1). The UI hides unsupported features behind an explanation; this
   * exists so a direct API call still fails legibly with the contract's 501.
   *
   * <p>An UNKNOWN flavour is allowed through rather than blocked: refusing every DDL against a
   * cluster we could not fingerprint would be worse than letting the server reject it.
   */
  public void require(String connectionId, Capability capability, String explanation) {
    ClusterCapabilities capabilities = capabilities(connectionId);
    if (capabilities.capabilities().isEmpty()) {
      return;
    }
    if (!capabilities.supports(capability)) {
      throw new UnsupportedCapabilityException(
          capability, explanation + " This cluster reports " + capabilities.versionString() + ".");
    }
  }

  private SessionRegistry require(String connectionId) {
    SessionRegistry sessions = registry.getIfAvailable();
    if (sessions == null) {
      throw new NotConnectedException(connectionId, null);
    }
    if (!sessions.isConnected(connectionId)) {
      throw new NotConnectedException(connectionId, null);
    }
    return sessions;
  }

  /** No live session for this connection - the contract's {@code NotConnected} 409. */
  public static class NotConnectedException extends CassyxCoreException {

    private static final long serialVersionUID = 1L;

    public NotConnectedException(String connectionId, Throwable cause) {
      super("Connection " + connectionId + " has no live session. Connect it first.", cause);
    }
  }
}
