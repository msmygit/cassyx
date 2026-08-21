# Selling cassyx: the operator runbook

Everything between "the code works" and "money arrives and a customer has a working install".

This is the **commercial** runbook. It deliberately does not repeat what is already in
[`README.md`](../README.md) (how a user installs a release, how a maintainer cuts one) or
[`docs/plan.md`](plan.md) §9 (why the licensing model is shaped this way). It links to both.

> **Read §0 first.** Two defaults will quietly cost you money if you ship without changing them.

---

## 0. The two settings that will cost you money

### 0.1 `CASSYX_LICENSING_SCOPE=0` gives away every future major version

`scope` is the purchased major version (plan §9.5). `License.coversMajor()` reads it as
`scope == null || appMajor <= scope`, and **`0` means "omit the field entirely"**, which means
*unrestricted, forever*.

The shipped `licensing/.env.example` has `CASSYX_LICENSING_SCOPE=0`. Left alone, every key you sell
for 1.x also unlocks 2.x, 3.x and everything after. Since cassyx is a **one-time payment**, upgrade
revenue is the only revenue you will ever get from an existing customer, and this hands it over
permanently, silently, and irreversibly for every key already minted.

```bash
CASSYX_LICENSING_SCOPE=1     # keys cover 1.x only
```

Set this **before the first sale.** Keys are signed; you cannot retroactively narrow one.

### 0.2 `CASSYX_LICENSING_TOKEN=PLACEHOLDER` leaves minting unauthenticated

`POST /licensing/fulfillments` mints a licence. It is guarded by the `X-Cassyx-Licensing-Token`
header, and if `CASSYX_LICENSING_TOKEN` is unset the service logs:

```
CASSYX_LICENSING_TOKEN is not set: internal minting endpoints are UNAUTHENTICATED
```

Anyone who can reach the service can then mint themselves an unlimited licence. Set a real secret
and do not expose the service more widely than Stripe's webhook needs.

---

## 1. Services to sign up for

| # | Service | Why | Cost |
| --- | --- | --- | --- |
| 1 | **Stripe** | Takes the payment, calls your webhook | ~2.9% + 30c per charge |
| 2 | **Email provider (SMTP)** | Delivers the licence key. Postmark, SES, Resend, Mailgun, Fastmail all work | free tier to ~$15/mo |
| 3 | **A domain + DNS** | The From address, SPF/DKIM/DMARC, and the licensing service's public URL | ~$15/yr |
| 4 | **Somewhere to run `licensing/`** | Small always-on host: Fly.io, Railway, Hetzner, a VPS | ~$5/mo |
| 5 | **GHCR** | Already yours via GitHub. See §1.1 | free |

Not required to start: a status page, a support desk, analytics.

### 1.1 Make the GHCR packages public

**This is the step everyone misses.** Package visibility is *separate* from repository visibility.
The repo being public does **not** make the images pullable. Until you change it, every customer's
`docker compose up` fails with `denied` or `manifest unknown`.

After the first release publishes, go to your GitHub profile → **Packages** →
`cassyx-backend` → *Package settings* → **Change visibility → Public**. Repeat for
`cassyx-frontend`.

Verify from a machine that has never authenticated to GHCR:

```bash
docker logout ghcr.io
docker pull ghcr.io/msmygit/cassyx-backend:1.0.0   # must succeed
```

### 1.2 Stripe setup

1. Create the **Product** and a **one-time Price**. Note the price ID (`price_...`).
2. Create a **restricted key** (`rk_...`), not a secret key. It needs write on Checkout Sessions
   and read on Events. Restricted keys limit the blast radius if the licensing host is compromised.
3. Add a **webhook endpoint** pointing at your deployed licensing service:
   `https://licensing.yourdomain.com/licensing/webhook`
   Subscribe to exactly three events:
   - `checkout.session.completed`
   - `checkout.session.async_payment_succeeded`
   - `checkout.session.async_payment_failed`
4. Copy the **signing secret** (`whsec_...`).
5. Fill in your **refund policy** in Stripe's public business details. Stripe requires one, and its
   absence shows up as a failed account review rather than an error message.

### 1.3 Email setup

