package io.cassyx.api.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.cassyx.api.config.BillingProperties;
import io.cassyx.license.api.PaymentProvider;
import io.cassyx.license.impl.stripe.StripeConfig;
import io.cassyx.license.impl.stripe.StripePaymentProvider;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * {@code /api/billing/**} (plan section 9.3). The webhook cases drive the REAL
 * {@code StripePaymentProvider} over fixture payloads signed with a real HMAC, because the thing
 * being tested is precisely that the raw body survives Spring's request handling intact - a mocked
 * provider would pass no matter what the framework did to the bytes.
 *
 * <p>A Spring context is booted only to get a Flyway-migrated H2 (proving the V1 ledger plus
 * V3__billing_event_email.sql apply), then the controller is driven standalone.
 */
@SpringBootTest
@TestPropertySource(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:cassyx-billing;DB_CLOSE_DELAY=-1",
      "cassyx.license.enforce=false"
    })
class BillingEndpointsTest {

  private static final String WEBHOOK_SECRET = "whsec_test_secret_for_unit_tests_only";

  @Autowired private JdbcTemplate jdbc;

  private ProcessedEventStore events;
  private RecordingGateway gateway;

  @BeforeEach
  void reset() {
    jdbc.update("DELETE FROM cassyx_billing_event");
    events = new ProcessedEventStore(jdbc);
    gateway = new RecordingGateway();
  }

  // ---------------------------------------------------------------- checkout

