## Context

See `proposal.md` - Why. The constraints that shape this design:

- `zoop-aadhaar-esign` already specifies the **payment gate** (state `UNPAID`/`PAID`/`WAIVED`,
  modes `OPTIONAL`/`REQUIRED`, enforced at stamp intake and eSign initiation) plus a
  vendor-neutral confirmation seam. This change supplies an implementation of that seam and
  changes none of it.
- Razorpay's Orders API takes an integer amount in **paise**, currency, and a `receipt` unique
  per account, max 40 characters. Our agreement UUID is 36 - it fits.
- Razorpay signs webhooks with `X-Razorpay-Signature`: **HMAC-SHA256 over the raw request
  body** using a **webhook secret**. The checkout handler separately returns a signature over
  `order_id|payment_id` using the **key secret**. Two different secrets, two different inputs.
- The existing `WebhookController` already takes `@RequestBody String` rather than a parsed
  type, which is exactly what raw-body verification needs. Follow that precedent.
- We already run this shape once: verify -> re-read authoritative state -> idempotent FSM
  update, with a scheduled reconciliation fallback. Payment reuses the pattern.

### Sequence

```
Browser            Backend               Razorpay
   |                  |                     |
   |- start payment ->|                     |
   |                  |- POST /v1/orders -->|   (server-computed amount,
   |                  |<-- order_id --------|    receipt = agreement uuid)
   |<- key_id, order -|                     |
   |----- checkout.js modal --------------->|
   |<---- handler: payment_id, signature ---|
   |- (UX signal) --->|                     |   NOT authoritative
   |                  |<== webhook (raw body, HMAC-SHA256) ==|
   |                  |  verify -> amount check -> PAID (idempotent)
   |                  |                     |
   |            [reconciliation job re-reads stale orders]
```

## Goals / Non-Goals

**Goals:**

- A customer can pay for an agreement without leaving the SPA.
- Payment is confirmed authoritatively and idempotently, and cannot be lost by a closed browser.
- Nothing about pricing or amount can be influenced by the client.
- The gate stays `OPTIONAL`; enabling enforcement is a later, separate decision.

**Non-Goals:**

- Refunds, settlements, invoicing, subscriptions, partial payments, payment links.
- Stamp-duty calculation (needs the unbuilt `rules` module).
- Any handling of card/UPI credentials - deliberately out of scope to stay out of PCI scope.
- Changing the payment gate's states, modes, or enforcement points.

## Decisions

### D1: The webhook is authoritative; the browser callback is not

Mark `PAID` only from a verified webhook or an authoritative order read. Treat the checkout
handler's response as a UX signal that may advance the UI and may trigger an authoritative
read.

*Why:* the handler response travels through the user's browser. It can be withheld (tab
closed), replayed, or tampered with. Verifying its signature proves it was *issued* by
Razorpay, not that it is *current* or that it arrived at all. The failure that matters is the
common one: a customer pays and closes the tab. If the callback were authoritative, they would
be charged and remain `UNPAID`.

*Note:* this is the same discipline as the eSign webhook - verify, then re-read authoritative
state, never trust the transported claim. Reusing a proven pattern rather than inventing a
second one.

*Alternative rejected:* confirm on the verified handler signature for responsiveness. It makes
the happy path feel faster and gets the hard case wrong.

### D2: Two secrets, never conflated

Key secret signs the handler response; webhook secret signs webhooks. Separate configuration
properties, separate names, neither defaulting to the other.

*Why:* using the key secret to validate webhooks is a well-trodden integration error that
produces a verifier which rejects every legitimate webhook - or, if the codepaths are crossed,
one that accepts forged ones. Making them structurally distinct removes the possibility.

### D3: Raw body in, raw body verified

Accept the webhook as a `String` (as the eSign controller already does) and compute the HMAC
over exactly those bytes. Do not bind to a DTO first.

*Why:* Jackson round-tripping changes key order, whitespace, and number formatting, so the
digest will not match. This is the single most common cause of "signature mismatch" and it
fails 100% of the time rather than intermittently - which is at least a loud failure. Guard it
with a test that sends a body whose formatting would not survive re-serialisation.

### D4: `receipt` = agreement UUID; provider order id unique

*Why:* gives a reliable server-side join back from Razorpay's record to ours without trusting
anything the client holds, and gives Razorpay-side idempotency for free. 36 characters fits the
40-character limit with room to spare. Uniqueness on the provider order id stops one payment
being credited to two agreements.

