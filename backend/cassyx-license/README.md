# cassyx-license

Offline Ed25519 license verification and the `PaymentProvider` SPI. **Plain Java — no Spring, no
Cassandra driver.** This module is deliberately dependency-light so it can be reused anywhere.

Entry point: `io.cassyx.license.api.LicenseFactory`.

## Usage

```java
import io.cassyx.license.api.*;

LicenseVerifier verifier = LicenseFactory.verifier(System.getenv("CASSYX_LICENSE_PUBLIC_KEY"));

boolean enforce = Boolean.parseBoolean(System.getenv("CASSYX_LICENSE_ENFORCE"));
LicenseStatus status = LicenseFactory.check(verifier, System.getenv("CASSYX_LICENSE_KEY"), enforce);

if (status.valid()) {
  License license = status.licenseOpt().orElseThrow();
  System.out.println(license.edition());   // "standard", or "unlicensed-bypass" when enforce=false
} else {
  System.out.println("blocked: " + status.reason());
}

PaymentProvider provider = LicenseFactory.paymentProvider("noop");
```

## Model

* **Offline verification only.** The app embeds the *public* key, so a leaked build cannot mint
  licenses and the product works fully air-gapped. Minting (private key) lives in the separate
  `licensing/` deployment.
* Key format: `base64url(payloadJson) + "." + base64url(ed25519Signature)`.
* **Bypass flag** (`cassyx.license.enforce=false`) short-circuits to a synthetic license reporting
  edition `unlicensed-bypass`. The API logs a WARN at startup and the UI keeps its banner visible, so
  a bypassed instance is never mistaken for a paid one.

## SPI

`PaymentProvider` — `ServiceLoader`-discovered by `id()`: `createCheckout`, `verifyWebhook`,
`parseFulfillment`. `NoopPaymentProvider` ships here; `StripePaymentProvider` (Stripe Java 33.2.0,
pinned in the parent POM) arrives with Phase 1 workstream H. Rules that live in the provider, not in
license logic: Checkout Sessions with `mode: "payment"`; instantiate `StripeClient` rather than
setting `Stripe.apiKey`; never pass `payment_method_types`; use a restricted (`rk_`) key; and
fulfil from **webhooks**, idempotently on `event.id`, never from the success page.
