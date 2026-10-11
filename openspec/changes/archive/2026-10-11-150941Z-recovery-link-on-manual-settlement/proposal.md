## Why

`openspec/specs/agreement-recovery/spec.md` promises that the recovery link is emailed to every
party the moment the server settles payment, and calls that "the primary means by which a
customer retains access". Only **gateway** payments do it. `PaymentConfirmations.apply`
publishes `PaymentConfirmedEvent` and `RecoveryOnPaymentListener` sends the mail; the staff
paths (`PaymentService.confirm` and `PaymentService.waive`) call
`AgreementService.recordPayment` / `waivePayment` directly and publish nothing. No email, no
log line, no error.

This was drafted on 2026-09-06 and parked as low-volume: the staff endpoints were a curl-only
escape hatch. That is no longer true. Since then the staff path has become a **documented
remedy for a customer whose money the gateway already took**:

- `docs/DEPLOYMENT.md`, "A captured payment that was refused or not recorded", step 2:
  "**Not recorded:** record it by hand ... under the **exact `provider_payment_id` from the
  ledger row**." (`payment-recording-failure-staff-alert`, archived 2026-10-11.)
- `README.md` calls `POST /api/staff/payments/{id}/waive` "the normal remedy when money arrives
  out of band".

The first case is the sharp one. The customer paid by card, the database refused the write, so
the confirmation never completed and the event never fired. Staff then record the payment by
hand, and that path sends nothing either. The customer who hit a payment fault is exactly the
one who ends up with no recovery link.

The customer is not locked out. `DraftDeliveryService` emails every party the draft PDF before
payment begins, with the tracking reference in the subject and body, and `/recover` is linked
from the landing page. That email is best-effort by contract, and a transient failure to a
valid address was observed on 2026-09-06, so it is a fallback, not a guarantee.

## What Changes

- Publish the recovery-link event from the three `AgreementService` methods that settle an
  agreement, instead of from the Razorpay-specific `PaymentConfirmations`:
  `recordPayment` (staff), `recordGatewayPayment` (gateway) and `waivePayment` (staff waiver).
  The gateway path stopped calling `recordPayment` in `double-charge-invisible-to-staff`, so
  there is no longer one method all paths share; there is one class.
- The publish is gated on an **observed transition out of `UNPAID`**, read under the
  agreement's row lock. A repeat `POST /confirm` that corrects a mistyped reference still
  overwrites the reference and sends nothing.
- **Behaviour change on two existing paths.** In both, the link is sent earlier, by the staff
  action, and the later gateway confirmation no longer sends it:
  - a gateway payment that lands on a `WAIVED` agreement (still recorded, no second link);
  - a gateway confirmation of a payment staff already recorded by hand under the same payment
    id. This is the runbook case, and `payment-processing` currently says the link is sent at
    the gateway step.
- **No unprompted link for a claimed agreement.** Today the gateway path emails a link the app
  then refuses, because the send has no owner check. The unprompted send now uses the same
  "settled and unowned" rule as the reference-entry path, from one definition.
- The payment confirmation screen gets a "saved to your account" state. It otherwise tells a
  signed-in customer that a link was emailed, which the rule above makes false.
- A failed send logs the redacted agreement id, so it can be traced on the waiver path, which
  has no payment reference.
- A surplus payment stays silent, as today.
- **BREAKING (internal only):** `PaymentConfirmedEvent` is replaced by `PaymentSettledEvent`,
  declared in `signing.agreement` beside its publisher. A waiver is not a confirmation:
  `Agreement.waivePayment` invents no amount or reference so that "how much money came in" and
  "may this proceed" stay separately answerable, and an event named *confirmed* would collapse
  that distinction. The old event has one listener and two test references
  (`PaymentConfirmationsTest`, `AgreementIdToStringRedactionTest`). No published API, no
  persisted payload, no cross-module contract.
- `RecoveryMessages` is untouched. The same anonymous message is sent on two more paths.
- Terms of service: the sentence promising "a link back to it" is qualified for an agreement
  saved to an account.
- Docs: `README.md` (escape hatches and the Recovery paragraph), `docs/DEPLOYMENT.md` (the runbook step above) and
  `docs/ARCHITECTURE.md` (the outbound-message table and the staff-alert notes) say that
  settling by hand emails the recovery link.
