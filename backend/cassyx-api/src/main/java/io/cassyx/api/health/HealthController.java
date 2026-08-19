package io.cassyx.api.health;

import io.cassyx.api.config.CassyxVersion;
import io.cassyx.license.api.BypassPolicy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.info.BuildProperties;
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

  /**
   * {@code cassyx.version} was never set anywhere, so this used to report a hardcoded default that
   * was wrong the moment the reactor version moved. The version now comes from {@link
   * CassyxVersion}, whose source of truth is the {@code build-info} goal of spring-boot-maven-plugin
   * and which degrades to {@code unknown} rather than to a plausible-looking lie.
   *
   * <p>{@code licenseEnforced} is likewise the EFFECTIVE value (plan section 9.2): a release build
   * ignores {@code CASSYX_LICENSE_ENFORCE=false}, and reporting the raw flag here would have this
   * endpoint claim enforcement is off while the gate refuses every request.
   */
  public HealthController(
      ObjectProvider<BuildProperties> buildProperties,
      @Value("${cassyx.version:}") String configuredVersion,
      @Value("${spring.application.version:}") String springApplicationVersion,
      @Value("${cassyx.license.enforce:true}") boolean enforce,
      @Value("${cassyx.license.bypass-allowed:true}") boolean bypassAllowed) {
    this.version =
        new CassyxVersion(buildProperties, configuredVersion, springApplicationVersion).value();
    this.licenseEnforced = BypassPolicy.of(enforce, bypassAllowed).enforcing();
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
