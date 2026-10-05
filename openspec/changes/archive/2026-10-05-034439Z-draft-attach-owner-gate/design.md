## Context

Agreement ownership is one account per agreement, and the first claim wins (`Agreement.claimBy`, under a write lock in `AgreementService.claim`). Party roles (landlord, tenant) are not tied to accounts. Owner-scoping today:

| Route | Owner-scoped? | Where |
|---|---|---|
| `GET /{id}` | yes | `AgreementService.findByIdForReader` (inline filter) |
| `PUT /{id}` | yes — owner only, authenticated (a **different** rule: an unclaimed agreement is not editable here) | `AgreementService.update` |
| `PATCH /{id}/contacts` | yes | `AgreementService.updateContacts` (inline filter) |
| payment order/status/callback, stamp quote, signing progress, signed-document download | yes (+ STAFF) | `AgreementService.isAccessibleBy` (inline filter) |
| `POST /{id}/document` | **no** | `AgreementDocumentService.renderForDraft` → `DraftService.attachDraft` → `pinEffectiveTemplate` (three separate transactions) |
| `POST /{id}/draft` | **no** | `DraftService.attachDraft` |
| `GET /{id}/preview` | **no** | `AgreementDocumentService.renderPreview` |
| `POST /{id}/finalise` | **no** | `SigningRequestService.finalise` |

All four ungated service methods have exactly one caller each, the customer handler in `AgreementController`. No staff route goes through them. After this change, every customer route on an agreement id applies the owner rule, or is staff-only or anonymous by design. The remaining weak point is how ownership is *acquired* (first claim wins on a bearer id), not how it is enforced. That is the existing `claim-bound-to-initiator` register row.

`GlobalExceptionHandler` renders every `ResourceNotFoundException` with the same type (`urn:agreementmitra:problem:resource-not-found`), title and detail (`Resource not found`). Throwing that exception is therefore what makes "unknown" and "not yours" indistinguishable. The integration test in task 3.2 pins that coupling.

## Goals / Non-Goals

**Goals:**
- Close the four routes to a non-owner of a claimed agreement, with no ownership oracle.
- One definition of the owner rule, used by every route that applies it.
- An honest capture-form message for the `404` a legitimate customer can actually hit, without identifying anyone.

**Non-Goals:**
- **How ownership is acquired.** First-claim-wins, binding claim to the initiator, and gating claim after payment are all `claim-bound-to-initiator`, and this change appends its findings there.
- **Co-ownership**, or account access for the other parties.
- **Showing who claimed an agreement.** Decided against: it is an ownership oracle and leaks account PII.
- **A draft replaced while the agreement was still unclaimed.** That remains the owner's draft after they claim. Unclaimed agreements are open by product design.
- **Tightening the filter chain.** These routes stay `permitAll`.
- **STAFF access to these customer routes.** Staff have their own routes. A STAFF session is just another non-owner here, and task 3.1 tests that.
- **The upload-versus-finalise freeze race.** `placeOrder` takes no agreement lock, so an upload can still race a finalise. This predates the change and is unchanged by it.

## Decisions

### D1 — The owner rule lives on the aggregate, as `admits(caller)`

Add `boolean admits(UUID callerIdentityId)` to `Agreement`. It returns true when `ownerIdentityId == null` or equals the caller, and a null caller is admitted only to an unowned agreement. `AgreementService.findByIdForReader`, `isAccessibleBy` and `updateContacts`, plus every gate in D2, call it.

It is named `admits`, not `isAccessibleBy`, so that a grep separates the aggregate predicate from the service method that loads the row. `AgreementService.isAccessibleBy(id, caller)` stays the module-API entry for the payment surface, the stamp quote, signing progress and the signed-document download, and now delegates to `admits`.

**Two look-alikes are deliberately not this rule** and must not be routed through it:
- `AgreementService.update` (`PUT`) is owner-only. Routing it through `admits` would let any signed-in account edit an unclaimed agreement.
- `findRecoverableByTrackingReference` admits unowned agreements only.

*Why:* the predicate is already written out three times, and this change would add four more. The rule is a fact about the aggregate, so it belongs there.
*Alternative:* call `AgreementService.isAccessibleBy` from each gate. Rejected: it costs a second load, and from `DraftService` it would be a read outside the lock D3 takes, which reopens the race D3 closes.

### D2 — The gate sits in the service, at the first load, keyed on the principal

Each gated method gains a `UUID callerIdentityId` parameter. The controller passes `@AuthenticationPrincipal UUID identityId`, which is null for an anonymous caller, as the contacts handler already does.

- **`DraftService.attachDraft(id, caller, bytes)`:** load under the lock (D3), check the owner, then the magic-byte PDF check, then the freeze check. The magic-byte check depends only on the bytes, so the spec would allow it to come first. It stays after the owner check here because that is the simplest order, and a non-owner then gets `404` regardless of body.
- **`AgreementDocumentService.renderPreview(id, caller)` / `renderForDraft(id, caller)`:** load through a new private `findFor(id, caller)` that checks the owner before rendering. A non-owner costs no Chromium render, and no PII-bearing bytes are produced. Target shape:
  - `render(UUID)` becomes `render(Agreement)`;
  - `renderForStamp` keeps the caller-less `find`, because it is a staff/fulfilment path and must not acquire a gate;
  - `reRenderStoredDraft` already takes an `Agreement`.
