package io.cassyx.licensing.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.cassyx.license.api.License;
import io.cassyx.license.api.LicenseStatus;
import io.cassyx.license.api.LicenseVerifier;
import io.cassyx.licensing.LicensingTestKeys;
import io.cassyx.licensing.store.IssuedLicense;
import io.cassyx.licensing.store.IssuedLicenseRepository;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** Trial issuance (plan section 9.4), recovery, and the Stripe webhook path (9.3). */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(
    properties = {"spring.datasource.url=jdbc:h2:mem:cassyx-licensing;DB_CLOSE_DELAY=-1"})
class LicensingEndpointsTest {

  @DynamicPropertySource
  static void keys(DynamicPropertyRegistry registry) {
    LicensingTestKeys.register(registry);
  }

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private IssuedLicenseRepository repository;
  @Autowired private LicenseVerifier verifier;

  @BeforeEach
  void reset() {
    jdbc.update("DELETE FROM cassyx_issued_license");
    jdbc.update("DELETE FROM cassyx_licensing_event");
  }

  @Test
  void issuesATrialKeyThatVerifiesAndExpiresInFourteenDays() throws Exception {
    mvc.perform(
            post("/licensing/trial")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"Ops@Example.com\",\"name\":\"Example GmbH\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.edition").value("trial"))
        .andExpect(jsonPath("$.delivery").value("SENT"));

    IssuedLicense issued = repository.findByEmail("ops@example.com").get(0);
    // Addresses are normalised: Ops@ and ops@ are one customer, not two trials.
    assertThat(issued.email()).isEqualTo("ops@example.com");
    LicenseStatus status = verifier.verify(issued.licenseKey());
    assertThat(status.valid()).isTrue();
    License license = status.licenseOpt().orElseThrow();
    assertThat(license.edition()).isEqualTo(License.TRIAL_EDITION);
    // Inclusive expiry: 14 days INCLUDING the issue date.
    assertThat(license.expires())
        .isEqualTo(license.issued().plusDays(License.DEFAULT_TRIAL_DAYS - 1L));
    assertThat(license.daysRemaining(LocalDate.now())).isEqualTo(13);
  }

  @Test
  void refusesASecondTrialForTheSameAddress() throws Exception {
    trial("ops@example.com").andExpect(status().isCreated());

    // 409 rather than silently re-arming the clock: otherwise the trial is an infinite renewal.
    trial("OPS@example.com")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.recover").value("/licensing/recover"));

    assertThat(repository.findByEmail("ops@example.com")).hasSize(1);
  }

  @Test
  void recoveryReSendsTheKeyAndRevealsNothingAboutUnknownAddresses() throws Exception {
    trial("ops@example.com").andExpect(status().isCreated());

    String knownBody = recover("ops@example.com");
    String unknownBody = recover("nobody@example.com");

    // Byte-identical answers: a different response for a known address is a customer-list oracle.
    assertThat(knownBody).isEqualTo(unknownBody);
    assertThat(knownBody).contains("If that address has a licence");
  }

  @Test
  void mintsOnAVerifiedPaidWebhookAndIsIdempotentOnEventId() throws Exception {
    String body = event("evt_paid", "checkout.session.completed", "paid");

    webhook(body)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.action").value("FULFILLED"));
    webhook(body)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.action").value("DUPLICATE"));

    List<IssuedLicense> issued = repository.findByEmail("ops@example.com");
    // One payment, one licence, however many times Stripe retries.
    assertThat(issued).hasSize(1);
    assertThat(issued.get(0).edition()).isEqualTo(License.STANDARD_EDITION);
    assertThat(issued.get(0).sourceEvent()).isEqualTo("evt_paid");
    assertThat(verifier.verify(issued.get(0).licenseKey()).valid()).isTrue();
  }

  @Test
  void mintsNothingForACompletedButUnpaidSession() throws Exception {
    webhook(event("evt_unpaid", "checkout.session.completed", "unpaid"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.action").value("NOT_PAID"));

    assertThat(repository.findByEmail("ops@example.com")).isEmpty();
  }

  @Test
  void rejectsAWebhookWhoseSignatureDoesNotMatchTheBody() throws Exception {
    String signed = event("evt_tamper", "checkout.session.completed", "unpaid");
    String header = signature(signed);

    mvc.perform(
            post("/licensing/webhook")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Stripe-Signature", header)
                .content(signed.replace("\"unpaid\"", "\"paid\"")))
        .andExpect(status().isBadRequest());

    assertThat(repository.eventStatus("evt_tamper")).isEmpty();
  }

  @Test
  void internalFulfilmentRequiresTheSharedToken() throws Exception {
    mvc.perform(
            post("/licensing/fulfillments")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"eventId\":\"evt_forwarded\",\"email\":\"ops@example.com\"}"))
        .andExpect(status().isUnauthorized());

    mvc.perform(
            post("/licensing/fulfillments")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Cassyx-Licensing-Token", "test-token")
                .content("{\"eventId\":\"evt_forwarded\",\"email\":\"ops@example.com\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.edition").value("standard"));

    assertThat(repository.findByEmail("ops@example.com")).hasSize(1);
  }

  // ------------------------------------------------------------------ helpers

  private org.springframework.test.web.servlet.ResultActions trial(String email) throws Exception {
    return mvc.perform(
        post("/licensing/trial")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"" + email + "\"}"));
  }

  private String recover(String email) throws Exception {
    return mvc.perform(
            post("/licensing/recover")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\"}"))
        .andExpect(status().isAccepted())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  private org.springframework.test.web.servlet.ResultActions webhook(String body) throws Exception {
    return mvc.perform(
        post("/licensing/webhook")
            .contentType(MediaType.APPLICATION_JSON)
            .header("Stripe-Signature", signature(body))
            .content(body));
  }

  private static String event(String id, String type, String paymentStatus) {
    return """
        { "id": "%s", "object": "event", "type": "%s", "created": 1755388800,
          "data": { "object": { "id": "cs_test_1", "object": "checkout.session", "mode": "payment",
            "payment_status": "%s",
            "customer_details": { "email": "ops@example.com", "name": "Example GmbH" } } } }
        """
        .formatted(id, type, paymentStatus);
  }

  private static String signature(String payload) {
    try {
      long timestamp = System.currentTimeMillis() / 1000L;
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(
          new SecretKeySpec(
              LicensingTestKeys.WEBHOOK_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return "t="
          + timestamp
          + ",v1="
          + HexFormat.of()
              .formatHex(
                  mac.doFinal((timestamp + "." + payload).getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }
}
