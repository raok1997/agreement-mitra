## Why

Payment confirmation treats **every** database integrity error as "this payment is already
recorded". `PaymentOrderService.applyConfirmation` catches any `DataIntegrityViolationException` and
returns `DUPLICATE_REFERENCE`; the webhook then answers `202`, the gateway stops redelivering, and a
captured payment is left unrecorded with nothing but a misleading warning in the log. The manual
staff path (`PaymentService.confirm`) makes the same assumption and answers
`409 PAYMENT_REFERENCE_ALREADY_USED` for any integrity error.

Only two violations are real duplicates. Anything else - a value too long for its column, a check or
not-null constraint, any constraint a later change adds to the confirmation transaction - is a
failure to record a payment and must be seen and retried, not acknowledged. Two such cases exist
today: a gateway payment id longer than `payment_order.provider_payment_id` (`VARCHAR(64)`), and a
manually entered amount too large for `agreement.payment_amount` (`NUMERIC(12,2)`), which staff are
told is a reused reference.

## What Changes

- A refusal is a **duplicate reference** only when the violated constraint is
  `uq_agreement_payment_reference` (one payment, one agreement) or
  `uq_payment_order_provider_payment_id` (one gateway payment id, one payment order). The two names
  are held in one place in the payment package.
- Any other integrity error during a gateway confirmation is raised as a **payment-recording
  failure**: nothing is recorded, and the failure carries only the constraint name and SQL state -
  never the database's message, which holds the SQL and the failing row.
- **Gateway webhook**: a payment-recording failure is answered with a server error (`5xx`) instead
  of `202`, so the gateway redelivers. A verified webhook is not always `202` today either - any
  other runtime failure (a lock timeout, a lost connection) already escapes as a non-2xx - so this
  routes one more failure class into a path that exists, rather than opening a new one.
- **Reconciliation job**: unchanged - it already skips a failing order and re-reads it on the next
  sweep.
- **Browser checkout callback**: a payment-recording failure is caught there; the customer still
  receives the current payment progress, never an error page.
- **Manual staff confirmation**: `409 PAYMENT_REFERENCE_ALREADY_USED` only for
  `uq_agreement_payment_reference`; any other integrity error is a server error.
- The missing test: a gateway payment id that staff already recorded against **another** agreement
  is refused as a duplicate reference (only `uq_agreement_payment_reference` can refuse it).

**Signing-status FSM**: no transition is touched. **Payment state**: no new state and no new
transition; the change only decides whether a refused write is reported as a duplicate or as a
failure.

**PII / security checklist**: no Aadhaar, OTP, VID or signer PII is introduced or moved, and no
secret. The change *removes* a leak path it would otherwise open: an integrity exception rethrown
as-is would reach the container's error log with the SQL statement and Postgres' failing-row detail,
so the failure is re-raised without the original message or cause. Gateway order ids stay redacted
in logs; payment ids and amounts stay out of them. Sandbox + dummy data only is preserved.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `payment-processing`: adds a requirement that a gateway confirmation the database refuses is a
  duplicate reference only for the two named uniqueness rules, and is otherwise a failure that is
  not acknowledged to the gateway.
- `payment-gate`: adds a requirement that a manual confirmation is refused as a reused reference
  only when the reference uniqueness rule is what refused it.

## Impact

- **Code** (`in.agreementmitra.signing.payment`): `PaymentOrderService` (`applyConfirmation`,
  `acknowledgeCheckout`), `PaymentService.confirm`, one new package-private classifier and one new
  package-private exception. `PaymentConfirmations`, `ConfirmationOutcome` and
  `RazorpayWebhookService` javadoc only.
- **API**: `POST /api/webhooks/razorpay` answers `500` for a payment-recording failure where it
  answered `202`; `POST` manual payment confirmation answers `500` where it answered a false `409`.
- **Specs**: both deltas are `ADDED` only. The webhook, reconciliation and browser-callback
  requirements they refer to are specified in the still-active `razorpay-payment` change, not yet in
  `openspec/specs/payment-processing`; nothing here modifies them, so the two changes can archive in
  either order.
- **Data**: no migration. The two index names (`V16`, `V28`) become names the code depends on.
- **Frontend, deploy, env vars**: none.
- **Register**: closes the `payment-confirmation-catch-all-integrity-mapping` row in
  `docs/ROADMAP.md`.
