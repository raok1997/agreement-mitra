# AgreementMitra — Architecture

This is the durable record of the architecture decisions. CLAUDE.md is the
short version Claude loads every session; this is the detail. Treat changes to
these decisions as proposals (via OpenSpec), not casual edits.

## Product

Online rental-agreement platform for India. The MVP capability is Aadhaar
eSign (OTP) of rental agreements. Planned later: KYC + DigiLocker with
fraud/forgery detection; a multi-state legal-logic rules engine that
auto-generates jurisdiction-correct agreements; vernacular/multilingual
document generation.

## Stack decision and rationale

- **Backend: Java 21 + Spring Boot 3.x.** Chosen over PHP/Laravel because this
  is a production system (not a throwaway prototype) owned by a Java expert,
  and because the flagship future feature — the multi-state rules engine — is
  a near-perfect fit for Drools (JVM-native, mature). Greenfield, so no
  migration cost.
- **Modular monolith (Spring Modulith).** One deployable with enforced internal
  module boundaries. Gives clean seams to extract services later *if* scale
  demands, without paying the microservices tax now.
- **Frontend: Vue 3 + Vite + TypeScript + Tailwind.** Uses the framework the
  team already knows (not React). Responsiveness is handled by Tailwind;
  interactivity needs are light (trigger + live status).
- **PostgreSQL** for relational state and the audit trail. **Object storage**
  (MinIO local, S3-compatible prod) for signed/unsigned PDFs.

## Modules

| Module      | Responsibility                                               | Status   |
|-------------|--------------------------------------------------------------|----------|
| `signing`   | Agreements, signing requests, status FSM, webhook intake, `EsignProvider` + vendor adapters | active   |
| `documents` | Template → PDF (headless Chromium / Playwright), fonts        | active   |
| `identity`  | Aadhaar/PAN/DigiLocker KYC, fraud/forgery checks             | future   |
| `rules`     | Multi-state legal-logic rules engine (Drools)               | future   |

Modules expose a small public API package; everything else is package-private.
Cross-module communication is via published interfaces or Spring application
events — never direct access to another module's internals. `ModularityTests`
enforces this.

## Signing flow (asynchronous)

```
Vue SPA ──create agreement──▶ signing
signing ──render──▶ documents (template → PDF)
signing ──create sign request──▶ EsignProvider (Leegality sandbox)
        ◀── signing URL ──
signer authenticates (Aadhaar + OTP) on the ESP page
EsignProvider ──webhook: signed PDF + audit──▶ signing.WebhookController
signing updates status ──SSE/poll──▶ Vue SPA
```

The request thread never blocks on a signature. Webhook drives completion; a
scheduled reconciliation job polls for any signing requests stuck past a
timeout (covers missed webhooks).

## eSign vendor strategy

`EsignProvider` is the abstraction. Start on **Leegality** (license-free
sandbox, built-in e-stamping which rental agreements need, document templating,
webhooks). Keep **Digio** in mind as the likely production swap when KYC /
DigiLocker arrive, since it bundles KYC + eSign + eStamp behind one API. The
abstraction means switching is one adapter, not a rewrite.

## Document generation

`documents` renders a template to PDF via headless Chromium (Playwright for
Java). Pure-Java PDF libraries do not shape complex Indic scripts correctly, so
Chromium + bundled Noto fonts (Devanagari, Tamil, etc.) is the path that
future-proofs the vernacular feature. English-only would be simpler today, but
we build the Chromium pipeline from the start to avoid a later rearchitecture.

## Access to an agreement: what authorises what

Three values can identify an agreement, and only one of them authorises anything.
Conflating them is the most likely way to introduce a serious PII leak here, so
the distinction is recorded rather than left to be rediscovered.

- **The agreement UUID is a bearer capability.** 122 bits, unguessable. Any caller
  presenting it may read or act on an **unowned** agreement (generate, upload,
  preview, finalise, contacts, pay); once claimed, only the owner may, and anyone
  else gets the same 404 as an unknown id. The rule is `Agreement.admits`, defined
  once. That is the existing model and it predates recovery.
- **The tracking reference authorises nothing.** `AM` + eight characters + a check
  character - roughly 40 bits. It appears on the rendered document, in emails, and
  in support conversations. It is a *selector*, never a credential.
- **An identity session** is what a claimed agreement requires.

**Recovery works by delivering the UUID out of band, not by looking up the
reference.** Entering a reference causes a link to be emailed to the parties
already on the agreement; it never returns agreement data, and it answers
identically whether or not the reference matched. This is what lets a 40-bit
value be usable in a support conversation without making every paid agreement
enumerable.

A future change that "simplifies" this into a reference lookup that returns the
agreement would reverse it. So would adding a "not found" response to the
recovery endpoint. Both leak full signer PII - names, fathers' names, addresses,
rent - to anyone who guesses a code.

**Claiming is the revocation.** The emailed link does not expire. It stops working
when the agreement acquires an owner, because anonymous access to a claimed
agreement is already refused. That behaviour is load-bearing now, not incidental:
it is the only off-switch a permanent link has, and the customer is told about it.

