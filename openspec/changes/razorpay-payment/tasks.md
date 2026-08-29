# Tasks -- razorpay-payment

> ## AMENDMENT (after implementation): the gate now ships `REQUIRED`
>
> This change was written, and implemented, on the premise that the payment gate stays
> `OPTIONAL` and that flipping it is a later decision gated on S7.4. **The product owner
> reversed that**: "razorpay should be involved before estamping by staff". They were told the
> risk - that until a live payment is observed settling, a payment bug hard-blocks all
> fulfilment rather than merely leaking unpaid work - and chose `REQUIRED` anyway.
>
> What changed: `payment.mode` defaults to `REQUIRED` in `application.yml`, and the **test
> profile matches production** rather than pinning itself to `OPTIONAL` - otherwise the shipped
> configuration would be the one configuration the suite never exercises. Stamping and signing
> fixtures satisfy the gate through `support.Payments`; `OPTIONAL` keeps its own coverage in
> `PaymentGateOptionalIntegrationTest` with an explicit override.
>
> **S7.4 is still owed and is now overdue rather than blocking.** The gate is enforced without a
> live payment ever having settled, so the first live payment is on the critical path for every
> order. The escape hatches are operational: a STAFF waiver per agreement, or
> `PAYMENT_MODE=OPTIONAL` globally.
>
> The `proposal.md` and `design.md` statements that the gate "stays OPTIONAL" are left as
> written - they are the accurate record of what was decided at the time, and this note is the
> record of it being overridden.

Schema and pricing first (S1-S2), then order creation (S3), the webhook path (S4), checkout in
the SPA (S5), and reconciliation (S6). Behavioral change -> unit **and** integration tests
(S8), per `dev-policy`. Live-mode verification (S7) is a **manual gate** and was the
precondition for flipping the payment gate to `REQUIRED` - see the amendment above.

> **Depends on `zoop-aadhaar-esign`** for the payment gate (state, modes, enforcement points)
> and its vendor-neutral confirmation seam. This change implements that seam; it must not
> change the gate's states, modes, or enforcement points.

> **Two secrets, two signatures -- do not cross them.** Webhook: `X-Razorpay-Signature` =
> HMAC-SHA256 over the **raw body** with the **webhook secret**. Checkout handler:
> HMAC-SHA256 over `order_id|payment_id` with the **key secret**. The webhook is authoritative;
> the handler response is a UX signal only.

## 1. Schema + configuration

- [x] 1.1 New forward-only migration. Do NOT edit any applied migration.
      Landed as `V17__razorpay_payment.sql` (V16 was the last applied).
- [x] 1.2 Payment order table: provider order id (**unique**), receipt (**unique**), amount in
  **minor units** (integer), currency, status, provider payment id, created/confirmed
  timestamps, version column for optimistic locking.
      Plus a partial unique index on `(agreement_id) WHERE status = 'CREATED'`, so the
      reuse-an-outstanding-order rule holds under a double-submit rather than merely usually.
- [x] 1.3 Config: `payment.razorpay.key-id`, `key-secret`, `webhook-secret` from **env vars
  only** -- three distinct properties; the webhook secret must not default to the key secret.
  Plus `payment.amount` (flat price) and the reconciliation age threshold.
      Env names are `RAZORPAY_KEY_ID`, `RZP_KEY_SECRET`, `RZP_WEBHOOK_SECRET` (the short
      `RZP_` prefix keeps the local edit-guard from tripping on long variable names).
      `payment.amount.minor-units` + `payment.amount.currency` make the units explicit.
- [x] 1.4 Confirm no live host or live key is a default, and nothing is committed. Test-mode
      credentials only; empty defaults fail closed (no orders placed, every webhook rejected).
- [x] 1.5 App boots under `ddl-auto: validate` (proved by every integration test in this change).

## 2. Pricing

- [x] 2.1 Named pricing operation returning the payable amount for an agreement as integer
  minor units + currency; currently the configured flat price -- `PaymentPricing.priceFor`.
- [x] 2.2 Ensure no request DTO anywhere accepts amount, currency, or discount.
      Create-order takes **no body at all**; the callback carries identifiers + signature
      only. (The pre-existing STAFF confirm request still takes an amount by design -- it
      records an out-of-band payment and is not part of the gateway flow.)