  @Test
  void createsACheckoutSessionAndReturnsTheHostedUrl() throws Exception {
    MockMvc mvc = mvc(new StubProvider(), properties(true));

    mvc.perform(
            post("/api/billing/checkout-session")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"ops@example.com\",\"quantity\":2}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.sessionId").value("cs_test_stub"))
        .andExpect(jsonPath("$.url").value("https://checkout.stripe.com/c/pay/cs_test_stub"))
        .andExpect(jsonPath("$.publishableKey").value("pk_test_PLACEHOLDER"));
  }

  @Test
  void passesQuantityAndTheSuccessUrlTemplateToTheProvider() throws Exception {
    StubProvider stub = new StubProvider();
    MockMvc mvc = mvc(stub, properties(true));

    mvc.perform(
            post("/api/billing/checkout-session")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"ops@example.com\",\"quantity\":3}"))
        .andExpect(status().isCreated());

    assertThat(stub.lastRequest.metadata()).containsEntry("quantity", "3");
    // Fulfilment never depends on the return page being reached, but Stripe still needs the
    // template so the activation screen can look the session up.
    assertThat(stub.lastRequest.successUrl()).contains("session_id={CHECKOUT_SESSION_ID}");
    assertThat(stub.lastRequest.priceId()).isEqualTo("price_PLACEHOLDER");
  }

  @Test
  void answers503RatherThanAFakeUrlWhenBillingIsDisabled() throws Exception {
    MockMvc mvc = mvc(new StubProvider(), properties(false));

    mvc.perform(
            post("/api/billing/checkout-session")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"ops@example.com\"}"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.title").value("Billing is disabled"));
  }

  @Test
  void rejectsACheckoutWithNoEmail() throws Exception {
    MockMvc mvc = mvc(new StubProvider(), properties(true));

    mvc.perform(
            post("/api/billing/checkout-session")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isBadRequest());
  }

  // ----------------------------------------------------------------- webhook

  @Test
  void rejectsATamperedWebhookBodyWithoutProcessingAnything() throws Exception {
    String signed = event("evt_tamper", "checkout.session.completed", "unpaid");
    String header = signatureHeader(signed);
    String tampered = signed.replace("\"unpaid\"", "\"paid\"");

    webhookMvc()
        .perform(
            post("/api/billing/webhook")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Stripe-Signature", header)
                .content(tampered))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.title").value("Signature verification failed"));

    assertThat(gateway.fulfilled).isEmpty();
    assertThat(events.statusOf("evt_tamper")).isEmpty();
  }

  @Test
  void fulfilsAPaidCompletedSession() throws Exception {
    postEvent(event("evt_paid", "checkout.session.completed", "paid"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.action").value("FULFILLED"))
        .andExpect(jsonPath("$.duplicate").value(false));

    assertThat(gateway.fulfilled).extracting(PaymentProvider.Fulfillment::email)
        .containsExactly("ops@example.com");
    assertThat(events.statusOf("evt_paid")).contains("FULFILLED");
  }

  @Test
  void doesNotFulfilACompletedSessionThatIsStillUnpaid() throws Exception {
    postEvent(event("evt_unpaid", "checkout.session.completed", "unpaid"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.action").value("IGNORED_UNPAID"));

    assertThat(gateway.fulfilled).isEmpty();
  }

  @Test
  void fulfilsADelayedPaymentThatLaterSucceeded() throws Exception {
    postEvent(event("evt_async_ok", "checkout.session.async_payment_succeeded", "paid"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.action").value("FULFILLED"));

    assertThat(gateway.fulfilled).hasSize(1);
  }

  @Test
  void marksADelayedPaymentFailureWithoutMinting() throws Exception {
    postEvent(event("evt_async_bad", "checkout.session.async_payment_failed", "unpaid"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.action").value("MARKED_FAILED"));

    assertThat(gateway.fulfilled).isEmpty();
    assertThat(gateway.failed).hasSize(1);
  }

  @Test
  void acknowledgesEventTypesItDoesNotHandle() throws Exception {
    postEvent(
            """
            { "id": "evt_other", "object": "event", "type": "payment_intent.created",
              "created": 1755388800, "data": { "object": { "id": "pi_test" } } }
            """)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.handled").value(false))
        .andExpect(jsonPath("$.action").value("IGNORED_UNHANDLED_TYPE"));
  }

  @Test
  void isIdempotentOnEventIdUnderReplay() throws Exception {
    String body = event("evt_replay", "checkout.session.completed", "paid");

    postEvent(body).andExpect(jsonPath("$.action").value("FULFILLED"));
    // Stripe retries until it sees a 2xx and can deliver the same event more than once anyway.
    postEvent(body)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.duplicate").value(true))
        .andExpect(jsonPath("$.action").value("DUPLICATE"));
    postEvent(body).andExpect(jsonPath("$.action").value("DUPLICATE"));

    // One payment, one licence. This assertion is the whole point of the ledger.
    assertThat(gateway.fulfilled).hasSize(1);
  }

  @Test
  void releasesTheClaimWhenFulfilmentFailsSoTheRetryWorks() throws Exception {
    gateway.accept = false;
    String body = event("evt_retry", "checkout.session.completed", "paid");

    postEvent(body).andExpect(status().isInternalServerError());
    assertThat(events.statusOf("evt_retry")).isEmpty();

    gateway.accept = true;
    postEvent(body).andExpect(jsonPath("$.action").value("FULFILLED"));
    assertThat(gateway.fulfilled).hasSize(1);
  }

  // ------------------------------------------------------------------ wiring

  private org.springframework.test.web.servlet.ResultActions postEvent(String body)
      throws Exception {
    return webhookMvc()
        .perform(
            post("/api/billing/webhook")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Stripe-Signature", signatureHeader(body))
                .content(body));
  }

  private MockMvc webhookMvc() {
    return mvc(
        new StripePaymentProvider(
            new StripeConfig("rk_test_unit", WEBHOOK_SECRET, null, "cassyx-test-abcdefgh")),
        properties(true));
  }

  private MockMvc mvc(PaymentProvider provider, BillingProperties properties) {
    return MockMvcBuilders.standaloneSetup(
            new BillingController(provider, properties, events, gateway))
        .build();
  }

  private static BillingProperties properties(boolean enabled) {
    return new BillingProperties(
        enabled,
        "stripe",
        "https://api.stripe.com",
        "pk_test_PLACEHOLDER",
        "rk_test_PLACEHOLDER",
        "whsec_PLACEHOLDER",
        "price_PLACEHOLDER",
        "http://localhost:8080/activate",
        "http://localhost:8080/pricing");
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

  private static String signatureHeader(String payload) {
    try {
      long timestamp = System.currentTimeMillis() / 1000L;
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(WEBHOOK_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      String signed = timestamp + "." + payload;
      return "t="
          + timestamp
          + ",v1="
          + HexFormat.of().formatHex(mac.doFinal(signed.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Records what the licensing service would have been asked to mint. */
  private static final class RecordingGateway implements FulfillmentGateway {
    private final List<PaymentProvider.Fulfillment> fulfilled = new ArrayList<>();
    private final List<PaymentProvider.Fulfillment> failed = new ArrayList<>();
    private boolean accept = true;

    @Override
    public boolean fulfil(PaymentProvider.Fulfillment fulfillment) {
      if (!accept) {
        return false;
      }
      fulfilled.add(fulfillment);
      return true;
    }

    @Override
    public void markFailed(PaymentProvider.Fulfillment fulfillment) {
      failed.add(fulfillment);
    }
  }

  /** Checkout-only stub: creating a real session would need the network. */
  private static final class StubProvider implements PaymentProvider {
    private CheckoutRequest lastRequest;

    @Override
    public String id() {
      return "stripe";
    }

    @Override
    public CheckoutSession createCheckout(CheckoutRequest request) {
      this.lastRequest = request;
      return new CheckoutSession(
          "cs_test_stub", "https://checkout.stripe.com/c/pay/cs_test_stub", "stripe");
    }

    @Override
    public Optional<WebhookEvent> verifyWebhook(String rawBody, Map<String, String> headers) {
      return Optional.empty();
    }

    @Override
    public Optional<Fulfillment> parseFulfillment(WebhookEvent event) {
      return Optional.empty();
    }
  }
}
