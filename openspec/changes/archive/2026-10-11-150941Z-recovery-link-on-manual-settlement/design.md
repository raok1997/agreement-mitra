## Context

The recovery link is sent by `RecoveryOnPaymentListener`, a `@TransactionalEventListener` for
`PaymentConfirmedEvent`. That event has one publisher: `PaymentConfirmations.apply`, the
Razorpay confirmation path. The two staff paths never pass through it:

| Path | Entry | `AgreementService` method | Publishes today |
|---|---|---|---|
| Gateway (callback, webhook, reconciliation) | `PaymentConfirmations.apply` | `recordGatewayPayment` | yes, unless surplus |
| Staff confirm | `PaymentService.confirm` | `recordPayment` | no |
| Staff waive | `PaymentService.waive` | `waivePayment` | no |

Each of the three methods loads the agreement with `findByIdForUpdate`, so each holds the
agreement's row lock while it decides and writes.

`recordGatewayPayment` records when the state is not `PAID` (so also when it is `WAIVED`) and
returns `RECORDED`, `ALREADY_RECORDED` or `SURPLUS`. `recordPayment` and `waivePayment` are
unguarded: they overwrite whatever state the agreement holds.

## Goals / Non-Goals

**Goals:**

- Every unowned agreement is sent the recovery link once, when it is first settled, by any path.
- One place decides whether a settlement happened.
- The event's name is true for a waiver.

**Non-Goals:**

- Guarding `recordPayment` or `waivePayment` against overwriting a settled agreement.
- A record of whether the mail was delivered, or a retry.
- A staff console action for confirm or waive.
- Changing `RecoveryMessages`, recipients, or the fail-closed rule for a missing base URL.

## Decisions

### D1. Publish from `AgreementService`, in the three settling methods

`AgreementService` is the only class all three paths share, and it is where the row lock is
held. Each method reads `agreement.paymentState()` before calling the aggregate's mutator and
publishes `PaymentSettledEvent` when that prior state was `UNPAID`.

*Alternative: publish from `PaymentService` as well as `PaymentConfirmations`.* Two publishers
for one fact, and neither holds the lock that makes the prior-state read reliable. Rejected.

*Alternative: a domain event raised by the `Agreement` aggregate.* Correct in shape, but the
aggregate registers no events today and this would be the first. Not worth a new pattern for
one event.

### D2. The gate is "the state was `UNPAID`", read under the row lock

This is what "once per agreement" means in code. The consequences, by case:

| Prior state | Call | Written | Link sent |
|---|---|---|---|
| `UNPAID` | staff confirm | `PAID` | yes |
| `UNPAID` | staff waive | `WAIVED` | yes |
| `UNPAID` | gateway | `PAID` | yes |
| `PAID` | staff confirm (correct a reference) | `PAID`, new reference | no |
| `WAIVED` | staff confirm | `PAID` | no |
| `WAIVED` | gateway | `PAID` | **no (yes today)** |
| `PAID` | gateway, same reference as a staff confirm | nothing | **no (yes today)** |
| `PAID` | gateway, other reference (surplus) | nothing | no |
| `PAID` | staff waive | `WAIVED`, record erased | no |

Two existing behaviours change, and in both the link moves earlier rather than disappearing:

- `WAIVED` then gateway. Today the waiver sends nothing and the later gateway payment sends the
  link. After this change the waiver sends it.
- Staff confirm under a gateway payment id, then the gateway confirms that same payment
  (`ALREADY_RECORDED`). Today `PaymentConfirmations` publishes for every non-surplus outcome,
  so the gateway step sends the link. After this change the staff confirm sends it. This is
  the runbook case in `docs/DEPLOYMENT.md`, and the `payment-processing` scenario "Staff
  recorded the same gateway payment by hand" is modified to match.

Nothing assigns `UNPAID` except the field default (`Agreement.java:178`), so "the prior state
was `UNPAID`" is true at most once in an agreement's life. A future refund or reversal that
returned an agreement to `UNPAID` would send again on re-settlement; that change would have
to decide whether it wants that.

The gate is independent of `PaymentRecording`: `recordGatewayPayment` still returns `RECORDED`
for a payment on a waived agreement, because the payment was recorded. Recording and
notifying are separate questions.

Two concurrent settlements cannot both publish: the second blocks on the row lock and then
reads a state that is no longer `UNPAID`.

*Alternative: a persisted "link sent" flag.* It would let a failed first send be retried by a
later settlement write, which the gate cannot: a send that fails is not repeated. It needs a
migration, and the customer's retry already exists at `/recover`. Rejected.

The prior-state read and the publish are one private helper used by all three methods.

### D3. `PaymentSettledEvent` replaces `PaymentConfirmedEvent`

A public record in `in.agreementmitra.signing.agreement`, carrying the agreement id, with the
same redacting `toString()` (`AgreementIds.redact`). It sits beside its publisher. The listener
in `signing.recovery` already imports from `signing.agreement`; both are inside the `signing`
module, so `ModularityTests` is unaffected.

**Its contract is narrower than its name suggests, and the record's javadoc says so:** it is
published once per agreement, on the first transition out of `UNPAID`. It is not published
when money arrives on a waived agreement, nor for a gateway confirmation of a payment already
recorded. A subscriber that needs every payment reads the payment order or the payment event
ledger, not this event.

The name `PaymentConfirmedEvent` appears in the `backend-security-baseline` spec's list of
types whose `toString()` must redact the id, so that requirement takes a one-symbol delta.

*Alternative: keep the old name and publish it for waivers.* No spec delta, but a waiver is
then announced as a confirmed payment. Rejected.