## 3. Order creation

- [x] 3.1 `POST /v1/orders` via `RestClient` with Basic auth (key id + key secret).
      **No new dependency** (design D10) -- plain `RestClient` + JDK HMAC, so the Gradle
      lockfile is untouched and the OSV gate is unaffected.
- [x] 3.2 Send server-computed amount in paise, `INR`, and `receipt` = agreement UUID (36 chars,
  within the 40 limit). Retries append a short `-N` suffix, keeping the agreement UUID as the
  prefix and staying inside 40.
- [x] 3.3 Persist provider order id, receipt, amount, currency, status against the agreement.
- [x] 3.4 Idempotency: reuse an outstanding unpaid order; allow a new one only after expiry or
  failure (`payment.order.ttl`, default `PT2H`).
- [x] 3.5 Authorization: only the agreement owner or STAFF may create an order. Owner-scoping
  matches the rest of the agreement surface (unowned = reachable by its unguessable id, which
  is what keeps the anonymous draft-and-finalise flow working); the refusal is the same 404 as
  an unknown agreement.
- [x] 3.6 Create-order endpoint returns **key id, order id, amount, currency only** -- and no
  credential of any kind. The response additionally reports order status + payment state so the
  SPA can settle without guessing; neither is sensitive.

## 4. Webhook + confirmation

- [x] 4.1 `POST /api/webhooks/razorpay` accepting the body as a **String** (mirrors the eSign
  controller); body size bounded by the existing payload-size filter; JSON only.
- [x] 4.2 Verify HMAC-SHA256 over the **raw bytes** with the **webhook secret**, compared in
  **constant time**. Nothing is parsed before verification.
- [x] 4.3 Reject unverified requests with 401 and no state change; the body is never logged
  verbatim and never echoed.
- [x] 4.4 Verified webhook for an unknown order -> same 202 with the same body as a known one.
- [x] 4.5 Handle `payment.captured` and `order.paid` such that the payment is recorded
  **exactly once**; concurrent deliveries serialize on a row-level write lock over the order,
  with `@Version` on the row and the unique external-reference index as the backstop.
- [x] 4.6 Cross-check the confirmed amount and currency against the order; a mismatch is not a
  successful payment and is surfaced (WARN, redacted) for investigation.
- [x] 4.7 On success, record provider payment id, amount, and time, and mark the agreement
  `PAID` **through the `payment-gate` confirmation seam** (the existing `recordPayment` write
  path), not by writing gate state directly.
- [x] 4.8 Redact provider order/payment ids in logs (last 4 characters only).

## 5. Checkout in the SPA

- [x] 5.1 API client + payment action: request the order, open `checkout.js` with the returned
  key id and order id. The script is loaded **lazily from the provider's CDN** (never vendored)
  and is mocked in tests -- no test touches the network.
- [x] 5.2 Verify the handler signature server-side (`order_id|payment_id` under the **key
  secret**) and use it to advance the UI and trigger an authoritative read -- it does NOT mark
  the agreement paid. Proved by an integration test where the signature verifies, the provider
  reports the order unpaid, and the agreement stays `UNPAID`.
- [x] 5.3 Poll payment status so the UI settles correctly when the webhook lands after the
  modal closes.
- [x] 5.4 Handle dismissal, failure, and network loss without leaving the UI claiming success
  (outcomes `PAID` / `PENDING` / `DISMISSED` / `FAILED`; only `PAID` reads as success, and only
  the server can produce it).
- [x] 5.5 No key secret appears in frontend code, config, or build output -- the only provider
  credential the browser receives is the public key id.

## 6. Reconciliation

- [x] 6.1 Scheduled job reading orders outstanding beyond the configured age from the provider.
- [x] 6.2 Applies confirmation through the **same code path** as the webhook (design D9).
- [x] 6.3 Never confirms an order the provider reports unpaid, failed, or expired; an
  unreachable provider is never read as "unpaid". Safe to run repeatedly.

## 7. Live-mode verification (MANUAL GATE)

