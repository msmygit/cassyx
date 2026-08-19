package io.cassyx.license.impl.stripe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stripe.StripeClient;
import com.stripe.net.HttpClient;
import com.stripe.net.HttpHeaders;
import com.stripe.net.StripeRequest;
import com.stripe.net.StripeResponse;
import io.cassyx.license.api.PaymentProvider;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Plan section 9.3: checkout creation, raw-body signature verification and fulfilment mapping. */
class StripePaymentProviderTest {

  private static final String SESSION_JSON =
      """
      { "id": "cs_test_a1PLACEHOLDER0000000000", "object": "checkout.session",
        "url": "https://checkout.stripe.com/c/pay/cs_test_a1PLACEHOLDER0000000000",
        "mode": "payment", "payment_status": "unpaid", "status": "open" }
      """;

  /** Captures the outbound request and answers from a fixture: no test ever hits the network. */
  private static final class RecordingHttpClient extends HttpClient {
    private StripeRequest captured;

    @Override
    public StripeResponse request(StripeRequest request) {
      this.captured = request;
      return new StripeResponse(200, HttpHeaders.of(Map.of()), SESSION_JSON);
    }

    String body() {
      return captured.content().stringContent();
    }
  }

  private static StripeConfig config() {
    return new StripeConfig("rk_test_unit", StripeFixtures.WEBHOOK_SECRET, null, "cassyx-test-abcdefgh");
  }

  private static StripePaymentProvider providerWith(RecordingHttpClient http) {
    StripeClient client =
        StripeClient.builder().setApiKey("rk_test_unit").setHttpClient(http).build();
    return new StripePaymentProvider(config(), client);
  }

  @Test
  void isDiscoverableThroughTheServiceLoader() {
    // The SPI file must list BOTH providers; clobbering it would silently disable noop in CI.
    assertThat(PaymentProvider.available()).extracting(PaymentProvider::id).contains("stripe", "noop");
    assertThat(PaymentProvider.forId("stripe")).isInstanceOf(StripePaymentProvider.class);
  }

  @Test
  void createsAOneTimeCheckoutSessionWithoutPaymentMethodTypes() throws Exception {
    RecordingHttpClient http = new RecordingHttpClient();
    StripePaymentProvider provider = providerWith(http);

    PaymentProvider.CheckoutSession session =
        provider.createCheckout(
            new PaymentProvider.CheckoutRequest(
                "price_PLACEHOLDER",
                "ops@example.com",
                "https://cassyx.example.com/activate",
                "https://cassyx.example.com/pricing",
                null,
                Map.of("quantity", "3", "deployment", "self-hosted")));

    assertThat(session.id()).isEqualTo("cs_test_a1PLACEHOLDER0000000000");
    assertThat(session.url()).startsWith("https://checkout.stripe.com/");
    assertThat(session.provider()).isEqualTo("stripe");

    String body = URLDecoder.decode(http.body(), StandardCharsets.UTF_8);
    assertThat(body).contains("mode=payment");
    assertThat(body).contains("line_items[0][price]=price_PLACEHOLDER");
    assertThat(body).contains("line_items[0][quantity]=3");
    assertThat(body).contains("customer_email=ops@example.com");
    assertThat(body).contains("integration_identifier=cassyx-test-abcdefgh");
    // The rule that pays for itself in conversion: omitting the parameter enables dynamic
    // payment methods. Asserted here because it is invisible in a passing manual test.
    assertThat(body).doesNotContain("payment_method_types");
    assertThat(http.captured.url().toString()).endsWith("/v1/checkout/sessions");
  }

  @Test
  void generatesAnIntegrationIdentifierWhenNoneIsConfigured() {
    StripeConfig generated =
        new StripeConfig("rk_test_unit", "whsec_x", null, null).withGeneratedIdentifier();

    assertThat(generated.integrationIdentifier())
        .startsWith(StripeConfig.INTEGRATION_LABEL + "-")
        .matches("cassyx-selfhosted-[a-z]{8}");
    assertThat(generated.apiBaseUrl()).isEqualTo(StripeConfig.DEFAULT_API_BASE_URL);
  }