- `PARKED.md` is deleted when this is implemented.

## Capabilities

### New Capabilities

None. This makes an existing promise true.

### Modified Capabilities

- `agreement-recovery`: the requirement "The recovery link is emailed at payment confirmation"
  becomes a requirement about **settlement** (`PAID` or `WAIVED`, by any path including staff
  action), with the once-per-agreement rule and the unowned-only rule stated.
- `payment-processing`: in "A gateway payment on an already-paid agreement is kept as a surplus
  payment", the scenario "Staff recorded the same gateway payment by hand" no longer ends with
  a recovery link being sent. No other text changes.
- `backend-security-baseline`: the requirement "Agreement ids are redacted in logs, exception
  messages and named toString output" lists `PaymentConfirmedEvent` by name; the list names
  `PaymentSettledEvent` instead. No other text changes.

## Impact

**Code (backend, plus one confirmation-screen state in the SPA):**

- `signing/agreement/AgreementService.java`: an `ApplicationEventPublisher` is injected;
  the three methods publish on a transition out of `UNPAID`. Signatures unchanged. A new
  `findRecoverableById` shares the eligibility predicate of
  `findRecoverableByTrackingReference`.
- `signing/agreement/PaymentSettledEvent.java`: new public record carrying the agreement id,
  with the redacting `toString()` the old event has.
- `signing/payment/PaymentConfirmedEvent.java`: deleted.
- `signing/payment/PaymentConfirmations.java`: the publish and the injected publisher are
  removed; the javadoc that names the event is rewritten.
- `signing/recovery/RecoveryOnPaymentListener.java`: listens for the new event, skips a claimed
  agreement, and names the redacted id when a send fails.
- `signing/recovery/RecoveryDeliveryService.java`: the per-recipient failure WARN gains the
  redacted agreement id.
- `frontend/src/views/PaymentConfirmation.vue` and `CaptureForm.vue`: the third state, with its
  test in `PaymentConfirmation.test.ts`.
- Tests: `AgreementServiceTest`, `PaymentConfirmationsTest`,
  `AgreementIdToStringRedactionTest`, and `RazorpayPaymentIntegrationTest`, which holds the
  staff-path integration tests and one existing test that asserts the old behaviour.

**Behaviour:** staff-settled and waived agreements receive the recovery email. On the staff
paths the mail is sent on the staff request thread after the transaction commits, as it is on
the webhook thread today; the listener swallows every failure, so a mail fault cannot fail the
staff request.

**Signing-status FSM:** untouched. The *payment* state (`UNPAID`/`PAID`/`WAIVED`) is read for
the gate; its transitions are unchanged.

**Async signing/webhook flow:** unchanged. The Razorpay webhook keeps its HMAC verification and
its path into `PaymentConfirmations.apply`; only the line that publishes moves. The payment
event ledger and the staff alert sweep are not involved: neither subscribes to this event.

**PII / security:**

- No new PII flow. The event carries a `UUID` agreement id only and redacts it in `toString()`.
- The one outbound effect is the existing anonymous recovery email, to contacts already on
  file. No destination is accepted from a caller.
- Delivery still fails closed with no public base URL configured.
- The recovery link is a bearer credential. A staff member can now cause it to be emailed by
  settling an agreement; the recipients are the parties on file, never the staff member.
- No new log line carries an email address, a payment reference or an amount; the one changed
  line carries the redacted agreement id. No secrets.
- Unprompted sends are not written to `recovery_audit`, on the gateway path today or on the
  staff paths after this change.
- Agreements settled by hand before this release are not sent a link retroactively.

**Known adjacent gaps, not fixed here:**

- For an unowned agreement, `recoveryLinkSent` in `frontend/src/views/CaptureForm.vue`
  (line 909) still infers "we emailed you" from a party having an email address, not from a
  server report of a send.
- No staff console action for confirm or waive; the trigger is still `curl`.
  `docs/DEPLOYMENT.md` step 2 says "in the staff console", which does not exist yet.
- `Agreement.waivePayment` does not refuse a `PAID` agreement: a waiver after a payment erases
  the recorded amount and reference. This change sends no mail on that path (the state was not
  `UNPAID`) and does not alter it.
- `WAIVED` is used both for "no money received" and "money arrived by bank transfer".