### D4. `PaymentConfirmations` stops publishing

Its `ApplicationEventPublisher` is removed. The surplus branch needs no change: a surplus is
decided when the agreement is already `PAID`, so D2 already publishes nothing for it.

### D5. After-commit delivery is unchanged

All three methods are `@Transactional`, so the listener runs after the commit of whichever
transaction is outermost: `PaymentConfirmations.apply` on the gateway path, the method's own
transaction on the staff paths. A settlement that rolls back sends nothing and leaves the
agreement `UNPAID`, so a retry publishes again. A staff confirm refused by the unique
reference index fails at the commit flush, after `publishEvent` and before any listener runs,
and sends nothing.

The listener has no fallback execution, so an event published outside a transaction would be
dropped silently. The publish sits inside the `@Transactional` methods for that reason, and
the integration tests drive the staff paths over HTTP so the proxy and the commit are real.

On the staff paths the mail is sent synchronously on the staff request thread after commit.
The listener catches every `RuntimeException`, so a mail fault cannot turn a successful
settlement into an error response.

### D6. No unprompted link for a claimed agreement

An agreement can be claimed while `UNPAID` and settled afterwards. `sendRecoveryLink` has no
owner check, so today the gateway path emails such an agreement's parties a link the app
refuses. The reference-entry path already filters on "settled and unowned"
(`AgreementService.findRecoverableByTrackingReference`).

That predicate is extracted and reused: `AgreementService` gains `findRecoverableById`, and the
listener calls it in place of `findById`. The event is still published for a claimed
agreement, because the settlement happened; the listener decides not to send.

The listener's read runs after commit and without the row lock. A claim takes the same row
lock as the settling methods, so it lands wholly before the settlement or wholly after it. A
claim that commits before the listener reads means no link is sent; one that commits after
means a link is sent and is revoked by the existing claim rule. Neither grants anything.

In practice the read is served from the settling transaction's own persistence context, which
is still bound to the thread after commit, so it sees the agreement as the settlement left it.
A claim that commits in the instant between that commit and the listener is therefore not
seen, and falls in the second case: a link is sent and is already revoked.

*Alternative: check the owner inside `sendRecoveryLink`.* Its other caller has already applied
the rule, so the rule would then live in two places. Rejected.

### D7. A failed send names the agreement

A mail failure is caught per recipient inside `RecoveryDeliveryService.sendRecoveryLink`,
which logs a WARN with the channel and the redacted recipient and no agreement id. The
listener's own catch is reached only when the lookup throws. A waiver has no payment
reference, so on that path nothing in the log identifies the agreement.

Both lines gain the redacted agreement id (`AgreementIds.redact`), which the redaction
requirement permits: `AgreementIds.redact(agreement.id())` in the delivery service and
`AgreementIds.redact(event.agreementId())` in the listener. Neither line logs the throwable or
its message, because a mail exception's message can carry a recipient address. The listener's
wording changes from "after payment confirmation" to "after payment was settled".

### D8. The confirmation screen stops promising a link to a signed-in customer

`CaptureForm.vue` computes `recoveryLinkSent` from "some party has an email address", and
`PaymentConfirmation.vue` then says "We have emailed you a link to this agreement". A signed-in
customer's agreement is claimed before checkout, so under D6 that sentence becomes false on
the main signed-in path.

`PaymentConfirmation` takes a third state, "saved to your account", which replaces the link
paragraph with "This agreement is saved to your account. Sign in to open it at any time."
The reference stays on screen in every state.

`CaptureForm` passes it when `claimed || editMode`. `claimed` is set only when this session
claimed a freshly created agreement (`CaptureForm.vue:806`, `:836`); it stays false when an
agreement is reopened from My Agreements. That reopen is `editMode`, which is reachable only
for an agreement the caller owns, so the two together are "owned". A signed-in customer whose
automatic claim failed has neither, is unowned on the server, is sent the link, and sees the
link wording, which is correct. No new field is needed on any response.

The saved-to-account state takes precedence over the other two: a claimed agreement with no
email address on file shows the sign-in wording, not "Write down your reference".

This does not fix the wider gap that the unowned case still infers "we emailed you" from an
address being on file and not from a server report of a send.

## Risks / Trade-offs

- [A staff request now waits on SMTP, holding its JDBC connection until the listener returns] →
  The same wait the webhook thread carries today. Volume on the staff paths is a handful a
  week.
- [Unprompted sends are not written to `recovery_audit`] → Unchanged from the gateway path.
  The agreement records which staff member settled it. Not added here.
- [A staff member settles an agreement that was never finalised] → The staff methods check
  only that the row exists, today as before. The mail goes to that draft's own contacts.
  Accepted; a finalised-state precondition on staff settlement is a separate question.
- [The mail fails and nobody is told] → Unchanged from the gateway path: one redacted WARN, no
  retry. The customer's fallback is `/recover` with the reference from the draft email.
- [A staff member settles the wrong agreement and its parties are emailed a link] → The link
  goes only to that agreement's own parties, at contacts already on file. Nothing reaches a
  third party.
- [A waiver after a payment erases the payment record] → Exists today and is not changed. It
  sends no mail under D2.
- [The list of named types in `backend-security-baseline` must be edited on every rename] →
  Accepted; `AgreementIdToStringRedactionTest` fails if the new record is not redacted.

## Migration Plan

No schema change and no configuration. Deploy is a normal backend release; rollback is the
previous image.

Agreements waived or staff-confirmed before this release were sent no link, and a later
gateway payment on one no longer sends it. This is accepted without a resend: production is in
beta and only the founding team has used the staff endpoints. Those parties still have
`/recover`.
