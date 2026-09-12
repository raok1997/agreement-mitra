## Why

A customer whose first payment attempt fails **cannot try again**. This was observed live, not
theorised.

The pre-payment flow is: confirm contacts -> `PATCH /api/agreements/{id}/contacts` -> finalise ->
pay. Finalising places the order and creates the signing request. If the payment then fails, the
retry re-enters the **same** contact step, which re-sends the same PATCH -- and that route is
refused the moment a signing request exists (`AgreementService.updateContacts` ->
`ConflictException.draftFrozen()`). The customer is told "Could not save those contact details.
Please try again", which is untrue: the freeze is permanent, so retrying refuses forever and the
pay button is unreachable for the life of the agreement.

`finaliseAgreement` and `payForAgreement` are both idempotent precisely so that a retry is safe.
The contacts save in front of them was the one non-idempotent step in the path, and it closed the
door on the only route back.

The second, larger problem is the one the customer actually reported: **a mistyped email cannot be
corrected**. Contact details are the only thing on an agreement that a customer routinely gets
wrong and routinely notices late -- and the current boundary freezes them at the exact moment the
customer first sees them written down. A typo caught one second after finalising needs a support
ticket today.

The boundary is in the wrong place. Contacts are **not terms**: they do not appear in the rendered
agreement, and `updateContacts` already deliberately does not clear the draft pin for that reason.
Freezing them alongside the terms was over-application of a rule that exists to protect the
document.

## What Changes

1. **The contacts freeze moves from finalise to payment.** Party contacts become editable while the
   agreement is **UNPAID and open**, and are refused once it is `PAID` or `WAIVED`, or once it is
   `CLOSED`. The existing "a signing request exists" condition is removed as the gate.

   Payment is the right line because it is where the customer's own money commits them, and because
   everything downstream of it -- the recovery link, staff buying the e-stamp, the signing
   invitation -- is addressed **to** the contacts. Editing them after any of that has begun would
   mean a message already sitting in one inbox and a different address expecting it.

   The narrower window is deliberate over the alternative of staying editable until signing is
   requested. It keeps a leaked agreement identifier unable to redirect anything that matters: in
   this window the only outbound artifact is the **draft**, never a signed agreement and never a
   recovery link.

2. **A corrected address receives the agreement.** Saving contacts already sends the current draft
   to every party at the contacts then on file, so a corrected address gets the agreement without
   any new delivery path. This change does not add one; it records the behaviour as a requirement
   so a later refactor cannot quietly drop it.

3. **A distinct refusal, so the client can tell the truth.** A refusal after payment gets its own
   conflict kind and problem type (`contacts-frozen`) rather than reusing `draft-frozen`. The
   client can then say "this order is paid, contacts can no longer be changed" instead of "please
   try again", which is the specific lie this change exists to remove.

4. **The client stops sending a save that changes nothing.** The contact step compares the
   confirmed list against what the server last returned and skips the PATCH when they are
   identical. On the reported retry -- reopen, change nothing, confirm -- nothing is written and
   the customer goes straight back to payment.

**Not in scope, deliberately:**

- **Rotating the recovery link.** Considered and dropped on inspection: the recovery link is not a
  token, it is `<publicBaseUrl>/agreement/<agreementId>` built in `RecoveryDeliveryService`, and it
  is issued only on `PaymentConfirmedEvent`. With the window closing at payment, **no recovery link
  has been issued yet** at any moment a customer can edit contacts, so there is nothing outstanding
  to invalidate. Building a token indirection to rotate nothing would be dead code. If the
  leaked-identifier concern is to be addressed, its place is the post-payment recovery link, which
  is a separate change against `agreement-recovery`.
- **Notifying the previous address** on a contact change. Explicitly declined by the customer.
- **Any change to the terms freeze.** Rent, dates, address and the party list stay frozen at
  finalise exactly as today. Only contacts move.

**Signing-status FSM:** unchanged. This change touches **no** transition. It reads the agreement's
`PaymentState` and `ClosureState` and never writes either; the signing request's status is neither
read as a gate nor written. `DRAFT -> PDF_GENERATED -> STAMPED -> SIGN_REQUESTED -> SIGNED |
FAILED | EXPIRED` and the `STAMP_FAILED` branch are all untouched. No sequence diagram is required:
the change adds nothing to the async signing/webhook flow and does not alter its ordering.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `payment-processing`: the requirement "Confirmed contacts are saved against the agreement"
  changes its refusal condition from "already has a signing request" to "already paid, waived, or
  closed"; requires that refusal to be distinguishable from the terms freeze; and gains scenarios
  for correcting a contact after finalising and for the refusal once paid.

`api-error-handling` is deliberately **not** listed. It states the shape of a problem body and the
rule that distinct conditions get distinct types; it does not enumerate individual problem types,
and adding one entry to it would set a precedent no other change follows. The requirement that this
refusal be told apart from the terms freeze lives with the behaviour, in `payment-processing`.

## Impact

**Backend** (`in.agreementmitra.signing`)

- `AgreementService.updateContacts` -- the gate changes from `signingRequestQuery.existsForAgreement`
  to the agreement's own `PaymentState` plus a closure check. Note this **removes** this method's
  only use of `SigningRequestQuery`; the port stays in use by `update` and `DraftService`.
- `ConflictException` -- new `Kind.CONTACTS_FROZEN` plus its factory.
- `GlobalExceptionHandler` -- new problem type `urn:agreementmitra:problem:contacts-frozen`.
- No database migration: `payment_state` and `closure_state` already exist on `agreement`.
- No module-boundary change, so `ModularityTests` is unaffected.

**Frontend**

- `CaptureForm.vue` -- skip an unchanged contacts save; distinguish the frozen 409 by its problem
  type and render an accurate message.
- `agreements.ts` -- `AgreementHttpError` carries the RFC 9457 problem `type`, as
  `StaffQueueHttpError` already does, so the two 409 cases can be told apart.

**Security surface**

- The anonymous capability window on this route grows from "before finalise" to "before payment".
  The route still accepts contacts and nothing else, still refuses an agreement owned by somebody
  else with the same 404 as an unknown one, and still cannot change the party list.

**PII / security review checklist**

- **New or moved Aadhaar / OTP / VID data?** None. This change touches party email and mobile only
  and does not read, write, or transport any Aadhaar number, OTP, or virtual ID.
- **New outbound PII flow?** None. The draft send on contact save already exists and is unchanged;
  no new recipient, channel, or payload is introduced. Widening the window means that existing send
  can occur later in the lifecycle than before, to an address the customer just supplied -- which
  is the intent of the change.
- **Redaction.** Unchanged and reused: outbound recipients are logged only through
  `RecipientRedaction.redact`, and the new conflict carries a `Kind` with a fixed per-kind
  `detail` constant, so no submitted address can reach a response body or a log line. No new log
  statement records a contact value.
- **Secrets.** None added, read, or moved.
- **Sandbox + dummy data only** is preserved: no new external integration, no production
  credential, and no change to which environment anything runs against.
