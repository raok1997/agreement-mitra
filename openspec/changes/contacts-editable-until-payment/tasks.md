## 1. Backend: the refusal

- [x] 1.1 Add `Kind.CONTACTS_FROZEN` to `ConflictException` with a factory
      `contactsFrozen()`, documented as distinct from `DRAFT_FROZEN` (terms) and
      `AGREEMENT_CLOSED` (terminal) -- different fact, different remedy.
- [x] 1.2 Map it in `GlobalExceptionHandler` to `urn:agreementmitra:problem:contacts-frozen`
      with a fixed title/detail constant, asserting the handler's rule that no client text is
      derived from input.

## 2. Backend: move the gate

- [x] 2.1 In `AgreementService.updateContacts`, replace the
      `signingRequestQuery.existsForAgreement` check with, in order: closed -> `agreementClosed()`,
      then `paymentState` in (`PAID`, `WAIVED`) -> `contactsFrozen()` (design D1, D2). Read both off
      the aggregate already loaded by `findByIdForUpdate`; add no new query.
- [x] 2.2 Confirm `AgreementService.update` (terms) still gates on `existsForAgreement` and is
      untouched, and that `SigningRequestQuery` remains imported/used by its other callers.
- [x] 2.3 Update the method's javadoc to state the new window and why payment is the line, replacing
      the "refused once a signing request exists" wording that this change invalidates.

## 3. Backend tests

- [x] 3.1 **Unit** (`AgreementServiceTest`, no Spring context): contacts save on a finalised UNPAID
      agreement; refused with `CONTACTS_FROZEN` when `PAID`; refused when `WAIVED`; refused with
      `AGREEMENT_CLOSED` when closed; closed-and-paid reports closed (ordering, design D2).
- [x] 3.2 **Unit**: a contacts change does not clear the draft pin or alter terms -- the document
      the parties were shown is unchanged (spec: "Changing a contact leaves the document untouched").
- [x] 3.3 **Integration** (`ContactGateIntegrationTest`, Testcontainers, through the HTTP API):
      finalise an agreement, then PATCH `/contacts` and assert 2xx -- the regression test for the
      reported dead end. Assert the corrected address receives the draft via `RecordingEmailSender`.
- [x] 3.4 **Integration**: mark the agreement paid, PATCH `/contacts`, assert `409` and that the
      body's `type` is `urn:agreementmitra:problem:contacts-frozen` (not `draft-frozen`), and that
      no contact changed.
- [x] 3.5 **Integration**: the terms route (`PUT /api/agreements/{id}`) still returns `409` after
      finalise -- proving the terms freeze did not move with the contacts freeze.
- [x] 3.6 Run `./run-tests.sh test` and keep `ModularityTests` green.

## 4. Frontend

- [x] 4.1 Give `AgreementHttpError` a `problemType` read from the RFC 9457 body, following
      `StaffQueueHttpError`; add a `contactsFrozen` accessor.
- [x] 4.2 In `CaptureForm.vue`, skip the contacts PATCH when the confirmed list matches the
      server-seeded baseline, and adopt saved values as the new baseline (design D4).
- [x] 4.3 Replace the blanket "Could not save those contact details. Please try again." with a
      message keyed on the problem type: paid -> contacts are locked and why; anything else ->
      the retryable message.

## 5. Frontend tests

- [x] 5.1 **Unit** (`agreements.test.ts`): `updateAgreementContacts` surfaces the problem `type` on
      the thrown error, and degrades to `null` on a non-problem body.
- [x] 5.2 **Unit** (`CaptureForm.test.ts`): an unchanged confirm sends no PATCH and proceeds to
      finalise + pay (the reported retry); a changed confirm does send it.
- [x] 5.3 **Unit** (`CaptureForm.test.ts`): a `contacts-frozen` 409 renders the locked message and
      does not say "try again"; a generic failure still renders the retryable one.
- [x] 5.4 Run `npm run test`, `npm run lint`, and `npx prettier --check` on the touched files.

## 6. Close-out

- [x] 6.1 Re-read `proposal.md` - Not in scope and confirm nothing in the implementation drifted
      into recovery-link rotation or an old-address notice.
- [ ] 6.2 Manual check against the live stack: finalise, fail payment, reopen the contact step,
      correct an address, and reach checkout.
