## Context

Nothing deletes an agreement today. The agreement is status-less: the list's `DRAFT` status is derived as
"no signing request exists" (`AgreementDisplayStatus.from`, `signing/api/AgreementDisplayStatus.java:25-27`)
and says nothing about payment. Every FK to `agreement` uses the default `NO ACTION`, `BlobStore` has only
`put`/`get`, and the closure spec rules out closure-as-deletion. Owner gating on drafting surfaces follows
one pattern (`DraftService.attachDraft`, `AgreementService.update` :257-269): load under
`findByIdForUpdate` (PESSIMISTIC_WRITE), owner filter → same 404 as unknown, then state refusals (409).

Grounding of what can hang off an unpaid, never-finalised agreement:

| Table | Can reference a deletable draft? | Why |
|---|---|---|
| `signer` | yes | parties; `cascade = ALL, orphanRemoval` on `Agreement.signers` (:237) |
| `signing_request`, `signing_request_invitee`, `signed_document_delivery` | no | exist only after finalise — excluded by the rule |
| `payment_order`, `stamp_quote` | possible | `startCheckout` does not require a signing request — excluded by the rule; `stamp_quote` only exists with an order |
| `stamp_intake_audit` | **yes** | staff intake resolves the reference, is refused (no order placed → outcome `REJECTED_ERROR`), and records the agreement id in a `REQUIRES_NEW` audit write |
| `recovery_audit` | no | `agreement_id` is set only for unowned `PAID`/`WAIVED` agreements (`RecoveryService.java:90`, `AgreementService.java:407-414`); payment state never returns to `UNPAID` |

Object storage for a draft: only `drafts/{id}.pdf`, uploaded or generated. Its key is fixed per agreement
(`DraftService.java:83`), but the `draft_pdf_key` column does **not** reliably say whether the object
exists: an edit clears the pin (`AgreementService.java:298` → `Agreement.clearDraftPin` :394-395) without
removing the object, and `attachDraft` writes the blob before its transaction commits. Stamp, signed and
audit objects exist only after finalise (intake refuses a deletable draft before any blob write,
`StampIntakeService.java:205-207`). The in-flight `byo-document-upload` change adds two more draft-stage
keys (`uploads/{id}.{pdf,docx}`, `converted/{id}.pdf`); D6 makes one helper the single list of draft-stage
keys so that change extends it rather than adding a second copy.

## Goals / Non-Goals

**Goals:** an owner can remove from the live service an unpaid, never-finalised draft and the parties' personal data
it holds; the list offers Delete exactly when the server would accept it; the endpoint is not an ownership
oracle; concurrent finalise/checkout cannot leave a paid-and-deleted agreement.

**Non-Goals:**
- Anonymous-draft deletion or purge (`anonymous-draft-retain-and-purge`).
- Deleting an agreement once finalised, even if unpaid. The capture form finalises immediately before
  checkout (`CaptureForm.vue` ~:962), so a customer who pressed Pay and left has an `IN_PROGRESS`
  agreement, which this change does not let them delete. That is deliberate: a finalised agreement has a
  signing request, possibly a provider order, and a frozen stamp quote, and withdrawing it is a different
  operation, recorded as the register row `withdraw-unpaid-finalised-agreement`.
- Soft delete or undo; recalling emailed copies; a Delete button on the capture screen.
- Tightening `startCheckout` to require finalise — a payment-flow change; the rule makes delete safe
  without it.

## Decisions

**D1 — Hard delete, not soft delete.** The goal is to stop holding the parties' personal data for something
the customer abandoned; a `deleted_at` flag keeps all of it and adds a filter every read path must remember.
ToS §10 already frames an unpaid draft as disposable. *Alternative:* soft delete with a later purge —
rejected: two mechanisms and the PII stays.

**D2 — Strict owner rule, authenticated route.** The owner must equal the caller, compared null-safely
(`caller != null && caller.equals(owner)`), mirroring `PUT /api/agreements/{id}` rather than the looser
`admits`. Unknown / unowned / other owner → one `ResourceNotFoundException` (404), thrown before any state
check. **What this does and does not protect:** it keeps a bodyless request on an emailed link from
destroying a draft. It does **not** stop a holder of an unowned draft's id from claiming it (any signed-in
caller may claim an unowned agreement, `AgreementService.claim` :213-230) and then deleting it. That is
accepted: the same holder can already claim and then overwrite every term via `PUT`, so delete adds no new
power, and anonymous-link hardening belongs to `anonymous-draft-retain-and-purge`.

