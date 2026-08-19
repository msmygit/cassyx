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

### 2.1 Email delivery

`CASSYX_LICENSING_EMAIL_PROVIDER` picks the sender. There are two, and an unrecognised value
**fails startup** rather than falling back, because a silent fallback to `log` looks exactly like a
service that is emailing every customer correctly.

| Value | What it does |
| --- | --- |
| `log` (default) | Writes the whole email, licence key included, to the service log. Development only. |
| `smtp` | Sends it. Both a plain-text and an HTML body, content differing per purchase / trial / re-send. |

**Why SMTP and not a vendor SDK.** Postmark, SES, Resend, Mailgun, Fastmail and Gmail all speak
SMTP, so one implementation covers whichever one you pick, and moving between them is a change to
four environment variables rather than a code change and a redeploy. It also adds no vendor SDK -
no HTTP client, no credential library, no transitive dependency tree - to the one service in this
repository that holds the Ed25519 private key.

Full variable list, all with defaults, all documented inline in `.env.example`:

| Variable | Default | Notes |
| --- | --- | --- |
| `CASSYX_LICENSING_EMAIL_PROVIDER` | `log` | `log` or `smtp`; anything else fails startup. |
| `CASSYX_LICENSING_EMAIL_FROM` | `licensing@cassyx.dev` | Must be a domain you control. See below. |
| `CASSYX_LICENSING_EMAIL_FROM_NAME` | `cassyx` | Display name. A bare address reads as machine spam. |
| `CASSYX_LICENSING_EMAIL_REPLY_TO` | `support@cassyx.dev` | Where a stuck buyer's reply lands. |
| `CASSYX_LICENSING_EMAIL_SUBJECT` | `Your cassyx licence key` | Purchase subject; trial and re-send are derived from it. |
| `CASSYX_LICENSING_PURCHASE_URL` | *(blank)* | Shown in trial emails. Blank omits the line. |
| `CASSYX_LICENSING_RECOVERY_URL` | *(blank)* | Shown in purchase and re-send emails. Blank omits the line. |
| `CASSYX_LICENSING_SMTP_HOST` | *(blank)* | Required when provider is `smtp`. |
| `CASSYX_LICENSING_SMTP_PORT` | `587` | `465` with `ssl`. |
| `CASSYX_LICENSING_SMTP_USERNAME` | *(blank)* | Blank disables SMTP AUTH entirely. |
| `CASSYX_LICENSING_SMTP_PASSWORD` | *(blank)* | Never logged, never echoed in an error. |
| `CASSYX_LICENSING_SMTP_TLS` | `starttls` | `starttls` (587), `ssl` (implicit TLS, 465), `none` (local test server only). |
| `CASSYX_LICENSING_SMTP_CONNECT_TIMEOUT_MS` | `10000` | |
| `CASSYX_LICENSING_SMTP_READ_TIMEOUT_MS` | `15000` | |
| `CASSYX_LICENSING_SMTP_WRITE_TIMEOUT_MS` | `15000` | |
| `CASSYX_LICENSING_SMTP_MAX_ATTEMPTS` | `3` | Total attempts, including the first. |
| `CASSYX_LICENSING_SMTP_RETRY_DELAY_MS` | `500` | Pause between attempts. |

The three timeouts are not decoration. Jakarta Mail defaults every one of them to **infinite**, and
this send happens inside the Stripe webhook handler: one hung SMTP connection would stall
fulfilment for every buyer queued behind it, and Stripe would start redelivering the webhook on top
of that. Failing is recoverable (the key is already persisted, and `POST /licensing/recover` exists);
hanging is not.

Retries are bounded and only cover transient failures. A permanent rejection - bad credentials, a
rejected recipient, any 5xx SMTP reply - is not retried at all, because it cannot succeed on the
second try and a loop would turn one typo into a sustained hammering of the provider. On giving up,
the sender throws, the licence is recorded as undelivered, and `POST /licensing/deliveries/retry`
or `POST /licensing/recover` picks it up.

### 2.2 Deliverability: the part that bites operators

**Licence email landing in spam is indistinguishable, from the buyer's side, from not sending it at
all.** They paid, nothing arrived, and they open a ticket or a chargeback. Everything below is a
prerequisite for taking money, not a nice-to-have.

* **Send from a domain you control, and authenticate it.** `CASSYX_LICENSING_EMAIL_FROM` must be on
  a domain whose DNS you can edit. A From address on someone else's domain (a Gmail address, a
  customer's domain, anything you have not authenticated) fails DMARC at the recipient and is
  rejected or spam-foldered. This is the single most common reason transactional mail vanishes.
* **SPF** - a DNS TXT record on the sending domain listing who may send for it, e.g.
  `v=spf1 include:spf.example-provider.com -all`. Your provider gives you the exact `include:`.
  End with `-all`, not `~all`, once you are sure the list is complete.
* **DKIM** - your provider gives you a public key to publish as a TXT record at
  `<selector>._domainkey.<your-domain>`; it then signs every message with the private half. DKIM is
  the one that survives forwarding, which SPF does not, so do not skip it because SPF passes.
* **DMARC** - a TXT record at `_dmarc.<your-domain>`, e.g.
  `v=DMARC1; p=none; rua=mailto:dmarc@<your-domain>`. Start at `p=none` and read the aggregate
  reports for a week or two, then move to `p=quarantine` and `p=reject`. Gmail and Yahoo now
  **require** a DMARC record for bulk senders, and treat its absence as a negative signal even
  below their volume thresholds.
* **Verify before you sell anything.** Send a purchase email to a Gmail address, an Outlook address
  and one corporate address, and check the raw headers for `spf=pass`, `dkim=pass` and
  `dmarc=pass`. Anything less than three passes is a licence key you are about to lose.
* **Do not send from a bare VPS on port 25.** Consumer and cloud IP ranges are blocked wholesale.
  Use a provider whose IPs have a reputation, which is most of what you are paying them for.
* **Watch for the undelivered log line** (below). It is the only signal you get that any of this is
  wrong, and it does not fire when the message is accepted by the provider and then filed as spam -
  which is why the checks above happen before the first sale, not after the first complaint.

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
* **Set `CASSYX_LICENSING_EMAIL_PROVIDER=smtp` before the first sale**, and work through §2.2. The
  `log` default mints correct keys and delivers none of them. An unknown provider value fails
  startup rather than quietly falling back to logging, so nobody believes customers are being
  emailed when they are not.

## Tests

```bash
mvn -B -f backend/pom.xml -Plicensing -pl ../licensing -am test
```

`MintVerifyRoundTripTest` is the one to protect: it mints a key here and verifies it through the
real `Ed25519LicenseVerifier` that ships in the product. If it ever goes red, every licence sold
since it broke is worthless to the customer holding it.

`SmtpLicenseEmailSenderTest` is the other one. It runs against **GreenMail**, a real in-process
SMTP server, rather than a mocked `JavaMailSender`: a mock proves a method was called, which is not
the question. The question is whether the message survives an actual SMTP conversation with the key
intact in both body parts, and only a server answers that.