  @Test
  void refusesToCheckOutWhenOnlyAPlaceholderKeyIsConfigured() {
    StripePaymentProvider provider =
        new StripePaymentProvider(
            new StripeConfig("rk_test_PLACEHOLDER", "whsec_PLACEHOLDER", null, "cassyx-x"));

    assertThat(provider.isConfigured()).isFalse();
    assertThatThrownBy(
            () ->
                provider.createCheckout(
                    new PaymentProvider.CheckoutRequest("price_x", "a@b.c", "s", "c", null, Map.of())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not configured");
  }

  @Test
  void verifiesAGenuineSignatureOverTheRawBody() {
    StripePaymentProvider provider = new StripePaymentProvider(config());
    String body = StripeFixtures.completed("evt_1", "paid");

    Optional<PaymentProvider.WebhookEvent> event =
        provider.verifyWebhook(
            body,
            Map.of("Stripe-Signature", StripeFixtures.signatureHeader(body, StripeFixtures.WEBHOOK_SECRET)));

    assertThat(event).isPresent();
    assertThat(event.orElseThrow().id()).isEqualTo("evt_1");
    assertThat(event.orElseThrow().type()).isEqualTo("checkout.session.completed");
  }

  @Test
  void rejectsATamperedBody() {
    StripePaymentProvider provider = new StripePaymentProvider(config());
    String signed = StripeFixtures.completed("evt_1", "unpaid");
    String header = StripeFixtures.signatureHeader(signed, StripeFixtures.WEBHOOK_SECRET);
    // Exactly the attack the signature exists to stop: flip "unpaid" to "paid" after signing.
    String tampered = signed.replace("\"unpaid\"", "\"paid\"");

    assertThat(provider.verifyWebhook(tampered, Map.of("Stripe-Signature", header))).isEmpty();
  }

  @Test
  void rejectsAWrongSecretAMissingHeaderAndAnUnconfiguredSecret() {
    StripePaymentProvider provider = new StripePaymentProvider(config());
    String body = StripeFixtures.completed("evt_1", "paid");

    assertThat(
            provider.verifyWebhook(
                body, Map.of("Stripe-Signature", StripeFixtures.signatureHeader(body, "whsec_other"))))
        .isEmpty();
    assertThat(provider.verifyWebhook(body, Map.of())).isEmpty();
    assertThat(provider.verifyWebhook(null, Map.of())).isEmpty();
    assertThat(provider.verifyWebhook(body, null)).isEmpty();

    StripePaymentProvider unconfigured =
        new StripePaymentProvider(new StripeConfig("rk_test_x", "whsec_PLACEHOLDER", null, "id"));
    assertThat(unconfigured.verifyWebhook(body, Map.of("Stripe-Signature", "t=1,v1=deadbeef")))
        .isEmpty();
  }

  @Test
  void acceptsTheHeaderInAnyCasing() {
    StripePaymentProvider provider = new StripePaymentProvider(config());
    String body = StripeFixtures.asyncSucceeded("evt_case");

    assertThat(
            provider.verifyWebhook(
                body,
                Map.of("stripe-signature", StripeFixtures.signatureHeader(body, StripeFixtures.WEBHOOK_SECRET))))
        .isPresent();
  }

  @Test
  void doesNotFulfilACompletedSessionThatIsStillUnpaid() {
    // Delayed-notification methods complete the session before the money arrives. Fulfilling here
    // ships a licence for a payment that may never settle.
    PaymentProvider.Fulfillment fulfillment = fulfillmentFor(StripeFixtures.completed("evt_u", "unpaid"));

    assertThat(fulfillment.paid()).isFalse();
    assertThat(fulfillment.eventId()).isEqualTo("evt_u");
  }

  @Test
  void fulfilsACompletedPaidSessionAndCarriesTheBuyer() {
    PaymentProvider.Fulfillment fulfillment = fulfillmentFor(StripeFixtures.completed("evt_p", "paid"));

    assertThat(fulfillment.paid()).isTrue();
    assertThat(fulfillment.email()).isEqualTo("ops@example.com");
    assertThat(fulfillment.name()).isEqualTo("Example GmbH");
  }

  @Test
  void fulfilsAsyncSuccessAndMarksAsyncFailureUnpaid() {
    assertThat(fulfillmentFor(StripeFixtures.asyncSucceeded("evt_a")).paid()).isTrue();
    assertThat(fulfillmentFor(StripeFixtures.asyncFailed("evt_f")).paid()).isFalse();
  }

  @Test
  void ignoresEventTypesItDoesNotFulfilOn() {
    StripePaymentProvider provider = new StripePaymentProvider(config());
    String body = StripeFixtures.unrelatedEvent("evt_other");
    PaymentProvider.WebhookEvent event =
        provider
            .verifyWebhook(
                body,
                Map.of("Stripe-Signature", StripeFixtures.signatureHeader(body, StripeFixtures.WEBHOOK_SECRET)))
            .orElseThrow();

    assertThat(provider.parseFulfillment(event)).isEmpty();
    assertThat(provider.parseFulfillment(null)).isEmpty();
    PaymentProvider.WebhookEvent bodyless =
        new PaymentProvider.WebhookEvent("evt_x", "checkout.session.completed", Map.of());
    assertThat(provider.parseFulfillment(bodyless)).isEmpty();
  }

  @Test
  void fallsBackToThePrefilledEmailWhenCheckoutCollectedNone() {
    String body =
        StripeFixtures.completed("evt_pref", "paid")
            .replace("\"customer_details\": { \"email\": \"ops@example.com\", \"name\": \"Example GmbH\" },", "");

    PaymentProvider.Fulfillment fulfillment = fulfillmentFor(body);

    assertThat(fulfillment.email()).isEqualTo("prefilled@example.com");
    assertThat(fulfillment.name()).isEqualTo("Example GmbH");
  }

  private static PaymentProvider.Fulfillment fulfillmentFor(String body) {
    StripePaymentProvider provider = new StripePaymentProvider(config());
    List<String> headers = List.of(StripeFixtures.signatureHeader(body, StripeFixtures.WEBHOOK_SECRET));
    PaymentProvider.WebhookEvent event =
        provider.verifyWebhook(body, Map.of("Stripe-Signature", headers.get(0))).orElseThrow();
    return provider.parseFulfillment(event).orElseThrow();
  }
}
