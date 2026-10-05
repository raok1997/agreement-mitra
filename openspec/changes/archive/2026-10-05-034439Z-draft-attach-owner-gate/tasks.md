## 1. One owner rule on the aggregate (D1)

- [x] 1.1 Add `boolean admits(UUID callerIdentityId)` to `backend/src/main/java/in/agreementmitra/signing/agreement/Agreement.java`. True when unowned, or when owned by the caller. A null caller is admitted only to an unowned agreement.
- [x] 1.2 Make `AgreementService.findByIdForReader`, `isAccessibleBy` and `updateContacts` call `admits` instead of their inline filters. Leave `update` (owner-only) and `findRecoverableByTrackingReference` (unowned-only) alone: they are different rules (D1). Afterwards, `grep -rn "ownerIdentityId() == null ||" backend/src/main/java` must return nothing.
- [x] 1.3 Unit test in `AgreementTest` for `admits`: unowned + null caller, unowned + any caller, owned + owner, owned + other identity, owned + null caller.

## 2. Gate the four routes (D2, D3)

- [x] 2.1 Change `DraftService.attachDraft` to `attachDraft(UUID agreementId, UUID callerIdentityId, byte[] bytes)`:
  - load with `findByIdForUpdate`;
  - refuse a non-owner with the canonical `ResourceNotFoundException`, before `validatePdf` and before the freeze check;
  - add a javadoc note that row-independent byte processing belongs before this call, not inside it.
- [x] 2.2 In `AgreementDocumentService`:
  - `renderPreview(id, caller)` and `renderForDraft(id, caller)` load through a new private `findFor(id, caller)`;
  - `render(UUID)` becomes `render(Agreement)`;
  - `renderForStamp` stays on the caller-less `find`.
- [x] 2.3 Make `AgreementDocumentService.pinEffectiveTemplate(id, caller, identity, date)` load with `findByIdForUpdate` and check `admits` (D2: this is a write, and an unlocked full-row flush can undo a claim). Remove the two-argument overload, which has no callers. Add a javadoc invariant to the pin.
- [x] 2.4 In `SigningRequestService.finalise(id, caller)`, replace the initial `agreementService.findById` with `findByIdForReader(id, caller)`.
- [x] 2.5 In `AgreementController`, pass `@AuthenticationPrincipal UUID identityId` into `generateDocument` (render, attach and pin), `uploadDraft`, `preview` and `finalise`. Rewrite their javadoc and drop the "TEMPORARY – tighten when ownership lands" notes.
- [x] 2.6 Rewrite the `SecurityConfig` comments on the preview, document, finalise and draft `permitAll` matchers to say they are owner-scoped in the handler, like `GET /api/agreements/*`. Fix two stale comments while there:
  - `:258` says the read is "unauthenticated today; TEMPORARY";
  - `:311` says contacts "refuses an owned" agreement.

  The matchers do not change.
- [x] 2.7 Update the unit tests broken by the new signatures:
  - `DraftServiceTest`: all six tests stub `repository.findById`; switch them to `findByIdForUpdate`.
  - `AgreementIdExceptionMessageTest:37`: it stubs `findById` for `attachDraft`; switch it to `findByIdForUpdate`, or strict stubs fail.
  - `AgreementDocumentServiceTest`: render stubs stay on `findById`; pin stubs move to `findByIdForUpdate`.
  - `AgreementControllerTest`: the new handler signatures.
  - `SigningRequestServiceTest` has no `finalise` test today, so nothing breaks there; it only gains the tests in 2.8.
- [x] 2.8 New unit tests:
  - **`DraftServiceTest`:** a non-owner with a non-PDF body gets `ResourceNotFoundException`, `blobStore.put` is never called, and the load goes through `findByIdForUpdate`, not `findById`.
  - **`SigningRequestServiceTest`:**
    - a non-owner's finalise throws `ResourceNotFoundException` and never reaches `requireOpen`, `requireDraft`, `jurisdiction.require` or `placeOrder`;
    - the owner's finalise returns the same tracking number and status as before.
  - **`AgreementDocumentServiceTest`:**
    - a non-owner's preview and render-for-draft never call the projection;
    - a non-owner's pin writes nothing, and the pin loads through `findByIdForUpdate`;
    - `renderForStamp` still works with no caller.

## 3. Integration test (backend-security-baseline requirement)

- [x] 3.1 Add `backend/src/test/java/in/agreementmitra/signing/DraftingSurfaceOwnerGateIntegrationTest.java`.
  - **Setup:** reuse the context configuration and `DocumentProjectionApi` mocking of `AgreementCapturePersistenceIntegrationTest`, so the Spring context cache is hit and the check budget of 3 minutes or less holds. Follow the pattern of `AgreementOwnershipIntegrationTest.claimMakesReadsOwnerScopedWithNoOracle` (`sessionFor`, `bearer`).
  - **Cases, for each of generate, upload, preview and finalise:**
    - unowned + anonymous → served (finalise last);
    - claimed by A + A's session → served;
    - claimed by A + anonymous → `404`;
    - claimed by A + B's session → `404`;
    - claimed by A + a STAFF session (`support/StaffSessions.staffSession`) → `404`.
  - **Fixtures:** each case uses a fresh agreement in an eligible jurisdiction (TG, as `SigningRequestApiIntegrationTest` does), because a served finalise needs one and freezes generate and upload afterwards.
- [x] 3.2 In the same class, check that a refused request changed nothing:
  - the non-owner `404` and the unknown-id `404` carry the same `type`, `title` and `detail`. Assert only those fields, not the whole body;
  - after the refused calls, the stored draft key and bytes, the template pin and `last_edited_at` are unchanged, and no signing request row exists.
