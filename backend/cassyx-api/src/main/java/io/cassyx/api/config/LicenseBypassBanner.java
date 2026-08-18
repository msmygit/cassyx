package io.cassyx.api.config;

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
    if (!properties.enforce()) {
      LOG.warn(
          "LICENSE ENFORCEMENT IS DISABLED (cassyx.license.enforce=false). "
              + "This instance reports edition '{}' and is fully unlocked. "
              + "Intended for development, CI, evaluation and enterprise site licences only.",
          License.BYPASS_EDITION);
    }
  }
}
