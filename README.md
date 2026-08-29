# AgreementMitra

Online rental-agreement platform for India. MVP: Aadhaar eSign (OTP) of rental
agreements. See `docs/ARCHITECTURE.md` for the full design and `CLAUDE.md` for
the working context Claude Code loads each session.

## Stack

- Backend: Java 21 + Spring Boot 3.x, modular monolith (Spring Modulith)
- Frontend: Vue 3 + Vite + TypeScript + Tailwind
- Postgres + MinIO (object storage) for local dev, via Docker Compose
- eSign behind an `EsignProvider` interface; first adapter: Leegality (sandbox)

## Prerequisites

- JDK 21
- Node.js >= 20.19 (required by both Vite and the OpenSpec CLI)
- Docker (for local Postgres + MinIO)
- OpenSpec CLI — for spec-driven development. No separate install needed if you
  use `npx openspec` (see [Wire up OpenSpec](#wire-up-openspec)); to install it
  globally instead, run `npm install -g openspec` (then use `openspec` directly).

### Windows

The repo's day-to-day commands assume a POSIX shell. On Windows:


- Run all commands from **Git Bash** or **WSL2** (not `cmd`/PowerShell). The
  helper scripts (`backend/start_local.sh`, `backend/run-tests.sh`) and the
  Claude Code hooks (`.claude/hooks/*.sh`) are bash-only and won't run otherwise.
- Use the bundled `gradlew.bat` if you do invoke Gradle from `cmd`/PowerShell —
  e.g. `gradlew.bat test` in place of `./gradlew test`.
- Substitute `copy` for `cp` (e.g. `copy .env.example .env`), or just use Git
  Bash where the `cp` commands below work as written.
- Run Docker Desktop with the **WSL2 backend** for Postgres + MinIO.

## First-time setup

1. Start local infra:
   ```
   docker compose up -d
   ```
2. Configure env (sandbox/dummy values only):
   ```
   cp .env.example .env   # fill in ZOOP test-environment values when you have them
   ```
   See "eSign provider env vars" below. Nothing here needs a real credential to
   boot: the app starts with empty vendor credentials and every test runs against
   WireMock.
3. Add the Gradle wrapper to the backend (run once, then commit it):
   ```
   cd backend && gradle wrapper --gradle-version 8.12 && cd ..
   ```
   (If you don't have a local `gradle`, generate the wrapper from your IDE's
   Gradle import instead.)
4. Backend:
   ```
   cd backend && ./gradlew test && ./gradlew bootRun
   ```
5. Frontend:
   ```
   cd frontend && npm install && npm run dev
   ```

## eSign provider env vars

The active provider is chosen by `ESIGN_PROVIDER` (`zoop` | `leegality`); exactly
one adapter is wired per configuration, so switching vendors is a config flip.
**ZOOP eSign v5 is the default.** Credentials come from env only and are never
committed.

| Variable | Default | Notes |
| --- | --- | --- |
| `ESIGN_PROVIDER` | `zoop` | `leegality` is the rollback path. |
| `ZOOP_BASE_URL` | `https://test.zoop.plus/contract/esign/` | The **test** host. The production host (`live.zoop.plus`) is never a default. |
| `ZOOP_APP_ID` | *(empty)* | From the ZOOP dashboard. Sent as the `app-id` header. |
| `ZOOP_API_KEY` | *(empty)* | From the ZOOP dashboard. Sent as the `api-key` header. |
| `ZOOP_TXN_EXPIRY_MIN` | `10080` | 7 days. The parties sign days apart, so this window is measured in days by design. |
| `ZOOP_ARTIFACT_HOSTS` | `test.zoop.plus,esign.zoop.plus,live.zoop.plus` | SSRF allowlist for signed-document URLs. ZOOP returns expiring links on a host that differs from the API host. Keep it an allowlist. |
| `ZOOP_RESPONSE_URL` | *(empty)* | The webhook URL. **Must be publicly reachable** - front it with cloudflared/ngrok locally or the callback never arrives. |
| `ZOOP_REDIRECT_URL` | *(empty)* | Where a signer lands after signing. |
| `ZOOP_ORG_NAME` | `AgreementMitra` | Shown in the invitation email ZOOP sends. |
| `ESIGN_WEBHOOK_KEY_PEPPER` | dev placeholder | Keys the at-rest encryption of the **per-transaction** webhook key. Set from env in any shared environment, like `AUTH_HASH_PEPPER`. |
| `LEEGALITY_*` | *(empty)* | Only read when `ESIGN_PROVIDER=leegality`. |

## Payment env vars (Razorpay, test mode only)

The payment **gate** and the payment **gateway** are separate decisions. The gate
(`PAYMENT_MODE`) is what blocks the pipeline; the gateway is what takes the money.
Razorpay implements the gate's confirmation seam alongside the manual STAFF path.

**The gate ships `REQUIRED`.** Payment must clear before staff spend real money on
an e-stamp - gating at intake is the whole point, because a purchased SHCIL
certificate cannot be un-bought. The accepted risk is explicit: until a live
payment has been observed settling, a payment bug hard-blocks **all** fulfilment
rather than merely leaking unpaid work.

Two escape hatches, in the order you should reach for them:

1. **Waive one agreement** - `POST /api/staff/payments/{id}/waive` (STAFF only).
   The normal remedy when money arrives out of band, or the provider is down for
   one customer. Recorded as `WAIVED`, permanently distinguishable from `PAID`, so
   the books never confuse a goodwill decision with money received.
2. **Relax the gate globally** - `PAYMENT_MODE=OPTIONAL`. A configuration change,
   no redeploy of code, no schema change. Payment state is still recorded; it just
   stops blocking. This is the lever if the integration itself is broken.

Unpaid orders **stay visible** on the staff stamp queue rather than being filtered
out - the console marks them "Awaiting payment" and disables the upload button, so
a blocked order reads as blocked rather than as missing.

Three credentials, and **two of them are secrets that must never be crossed**:

| Variable | Default | Notes |
| --- | --- | --- |
| `PAYMENT_MODE` | `REQUIRED` | The gate. Blocks e-stamp intake and eSign initiation until an agreement is `PAID` or `WAIVED`. `OPTIONAL` records payment state and blocks nothing. |
| `RAZORPAY_KEY_ID` | *(empty)* | **Public by design** - handed to the browser so Checkout can open. Use a `rzp_test_` key here. |
| `RZP_KEY_SECRET` | *(empty)* | **Server-only.** HTTP Basic password for the Orders API, and the key that signs the value Checkout returns to the browser. |
| `RZP_WEBHOOK_SECRET` | *(empty)* | **Server-only, and a DIFFERENT value from the key secret.** It, and only it, verifies inbound webhooks (HMAC-SHA256 over the raw body). Using the key secret here is a well-trodden integration error; the two never default to each other. |
| `RAZORPAY_BASE_URL` | `https://api.razorpay.com/` | Test and live are selected by the credentials, not the host. |
| `PAYMENT_AMOUNT_MINOR_UNITS` | `49900` | The flat price in **paise**, as an integer. `49900` = Rs. 499.00. A commercial decision; it sets this value and nothing else. |
| `PAYMENT_CURRENCY` | `INR` | ISO-4217. |
| `PAYMENT_ORDER_TTL` | `PT2H` | How long an outstanding order stays reusable, so a reload resumes it rather than accumulating orders. |
| `PAYMENT_RECONCILIATION_*` | see `application.yml` | The fallback scan that re-reads outstanding orders so a missed webhook cannot strand a customer who has paid. |

**The webhook needs a public URL in local dev.** Configure `POST /api/webhooks/razorpay`
in the Razorpay dashboard (events `payment.captured` and `order.paid`) pointed at a
cloudflared/ngrok tunnel - exactly like the eSign webhook. Without it the browser
callback is the only signal, and the browser callback is **not** authoritative: a
customer who pays and closes the tab would be charged and stay `UNPAID`.

Nothing here needs a real Razorpay account to boot or to test: with empty
credentials the app refuses to place orders and rejects every webhook (fail
closed), and the whole payment test suite runs against WireMock with fabricated
secrets.

ZOOP developer/test access is **free and self-serve** through their dashboard.
Before pointing anything beyond the test host at ZOOP, run the tracer in
`docs/integrations/zoop.md` section 5 - in particular the **visual** check that
both signatures land in their signature zones.

## Contact details, delivery channels, and agreement recovery

Three related pieces, all from the `post-payment-continuity` CR.

**A party must be reachable before an order is created.** Contact details stay
optional while drafting - the self-serve flow deliberately puts no data wall in
front of a visitor who has not decided to buy - but checkout refuses unless every
party is reachable on an **enabled delivery channel**. A dedicated confirmation
step sits between finalise and payment where the customer supplies or corrects
them; that step is UX, and the server refusal is the actual gate.

**Channels are modelled, and only email is enabled.**

| Variable | Default | Notes |
| --- | --- | --- |
| `DELIVERY_EMAIL_ENABLED` | `true` | The one working channel. Backed by the existing `EmailSender` seam. |
| `DELIVERY_SMS_ENABLED` | `false` | Declared, **no adapter**. Enabling it without one makes dispatch fail loudly. |
| `DELIVERY_WHATSAPP_ENABLED` | `false` | Declared, **no adapter**. Same. |
| `PUBLIC_BASE_URL` | `http://localhost:5173` | Where the customer-facing app lives; recovery links are built from it. Blank means no link can be built, so none is sent. |

A channel absent from configuration is **disabled, not enabled**. A disabled
channel never satisfies reachability whatever contact is stored for it, and the
UI must never present one as a way a party will be contacted - there is no SMS
provider, so "we will text you" would be a promise the system cannot keep.

**Recovery.** A customer who paid anonymously and closed the tab gets back in
through a link emailed to **every** party at payment. The link carries the
agreement identifier and *is* the access - there is no token to redeem and no
expiry. It stops working when the agreement is claimed into an account, because
anonymous access to a claimed agreement is refused; that is the revocation, and
the message says so.

Lost the email? `POST /api/agreements/recovery` with a tracking reference
re-sends it. **That endpoint returns `202` with an empty body in every case** -
unknown reference, unpaid, already claimed, nobody contactable, throttled, or
sent. The uniformity is the security control, not a simplification: the tracking
reference carries far less entropy than the agreement id, so an endpoint that
revealed whether a reference existed would make every paid agreement enumerable
and leak full signer PII. What actually happened is recorded in `recovery_audit`,
which is the only place an operator can see it.

Recovery needs a real mail provider (`MAIL_PROVIDER=smtp`) and a
`PUBLIC_BASE_URL`. With the default stub provider it is inert - deliberately
fail-closed - so nothing is sent from a local run.

## Local stamping flow (manual e-stamp)

Stamping is **not automatic**. In production a staff member buys a real e-stamp
certificate from the SHCIL portal, prints it, scans it, and uploads it; signing
refuses with `409 stamp-required` until one is attached. Locally you do the same
thing with a synthetic fixture image - there is no synthetic stamp generator any
more, and no SHCIL credential exists in this repo.

1. Create the agreement and generate/upload its draft PDF as usual.
2. **Finalise** it (`POST /api/agreements/{id}/finalise`). This is the end of the
   customer's involvement: it places the order, freezes the terms, and returns the
   agreement's **tracking reference** - a short `AM...` code with a check character,
   assigned at creation and persisted. There is exactly one such number: the
   customer holds it, the document prints it, and staff type it back. (The old
   derived `AM-<LAST6>-<DDMMYY>` form is gone and is never accepted as a lookup key.)
3. Grant yourself the STAFF role - deliberately an out-of-band database action,
   never a request field:
   ```
   psql -c "UPDATE identity SET role = 'STAFF' WHERE email = '<your-login-email>'"
   ```
4. Log in and open **`/staff`** - the fulfilment console. It lists every order
   awaiting a stamp, longest-waiting first, and the upload form opens on a row, so
   the reference is never re-typed.
   To drive it by API instead, POST multipart to `/api/staff/estamp` with your
   session bearer token. Parts: `scan` (the JPEG or PNG file),
   `agreementReference`, `certificateNumber`, `issueDate`, `dutyAmount`,
   `jurisdiction`, and optionally `descriptionOfDocument` / `purchasedBy`.
5. The agreement moves to `STAMPED`, leaves the queue, and signing can start.

For a stand-in scan, `TestImages.certificateScan()` (backend test sources)
generates a valid fixture; certificate numbers in dev are fabricated - use a
fresh one each time, because a certificate number is single-use and a repeat is
rejected with `409` by a database constraint.

## Wire up OpenSpec

This repo is pre-staged for spec-driven development but the OpenSpec internals
are generated by its CLI (so they match your installed version and tool):

```
npx openspec init      # pick "Claude Code" when asked
```

`openspec init` creates its own files (e.g. `openspec/AGENTS.md`, command
definitions for Claude Code) and may touch `openspec/project.md`. A draft
`openspec/project.md` is already here — reconcile the wizard's output with it,
keeping it in sync with `docs/ARCHITECTURE.md`.

Then build features spec-first:

```
/opsx:propose create-signing-request   # writes proposal, specs, design, tasks
# review the proposal + spec deltas
/opsx:apply                            # implement the tasks
/opsx:archive                          # consolidate when done
```

The placeholder `UnsupportedOperationException`s in `SigningController` and the
Leegality adapter mark exactly where the first proposals land.

## Claude Code

- `CLAUDE.md` — project context loaded every session.
- `.claude/settings.json` — permissions (denies reading secrets and destructive
  git; allows test/build) and a post-edit formatting hook. Checked in.
- `.claude/settings.local.json` — your personal overrides (gitignored). See the
  `.example` file.

## Repo layout

```
backend/    Spring Boot modular monolith (signing, documents, identity, rules)
frontend/   Vue 3 SPA
docs/       ARCHITECTURE.md — durable design decisions
openspec/   spec-driven dev workspace (project.md draft; rest from `openspec init`)
.claude/    Claude Code settings + hooks
```