- [x] 3.3 In the same class, use an agreement claimed by A whose order is placed. For an anonymous caller and for B, each of these is `404`, never `400` or `409`:
  - a valid-PDF upload;
  - generate;
  - preview;
  - finalise.
- [x] 3.4 Fix the integration tests these signatures and gates break, each by acting as the owner, never by loosening the gate:
  - `AgreementCapturePersistenceIntegrationTest.editReplacesCaptureStateWholesaleAndClearsThePinnedDraft` (anonymous `/document` after a claim): send the owner's session;
  - `JurisdictionGateIntegrationTest` (direct `attachDraft` calls): pass the caller;
  - `AbuseLimitsIntegrationTest` (MockMvc): send the owner's session cookie if a claimed agreement is used.

  Then run the full backend suite and fix anything else it surfaces the same way. Record each changed test in the journal.

## 4. Capture-form not-available message (D4)

- [x] 4.1 Add `notFound: "urn:agreementmitra:problem:resource-not-found"` to `PROBLEM` in `frontend/src/api/problems.ts`.
- [x] 4.2 Add `AGREEMENT_UNAVAILABLE_MESSAGE` to `frontend/src/views/refusalMessages.ts`.
- [x] 4.3 In `frontend/src/views/CaptureForm.vue`, when the error has the not-found type, show the message and call `void reconcile()` from the auth store, in three catches:
  - `openContactStep`;
  - `confirmContacts`, after its contacts-frozen check;
  - `finaliseAndPay`, after its jurisdiction check. This one catch covers both finalise and the checkout-order call.
- [x] 4.4 Remove the dead `fetchAgreementPreview` export from `frontend/src/api/client.ts`, together with any test of it.
- [x] 4.5 Add frontend unit tests in `frontend/src/views/CaptureForm.refusals.test.ts`, first adding `reconcile: vi.fn()` to its `../api/authStore` mock:
  - loading the agreement for the contact step refused `404` not-found → the message (not "Could not load the party details"), and `reconcile` is called;
  - saving contacts refused `404` not-found → the message;
  - finalise refused `404` not-found → the message (not "Could not start payment" and no `request failed:`), and `reconcile` is called;
  - the checkout-order call refused `404` not-found → the same message;
  - the message text contains no account detail (it is the shared constant on both paths).

- [x] 4.6 In `frontend/src/views/AgreementStatus.vue`, show `AGREEMENT_UNAVAILABLE_MESSAGE` and call `void reconcile()` when "Complete payment" is refused `404`: the stamp-quote load (`StampQuoteHttpError` status 404) and the checkout-order call (not-found type). Folded in at manual test; D4.
- [x] 4.7 Add tests in `frontend/src/views/AgreementStatus.refusals.test.ts`: a stamp-quote `404` and a checkout `404` not-found each show the message (no "Contact support") and call `reconcile`.

## 5. Docs and register

- [x] 5.1 In `docs/ROADMAP.md`:
  - delete the `draft-attach-owner-gate` row from the `## Follow-up register`;
  - append to the `claim-bound-to-initiator` row: this change made the drafting surface owner-only too, so a first claimant now also controls generate, upload and finalise;
  - append to the same row: recovery emails `/agreement/{id}` to every party of a paid, unowned agreement;
  - append to the same row: a draft replaced while the agreement was unclaimed survives the claim.

  (There is no `delete-draft-agreement` row, so there is nothing to append there.)
- [x] 5.2 Check `docs/ARCHITECTURE.md` and the Terms source (`frontend/scripts/render-terms.mjs`, which renders `docs/TERMS-OF-SERVICE.md`; never hand-edit the output) for any statement that a link holder can act on a saved agreement, and correct it if found.

## Coverage

| Scenario | Disposition | Test |
|---|---|---|
| backend-security-baseline › An unclaimed agreement stays open to any link holder | COVERED | 3.1 `DraftingSurfaceOwnerGateIntegrationTest` |
| backend-security-baseline › The owner of a claimed agreement is served | COVERED | 3.1 `DraftingSurfaceOwnerGateIntegrationTest`; 2.8 `SigningRequestServiceTest` (owner finalise unchanged) |
| backend-security-baseline › A non-owner gets the unknown-agreement 404 and changes nothing | COVERED | 3.1 + 3.2 `DraftingSurfaceOwnerGateIntegrationTest`; 2.8 unit |
| backend-security-baseline › A non-owner cannot learn a claimed agreement's state from the status code | COVERED | 3.3 `DraftingSurfaceOwnerGateIntegrationTest`; 2.8 unit |
| backend-security-baseline › A write racing a claim cannot land on the claimed agreement | COVERED | 2.8 `DraftServiceTest` + `AgreementDocumentServiceTest` (upload and pin load through `findByIdForUpdate`, the lock `claim` takes, with the owner check under it). Unit-only: the interleaving is not driven end to end. |
| client-error-reporting › The pay path's first step finds the agreement not available | COVERED | 4.5 `CaptureForm.refusals.test.ts` |
| client-error-reporting › Saving contacts finds the agreement not available | COVERED | 4.5 `CaptureForm.refusals.test.ts` |
| client-error-reporting › Finalise refused because the agreement is not available to this caller | COVERED | 4.5 `CaptureForm.refusals.test.ts` |
| client-error-reporting › The checkout call after finalise is refused as not available | COVERED | 4.5 `CaptureForm.refusals.test.ts` |
| client-error-reporting › The not-available message never identifies the claiming account | COVERED | 4.5 `CaptureForm.refusals.test.ts` |
| client-error-reporting › The status view's payment finds the agreement not available | COVERED | 4.7 `AgreementStatus.refusals.test.ts` |
| client-error-reporting › (existing scenarios, unchanged) | COVERED | existing `CaptureForm.refusals.test.ts` and status-view tests, re-run at the Stage 4a build gate |