**One definition of "contactable".** A party is reachable when they have a contact
on an **enabled delivery channel**. That single rule gates order creation and eSign
initiation. It replaced an earlier email-or-mobile check that disagreed with
email-only delivery, letting a mobile-only party sign an agreement they could never
be sent. Two rules that disagree is the defect; keep it one.

## What we email a customer, and when

Three distinct messages reach the parties, at three different moments. They are easy to conflate --
two of them carry links that look similar and only one is per-party -- so the distinctions are
recorded here rather than rediscovered.

| # | Message | Trigger | Per party? | Carries |
|---|---|---|---|---|
| 1 | **The draft** | saving contacts (`PATCH /api/agreements/{id}/contacts`) | No | the draft document |
| 2 | **The recovery link** | payment confirming | No -- one URL for everyone | a link only, no attachment |
| 3 | **Signing invitations** | staff attaching the stamp, which starts signing | **Yes** -- one `sign_url` per signer | that signer's own signing URL |

Three things people get wrong about this:

- **The draft is not sent by paying.** Its only trigger is a contacts save. Contacts must be set to
  pass the checkout gate and freeze once paid, so in practice it lands just before payment and feels
  like a payment email -- but correcting contacts twice sends it twice, and paying sends it never.
- **Only the signing invitation is per-party.** The recovery link is `<base>/agreement/<uuid>`,
  identical for every party. `SigningRequestInvitee` is what holds a per-signer `sign_url`.
- **The recovery message deliberately carries no attachment** (asserted by
  `RecoveryDeliveryServiceTest.theMessageNeverCarriesAnAttachment`). It is a way back in, not a copy
  of the document -- the copy is message 1.

**Between payment and message 3, the customer has nothing to do.** Staff attach the stamp; the
customer waits. That dead interval is what the `agreement-status-link-page` change addresses -- today
the recovery link lands them on a capture form they cannot submit, because the agreement froze at
finalise.

**Who paid is never an authorisation input.** In practice whoever starts the agreement pays, but the
system records no payer-only rule and must not gain one (design D15 in `post-payment-continuity`):
any party holding the link is equally entitled. Guarded by
`RazorpayPaymentIntegrationTest.payingSendsEveryPartyALinkTheyCanOpen`.

## How staff learn a paid order is waiting

The staff console does not refresh itself, so a paid order is pushed: one message to a private staff
Discord channel, within about a minute of the gateway confirming payment (`signing.staffalert`,
change `staff-paid-order-alert`).

- **Derived from paid orders, not from the confirmation.** A scheduled sweep (every 30 seconds) reads
  the payment orders marked `PAID` in the last 24 hours and records one `staff_alert` row per
  agreement that has none. The payment confirmation path is untouched: no listener, no write in its
  transaction, no outbound call on the webhook thread. An alert cannot roll a payment back, and a
  crash cannot lose one, because the paid order is the durable trigger.
- **Gateway-paid only, one paid-order alert per agreement.** A manual staff confirmation or a waiver
  creates no payment order and raises nothing.
- **A second payment raises a different alert.** A gateway payment confirmed for an agreement that
  already holds one under another reference is still marked `PAID` (the money was captured) but is
  marked **surplus** on its `payment_order` row: the agreement's first payment record is kept and no
  second recovery link is sent. `AgreementService.recordGatewayPayment` decides this under the
  agreement's row lock. The sweep raises one `DUPLICATE_PAYMENT` alert per surplus order -- *instead
  of* the paid-order alert, never both -- worded "Possible duplicate payment - check before
  refunding". `staff_alert` is keyed by the alert's subject and its kind: the subject is the
  agreement for `ORDER_PAID` and the payment order for every other kind, so one order can hold one
  alert of each kind. A gateway payment after a waiver is the agreement's one
  payment, not a surplus. The system refunds nothing itself; the runbook is in `docs/DEPLOYMENT.md`.
- **A captured payment that was refused or not recorded raises its own alert.** The same sweep
  reads the payment event ledger (below) and raises one alert per payment order and kind. That
  look-back and its insert run first and on their own, in their own transaction: a fault in them is
  one ERROR line a sweep and stops neither the other two look-backs nor the dispatch, and a fault in
  those two does not hold it back.
- **Claim, send, record.** The sweep claims a row by a conditional update that counts the attempt
  and writes the next-attempt time *before* sending; that time is the lease, so a crash mid-send
  needs no recovery step. The send runs with no transaction open. Delivery is at-least-once.
- **Bounded.** Ten attempts, backoff 30 seconds doubling to an hour (about three hours in all). A
  timeout, a 429 or a 5xx is retried; a redirect or any other 4xx fails the alert at once; an alert
  still pending after 24 hours is failed unsent. `FAILED` is terminal and is reported only as an
  ERROR log line -- the console queue remains the source of truth for what is waiting. That does
  not hold for the three unrecorded-payment kinds: no console queue lists those orders yet, so
  the runbook's stuck-order query in `docs/DEPLOYMENT.md` is.
