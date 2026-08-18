package io.cassyx.api.health;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/health} - one of the three ungated paths (plan section 9.1), and what the Docker
 * Compose health check and the UI's connected indicator poll.
 *
 * <p>The response shape is the {@code ServiceHealth} schema in {@code openapi/cassyx-api.yaml}.
 * Per plan section 2.3 the contract governs: {@code status} and {@code version} are both required,
 * so this must not be reduced back to a bare status map. The remaining {@code ServiceHealth} fields
 * are optional and are filled in by the workstreams that own them - session and job counts by A and
 * D/E respectively.
 */
@RestController
public class HealthController {

  private final String version;
  private final boolean licenseEnforced;

  public HealthController(
      @Value("${cassyx.version:0.1.0-SNAPSHOT}") String version,
      @Value("${cassyx.license.enforce:true}") boolean licenseEnforced) {
    this.version = version;
    this.licenseEnforced = licenseEnforced;
  }

  @GetMapping("/api/health")
  public ServiceHealth health() {
    return new ServiceHealth(Status.UP, version, licenseEnforced);
  }

  /** Mirrors the {@code ServiceHealth.status} enum in the contract. */
  public enum Status {
    UP,
    DEGRADED,
    DOWN
  }

  /**
   * Subset of the contract's {@code ServiceHealth}. Both {@code status} and {@code version} are
   * required by the schema; {@code licenseEnforced} is optional but cheap to supply here and lets
   * the UI render the bypass banner without a second round trip.
   */
  public record ServiceHealth(Status status, String version, boolean licenseEnforced) {}
}
