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

- [x] 3.1 **Unit** (`AgreementContactsWindowTest`, no Spring context): contacts save on a finalised UNPAID
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
- [x] 3.5 **Integration**: the terms route (`PUT /api/agreements/{id}`) still returns `409` with
      problem type `draft-frozen` after finalise -- proving the terms freeze did not move with the
      contacts freeze. The call must be made **as the signed-in owner and with a valid body**: the
      route is `.authenticated()` and `@Valid`, so an anonymous caller (403) or an empty signer list
      (400) is refused before `AgreementService.update` runs and the test would pass even with the
      freeze deleted.
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
      - Partially driven 2026-09-05 (API half): `PATCH /api/agreements/{id}/contacts` on an unowned
        pre-payment draft answers `200` and accepts a corrected address anonymously; an empty
        contact set is refused with the RFC 9457 ProblemDetail body ("Provide contact details for at
        least one party"), so the route validates rather than silently no-oping.
      - NOT driven: the sequence this task is actually about -- finalise, **fail a payment**, and
        reopen the contact step in the SPA. Failing a Razorpay payment needs the checkout widget in a
        browser; it cannot be curl'd. Human drive required.

## Coverage

Retrofitted 2026-09-10, after implementation. This change predates the coverage gate, so the
matrix is a record of what the tests actually reach rather than a contract agreed before code —
`review-spec` step 2 built it against the delta's 11 scenarios and the tests on disk.

| # | Scenario (`specs/payment-processing/spec.md`) | Disposition | Evidence |
|---|---|---|---|
| 1 | An anonymous caller saves contacts | `COVERED` | 3.3 — `ContactGateIntegrationTest.contactsCanBeSetAnonymouslyAndUnblockCheckout` |
| 2 | The owner saves contacts on their own agreement | `GROUPED` | pre-existing `AgreementOwnershipIntegrationTest.ownerCanSaveContactsOnTheirOwnAgreement`; this change does not alter the ownership path |
| 3 | Only contacts can be changed | `GROUPED` | 3.3 — `ContactGateIntegrationTest.theContactsRouteCannotChangeAnythingButContacts` |
| 4 | An agreement owned by somebody else is refused | `GROUPED` | 3.3 — `ContactGateIntegrationTest.aClaimedAgreementRefusesAnonymousContactChanges` (404, indistinguishable from unknown) |
| 5 | A mistyped address is corrected after the order is placed | `COVERED` | 3.3 — `ContactGateIntegrationTest.contactsCanStillBeCorrectedAfterTheOrderIsPlaced` |
| 6 | The corrected address receives the agreement | `COVERED` | 3.3 — same test, `mail.sentTo("corrected@example.com")` via `RecordingEmailSender` |
| 7 | Contacts are frozen once payment is confirmed | `COVERED` | 3.1 — `AgreementContactsWindowTest.contactsAreFrozenOncePaid` + `.contactsAreFrozenOnceWaived`; 3.4 — `ContactGateIntegrationTest.contactsAreRefusedOncePaidAndSayWhy` |
| 8 | Contacts are frozen on a closed agreement | `COVERED` | 3.1 — `AgreementContactsWindowTest.contactsAreFrozenOnAClosedAgreement` + `.aClosedAndPaidAgreementReportsClosedNotPaid` (D2 ordering) |
| 9 | The paid refusal is distinguishable from the terms freeze | `COVERED` | 3.4 — asserts `contacts-frozen`, `doesNotContain("draft-frozen")`; 3.5 — the mirror assertion on the terms route |
| 10 | Placing the order does not freeze contacts | `COVERED` | 3.1 — `AgreementContactsWindowTest.contactsAreEditableOnAnUnpaidAgreementEvenAfterTheOrderIsPlaced` |
| 11 | Changing a contact leaves the document untouched | `COVERED` | 3.2 — `AgreementContactsWindowTest.changingAContactLeavesTheDocumentPinnedAndTheTermsAlone` |

**Totals:** 11 scenarios — 8 `COVERED`, 3 `GROUPED`, 0 `MANUAL`, 0 `WAIVED`, **0 `UNMAPPED`**.

Waiver rate 0%, so the cap does not bind. Nothing here is waived, which matters because scenarios
5–9 are on the money path (the freeze is keyed on `PaymentState`) and this project never waives
money, PII, or document-validity paths.

**Note on scenario 9 and task 3.5.** The terms-route half of this scenario was covered by a
**vacuous** test until 2026-09-10: it called an `.authenticated()` route anonymously, got a 403,
and asserted only "not 200" — it would have passed with the terms freeze deleted. Repaired under
task 3.5 to sign in as the owner, send a valid body, and assert `409` + `draft-frozen`. This is
the coverage gate's own argument for itself: the row looked covered and was not.

Task 6.2 (drive a failed payment through the SPA and reopen the contact step) is the change's
manual gate, not a scenario row — no delta scenario describes the browser retry, because the
requirement is about the server's window, not the client's route back to it.