- **What the message may contain:** a fixed lead naming the kind of alert, the tracking reference, a
  two-letter state code and a link to the public site. Never the agreement id (a bearer credential), a party name, a contact, an
  address, an amount, or any user-entered text -- which is why there is no city.
- **The webhook URL is a secret** (the token is in its path). Blank or unusable switches the feature
  off entirely. It is never logged: the adapter catches the HTTP client's exceptions itself, because
  their messages embed the URL.
- **One channel.** The sender sits behind `StaffNotifier`; a WhatsApp adapter replaces Discord there
  (`staff-alert-whatsapp-adapter`).

## The payment event ledger

Every gateway payment confirmation attempt is recorded in `payment_event` (change
`payment-event-ledger`), so "what did the gateway send and what did we do with it" is answered
from the database and not from the server log beside the gateway dashboard.

**One reader today: the staff alert sweep** (change `payment-recording-failure-staff-alert`). Every
sweep it reads the attempts last received in the past 24 hours whose outcome is `RECORDING_FAILED`,
`DUPLICATE_REFERENCE` or `AMOUNT_MISMATCH`, joined to `payment_order`, and raises one alert per
payment order and kind (`PAYMENT_NOT_RECORDED`, `PAYMENT_ID_REUSED`, `PAYMENT_AMOUNT_MISMATCH`)
for an order whose status is anything but `PAID`. An attempt with no gateway payment id is left
out: it is an event without payment detail, not a captured payment. Only the order's status is read, never the
agreement's payment state: an expired order whose customer paid again is still alerted, because it
is a possible double charge. The three outcomes are listed once, in
`PaymentOrderQueryAdapter.UNRECORDED_OUTCOMES`. "Already handled" is the `staff_alert` row itself,
so a failure that recurs raises nothing more. An order whose attempts only ever end `ERRORED`
raises no alert. The staff console panel for these payments does not exist yet and reads nothing.

- **What a row is.** One **distinct** attempt that reached `PaymentOrderService.applyConfirmation`
  - from the webhook, the browser callback or the reconciliation job - and a count of its repeats.
  An attempt identical in order id, source, event type, outcome, payment id, amount, currency,
  refused rule and SQL state raises `attempt_count` and widens `first_received_at` /
  `last_received_at`; it adds no row. A stuck order re-read every sweep, or a replayed webhook,
  cannot grow the table.
- **Written outside the confirmation transaction.** `PaymentEventLedger` writes in its own
  transaction after the confirmation's has ended, so the row survives a rollback: a payment the
  database refused (`RECORDING_FAILED`), a refused duplicate, or any other error (`ERRORED`) all
  leave one. The ledger cannot fail its caller - a row that cannot be written is one WARN and the
  attempt's result stands.
- **What is not recorded.** A webhook whose signature fails, a verified body that does not parse,
  an event type we do not act on, and a gateway read that reports the order unpaid or without a
  captured payment. None of them is a confirmation attempt.
- **What is not stored.** No webhook payload and no payer detail (email, phone, UPI id, card
  digits). No database error text. No foreign key (an unknown order has no row to point at) and no
  JPA entity (one statement writes the table). No HTTP status: it follows from source and outcome,
  and a stored copy would drift from the controllers that decide it.

| Outcome | Webhook | Browser callback | Reconciliation |
|---|---|---|---|
| `RECORDING_FAILED` | server error | `200` with progress | none; the order is skipped |
| `ERRORED` | server error | server error | none; the order is skipped |
| anything else | `202` | `200` with progress | none |

- **`presented_event_id` is not verified.** It is the `X-Razorpay-Event-Id` header, which the
  webhook signature does not cover. It is kept only when it is a short plain token, nothing
  decides anything on it, and a replay under a fresh id does not change the one first stored.

**Contract for anything that reads the ledger:**

- Find "what have I not handled yet" by comparing against the reader's own state, never by a time
  or id watermark: rows are not written in received order, and a row changes after it is written.
- A row existing does not mean it was handled. A failure that recurs only raises the count on the
  same row, and every `ERRORED` attempt for one order and source shares one row; compare
  `last_received_at` with the reader's own handled-at time.
- `attempt_count` is not severity and not the gateway's retry count, and `last_received_at` is not
  a gateway delivery time: a replay raises both on a webhook row, and the payer can on a callback
  row.
- A `RECORDING_FAILED` or `ERRORED` row does not mean the order is unpaid. `payment_order` and the
  agreement say whether it is paid.
- A surplus payment is recorded as `CONFIRMED`; `payment_order.surplus` tells it apart.
- The strings are gateway-supplied free text: encode them when rendering.
- `presented_event_id` is not the gateway's: it is whichever value was presented first for that
  distinct attempt, by whoever got there first.
- The ledger is best-effort and may be incomplete.
- A later change to the values that make an attempt distinct, to a column width, or to the key's
  encoding gives an identical attempt a second row from then on.

## What we are deliberately NOT doing yet

- No microservices, no message broker (add RabbitMQ/Kafka only when multi-step
  orchestration — KYC gate → e-stamp → multi-party signing — actually exists).
- No production ASP contract, no real Aadhaar data, no company/trademark work.
- No Drools wiring until the rules feature is specced.