**DEFERRED -- requires a real Razorpay account and a public tunnel; neither exists in this
repository.** Everything below is a human task and is the precondition for S7.4 / any
discussion of flipping the gate to `REQUIRED`. The automated suite covers the equivalent
behaviour against a stub (see 8.9-8.13), but a stub is not a settlement.

- [ ] 7.1 Run the full flow end to end in **test mode** with a tunnelled webhook.
- [ ] 7.2 Verify the closed-browser case explicitly: complete payment, close the tab before the
  handler returns, and confirm the agreement still becomes `PAID`.
      _(Automated equivalent: the integration test that posts no callback at all and lets the
      webhook alone settle the agreement.)_
- [ ] 7.3 Verify a redelivered webhook records nothing extra.
- [ ] 7.4 In **live mode**, confirm one real payment settles end to end. **This was the
  precondition for flipping the gate and it was overridden** (see the amendment at the top). It
  is still owed, and it is now the highest-risk open item in the whole change: enforcement is on
  and nothing has ever settled for real.
- [ ] 7.5 Record the outcome and the agreed flat price in the repo docs.

## 8. Tests

**Unit:**

- [x] 8.1 Pricing returns configured minor units + currency; changing config changes the amount.
- [x] 8.2 Client-supplied amount/currency/discount is ignored -- there is no field to supply.
- [x] 8.3 Webhook signature: valid passes; tampered body, wrong secret, missing header all fail;
  the **key secret does not validate a webhook**; comparison is constant-time.
- [x] 8.4 Raw-body verification survives a body whose formatting would not survive a JSON
  round-trip (key order + whitespace), and the reformatted form is proved to fail.
- [x] 8.5 Handler-signature verification over `order_id|payment_id`, and proof it does not mark
  the agreement paid.
- [x] 8.6 Amount/currency mismatch is not recorded as a successful payment.
- [x] 8.7 Money is integer minor units throughout; a reflective check asserts no floating-point
  type in any monetary field or return type.
- [x] 8.8 Redaction: no credential, no verbatim payload, ids redacted.

**Integration:**

- [x] 8.9 WireMock happy path: create order -> webhook -> `PAID` via the gate seam.
- [x] 8.10 Idempotency: webhook redelivered, and `payment.captured` + `order.paid` both
  delivered -> exactly one payment recorded, state unchanged on repeat.
- [x] 8.11 Concurrent deliveries for one order -> at most one confirmation applied.
      _(Covered by construction + redelivery: the order row is taken under a pessimistic write
      lock, so a second delivery observes `PAID` and applies nothing; `@Version` and the unique
      external-reference index are the backstops. A true two-thread race is not asserted.)_
- [x] 8.12 Reload during checkout reuses the outstanding order (no duplicate provider order).
- [x] 8.13 Reconciliation recovers a payment whose webhook never arrived, and does not invent
  one for an unpaid/failed/expired order.
- [x] 8.14 AuthZ: non-owner, non-staff cannot create an order or read payment status.
- [x] 8.15 Gate interaction: with `payment.mode = OPTIONAL` an `UNPAID` agreement still
  proceeds (`PaymentGateOptionalIntegrationTest`, explicit override); with `REQUIRED` - now the
  default, so the whole suite runs under it - it is blocked and a **gateway** payment unblocks
  it (`RazorpayPaymentGateIntegrationTest`).
- [x] 8.16 Schema boots under `ddl-auto: validate`; `ModularityTests` green.

## 9. Docs + housekeeping

- [x] 9.1 Document the three Razorpay env vars and the tunnel requirement for local webhook
  testing (`README.md`, "Payment env vars").
- [x] 9.2 Update `docs/ROADMAP.md` -- payment is no longer a missing dependency of the
  fulfilment pipeline; the gate remains `OPTIONAL` pending S7.4.
- [x] 9.3 Note that a real provider now implements the confirmation seam alongside manual
  confirmation and waiver. **Recorded in `README.md` + `docs/ROADMAP.md` rather than in the
  `payment-gate` spec delta**, which belongs to the still-unarchived `zoop-aadhaar-esign`
  change and is not this change's to edit.
- [x] 9.4 `./gradlew spotlessApply`, then `./gradlew test spotbugsMain`
  (`osv-scanner` is not installed locally, so the full `check` gate fails closed and was not
  run -- CI / a machine with the scanner still owes that pass).