Pick any SMTP provider. cassyx speaks plain SMTP deliberately, so switching provider is an env-var
change (see [`licensing/README.md`](../licensing/README.md) §2.1).

Then do the part that actually determines whether customers receive their keys:

- **SPF, DKIM and DMARC** records on the sending domain
- a **From address on a domain you control** (never `gmail.com`)
- send a test to **Gmail, Outlook and a corporate tenant** and confirm `spf=pass dkim=pass
  dmarc=pass` in the raw headers

**A licence key in the spam folder is indistinguishable from never having sent it**, except that
the customer is angrier. `licensing/README.md` §2.2 has the concrete records.

---

## 2. Every key and secret, and where it goes

There are **two** deployments with **two** different config files. Mixing them up is the one
mistake that cannot be undone.

### 2.1 The licensing service (private, you operate it)

`licensing/.env`, never distributed. Generate the keypair - **runnable from anywhere**, no repo
checkout, no Maven, no build:

```bash
mkdir -p /tmp/cassyx-keys && cd /tmp/cassyx-keys
cat > KeyGen.java <<'EOF'
import java.security.*;
import java.util.Base64;
public class KeyGen {
  public static void main(String[] a) throws Exception {
    KeyPair p = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    Base64.Encoder e = Base64.getEncoder();
    System.out.println("# PUBLIC half - SHIPS with the product. Safe to publish.");
    System.out.println("CASSYX_LICENSE_PUBLIC_KEY=" + e.encodeToString(p.getPublic().getEncoded()));
    System.out.println();
    System.out.println("# PRIVATE half - NEVER ships. licensing/.env only. Back this up.");
    System.out.println("CASSYX_LICENSING_PRIVATE_KEY=" + e.encodeToString(p.getPrivate().getEncoded()));
  }
}
EOF
docker run --rm -v "$PWD":/w -w /w eclipse-temurin:21-jdk java KeyGen.java
rm -rf /tmp/cassyx-keys   # after you have stored both halves
```

Output is X.509 base64 for the public half and PKCS#8 base64 for the private half, identical to
what `licensing/`'s own `KeyPairTool` produces.

> **Why not `KeyPairTool` via Maven?** `mvn -Plicensing -pl ../licensing exec:java` fails with
> `Could not find artifact io.cassyx:cassyx-license:jar:1.0.0`, because `-pl` without `-am` never
> builds the dependency. The tool that generates your single most important secret should not need
> a working reactor, so use the standalone version above.

The private half will be in your terminal scrollback and shell history. Clear both.

Then:

| Variable | Value | Notes |
| --- | --- | --- |
| `CASSYX_LICENSING_PRIVATE_KEY` | private half | **Never ships. Never in git. Back it up.** Lose it and you cannot mint or reissue any key, ever. |
| `CASSYX_LICENSE_PUBLIC_KEY` | public half | Kept here only so the service self-verifies what it mints before emailing it |
| `CASSYX_LICENSING_TOKEN` | a long random secret | §0.2 |
| `CASSYX_LICENSING_SCOPE` | `1` | §0.1 |
| `CASSYX_LICENSING_SEATS` | `1` | Or your per-seat policy |
| `CASSYX_LICENSING_TRIAL_DAYS` | `14` | |
| `STRIPE_SECRET_KEY` | `rk_...` | Restricted key |
| `STRIPE_WEBHOOK_SECRET` | `whsec_...` | |
| `CASSYX_LICENSING_EMAIL_PROVIDER` | `smtp` | Default is `log`, which prints keys instead of sending them |
| `CASSYX_LICENSING_EMAIL_*` | host, port, user, password, from, reply-to | See `licensing/.env.example` |
| `CASSYX_LICENSING_DB_PATH` | a **persistent volume** | Holds every issued licence. Losing it breaks recovery for every past customer. |

### 2.2 The product (public, customers run it)