- **`AgreementDocumentService.pinEffectiveTemplate(id, caller, identity, date)` is gated too, and loads under the lock** (`findByIdForUpdate` plus `admits`). Generate is three committed transactions, so a claim or an owner's `PUT` can land between `attachDraft` and the pin.
  - **Why the lock is required:** `Agreement` has no `@Version` and no `@DynamicUpdate`, so Hibernate flushes every column from the snapshot it loaded. An unlocked pin that read `owner = null` would wait behind the claim's row lock and then write `owner_identity_id = NULL` back, undoing the claim, along with stale terms, capture state and draft key that undo an owner's edit. The lock makes the pin read the post-claim row, so it is refused `404`.
  - **Cost:** the pin does no I/O, so the lock costs nothing measurable.
  - **What a refused pin leaves:** the draft that request stored before the claim stays, next to whatever hash and layer columns the row already held. That is safe for stamping. `Agreement.attachDraft` nulls `draftExecutionDate`, so `reRenderStoredDraft` takes its `NOT_A_RECORDED_RENDER` fallback and stamps the stored draft without reading the hash. Nothing may assume the hash describes the current draft unless `draftExecutionDate` is set; `byo-document-upload`'s integrity record should note that.
  - **Overload:** the two-argument overload has no caller in main or test code, and is removed.
- **`SigningRequestService.finalise(id, caller)`:** the first step becomes `agreementService.findByIdForReader(id, caller)` in place of `findById`. So a non-owner gets the `404` before `requireOpen`, `requireDraft` or `jurisdiction.require` can answer. `findByIdForReader` returns the `AgreementResponse` that finalise already consumes, so no new module-API method is needed.

Each gate throws the canonical `ResourceNotFoundException("Agreement not found: " + AgreementIds.redact(id))`, the same as the existing gates. The response body is made uniform by `GlobalExceptionHandler`, not by these messages.

*Why the service, not the controller:* the service holds the loaded row, so the check costs nothing extra. It is also where `updateContacts` and `findByIdForReader` already check.
*Why not the filter chain:* the chain cannot see the row's owner.

### D3 — `attachDraft` loads under the write lock, with a bounded lock scope

`attachDraft` switches from `findById` to `findByIdForUpdate`, the lock `claim` and `updateContacts` already take. An upload and a claim then serialise: the upload either completes before the claim, or sees the new owner and returns `404`.

**Lock scope.** The lock is held from the load until the transaction commits. That covers the owner check, the freeze query, the `blobStore.put` of at most 10 MiB, and the key and last-edited write. The blob write cannot move before the lock, because the storage key is fixed per agreement: writing first would let a non-owner overwrite the owner's blob before being refused. So `claim` and `updateContacts` on that one agreement can wait for at most one object-store write. That cost is accepted.

**Work that does not depend on the row SHALL stay outside the lock.** That means parsing or converting uploaded bytes, as `byo-document-upload` plans. It must run before `attachDraft` takes the lock, never inside it (D5).

**The pin uses the same lock** (D2). It is a write path, so the read-only rationale below does not apply to it.

**Accepted residuals:**
- **Timing.** A claimed row can be slower to answer while its owner holds the lock, whereas an unknown id answers at once. The response body is identical. The difference is only measurable by a caller who already holds the UUID, and it reveals a lock, not an owner.
- **Read-only paths are unlocked.** `renderForDraft` reads without the lock. That is harmless: the render stores nothing, and the `attachDraft` and pin that follow re-check. `finalise` reads without a lock too. A link holder's finalise that beats a simultaneous claim is one they could equally have made a moment earlier, and order placement is idempotent (`SigningRequestPersistence.placeOrder` returns the existing request). Locking there would be a signing-flow change this CR does not need.

### D4 — Frontend: one not-available message, on every call of the capture form's pay path

"Finalise and pay" makes four owner-gated calls in sequence:
1. `openContactStep` → `getAgreement` (`GET /{id}`);
2. `confirmContacts` → `PATCH /contacts`;
3. `StampQuoteStep` → `GET /stamp-quote`;
4. `finaliseAgreement` → `payForAgreement` (checkout order).

A customer whose session ended, or whose agreement another account claimed, therefore meets the `404` at **step 1**, where today it reads "Could not load the party details. Please try again." The later steps return `404` only if the session ends partway through the pay path. The save path cannot realistically reach a gate:
- in edit mode, `PUT` runs first and is authenticated, so an ended session is a `401` there, which is existing behaviour unchanged by this CR;
- in create mode, generate runs on the just-created, still-unclaimed agreement.

