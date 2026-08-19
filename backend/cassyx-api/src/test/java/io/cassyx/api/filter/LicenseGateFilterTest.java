package io.cassyx.api.filter;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.cassyx.api.config.CassyxVersion;
import io.cassyx.api.license.LicenseGate;
import io.cassyx.api.license.TestLicenses;
import jakarta.servlet.ServletException;
import java.io.IOException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * The server-side gate of plan section 9.1. Before this existed the ONLY licence check was
 * {@code frontend/src/license/LicenseGate.tsx}, so anyone could curl {@code /api/**} and get the
 * entire product - every other part of the monetization stack was guarding a door with no lock.
 *
 * <p>These tests drive the filter directly with mock servlet objects rather than through MockMvc:
 * the question is what happens to a raw HTTP request, and a controller slice would answer a
 * narrower one.
 */
class LicenseGateFilterTest {

  private static final ObjectMapper MAPPER = Jackson2ObjectMapperBuilder.json().build();

  /** The shipped default: enforcement on, no key. Nothing gated may be served. */
  private static LicenseGateFilter unlicensed() {
    return filter(TestLicenses.publicKey(), "", true, true, "1.0.0");
  }

  private static LicenseGateFilter filter(
      String publicKey, String key, boolean enforce, boolean bypassAllowed, String version) {
    return new LicenseGateFilter(
        new LicenseGate(publicKey, key, enforce, bypassAllowed, CassyxVersion.of(version)), MAPPER);
  }

  private static MockHttpServletResponse run(LicenseGateFilter filter, String path)
      throws ServletException, IOException {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
    request.setRequestURI(path);
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(request, response, new MockFilterChain());
    return response;
  }

  private static boolean allowed(LicenseGateFilter filter, String path)
      throws ServletException, IOException {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
    request.setRequestURI(path);
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();
    filter.doFilter(request, response, chain);
    return chain.getRequest() != null;
  }

  // ---- which paths the gate applies to at all ----

  @ParameterizedTest
  @DisplayName("The three ungated paths stay reachable on a completely unlicensed instance")
  @ValueSource(
      strings = {
        "/api/health",
        "/api/license",
        "/api/license/activate",
        "/api/billing/checkout",
        "/api/billing/webhook"
      })
  void ungatedPathsAreReachableUnlicensed(String path) throws Exception {
    // Lose any of these and the product cannot report its own state, be activated, or be bought -
    // an unlicensed instance would have no route back to being a licensed one.
    assertThat(allowed(unlicensed(), path)).as(path).isTrue();
  }

  @ParameterizedTest
  @DisplayName("SPA and static paths are never touched")
  @ValueSource(strings = {"/", "/index.html", "/activate", "/assets/index-abc123.js", "/favicon.ico",
      "/actuator/health"})
  void nonApiPathsPassThroughUntouched(String path) throws Exception {
    // The activation screen is served from here. Gating it would leave a locked instance with no UI
    // in which to unlock itself.
    assertThat(unlicensed().shouldNotFilter(request(path))).as(path).isTrue();
    assertThat(allowed(unlicensed(), path)).as(path).isTrue();
  }

  @ParameterizedTest
  @DisplayName("Everything else under /api is gated")
  @ValueSource(strings = {"/api/connections", "/api/schema/keyspaces", "/api/query", "/api/jobs/1"})
  void apiPathsAreGated(String path) throws Exception {
    assertThat(allowed(unlicensed(), path)).as(path).isFalse();
  }

  @Test
  @DisplayName("An ungated prefix ungates its subtree and nothing else")
  void prefixMatchingDoesNotLeak() throws Exception {
    // "/api/licenseholders".startsWith("/api/license") is true, which is exactly how an
    // ungated-path list quietly grows a hole.
    assertThat(allowed(unlicensed(), "/api/licenseholders")).isFalse();
    assertThat(allowed(unlicensed(), "/api/healthcheck")).isFalse();
    assertThat(allowed(unlicensed(), "/api/license/activate")).isTrue();
  }

  @Test
  @DisplayName("A servlet context path is stripped before matching")
  void contextPathIsStripped() {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/cassyx/api/health");
    request.setRequestURI("/cassyx/api/health");
    request.setContextPath("/cassyx");
    assertThat(LicenseGateFilter.pathWithinApplication(request)).isEqualTo("/api/health");
  }

  // ---- what unlocks a gated path ----

  @Test
  @DisplayName("A valid paid key unlocks gated paths")
  void validKeyAllows() throws Exception {
    LicenseGateFilter filter =
        filter(TestLicenses.publicKey(), TestLicenses.standard(), true, true, "1.0.0");
    assertThat(allowed(filter, "/api/connections")).isTrue();
  }

  @Test
  @DisplayName("A live trial unlocks gated paths (plan 9.4)")
  void validTrialAllows() throws Exception {
    LicenseGateFilter filter =
        filter(TestLicenses.publicKey(), TestLicenses.trial(), true, true, "1.0.0");
    assertThat(allowed(filter, "/api/connections")).isTrue();
  }

  @Test
  @DisplayName("A site licence unlocks a RELEASE build, with no flag involved (plan 9.2)")
  void siteLicenceAllowsInAReleaseBuild() throws Exception {
    LicenseGateFilter filter =
        filter(TestLicenses.publicKey(), TestLicenses.site(), true, false, "1.0.0");
    assertThat(allowed(filter, "/api/connections")).isTrue();
  }

  // ---- what does not ----

  @Test
  @DisplayName("ABSENT: no key at all")
  void refusesWhenAbsent() throws Exception {
    assertRefused(unlicensed(), "ABSENT");
  }

  @Test
  @DisplayName("EXPIRED: a genuine key past its date")
  void refusesWhenExpired() throws Exception {
    assertRefused(
        filter(TestLicenses.publicKey(), TestLicenses.expiredTrial(), true, true, "1.0.0"),
        "EXPIRED");
  }

  @Test
  @DisplayName("INVALID_SIGNATURE: well-formed, not ours")
  void refusesWhenSignatureIsForged() throws Exception {
    assertRefused(
        filter(TestLicenses.publicKey(), TestLicenses.tampered(), true, true, "1.0.0"),
        "INVALID_SIGNATURE");
  }

  @Test
  @DisplayName("UPGRADE_REQUIRED: a genuine key bought for an older major (plan 9.5)")
  void refusesWhenScopeIsTooOld() throws Exception {
    assertRefused(
        filter(TestLicenses.publicKey(), TestLicenses.scopedToMajorOne(), true, true, "2.0.0"),
        "UPGRADE_REQUIRED");
  }

  @Test
  @DisplayName("A scope:1 key unlocks the 1.x release it was sold for")
  void scopedKeyStillUnlocksTheVersionItBought() throws Exception {
    // The other half of plan section 9.5: a stale key is a reason to withhold a NEW release, never
    // to break a running install. Without this the previous test would pass just as well if the
    // gate refused every scoped key outright.
    LicenseGateFilter filter =
        filter(TestLicenses.publicKey(), TestLicenses.scopedToMajorOne(), true, true, "1.4.2");
    assertThat(allowed(filter, "/api/connections")).isTrue();
  }

  @Test
  @DisplayName("MALFORMED: the public key itself is unusable, and that must not open the gate")
  void refusesWhenPublicKeyIsAPlaceholder() throws Exception {
    // A config gap must be diagnosable (/api/license still answers MALFORMED) but never permissive.
    assertRefused(filter("PLACEHOLDER", TestLicenses.standard(), true, true, "1.0.0"), "MALFORMED");
  }

  // ---- the bypass, and the build-time gate on it (plan 9.2) ----

  @Test
  @DisplayName("A dev build honours CASSYX_LICENSE_ENFORCE=false")
  void bypassIsHonouredWhenTheBuildAllowsIt() throws Exception {
    LicenseGateFilter filter = filter(TestLicenses.publicKey(), "", false, true, "1.0.0");
    assertThat(allowed(filter, "/api/connections")).isTrue();
  }

  @Test
  @DisplayName("A release build keeps the gate ENFORCED despite CASSYX_LICENSE_ENFORCE=false")
  void bypassIsRefusedInAReleaseBuild() throws Exception {
    // The whole point of the site-licence work: the published image ignores the free switch, so the
    // gate stays shut and the operator needs a signed site licence instead.
    LicenseGateFilter filter = filter(TestLicenses.publicKey(), "", false, false, "1.0.0");
    assertThat(allowed(filter, "/api/connections")).isFalse();
  }

  // ---- the refusal itself ----

  @Test
  @DisplayName("Refusal is 402 with an RFC 9457 problem+json body")
  void refusalIsAProblemDetail() throws Exception {
    MockHttpServletResponse response = run(unlicensed(), "/api/connections");

    assertThat(response.getStatus()).isEqualTo(402);
    assertThat(response.getContentType()).startsWith("application/problem+json");

    JsonNode body = MAPPER.readTree(response.getContentAsString());
    assertThat(body.path("type").asText()).isEqualTo("https://cassyx.dev/problems/license-required");
    assertThat(body.path("title").asText()).isEqualTo("License required");
    assertThat(body.path("status").asInt()).isEqualTo(402);
    assertThat(body.path("instance").asText()).isEqualTo("/api/connections");
    assertThat(body.path("detail").asText()).isNotBlank();
    // The frontend renders a different screen per state; a generic error would collapse them.
    assertThat(body.path("state").asText()).isEqualTo("ABSENT");
    assertThat(body.path("invitesPurchase").asBoolean()).isTrue();
    assertThat(body.path("unlockHint").asText()).isNotBlank();
  }

  @Test
  @DisplayName("A release build's refusal points at a site licence, not at a flag it ignores")
  void refusalAdviceMatchesTheBuild() throws Exception {
    MockHttpServletResponse response =
        run(filter(TestLicenses.publicKey(), "", true, false, "1.0.0"), "/api/connections");

    assertThat(MAPPER.readTree(response.getContentAsString()).path("unlockHint").asText())
        .contains("site licence")
        .doesNotContain("set CASSYX_LICENSE_ENFORCE=false to run unlocked");
  }

  private static void assertRefused(LicenseGateFilter filter, String expectedState)
      throws Exception {
    MockHttpServletResponse response = run(filter, "/api/connections");
    assertThat(response.getStatus()).isEqualTo(402);
    assertThat(MAPPER.readTree(response.getContentAsString()).path("state").asText())
        .isEqualTo(expectedState);
  }

  private static MockHttpServletRequest request(String path) {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
    request.setRequestURI(path);
    return request;
  }
}
