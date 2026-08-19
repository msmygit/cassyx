package io.cassyx.api.config;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Plan section 9.2. Both halves of the startup banner are asserted on the real log output, because
 * the WARN is the only thing standing between an operator and two silent misreadings: "this paid-
 * looking instance is actually bypassed", and "I set CASSYX_LICENSE_ENFORCE=false and nothing
 * happened, so the product is broken".
 */
class LicenseBypassBannerTest {

  private ListAppender<ILoggingEvent> appender;
  private Logger logger;

  @BeforeEach
  void captureLogs() {
    logger = (Logger) LoggerFactory.getLogger(LicenseBypassBanner.class);
    appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
  }

  @AfterEach
  void releaseLogs() {
    logger.detachAppender(appender);
  }

  private List<ILoggingEvent> warnAfter(boolean enforce, boolean bypassAllowed) {
    new LicenseBypassBanner(new LicenseProperties(enforce, bypassAllowed, "", "PLACEHOLDER"))
        .warnIfBypassed();
    return appender.list.stream().filter(e -> e.getLevel() == Level.WARN).toList();
  }

  @Test
  void devBuildWithTheFlagSetShoutsThatEverythingIsUnlocked() {
    List<ILoggingEvent> warnings = warnAfter(false, true);

    assertThat(warnings).hasSize(1);
    assertThat(warnings.get(0).getFormattedMessage())
        .contains("LICENSE ENFORCEMENT IS DISABLED")
        .contains("unlicensed-bypass")
        // Points at the alternative that works everywhere, so nobody builds a habit on the flag.
        .contains("site");
  }

  @Test
  void releaseBuildShoutsThatItIgnoredTheFlagAndNamesTheEnvVar() {
    List<ILoggingEvent> warnings = warnAfter(false, false);

    assertThat(warnings).hasSize(1);
    assertThat(warnings.get(0).getFormattedMessage())
        .contains("CASSYX_LICENSE_ENFORCE=false")
        .contains("IGNORED")
        .contains("cassyx.license.bypass-allowed=false");
  }

  @Test
  void staysQuietWhenEnforcementWasNeverTurnedOff() {
    assertThat(warnAfter(true, true)).isEmpty();
    assertThat(warnAfter(true, false)).isEmpty();
  }
}