**D3 — One deletability rule, owned by the aggregate.** `Agreement.isDeletableDraft(boolean
hasSigningRequest, boolean hasPaymentOrder)` (package-private, pure) returns true only when
`!hasSigningRequest && !hasPaymentOrder && paymentState == UNPAID && closureState == OPEN`. Both the delete
and the list summary call it, so the button and the server cannot disagree (two copies of one fact was the
review's top finding). `closureState == OPEN` is defence-in-depth: today nothing closes an agreement without
a signing request, but the rule must not depend on that staying true.
- **Delete** — `DraftService.deleteDraft(id, caller)` (`@Transactional`): `findByIdForUpdate` → owner
  check (404) → `isDeletableDraft(signingRequestQuery.existsForAgreement(id),
  paymentOrderQuery.existsForAgreement(id))` else `ConflictException.draftNotDeletable()` (409) →
  `repository.delete(agreement)` (signers cascade). It lives in `DraftService` because that service owns
  the draft blob key and already holds `BlobStore`, `SigningRequestQuery` and the repository.
- **List** — `AgreementService.toSummary` computes `deletable` with the same call, taking
  `hasSigningRequest` from the `currentStatusForAgreement` Optional it already fetches (`isPresent()`), and
  querying `PaymentOrderQuery` only for a row with no signing request that is `UNPAID` and `OPEN` (otherwise
  the answer is already false). So at most one extra per-row query, only on candidate drafts (see
  `progress-read-hot-path` for the general cost note).
- One problem type for every refusal reason: the customer's remedy is the same, and finer kinds would only
  re-expose state the screen already shows.

**D4 — `PaymentOrderQuery` root seam.** A one-method public interface in `in.agreementmitra.signing`
(`boolean existsForAgreement(UUID)`), implemented package-private in `signing.payment` over
`PaymentOrderRepository.countByAgreementId`. Same shape as `SigningRequestQuery`; keeps `signing.agreement`
free of `payment` imports (none exist today).

**D5 — Concurrency: the row lock plus the FK.** Every agreement mutator reachable on a deletable draft loads under
`findByIdForUpdate` (`attachStamp` and `recordSelectedTemplate` use `findById` but are post-finalise or
unwired), so generate, upload, edit, claim and staff record/waive serialise with the delete and
see 404 afterwards. `finalise` (`placeOrder`) and `startCheckout` do not lock the row, but inserting a
`signing_request` / `payment_order` takes `FOR KEY SHARE` on the parent row, which conflicts with our
`FOR UPDATE`. **The delete must therefore load through `AgreementRepository.findByIdForDelete`, a native
`SELECT … FOR UPDATE`, not `findByIdForUpdate`:** Hibernate renders `PESSIMISTIC_WRITE` on PostgreSQL as
`FOR NO KEY UPDATE`, which does *not* conflict with `FOR KEY SHARE` — found by the 4.6 lock test, which
returned a 500 (FK violation at flush) until the lock was changed. The other mutators keep
`findByIdForUpdate`; `FOR NO KEY UPDATE` still conflicts with the delete's `FOR UPDATE`.
- insert commits first → our lock waits → the post-lock existence check (READ COMMITTED, fresh snapshot
  per statement) sees the row → 409;
- our delete holds the lock first → the insert waits, then fails its FK.
The second branch has two known side effects, accepted because only the owner can trigger it, racing their
own draft from two tabs:
- **finalise** fails with an unmapped error (500); nothing is written.
- **checkout** has already created the provider order (`PaymentOrderService.java:219`, before its insert
  transaction), so a Razorpay order exists with no local row. It is never paid (no session is returned) and
  expires at the provider. Its FK violation is misread by the double-submit catch (:228-240) as the
  unique-index race, finds no order and rethrows → 500, with a misleading "resolved to the existing
  outstanding order" log line. Not fixed here (payment-flow code); recorded so it is not rediscovered.
- **staff intake** racing a delete: its `REQUIRES_NEW` audit insert can fail the FK, so that one refused
  attempt goes unaudited (the auditor swallows and warns).
A deterministic integration test pins the first branch (hold an open transaction that inserted a
`signing_request`, send the DELETE, assert it blocks, commit, assert 409).

**D6 — Blob removal after commit, by the derived key, best effort.** Delete always targets the
draft-stage keys from one helper, `DraftService.draftStageKeys(id)` (today just `drafts/{id}.pdf`, also used
by `attachDraft` for its key; `byo-document-upload` adds its keys there) — never the nullable column, which misses
objects left by an edit or a rolled-back upload. `BlobStore.delete` is idempotent (MinIO `removeObject`
succeeds for a missing key) and wraps failures like `put`/`get` (`IllegalStateException` with
`AgreementIds.redactIn(key)`). `deleteDraft` registers a `TransactionSynchronization.afterCommit` that
calls it in a try/catch; on failure it logs a warning with the redacted id and the simple class name of the
exception's **cause** (the wrapper is always `IllegalStateException`) — never the throwable, whose MinIO cause can carry the raw object name. When synchronization is not active (never in production — the method is
`@Transactional`) it deletes inline and logs a warning, so the object is never skipped silently. Unit
tests initialise synchronization explicitly and clear it in `@AfterEach`.
- Why after commit: deleting before could remove the PDF of an agreement whose delete then rolls back.
- The call runs on the request thread after the commit, so a slow MinIO delays the `204` by the MinIO
  client's timeout; the delete itself has already committed and a retried request gets 404, which the UI
  treats as gone. Accepted.
