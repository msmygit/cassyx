# cassyx licensing service

The operator-run half of the monetization design (plan §9.1, §9.3, §9.4). It mints Ed25519-signed
licence keys, persists them, and emails them.

**This is not part of the product.** It is a separate deployment that you run, because minting
requires the Ed25519 *private* key and that key must never be inside a self-hosted image. The
distributed application only ever *verifies*, with the public half. Nothing in `backend/` depends on
this module at build time, and it is deliberately outside the default Maven reactor (see the
`licensing` profile in `backend/pom.xml`).

## What it does

| Endpoint | Purpose |
| --- | --- |
| `POST /licensing/webhook` | Stripe delivers here directly (recommended). Signature verified over the raw body, idempotent on `event.id`. |
| `POST /licensing/fulfillments` | A cassyx instance that verified the webhook itself forwards the result. Requires `X-Cassyx-Licensing-Token`. |
| `POST /licensing/fulfillments/failed` | Records a failed asynchronous payment. Mints nothing. |
| `POST /licensing/trial` | One 14-day trial per address; `409` on a repeat rather than re-arming the clock. |
| `POST /licensing/recover` | "Email me my key again." Always `202` with the same body, so it cannot be used to enumerate customers. |
| `POST /licensing/deliveries/retry` | Re-attempts every key that was minted but never delivered. Token-guarded. |
| `GET /licensing/health` | Liveness. |

Fulfilment mapping is the one in §9.3 and the `unpaid` gate is the part that matters:

| Event | Action |
| --- | --- |
| `checkout.session.completed` | mint **only if `payment_status != "unpaid"`** |
| `checkout.session.async_payment_succeeded` | mint |
| `checkout.session.async_payment_failed` | record, never mint |

## 1. Generate a key pair

```bash
# Compile this module and its one sibling dependency first (there is no local mvn in this repo -
# run it in a container exactly like the rest of the build does):
docker run --rm -v "$PWD":/w -v "$HOME/.m2":/root/.m2 -w /w/backend maven:3.9-eclipse-temurin-21 \
  mvn -B -Plicensing -pl ../licensing -am install -DskipTests
docker run --rm -v "$PWD":/w -v "$HOME/.m2":/root/.m2 -w /w/licensing maven:3.9-eclipse-temurin-21 \
  mvn -q exec:java
```

It prints both halves and says which is which:

* `CASSYX_LICENSE_PUBLIC_KEY` - **ships with the product**. Safe to publish; a leaked build reveals
  nothing that can mint licences. Set it in the product's environment *and* here, because this
  service verifies every key it mints before emailing it.
* `CASSYX_LICENSING_PRIVATE_KEY` - **stays here**. Never in the product image, never in the
  repository, never in a container that a customer can pull.

Getting those two backwards is silent: the product still boots, and you have published the key that
mints free licences forever.

## 2. Configure

```bash
cp licensing/.env.example licensing/.env
```

Every variable is documented in that file. The defaults run end to end with no accounts at all:
email provider `log` writes the whole message (key included) to the service log.

## 3. Run locally against a Stripe sandbox

No Stripe registration is required:

```bash
npm i -g @stripe/cli
stripe sandbox create              # prints working test keys
```

Put the restricted key in `STRIPE_SECRET_KEY`, then start the service and forward events to it:

```bash
mvn -B -f backend/pom.xml -Plicensing -pl ../licensing -am spring-boot:run   # or use the Dockerfile
stripe listen --forward-to localhost:8090/licensing/webhook
```

`stripe listen` prints a `whsec_...` - put it in `STRIPE_WEBHOOK_SECRET` and restart. Then:

```bash
stripe trigger checkout.session.completed
```

The minted key appears in the log (email provider `log`). To test the flows by hand:

```bash
curl -sX POST localhost:8090/licensing/trial \
     -H 'content-type: application/json' -d '{"email":"ops@example.com"}'

curl -sX POST localhost:8090/licensing/recover \
     -H 'content-type: application/json' -d '{"email":"ops@example.com"}'
```

## 4. Build and deploy

```bash
# Build context is the REPOSITORY ROOT - the module compiles against backend/cassyx-license.
docker build -f licensing/Dockerfile -t cassyx-licensing .
docker run --rm -p 8090:8090 --env-file licensing/.env -v cassyx-licensing-data:/data cassyx-licensing
```

Deployment notes that are not optional:

* **Point Stripe's webhook at this service**, not at a customer's cassyx instance. A self-hosted
  instance cannot mint (it has only the public key) and will report the fulfilment as failed so
  Stripe keeps retrying.
* **Back up `/data`.** It is the only record of which payment produced which key. Stripe knows a
  payment happened; only this database knows what it produced, and recovery depends on it.
* Watch for `LICENCE ... WAS MINTED BUT NOT DELIVERED` in the log. That means someone paid and has
  not received their key. `POST /licensing/deliveries/retry` re-attempts every such case.
* Email is a stub. `LicenseEmailSender` has exactly one implementation (`log`); a real provider is
  a new implementation of that interface plus one line in `LicensingConfiguration`. An unknown
  `CASSYX_LICENSING_EMAIL_PROVIDER` fails startup rather than quietly falling back to logging, so
  nobody believes customers are being emailed when they are not.

## Tests

```bash
mvn -B -f backend/pom.xml -Plicensing -pl ../licensing -am test
```

`MintVerifyRoundTripTest` is the one to protect: it mints a key here and verifies it through the
real `Ed25519LicenseVerifier` that ships in the product. If it ever goes red, every licence sold
since it broke is worthless to the customer holding it.
