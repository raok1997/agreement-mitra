## Context

See `proposal.md` - Why. The design-relevant facts:

- `AgreementService.updateContacts` gates on `signingRequestQuery.existsForAgreement(agreementId)`.
  That is the whole freeze, and it is the only thing this change has to move.
- The agreement aggregate already carries both states the new gate needs: `paymentState`
  (`UNPAID` / `PAID` / `WAIVED`, server-managed) and `closureState` (`OPEN` / `CLOSED`). Neither
  needs a schema change.
- The route already re-sends the draft after a successful save
  (`AgreementController.updateContacts` -> `draftDeliveryService.sendStoredDraftToParties`),
  outside the save transaction and non-fatal by construction.
- `ConflictException` carries a `Kind`, and `GlobalExceptionHandler` renders a fixed
  `type`/`title`/`detail` per kind. Adding a distinguishable refusal is a new enum constant and a
  new case, not a new mechanism.
- The frontend's `AgreementHttpError` carries only an HTTP status. `StaffQueueHttpError` in the
  same codebase already carries the RFC 9457 problem `type`; that is the pattern to follow.

## Goals / Non-Goals

**Goals**

- Move one gate, in one method, without touching the terms freeze that shares its shape.
- Make the post-payment refusal self-describing enough that a client never says "try again" about
  a permanent condition.
- Keep a no-op save off the wire, so the reported retry costs nothing.

**Non-Goals**

- No change to `AgreementService.update` (the terms edit). It keeps
  `existsForAgreement` -- terms freeze at finalise and that is correct.
- No new port on `SigningRequestQuery`, and no removal of it: `update` and `DraftService` still use
  it. This change simply stops `updateContacts` from being one of its callers.
- No change to who may call the route. The capability model (agreement id, or the owner) is
  unchanged; only the window changes.

## Decisions

### D1: Gate on the agreement's own state, not on the signing request

`updateContacts` reads `paymentState` and `closureState` off the aggregate it has already loaded
`findByIdForUpdate`, and drops the `signingRequestQuery` call entirely.

*Why:* the new rule is about payment and closure, both of which live on `Agreement`. Expressing it
through the signing request would mean asking a second module a question about a fact the aggregate
already holds, and would re-introduce the coupling the change is removing. It also removes a query
from the hot path.

*Alternative rejected:* keep `existsForAgreement` and add a payment check alongside it. That would
have kept the bug -- the signing-request check is precisely what refuses the retry.

*Alternative rejected:* gate on the signing request's **status** (`PDF_GENERATED` still editable,
`SIGN_REQUESTED` frozen). This is the wider window discussed in the proposal; it was declined in
favour of closing at payment.

### D2: Check closure explicitly rather than inheriting it

The old `existsForAgreement` gate incidentally refused a closed agreement, because a closed one has
a signing request. Removing it would silently open contacts on an abandoned, never-paid agreement.

So closure is checked **explicitly**, as `AgreementService.requireOpen` already does for stamp
intake. The check is stated in the spec as its own scenario rather than left as a side effect of
another rule, because a side effect is exactly what went missing here.

*Order:* ownership resolution (404) -> closed (409) -> paid (409) -> apply. Closure before payment:
a closed agreement is terminal whatever its payment state, and reporting "already paid" for an
abandoned order would send the reader after the wrong fact.

### D3: A new `Kind`, not a reused `DRAFT_FROZEN`

`Kind.CONTACTS_FROZEN` -> `urn:agreementmitra:problem:contacts-frozen`.

*Why:* the client behaviour that motivates this change is a message that tells the truth, and it
cannot do that while both conditions arrive as `draft-frozen`. Distinct kinds also keep the
handler's contract intact -- the detail string stays a per-kind constant and is never derived from
input.

*Alternative rejected:* reuse `AGREEMENT_CLOSED` for the paid case. Different fact, different
remedy.

### D4: The client carries the problem type, and skips a no-op save

Two frontend decisions, both mirroring code that already exists here:

1. `AgreementHttpError` gains a `problemType`, read from the RFC 9457 body exactly as
   `StaffQueueHttpError` does. Without it, "paid" and "terms frozen" are both `409` and the client
   is back to guessing.
2. The contact step compares the confirmed list against the values the server last returned and
   sends nothing when they match.

Decision 2 is worth stating because it is **not** merely an optimisation: it is what makes the
reported retry work at all on any agreement that has moved past the window -- and it keeps working
if the window is ever narrowed again. The server remains the authority; this only avoids asking it
a question whose answer cannot matter.

*Baseline correctness:* the contact step edits a local clone of the parent's list, so the parent's
copy is an untouched record of what the server sent. After a successful save the parent adopts the
saved values as the new baseline, so a second retry still sees "nothing changed".

## Risks / Trade-offs

- **A leaked agreement id can now redirect the draft for longer.** -> Bounded by the window: before
  payment the only outbound artifact is the draft. No signed agreement and no recovery link exists
  yet, and the recovery link is only issued at payment confirmation. The id already grants
  finalise, full-PII preview and pay, so this adds reach, not a new class of access. Accepted
  deliberately; the customer declined an old-address notice.
- **A customer can change contacts between finalise and pay, so the draft can be sent more than
  once.** -> Intended. The send is idempotent in effect (same document, current addresses) and
  non-fatal on failure.
- **Closure could be forgotten again by a future edit**, since it is now an explicit check rather
  than a by-product. -> Pinned by its own scenario and its own test, which is why it is stated
  separately in the spec.
- **`WAIVED` is treated as frozen, like `PAID`.** -> A waiver is staff asserting the money question
  is settled; fulfilment proceeds from there exactly as if paid, so the same freeze applies. The
  alternative -- leaving a waived agreement editable indefinitely -- would leave the widest window
  on the least-supervised path.

## Migration Plan

No migration. No schema change, no data backfill, no stored state reinterpreted: the change reads
two columns that already exist and are already populated for every row.

Deploy is backend-then-frontend safe in either order. An old client against the new backend simply
never exercises the widened window; a new client against the old backend sees the old `draft-frozen`
type and falls back to its generic message.

Rollback is reverting the gate; nothing written under the new rule becomes invalid under the old one
(a contact saved post-finalise is an ordinary contact).

## Open Questions

None. The window boundary, the old-address notice, and recovery-link rotation were all decided
before this design (see `proposal.md` - Not in scope).