`.env` next to `docker-compose.release.yml`. See [README → Install a release](../README.md#install-a-release).

The only licensing-relevant value is `CASSYX_LICENSE_PUBLIC_KEY`, which is **baked into the image
you publish** as the default for `cassyx.license.public-key` in
`backend/cassyx-api/src/main/resources/application.yml`, not something the customer sets. The
customer sets `CASSYX_LICENSE_KEY` (their key) and `CASSYX_SECRET_KEY` (encrypts their stored
cluster credentials; no default on purpose).

> **This is where 1.0.0 broke.** The default was the literal `PLACEHOLDER` and nothing set the env
> var, so the published image could verify *nothing*: every customer got "This server is not
> configured for licensing" and no key could have unlocked it. Every other release check passed.
> `CASSYX_SMOKE_EXPECT_LICENSABLE` now fails the release if an image cannot verify a licence.
>
> Baking the key in rather than asking the customer for it is deliberate: a public key is not a
> secret, and verification should not be breakable by editing a compose file.

> **The private key must never appear in the product image, in this repo, or in any customer-facing
> config.** The product only ever *verifies*. That is the entire reason `licensing/` is a separate
> deployment (plan §9.1).

---

## 3. How to test this end to end

Do this against the **Stripe sandbox**, before taking real money. It exercises payment, webhook,
minting, email, TLS and deliverability in one pass, which is exactly the set of things that cannot
be tested in CI.

```bash
# 1. Keypair (§2.1). Put the PUBLIC half in the product build, PRIVATE in licensing/.env
# 2. Run the licensing service locally with real SMTP and sandbox Stripe keys
docker run --rm -p 8090:8090 --env-file licensing/.env ghcr.io/msmygit/cassyx-licensing:1.0.0

# 3. Forward sandbox webhooks to it
stripe listen --forward-to localhost:8090/licensing/webhook
```

Then:

| Step | What to check |
| --- | --- |
| 1 | `curl localhost:8090/licensing/health` answers |
| 2 | Complete a sandbox checkout with card `4242 4242 4242 4242` |
| 3 | The webhook is received, and the log shows a mint |
| 4 | **The email arrives in a real inbox** - not the log. Check Gmail *and* spam |
| 5 | The key is copy-pasteable from the email without line-break corruption |
| 6 | Paste it into a **release** image (not a dev build) and confirm the app unlocks |
| 7 | Replay the same webhook (`stripe events resend <id>`). **Exactly one** licence total - idempotency |
| 8 | `POST /licensing/recover {"email":"..."}` re-sends, and the mail reads as a re-send, not a second charge |
| 9 | `POST /licensing/trial` issues a 14-day key; call it twice, second returns **409** |
| 10 | Set the clock past a trial's expiry, confirm the app reports `EXPIRED` and offers purchase |
| 11 | Mint with `CASSYX_LICENSING_SCOPE=1`, run it against a **2.x** build, confirm `UPGRADE_REQUIRED` |

**Test the failure paths too**, because they are the ones that cost money:

- Break the SMTP password. The mint must still succeed, the licence must be recorded undelivered,
  and `POST /licensing/deliveries/retry` must recover it. A customer must never pay and lose a key
  because your mail provider had a bad minute.
- Send a webhook with a bad signature. It must be rejected.
- Send `checkout.session.completed` with `payment_status: unpaid`. It must **not** mint.

### 3.1 Verify the product is actually locked

Run a **released** image with the bypass flag set. It must refuse:

```bash
docker run --rm -e CASSYX_LICENSE_ENFORCE=false -e CASSYX_SECRET_KEY=$(openssl rand -base64 32) \
  -p 8081:8080 ghcr.io/msmygit/cassyx-backend:1.0.0

curl -s localhost:8081/api/license   # expect enforce=true, bypass=false
curl -o /dev/null -w '%{http_code}\n' localhost:8081/api/connections   # expect 402
```

If that returns `200`, you built the image with the `dev` profile and are giving the product away.

---

## 4. How to cut a release

The mechanics are in [README → Cutting a release](../README.md#cutting-a-release-maintainers).
The commercial additions:

1. **The tag must equal `<version>` in `backend/pom.xml`.** The `guard` job enforces it. This is a
   licensing correctness rule, not tidiness: the running version is what `scope` is checked
   against, so a mismatched tag makes every customer's gate decide against the wrong number.
2. **Bumping the major is a pricing decision.** Going 1.x → 2.0.0 means every `scope: 1` key gets
   `UPGRADE_REQUIRED` on the new version. Their existing install keeps working; they simply cannot
   move up without buying. Decide the upgrade price *before* tagging.
3. **After the first release, make the GHCR packages public** (§1.1).
4. Verify the published image with §3.1 before announcing it.

---

## 5. How to create a licence per user, per version

### 5.1 Automatically, on purchase

Stripe → webhook → mint → email. Nothing to do per customer. `scope` comes from
`CASSYX_LICENSING_SCOPE` at mint time (§0.1).

### 5.2 Manually: enterprise deals, comps, replacements

```bash
curl -X POST https://licensing.yourdomain.com/licensing/fulfillments \
  -H "Content-Type: application/json" \
  -H "X-Cassyx-Licensing-Token: $CASSYX_LICENSING_TOKEN" \
  -d '{"email":"buyer@example.com","name":"Buyer Name","eventId":"manual-invoice-42"}'
```

Mints, persists and emails exactly as a purchase does. `eventId` is the idempotency key: reuse it
and you get `{"duplicate": true}` instead of a second licence, which makes the call safe to retry.

### 5.3 Per-version

`scope` is a **ceiling, and absent means unlimited**:

| `scope` | Covers | Use for |
| --- | --- | --- |
| `1` | 1.x only | Normal sale |
| `2` | 1.x and 2.x | Someone who bought the 2.x upgrade |
| absent (`0`) | every version, forever | Site licences, comps - **use knowingly** |

To sell a 2.x upgrade, set `CASSYX_LICENSING_SCOPE=2` and mint a new key for that customer. There
is **no** per-request scope override on the minting endpoint today; changing scope means changing
the service config. See §7.

### 5.4 Site licences, CI and evaluators

Mint a key with `edition: site` (plan §9.2). Unlimited seats, verified by exactly the same code as
a paid key, badged in the UI as granted rather than bypassed. This is the supported way to run
unlocked and it replaced the old free `CASSYX_LICENSE_ENFORCE=false` unlock, which is now inert in
release builds.

### 5.5 Trials

`POST /licensing/trial {"email":"...","name":"..."}`. 14 days by default, one per email address
enforced by a unique index rather than by convention, `409` on a second request.

**The trial is your conversion path.** Nobody buys a database tool they have not pointed at their
own cluster.

---

## 6. Where the end user gets the binary

**They never touch the source.** Published Docker images on GHCR, `linux/amd64` and `linux/arm64`:

- `ghcr.io/msmygit/cassyx-backend`
- `ghcr.io/msmygit/cassyx-frontend`

Their entire install is: create a directory, `curl` two files, set one secret, `docker compose up`.
Requirements: **Docker, and nothing else** - no checkout, no Java, Node, Maven or Make. The exact
commands are in [README → Install a release](../README.md#install-a-release).

They then either paste the key into the activation screen, or set `CASSYX_LICENSE_KEY` in `.env`
for a headless install. Both are in the purchase email.

> The repository being public does not mean customers build from source, and the Elastic License
> 2.0 does not stop them running it - ELv2 permits free use and self-hosting. **Your revenue rests
> on the key gate**, and what ELv2 adds is that circumventing that gate is a licence violation
> rather than merely impolite. See plan §9.0.

### 6.1 If you make the repository private again

The repo is currently public. Taking it private later is a legitimate choice, but it **breaks the
documented install** unless you do something about it first, because the commands in
[README → Install a release](../README.md#install-a-release) fetch two files over
`raw.githubusercontent.com`:

```
curl -fsSLO https://raw.githubusercontent.com/msmygit/cassyx/main/docker-compose.release.yml
curl -fsSL  https://raw.githubusercontent.com/msmygit/cassyx/main/.env.release.example -o .env
```

Those URLs 404 for anonymous users the moment the repo is private, and the customer's very first
command fails. Pick one before flipping the switch:

1. **Attach both files to each GitHub Release.** Release assets stay publicly downloadable even for
   a private repo, and it versions the compose file alongside the image tags, which is better
   practice anyway. Update the README to point at the release asset URLs.
2. **Serve them from your website**, e.g. `https://cassyx.dev/docker-compose.yml`.
3. **Paste the compose file into the docs** and have the customer save it. Ugly, but it works.

Two more things to check at the same time:

- **GHCR package visibility is independent of repository visibility** (§1.1). Verify with
  `docker logout ghcr.io && docker pull ...` from a clean machine *after* making the repo private -
  do not assume the packages stayed public.
- **Actions minutes are metered again** for private repos. The weekly CVE scan is the expensive
  job; it is already off the PR path, but budget for it.

Worth naming the trade-off honestly: ELv2 is a **source-available** licence, and both the README and
plan §9.0 describe cassyx that way. A private repo is not source-available. That breaks no law -
ELv2 does not oblige you to publish - but it does contradict your own positioning, and
source-availability is a real part of why a security-conscious buyer trusts a tool that holds their
database credentials. If you take it private, update that language so the claim matches reality.

---

## 7. Legal documents

Full drafts below. **These are drafts for a lawyer to review, not legal advice**, and they are
written to make that review cheap: the commercial decisions are already made and marked, so counsel
is checking and adjusting rather than starting from a blank page. Get them reviewed before the
first sale, particularly if you sell into the EU or to enterprises with procurement teams.

Publish each as its own page (for example `https://cassyx.dev/eula`) and link all three from the
pricing page and the Stripe checkout. Stripe's account review looks for them.

### 7.0 Fill these in first

Every draft uses these placeholders. Replace all of them before publishing.

| Placeholder | Meaning | Note |
| --- | --- | --- |
| `{LEGAL_ENTITY}` | Registered seller | Should be a company, not an individual. See §7.0.1 |
| `{ENTITY_NUMBER}` | Company registration number | |
| `{ADDRESS}` | Registered address | Required for consumer sales in most jurisdictions |
| `{JURISDICTION}` | Governing law and courts, e.g. "England and Wales" | Where **you** are, not the customer |
| `{SUPPORT_EMAIL}` | e.g. `support@cassyx.dev` | Must be monitored |
| `{PRIVACY_EMAIL}` | e.g. `privacy@cassyx.dev` | Can be the same address |
| `{WEBSITE}` | e.g. `https://cassyx.dev` | |
| `{EFFECTIVE_DATE}` | Date of publication | |
| `{PRICE}` / `{CURRENCY}` | | |
| `{REFUND_DAYS}` | Recommended: `30`. See §7.3 | |
| `{SUPPORT_MONTHS}` | Recommended: `12`. See §7.1 cl. 5 | |

#### 7.0.1 A note on the seller entity

The `LICENSE` file currently reads `Copyright (c) 2026-Today Cassyx`. Copyright and contractual
rights vest in a **person or a registered entity**; "Cassyx" is a product name unless you have
actually incorporated it. If you have not, either incorporate before selling or use your own legal
name consistently across `LICENSE`, the EULA and Stripe. A mismatch between the entity taking the
money and the entity granting the licence is the kind of thing that only becomes a problem when it
is expensive.

---

### 7.1 End User Licence Agreement

> **Scope note.** The *source code* is distributed under the Elastic License 2.0 (see `LICENSE`).
> This EULA governs the **commercial licence key** you sell: what the buyer gets, for how long, and
> what you promise. The two are complementary, and clause 12 resolves any conflict.

---

**CASSYX END USER LICENCE AGREEMENT**

Effective {EFFECTIVE_DATE}

This Agreement is between {LEGAL_ENTITY}, registered in {JURISDICTION} under number
{ENTITY_NUMBER}, of {ADDRESS} ("we", "us"), and the individual or entity purchasing a Licence Key
("you").

By purchasing, activating or using a Licence Key you accept this Agreement. If you are accepting on
behalf of an organisation, you confirm you are authorised to bind it.

**1. Definitions**

"Software" means cassyx, the self-hosted data management application for Apache Cassandra and
compatible databases. "Licence Key" means the cryptographically signed key we issue to you.
"Major Version" means the first component of a semantic version number (the `1` in `1.4.2`).
"Covered Version" means the Major Version stated in your Licence Key's `scope` field, or every
version if no such field is present.

**2. Grant**

Subject to your compliance with this Agreement and payment in full, we grant you a **perpetual,
worldwide, non-exclusive, non-transferable** licence to install and use the Software in any Covered
Version, for your own internal business purposes, on the number of installations stated at
purchase.

Perpetual means exactly that: your right to run a Covered Version does not expire, and does not
depend on any further payment, on our continued existence, or on network access. The Software
verifies your Licence Key **offline**.

**3. What is not included**

This licence does not cover Major Versions later than your Covered Version. Those are a separate
purchase. **Your existing installation continues to work indefinitely** - upgrading is your choice,
never something we force by expiry.

**4. Restrictions**

You may not:

(a) provide the Software to third parties as a hosted or managed service;
(b) remove, disable, circumvent or modify the Licence Key functionality, or any feature it
    protects;
(c) share, resell, sublicense or publish your Licence Key;
(d) remove or obscure any copyright, licensing or attribution notice.

These mirror the Elastic License 2.0 limitations that govern the source code. Breach terminates
this Agreement under clause 9.

Your rights to read, modify and self-host the source code are governed by the Elastic License 2.0
and are **not** narrowed by this Agreement.

**5. Trials, updates and support**

A trial Licence Key is time-limited and grants the same rights until it expires.

We provide patch and minor releases within your Covered Version for {SUPPORT_MONTHS} months from
purchase, and best-effort support by email at {SUPPORT_EMAIL}. **We do not commit to a response
time, an uptime figure, or to fixing any particular defect.** If you need contractual support
terms, contact us for a separate agreement.

**6. Your data**

The Software runs entirely on infrastructure you control. **We have no access to your databases,
your queries, or any data the Software touches.** The Software does not transmit usage data,
telemetry or licence checks to us. See the Privacy Policy for the limited personal data we hold in
connection with your purchase.

You are solely responsible for your data, for your backups, and for the correctness of any
operation you perform through the Software - including destructive operations such as `DROP`,
`TRUNCATE` and bulk writes, which the Software will carry out as instructed.

**7. Warranty**

We warrant that for {REFUND_DAYS} days from purchase the Software will perform substantially as
described in its documentation. Your exclusive remedy for breach of this warranty is a refund under
the Refund Policy.

Otherwise, and to the maximum extent permitted by law, the Software is provided **"as is"** without
warranties of any kind, express or implied, including merchantability, fitness for a particular
purpose and non-infringement.

**8. Limitation of liability**

To the maximum extent permitted by law:

(a) neither party is liable for indirect, incidental, special or consequential loss, or for loss of
    profit, revenue, data or goodwill, however caused;
(b) our total aggregate liability under or in connection with this Agreement is limited to the
    amount you actually paid us in the twelve months before the claim arose.

Nothing in this Agreement excludes liability for death or personal injury caused by negligence, for
fraud or fraudulent misrepresentation, or for anything else that cannot lawfully be excluded. If
you are a consumer, your statutory rights are unaffected.

**9. Term and termination**

This Agreement runs from purchase until terminated. It terminates automatically if you breach
clause 4. On termination you must stop using the Software and destroy your Licence Key.

We may terminate on written notice for material breach not remedied within 30 days.

**Note on enforcement:** because verification is offline by design, we have no technical means of
disabling a Licence Key remotely. Termination is a legal consequence, not an automatic one.

**10. Third-party components**

The Software includes open-source components under their own licences, listed in the distribution.
Nothing in this Agreement limits your rights under those licences.

**11. General**

This Agreement is governed by the laws of {JURISDICTION}, and the courts of {JURISDICTION} have
exclusive jurisdiction, save that either party may seek injunctive relief anywhere. You may not
assign this Agreement without our written consent; we may assign it as part of a sale of our
business. If any provision is unenforceable, the rest survives. This Agreement, the Elastic License
2.0, the Privacy Policy and the Refund Policy are the entire agreement between us on this subject.

**12. Conflicts**

Where this Agreement conflicts with the Elastic License 2.0 regarding **your rights in the source
code**, the Elastic License 2.0 prevails. Where it conflicts regarding **your commercial licence,
support or payment**, this Agreement prevails.

Contact: {SUPPORT_EMAIL} - {LEGAL_ENTITY}, {ADDRESS}

---

### 7.2 Privacy Policy

> **This is unusually short, and legitimately so.** cassyx is self-hosted and does not phone home,
> so the only personal data you hold is what a purchase inevitably creates. Do not pad it - an
> honest, narrow policy is both more accurate and more persuasive to a security reviewer than a
> boilerplate one that claims collection you do not perform.

---

**CASSYX PRIVACY POLICY**

Effective {EFFECTIVE_DATE}

{LEGAL_ENTITY} ({ENTITY_NUMBER}), {ADDRESS}, is the data controller for the personal data described
here. Contact: {PRIVACY_EMAIL}.

**1. The short version**

The cassyx application runs on your own infrastructure. **It does not send us your data, your
queries, your database credentials, or any telemetry.** Licence verification happens offline, on
your machine, with no network call to us. We could not see your data if we wanted to.

The only personal data we hold is what is needed to sell you a licence and re-send it if you lose
it.

**2. What we collect, and why**

| Data | Why | Lawful basis (UK/EU GDPR) |
| --- | --- | --- |
| Email address | To deliver your Licence Key and let you recover it | Contract |
| Name (if given) | To personalise the licence and the email | Contract |
| Licence records: key ID, edition, dates, delivery status | To honour the licence and support recovery | Contract |
| Payment records: amount, currency, timestamp, Stripe identifiers | Order records and tax compliance | Legal obligation |
| Support correspondence | To answer you | Legitimate interests |

**We never receive or store your card details.** Payments are processed by Stripe, which acts as an
independent controller for payment data. See Stripe's privacy policy at
<https://stripe.com/privacy>.

**3. What we do not collect**

No product telemetry, usage analytics or crash reports. No database contents, schemas, queries or
credentials. No IP-based tracking of the application. No advertising or profiling cookies. We do
not sell or share personal data, and we do not use it for automated decision-making.

**4. Who we share it with**

Only these processors, and only as needed:

- **Stripe** - payment processing
- **{Your email provider}** - delivering licence emails
- **{Your hosting provider}** - running the licensing service

We may disclose data where legally required.

**5. International transfers**

Our processors may process data outside {JURISDICTION}. Where they do, transfers rely on Standard
Contractual Clauses or an adequacy decision.

**6. How long we keep it**

Licence and order records for **7 years** after purchase, to meet tax and accounting obligations
and so that we can reissue a perpetual licence years later - which is the point of selling one.
Support correspondence for 2 years. We delete what we no longer need.

**7. Your rights**

Subject to law, you may request access, correction, deletion, restriction, portability, or object
to processing. Email {PRIVACY_EMAIL}; we respond within one month.

**Please note:** deleting your licence record means we can no longer prove your purchase or reissue
your key. Your existing installation will keep working, because verification is offline, but we
will not be able to help you recover a lost key.

You may complain to your data protection authority. In the UK that is the ICO
(<https://ico.org.uk>).

**8. Security**

The private signing key is held only on the licensing service, never distributed. Licence records
are held on access-controlled infrastructure. Credentials the application stores for **your**
clusters are encrypted with AES-256-GCM using a key **you** supply and we never see.

**9. Changes**

We will post changes here and update the effective date. Material changes will be emailed to
licence holders.

---

### 7.3 Refund Policy

> **Commercial decision embedded here.** {REFUND_DAYS} is drafted as 30 days. Combined with the
> 14-day free trial, this is generous, and generosity is the right call: a visible refund policy
> raises conversion by more than refunds cost, and Stripe requires one to be published anyway.
>
> **The tension to understand:** a refunded key keeps working, because verification is offline and
> there is no revocation (§7 gaps). Refunds are therefore an honour system. That is a deliberate
> trade - remote kill switches require phoning home, which would break air-gapped installs, which
> is precisely the customer you are selling to. Price the small amount of abuse in, and do not
> build DRM to chase it.

---

**CASSYX REFUND POLICY**

Effective {EFFECTIVE_DATE}

**1. {REFUND_DAYS}-day money-back guarantee**

If cassyx is not right for you, email {SUPPORT_EMAIL} within **{REFUND_DAYS} days** of purchase and
we will refund you in full. You do not need to give a reason, and we will not ask you to sit
through a retention conversation.

**2. Try before you buy**

We offer a **14-day free trial** with no card required. We would rather you evaluated cassyx
properly than bought it and asked for the money back - request a trial at {WEBSITE}.

**3. How to request one**

Email {SUPPORT_EMAIL} from the address you purchased with, or quote your licence ID (`CSX-...`).
That is all we need.

**4. How it is processed**

Refunds go to the original payment method via Stripe, usually within 3 business days on our side.
Your bank may take a further 5 to 10 business days. You will get email confirmation when we issue
it.

**5. On termination of your licence**

When we refund you, your licence terminates and you agree to stop using the Software and to destroy
your Licence Key.

We are being straightforward about this: **your key will continue to work after a refund.** cassyx
verifies licences offline, with no connection to us, so that it runs in air-gapped and restricted
networks. That design has no remote off switch. We rely on you to honour clause 5, and we would
rather say so plainly than pretend to a control we do not have.

**6. After {REFUND_DAYS} days**

Licences are perpetual and non-refundable after the guarantee period. If something is broken,
contact us - we would usually rather fix it than keep money from an unhappy customer, and we
consider refunds outside the window case by case.

**7. Upgrades**

A Major Version upgrade is a separate purchase and carries its own {REFUND_DAYS}-day guarantee.
Refunding an upgrade does not affect your original licence, which remains valid for the version it
covers.

**8. Chargebacks**

Please email us before raising a chargeback. A chargeback costs us a fee on top of the refund and
takes months to resolve, whereas an email takes a day. We have never refused a good-faith refund
request within the guarantee period.

Contact: {SUPPORT_EMAIL} - {LEGAL_ENTITY}, {ADDRESS}

---

## 8. Known gaps

Honest list. None block a first sale; several will annoy you by the tenth.

| Gap | Impact | Effort |
| --- | --- | --- |
| **No `licensing/` image is published.** The release pipeline builds the two product images only. Deploy it from source or add a third build. | You cannot `docker run` the licensing service as shown in §3 until this exists | small |
| **No pricing or landing page.** No checkout entry point outside the app. | Nobody can find the buy button | small, needs copy |
| **EULA, privacy and refund policy are drafted but not reviewed or published** (§7). ELv2 covers distribution, not your terms of sale or data handling. | Account review friction until published; legal exposure until reviewed | drafts done; needs counsel + hosting |
| **Seller entity is ambiguous** (§7.0.1). `LICENSE` names "Cassyx", which is a product, not a registered entity. | The party granting the licence and the party taking the money should be the same, and should exist | needs a decision |
| **Scope is service-wide, not per-request.** §5.3. Selling 1.x and 2.x concurrently means reconfiguring between mints. | Awkward at upgrade time | small |
| **TLS to the SMTP provider is untested end to end.** Proven against an in-process server only. | §3 step 4 is the real test | covered by testing |
| **Deliverability is unverified.** Cannot be tested in CI, only against real inboxes. | §1.3 | covered by testing |
| **No licence revocation.** A refunded or charged-back customer keeps a working key; verification is offline by design (plan §9.1). | Accept it, or add online activation later | design decision |
| **Compatibility matrix unverified** (plan §7.1) and **no perf benchmark** (§11.2), so `BulkEngine.AUTO` routes on assumption. | Support surprises | medium |
| **Migration tools (§8) are a skeleton.** | A NoSQL Manager parity gap, not a blocker | large |

---

## 9. Shortest path to first revenue

1. Set `CASSYX_LICENSING_SCOPE=1` and a real `CASSYX_LICENSING_TOKEN` (§0)
2. Generate and **back up** the keypair (§2.1)
3. Stripe sandbox: product, price, restricted key, webhook (§1.2)
4. SMTP provider plus SPF/DKIM/DMARC (§1.3)
5. Publish `1.0.0`, then **make the GHCR packages public** (§1.1, §4)
6. Full end-to-end test on the sandbox, including the failure paths (§3)
7. Verify a released image refuses the bypass flag (§3.1)
8. Write the refund policy; put a price on a page
9. Switch Stripe to live keys and sell one to yourself first

Steps 1, 2, 5 and 7 are the ones where a mistake is expensive or irreversible. The rest you can
iterate on.
