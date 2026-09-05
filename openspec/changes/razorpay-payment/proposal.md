## Why

`zoop-aadhaar-esign` specifies a **payment gate** with a vendor-neutral confirmation seam, but
its only implementations are a staff-recorded manual confirmation and a waiver - because no
payment gateway existed. A Razorpay account now exists, so the gate can be given a real
implementation.

This change integrates Razorpay as the first payment provider behind that seam: create an
order, take payment through Standard Checkout inside our SPA, and confirm it authoritatively
by webhook.

The gate **stays `OPTIONAL`**. Turning it `REQUIRED` is a configuration change, deliberately
withheld until real payments have been observed settling in live mode. Shipping the integration
and enabling enforcement are two decisions, not one.

## What Changes

- **New `payment-processing` capability** - the gateway integration behind the `payment-gate`
  confirmation seam. Razorpay is the first adapter; the seam stays vendor-neutral.
- **Order creation is server-side and server-priced.** The backend calls Razorpay's Orders API
  with an amount **it computes**, never one supplied by the client. The agreement's UUID (36
  chars) is used as the `receipt` (Razorpay's limit is 40), giving idempotency and a reliable
  join back to our record.
- **Money is modelled in minor units** (paise) as an integer throughout - never a float, never
  a double. Razorpay requires paise and rejects strings and floats.
- **Amount is a computed value from day one**, currently returning a configured flat price.
  Adding state-and-rent-dependent stamp duty later changes the calculation, not the payment
  flow.
- **Standard Checkout in the Vue SPA.** The backend hands the browser a `key_id` and the order
  id; `checkout.js` opens in-page. The **key secret never reaches the frontend**.
- **BREAKING for callers of the webhook path - a second webhook endpoint with different
  verification.** Razorpay signs with `X-Razorpay-Signature`: HMAC-SHA256 over the **raw,
  unparsed request body** using a **webhook secret** that is distinct from the API key secret.
  The body must reach verification byte-for-byte unmodified.
- **Two verification paths, only one authoritative.** The checkout handler returns
  `razorpay_payment_id`, `razorpay_order_id`, and `razorpay_signature` (HMAC-SHA256 of
  `order_id|payment_id` under the **key secret**). That is a **client-supplied** value and is
  treated as a UX signal only. **Payment is marked confirmed solely on the verified webhook**,
  or on an authoritative read of the order from Razorpay - never on the browser callback alone.
- **Idempotent confirmation.** Razorpay redelivers webhooks and fires more than one relevant
  event (`payment.captured`, `order.paid`). Confirming twice must not double-record a payment
  or re-open a settled agreement.
- **Reconciliation fallback**, mirroring the existing signing-reconciliation job: orders left
  unconfirmed are re-read from Razorpay so a missed webhook cannot strand a paid customer.
- **Test mode only in this repository.** `rzp_test_` keys; the live host and live keys are
  never a default and never committed.

Deliberately **not** in scope:

- Refunds, settlements, invoicing, subscriptions, payment links, or partial payments.
- Turning the gate `REQUIRED` (a later configuration change).
- Stamp-duty calculation - the `rules` module that would own it is not built.
- Storing or transmitting card/UPI credentials. Razorpay Checkout handles those; they never
  reach our servers.

## Capabilities

### New Capabilities

- `payment-processing`: gateway-backed payment for an agreement - order creation, server-side
  pricing, checkout initiation, the two signature-verification paths and which one is
  authoritative, idempotent confirmation, reconciliation, and the secret/PCI handling rules.

## Impact

**Code** - new `payments` concern (order creation, signature verification, webhook intake,
reconciliation job) implementing the `payment-gate` confirmation seam; a new webhook controller
alongside the eSign one; Vue checkout view and API client.

**Schema** - one forward-only migration: payment order records (provider order id, our
receipt, amount in minor units, currency, status, provider payment id, timestamps), with
uniqueness on the provider order id and on the receipt. `ddl-auto: validate` stays green.

**API** - a create-order endpoint for the authenticated agreement owner; a new
`POST /api/webhooks/razorpay`; payment status readable on the agreement.

**Config** - `payment.razorpay.key-id`, `key-secret`, `webhook-secret` from **env vars only**
(three distinct secrets - the webhook secret is not the key secret); `payment.amount` flat
price; `payment.mode` stays `OPTIONAL`.

**Dependencies** - Razorpay Java SDK, or a plain `RestClient` + JDK HMAC. Prefer the latter if
it avoids a new dependency, since we only need two endpoints and one HMAC. Regenerate the
Gradle lockfile and re-run the OSV gate if a dependency is added.

**Security posture** - a new inbound money-moving surface. The webhook endpoint must be
publicly reachable (tunnel in local dev, as the eSign one already is).

## PII / security review

- **Does this change introduce or move Aadhaar/OTP/VID/PII or secrets?** **Secrets: yes. Card
  data: no. Aadhaar/OTP/VID: no.**
  - Three new secrets: Razorpay **key id** (public-by-design, sent to the browser), **key
    secret** (server-only), and **webhook secret** (server-only, and **distinct** from the key
    secret - conflating them is a real and common integration error).
  - **No card, UPI, or bank credential ever reaches our servers.** Razorpay Checkout collects
    them in its own context, so we stay out of PCI scope. This change must not add any field
    that would carry them.
  - Contact details already held (name, email, phone) may be passed to Checkout as prefill.
    That is existing PII, not new, and is not logged.
- **How is it redacted/secured?** Secrets come from env vars only; the key secret and webhook
  secret are never sent to the client and never logged. Webhook bodies are **never logged
  verbatim**; provider order and payment ids are redacted to a trailing fragment, consistent
  with the existing helper. Signature comparison is **constant-time**. The raw body is used for
  verification and is not re-serialised. Amount and currency are always server-side values, so
  a tampered client cannot alter what is charged or what is credited.
- **Sandbox + dummy data only preserved?** **Yes.** Only `rzp_test_` credentials are used;
  tests run against WireMock with fabricated secrets and require no live account. Live keys are
  never a default, never committed, and the gate stays `OPTIONAL` so no real money is required
  for any test to pass.