So:
- Add `notFound: "urn:agreementmitra:problem:resource-not-found"` to `PROBLEM` (`src/api/problems.ts`).
- Add `AGREEMENT_UNAVAILABLE_MESSAGE` to `src/views/refusalMessages.ts`: *"This agreement isn't available here. If it has been saved to an account, sign in with that account to continue."*
- Show it, and call `void reconcile()` from the auth store, in three catches in `CaptureForm.vue`:
  - `openContactStep`;
  - `confirmContacts`, after its contacts-frozen check;
  - `finaliseAndPay`, after its jurisdiction check. This one catch covers both finalise and the checkout-order call (only `startCheckout` errors escape `payForAgreement`).

  A `404` does not trigger the client's 401/403 reconcile hook, so this is what corrects a header still showing an ended session. `reconcile()` does nothing when signed out, shares concurrent calls, and only issues `GET /me`. All three clients (`AgreementHttpError.from`, `PaymentHttpError.from`) already carry the problem type, so no client change is needed.
- **The status view too (folded in at manual test, 2026-10-05).** Its "Complete payment" loads the stamp quote and then calls checkout, and both routes were already owner-gated before this change. A customer whose session ended met "Payment cannot be started yet. Contact support quoting your reference." there, which sends them to support when signing in is the remedy. `AgreementStatus.vue` now shows the same message and calls `void reconcile()`:
  - on a stamp-quote `404`, keyed on `StampQuoteHttpError.status`, because that client carries no problem type and the route's only `404` is "unknown or not yours";
  - on a checkout `404` with the not-found type.
- **Left as is:**
  - The capture form's stamp-quote load (step 3) keeps its message. Its client error (`stampQuote.ts`) carries no problem type, and its `404` is reachable only in the mid-path race.
  - `generateAgreementDocument` is not changed.
- The dead `fetchAgreementPreview` export (`src/api/client.ts`, no caller and no test) is removed. A future caller of the gated preview route will then have to be written against current error handling.

*Why not reuse `LINK_UNAVAILABLE_MESSAGE`:* its wording describes following a link, and here the customer is pressing a button in a form. Both messages carry the same no-oracle hedge, and each is the single source for its own context.

### D5 — Spec placement and coordination with `byo-document-upload`

The owner rule is one **ADDED** requirement in `backend-security-baseline`, not MODIFIED blocks on `draft-ingestion` "Upload a draft agreement PDF" and `agreement-preview` "Preview the filled agreement document". The active `byo-document-upload` change (proposed 2026-10-03, not implemented) already MODIFIES both, and two MODIFIED deltas on one requirement overwrite each other at archive.

The overlap goes beyond spec text. This change **lands first** (High priority, small), and `byo-document-upload` rebases onto it:
- **byo task 1.1 ("carry ownership authZ on `/draft` and the preview fetch") is satisfied by this change.** byo builds on these gates rather than adding its own.
- **byo's ADDED "The composed draft SHALL be retrievable for review under owner scope" says "owner or link holder".** It must be read, and reworded, as this change's rule: a link holder is admitted only while the agreement is unclaimed.
- **byo 7.1's stored-draft branch on `GET /{id}/preview`** must load through the gated `findFor`. Its new refusals must come after the owner check, per this change's ordering clause.
- **byo's parse and conversion steps** must run outside `attachDraft`'s lock (D3). Because they inspect only the uploaded bytes, the spec's ordering clause classes them as id-independent refusals, so they may precede the owner check.

This change does not edit `byo-document-upload`'s artifacts, because that change belongs to a teammate. The wrap-up flags this list for them.

## Risks / Trade-offs

- **[Risk] Existing tests claim an agreement and then call these routes without a session, or call the changed service methods directly.** Already known:
  - `AgreementCapturePersistenceIntegrationTest.editReplacesCaptureStateWholesaleAndClearsThePinnedDraft` claims, then POSTs `/document` anonymously;
  - `JurisdictionGateIntegrationTest` calls `attachDraft` directly;
  - `AbuseLimitsIntegrationTest` uses MockMvc;
  - `DraftServiceTest` and `AgreementIdExceptionMessageTest` stub `findById`.

  → Mitigation: each is updated to act as the owner (send the session, or pass the owner id), never by loosening the gate. A test that relied on non-owner access is surfaced, not papered over.
- **[Risk] A claimed agreement's per-resource rate-limit bucket can be spent by refused non-owners.** This is pre-existing: the same holds for `GET /{id}` today. The limiter runs before the handler and keys on the parsed UUID whether or not the row exists, so it is not an oracle. Not changed here.
- **[Risk] Signing flow.** `finalise` creates the signing request. → The change only adds a refusal before any write. No FSM transition, state, webhook or reconciliation path changes, and task 2.7 pins that the owner's finalise returns the same result as before.
- **[Trade-off] An unclaimed agreement stays fully open to any link holder.** This is the existing product decision, not something this CR changes.

## Migration Plan

No schema change. Ship as one deploy. Rollback is a code revert, and no data is written differently.

## Open Questions

None.
