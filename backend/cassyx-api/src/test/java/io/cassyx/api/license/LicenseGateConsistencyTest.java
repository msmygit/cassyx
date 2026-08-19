package io.cassyx.api.license;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.cassyx.api.config.CassyxVersion;
import io.cassyx.api.filter.LicenseGateFilter;
import io.cassyx.license.api.BypassPolicy;
import io.cassyx.license.api.LicenseState;
import io.cassyx.license.api.LicenseStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * The filter and {@code GET /api/license} must never disagree (plan section 9.1).
 *
 * <p>Both directions of disagreement are expensive and neither is obvious from either side alone:
 * an instance that reports itself unlocked while refusing every request looks broken to a customer
 * who paid, and one that reports itself locked while serving every request is the product given
 * away. This asserts the agreement directly, over the whole configuration matrix, rather than
 * trusting that they happen to share a class.
 */
class LicenseGateConsistencyTest {

  private static final ObjectMapper MAPPER = Jackson2ObjectMapperBuilder.json().build();

  @Test
  @DisplayName("Across the whole matrix, the gate opens exactly when /api/license says licensed")
  void theGateAndTheStatusEndpointAlwaysAgree() throws Exception {
    List<String> keys =
        new ArrayList<>(
            List.of(
                "",
                TestLicenses.standard(),
                TestLicenses.site(),
                TestLicenses.trial(),
                TestLicenses.expiredTrial(),
                TestLicenses.scopedToMajorOne(),
                TestLicenses.tampered(),
                "not-a-key"));
    List<String> publicKeys = List.of(TestLicenses.publicKey(), "PLACEHOLDER", TestLicenses.foreignPublicKey());
    List<String> versions = List.of("1.0.0", "2.0.0");

    for (String publicKey : publicKeys) {
      for (String key : keys) {
        for (boolean enforce : new boolean[] {true, false}) {
          for (boolean bypassAllowed : new boolean[] {true, false}) {
            for (String version : versions) {
              // ONE gate, shared - which is the property under test as much as the outcome is.
              LicenseGate gate =
                  new LicenseGate(publicKey, key, enforce, bypassAllowed, CassyxVersion.of(version));
              boolean reported = new LicenseController(gate).current().licensed();
              boolean served = passes(new LicenseGateFilter(gate, MAPPER), "/api/connections");

              assertThat(served)
                  .as(
                      "publicKey=%s key=%s enforce=%s bypassAllowed=%s version=%s",
                      publicKey.length() > 12 ? publicKey.substring(0, 12) : publicKey,
                      key.isEmpty() ? "<none>" : key.substring(0, Math.min(12, key.length())),
                      enforce,
                      bypassAllowed,
                      version)
                  .isEqualTo(reported);
            }
          }
        }
      }
    }
  }

  @Test
  @DisplayName("The state in the 402 body is the state /api/license reports")
  void refusalCarriesTheSameStateTheStatusEndpointReports() throws Exception {
    LicenseGate gate =
        new LicenseGate(
            TestLicenses.publicKey(), TestLicenses.expiredTrial(), true, true, CassyxVersion.of("1.0.0"));

    String reported = new LicenseController(gate).current().state();
    MockHttpServletResponse response = refuse(new LicenseGateFilter(gate, MAPPER));

    // The frontend routes to a per-state screen off this field; if it disagreed with the status
    // endpoint the user would be shown one story and told another.
    assertThat(MAPPER.readTree(response.getContentAsString()).path("state").asText())
        .isEqualTo(reported)
        .isEqualTo("EXPIRED");
  }

  @Test
  @DisplayName("A verifier that throws denies rather than allows, and says so consistently")
  void failsClosedWhenVerificationThrows() throws Exception {
    LicenseGate gate =
        LicenseGate.forTesting(
            key -> {
              throw new IllegalStateException("boom");
            },
            "anything",
            BypassPolicy.of(true, true),
            CassyxVersion.of("1.0.0"),
            Clock.systemUTC());

    // Deny, never allow. The opposite default hands the product away on any bug that reaches here.
    assertThat(passes(new LicenseGateFilter(gate, MAPPER), "/api/connections")).isFalse();
    assertThat(new LicenseController(gate).current().licensed()).isFalse();
  }

  // ---- hot-path caching (plan section 9.1: no re-verification per request) ----

  @Test
  @DisplayName("A burst of requests verifies once, not once per request")
  void verdictIsCachedAcrossRequests() throws Exception {
    AtomicInteger verifications = new AtomicInteger();
    LicenseGate gate = countingGate(verifications, Clock.systemUTC());

    for (int i = 0; i < 50; i++) {
      passes(new LicenseGateFilter(gate, MAPPER), "/api/connections");
    }

    assertThat(verifications.get()).isEqualTo(1);
  }

  @Test
  @DisplayName("The cache expires, so a lapsing licence does not survive until the next restart")
  void cacheIsNotUnbounded() {
    AtomicInteger verifications = new AtomicInteger();
    MutableClock clock = new MutableClock(Instant.parse("2026-08-18T00:00:00Z"));
    LicenseGate gate = countingGate(verifications, clock);

    gate.status();
    gate.status();
    assertThat(verifications.get()).isEqualTo(1);

    clock.advance(LicenseGate.TTL.plusSeconds(1));
    gate.status();
    assertThat(verifications.get()).isEqualTo(2);
  }

  @Test
  @DisplayName("Activation drops the cache, so a customer is not made to wait out the TTL")
  void activationInvalidatesTheCache() {
    AtomicInteger verifications = new AtomicInteger();
    LicenseGate gate = countingGate(verifications, Clock.systemUTC());

    gate.status();
    gate.invalidate();
    gate.status();

    assertThat(verifications.get()).isEqualTo(2);
  }

  private static LicenseGate countingGate(AtomicInteger counter, Clock clock) {
    return LicenseGate.forTesting(
        key -> {
          counter.incrementAndGet();
          return LicenseStatus.invalid("no", LicenseState.ABSENT);
        },
        "",
        BypassPolicy.of(true, true),
        CassyxVersion.of("1.0.0"),
        clock);
  }

  private static boolean passes(LicenseGateFilter filter, String path) throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
    request.setRequestURI(path);
    MockFilterChain chain = new MockFilterChain();
    filter.doFilter(request, new MockHttpServletResponse(), chain);
    return chain.getRequest() != null;
  }

  private static MockHttpServletResponse refuse(LicenseGateFilter filter) throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/connections");
    request.setRequestURI("/api/connections");
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(request, response, new MockFilterChain());
    return response;
  }

  /** Minimal advanceable clock; {@code Clock.offset} would need a new gate per step. */
  private static final class MutableClock extends Clock {
    private Instant now;

    MutableClock(Instant now) {
      this.now = now;
    }

    void advance(Duration by) {
      now = now.plus(by);
    }

    @Override
    public ZoneOffset getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
