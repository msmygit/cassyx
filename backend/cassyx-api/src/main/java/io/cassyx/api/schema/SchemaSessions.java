package io.cassyx.api.schema;

import com.datastax.oss.driver.api.core.CqlSession;
import io.cassyx.core.api.Capability;
import io.cassyx.core.api.CassyxCoreException;
import io.cassyx.core.api.ClusterCapabilities;
import io.cassyx.core.api.SessionRegistry;
import io.cassyx.core.api.schema.UnsupportedCapabilityException;
import org.springframework.stereotype.Component;

/**
 * Resolves a live session and its capabilities for a connection id.
 *
 * <p>Injected directly. This was an {@code ObjectProvider} while workstream A's registry was still
 * in flight; there is now exactly one {@code SessionRegistry} bean, so the optional lookup could
 * only ever return it. Keeping the fallback would mean a wiring mistake degraded silently into
 * "not connected" for every schema call instead of failing at start-up where it is diagnosable.
 */
@Component
public class SchemaSessions {

  private final SessionRegistry registry;

  public SchemaSessions(SessionRegistry registry) {
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
    if (!registry.isConnected(connectionId)) {
      throw new NotConnectedException(connectionId, null);
    }
    return registry;
  }

  /** No live session for this connection - the contract's {@code NotConnected} 409. */
  public static class NotConnectedException extends CassyxCoreException {

    private static final long serialVersionUID = 1L;

    public NotConnectedException(String connectionId, Throwable cause) {
      super("Connection " + connectionId + " has no live session. Connect it first.", cause);
    }
  }
}