- An orphaned object after a failed delete is protected by the bucket being private (served only by
  streaming through owner-checked endpoints, never presigned) — not by its key, which is as guessable as
  the agreement id. Sweeping orphaned `drafts/` objects is appended to `anonymous-draft-retain-and-purge`,
  the row that already owns draft retention.
- The bucket is assumed **unversioned** (nothing in the repo enables versioning or object lock); on a
  versioned bucket `removeObject` only adds a delete marker. `docs/DEPLOYMENT.md` states the assumption.
- **Backups.** `docs/DEPLOYMENT.md` §8 plans nightly `pg_dump` and a blob push to off-site storage. A deleted
  draft survives in those until they rotate out, so customer-facing wording says the draft is deleted
  **from the service** and that backup copies are overwritten as backups rotate — never an unqualified
  "permanent". The DEPLOYMENT note requires the backup target to be unversioned or to expire old versions.

**D7 — V23 (part 1): `stamp_intake_audit.agreement_id` → `ON DELETE SET NULL`.** Drop
`stamp_intake_audit_agreement_id_fkey` (the default name of the inline FK in V14:92) and re-add it under
the same name with `ON DELETE SET NULL`. The audit row keeps `submitted_reference`, `outcome`,
`staff_identity_id` and `occurred_at`. V23 adds a `COMMENT ON COLUMN` restating the column's meaning: NULL
now means either "the reference resolved to nothing" or "the agreement was later deleted" (the outcome
column tells them apart). `recovery_audit` is unchanged (see Context). The new FK takes a brief SHARE ROW
EXCLUSIVE lock on both tables and validates existing rows at startup — negligible at beta scale.
- This changes the `estamp-intake` requirement "Stamp intake is audited…" (each attempt records "the target
  agreement"), so the change carries a MODIFIED delta there: the target is cleared if the owner later deletes
  the agreement.
- `submitted_reference` is stored as staff typed it (raw, truncated by `StampIntakeAuditor`), so an orphaned
  audit row is joined to `agreement_deletion.tracking_reference` after trimming and upper-casing it; the
  column comment says so.
- A freed tracking reference could in principle be reissued (31^8 space); see D10 for lookups.

**D8 — Endpoint shape.** `DELETE /api/agreements/{id}` in `AgreementController`, `@AuthenticationPrincipal
UUID identityId`, returns `ResponseEntity.noContent()`; it reads no body. `SecurityConfig`: exact matcher
`DELETE /api/agreements/*` → `authenticated()`, next to `PUT /api/agreements/*`. No session → `403` from the
default entry point (401 is only for `/api/staff/`); missing CSRF token → `403` with the `csrf` problem
type. No `RouteClassifier` entry (authenticated routes use the default class; `RouteClassifierTest:101`
already expects DEFAULT), but `AbuseLimitsIntegrationTest`'s `DEFAULT_CLASS` set must list it.

**D9 — Frontend.**
- `AgreementSummary` gains `deletable: boolean`. `MyAgreements.vue` shows Delete when `a.deletable`, never
  by status.
- `deleteAgreement(id): Promise<void>` in `src/api/agreements.ts` resolves on `204` without reading a body
  and throws `AgreementHttpError` otherwise; `PROBLEM.draftNotDeletable` in `src/api/problems.ts`.
- A small reusable `components/ConfirmDialog.vue` (title, body slot, confirm/cancel labels, `busy` prop):
  `role="dialog"`, `aria-modal`, labelled by its title, focus moves to Cancel on open and is trapped inside,
  Escape and backdrop click cancel. On cancel focus returns to the triggering Delete button; after a
  successful delete it moves to the search box, or the "new agreement" action when the list is now empty.
  CaptureForm's inline dialog is not migrated in this change.
- The dialog names the agreement by first owner, first tenant (falling back to "—" as the row does) and the
  tracking number, all by text interpolation (no `v-html`).
- Outcomes: 204 or 404 → row removed (404 also says the agreement no longer exists); 409
  `draftNotDeletable` → "no longer a draft" message and list reload; anything else → generic failure, row
  kept. Outcome messages go in a **separate notice ref** (`data-testid="list-notice"`, `role="status"`)
  that `load()` does not reset — the existing `error` ref replaces the whole list and is cleared by `load()`,
  so it cannot carry them. Focus after any outcome other than cancel goes to the notice (or, for a silent
  204, the targets above).
- No `<Teleport>`: the dialog renders inside the component so tests can find it, and the focus trap is a
  `keydown` handler on Tab (jsdom does not move focus itself).
- Wording: "Delete this draft? It will be removed from AgreementMitra and cannot be restored. Copies already
  emailed to the parties cannot be recalled." — no unqualified "permanent" (see D6 Backups).

**D10 — A record of every delete, with no party data (`agreement_deletion`).** Hard delete removes the
evidence that the agreement existed, so support could not tell "the owner deleted it" from "it never
existed". V23 creates `agreement_deletion (agreement_id UUID PRIMARY KEY, tracking_reference VARCHAR(16) NOT
NULL, owner_identity_id UUID NOT NULL, deleted_at TIMESTAMPTZ NOT NULL)` with an index on
`tracking_reference`. The agreement id as primary key makes "one record per deleted agreement" structural.
The package-private entity (`signing.agreement.AgreementDeletion`) implements `Persistable<UUID>` with
`isNew = true`, like `Agreement`, so `save` inserts without a merge SELECT. `deleteDraft` inserts it in the
same transaction as the delete: a rolled-back delete leaves no record, a committed one always has one.
- **No foreign keys**: `agreement_id` points at a row that no longer exists, and an FK to `identity` would
  block a future account erasure — which must therefore clear `owner_identity_id` here itself (stated in the
  V23 header).
- **What it is, honestly:** no party name, contact, address or money value — but it is **pseudonymous
  personal data**, not PII-free: `owner_identity_id` joins to the account's name and email, and the tracking
  reference was emailed to every party. Purpose: answering "what happened to my draft" and abuse
  investigation. Readers: staff with database access only; nothing in-product reads it. Retention: kept on
  the same three-year horizon as ToS §12's records until counsel answers the DPDP retention question, which
  is appended to `docs/COUNSEL-BRIEF.md` (b). ToS §15 gains one sentence describing it.
- **Lookups by reference** must also check `agreement`: a reference can in principle be reissued after a
  delete (31^8 space), so a hit here is "deleted" only if no live agreement holds that reference.

## Risks / Trade-offs

- [Emailed copies persist outside our control] → stated in the confirm dialog and ToS §10.
- [Orphaned blob after a failed post-commit delete still holds PII] → private bucket; logged; sweep owned
  by `anonymous-draft-retain-and-purge`.
- [Self-race with finalise/checkout yields a 500 and possibly an unpaid provider order] → D5; owner-only.
- [A non-owner who knows the id can take the row lock and add latency] → pre-existing in every
  owner-checked path; ids are random UUIDs.
- [A new table with an FK to `agreement` would turn delete into a 500] → an integration test enumerates
  every FK to `agreement` and `signer` from `information_schema` and fails on one not classified by this
  design.


## Migration Plan

Forward-only V23 applied on startup. Rollback = redeploy the previous build; V23 is compatible with it (an
FK action change is invisible to code that never deletes, and the old build never touches the new table).

## Open Questions

None. Q1 (deletion record) resolved as D10; Q2 (finalised-but-unpaid withdrawal) resolved as a non-goal
with the register row `withdraw-unpaid-finalised-agreement`.