### D5: Order creation is idempotent per agreement

Reuse an outstanding unpaid order rather than creating a new one; allow a new order only once
the previous has expired or failed.

*Why:* customers reload payment pages. Without this, one agreement accumulates orders, and
reconciliation then has to decide which one counts.

### D6: Money as integer minor units, end to end

`long` paise plus an explicit currency, in the calculation, the database, and the wire format.

*Why:* Razorpay requires paise and rejects floats. More importantly, floating-point money
produces off-by-one-paise errors that fail amount-equality checks and are miserable to
reconcile. Deciding this once, at the boundary, avoids it everywhere.

### D7: Pricing is a named operation returning a flat configured price

*Why:* the user's decision is "flat now, computed later". Introducing the seam now means adding
state-and-rent-dependent stamp duty later touches one calculation, not the order-creation flow,
the API shape, or the stored record. The cost today is one indirection.

### D8: Verify the confirmed amount against the order

*Why:* defence in depth. If an amount ever diverges - a mis-created order, a tampered flow, a
provider bug - recording it as a successful payment would credit an agreement for the wrong
sum. Better to refuse and surface it than to silently accept.

### D9: Reconciliation reuses the webhook's confirmation path

*Why:* two paths that both write payment state will eventually disagree. The existing
signing-reconciliation job already establishes this pattern.

### D10: Prefer `RestClient` + JDK HMAC over the Razorpay SDK

*Why:* we need two endpoints and one HMAC. A new dependency adds supply-chain surface, must be
locked, and must clear the OSV gate on every build. Revisit if we later need refunds,
settlements, or subscriptions, where the SDK earns its place.

## Risks / Trade-offs

- **Customer pays, is not marked paid.** The worst outcome here - money taken, service
  withheld. -> Webhook authoritative (D1) plus reconciliation (D9); the browser callback is
  never load-bearing.
- **Signature mismatch from body re-serialisation.** -> D3, plus a test using a body whose
  formatting would not survive a JSON round-trip.
- **Webhook secret leak allows forged payment confirmations.** Higher impact than the eSign
  key: this one moves money in our records. -> Env vars only, never logged, never returned,
  constant-time comparison; amount cross-check (D8) limits what a forgery can claim.
- **Gate stays `OPTIONAL`, so a payment bug is invisible.** Nothing blocks, so a broken
  integration produces unpaid fulfilment rather than an error. -> Deliberate, and the reason to
  verify real settlement in live mode before flipping to `REQUIRED`. Until then, unpaid
  agreements reaching stamp purchase is an operational risk staff must watch.
- **Test mode diverges from live.** Test keys do not exercise real settlement, and some
  behaviours differ. -> The flip to `REQUIRED` is explicitly gated on observing live payments
  settle (tasks S7), not on the test suite passing.
- **Public webhook endpoint.** A second unauthenticated-by-default surface. -> Signature
  verified before any side effect, body size bounded, no existence oracle, nothing logged
  verbatim - same posture as the eSign webhook.
- **Amount is flat while stamp duty varies by state.** Under-recovery on high-duty agreements
  is a commercial risk, not a technical one. -> D7 keeps the door open; flagged for whoever sets
  the price.

## Migration Plan

1. One forward-only Flyway migration: payment order records (provider order id unique, receipt
   unique, amount in minor units, currency, status, provider payment id, timestamps).
2. Ship with `payment.mode` unchanged at `OPTIONAL` and **test-mode** credentials. Live keys and
   the live host are never defaults.
3. Configure the Razorpay webhook to point at the deployed endpoint (a tunnel in local dev, as
   the eSign webhook already requires).
4. Verify a real payment settles in live mode **before** any consideration of `REQUIRED`.

**Rollback:** the gate is `OPTIONAL`, so disabling the payment endpoints stops new orders
without blocking fulfilment. Schema additions are additive and nullable; no down migration.

**Sequencing:** depends on `zoop-aadhaar-esign` for the payment gate and its confirmation seam.

## Open Questions

- **What is the flat price?** A commercial decision. It sets a configuration value, nothing else.
- **Auto-capture or manual capture?** Auto-capture is the simpler default and is assumed. Manual
  capture only matters if we ever want to authorise before stamping and capture after - worth
  revisiting if stamp purchase starts failing often enough to warrant it.
- **How long should an order stay outstanding before reconciliation reads it?** A tuning value;
  affects job cadence only.
- **When does the gate flip to `REQUIRED`?** Deliberately deferred to a live-mode observation,
  not a code change.
