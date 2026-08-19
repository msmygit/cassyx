package io.cassyx.api.config;

import io.cassyx.license.api.BypassPolicy;
import io.cassyx.license.api.License;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Plan section 9.2: when {@code cassyx.license.enforce=false} the whole product is unlocked, so log
 * a WARN at startup. The UI keeps its own banner up - a bypassed instance must never be mistaken
 * for a paid one.
 *
 * <p>A release build refuses that flag ({@code cassyx.license.bypass-allowed=false}), and the
 * refusal is logged just as loudly. Silently ignoring a flag the operator deliberately set is how
 * you get a bug report saying the licence check is broken.
 */
@Component
public class LicenseBypassBanner {

  private static final Logger LOG = LoggerFactory.getLogger(LicenseBypassBanner.class);

  private final LicenseProperties properties;

  public LicenseBypassBanner(LicenseProperties properties) {
    this.properties = properties;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void warnIfBypassed() {
    BypassPolicy policy = BypassPolicy.of(properties.enforce(), properties.bypassAllowed());
    if (policy.granted()) {
      LOG.warn(
          "LICENSE ENFORCEMENT IS DISABLED (cassyx.license.enforce=false). "
              + "This instance reports edition '{}' and is fully unlocked. "
              + "Intended for development and CI only; for CI, evaluation and enterprise use "
              + "prefer a free signed site licence (edition '{}'), which works in every build.",
          License.BYPASS_EDITION,
          License.SITE_EDITION);
    } else if (policy.refused()) {
      LOG.warn(policy.refusalWarning());
    }
  }
}
