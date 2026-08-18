package io.cassyx.api.capabilities;

import io.cassyx.api.connections.ConnectionSessionService;
import io.cassyx.api.connections.dto.ClusterCapabilitiesView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The {@code capabilities} tag: the connect-time probe result that drives feature gating
 * (plan section 7.1).
 *
 * <p>The UI hides unsupported features behind the {@code reason} text rather than showing them
 * broken. The probe runs once at connect time and is cached for the session's lifetime, because
 * sniffing {@code system.local} on every schema render would be a query per keystroke;
 * {@code ?refresh=true} exists for the case where an operator upgraded the cluster underneath a
 * long-lived session.
 */
@RestController
public class CapabilitiesController {

  private final ConnectionSessionService sessions;

  public CapabilitiesController(ConnectionSessionService sessions) {
    this.sessions = sessions;
  }

  @GetMapping("/api/connections/{connectionId}/capabilities")
  public ClusterCapabilitiesView getClusterCapabilities(
      @PathVariable String connectionId,
      @RequestParam(value = "refresh", defaultValue = "false") boolean refresh) {
    return sessions.capabilities(connectionId, refresh);
  }
}
