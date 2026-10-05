## Why

A signed-in customer's "My agreements" list keeps every draft they have ever started — abandoned
attempts, test runs, duplicates — and there is no way to remove one. The Terms of Service already
say an unpaid draft "is yours to abandon" (§10) and that the product has no deletion control
(§12); this change gives the owner that control for the one case where deletion is clean: an
unpaid draft that never reached an order.

## What Changes

- New endpoint `DELETE /api/agreements/{id}` — **authenticated, owner-only** (the same strict
  owner rule as `PUT /api/agreements/{id}`; an unowned/anonymous draft cannot be deleted through
  it). Returns `204` on success.
- **Deletable means a true draft**: no signing request exists, `payment_state` is `UNPAID`, no
  payment order exists, and the agreement is `OPEN`. Anything else is refused with `409` and a new
  problem type (`draft-not-deletable`). The rule is defined once and also drives a new `deletable`
  flag on the My agreements list, so the button and the server cannot disagree. Unknown or someone else's agreement is the same `404`,
  decided before any `409`, so the endpoint is not an ownership oracle.
- **Hard delete**: the `agreement` row and its `signer` rows are removed in one transaction. The
  draft PDF object (`drafts/{id}.pdf`, by its derived key, whether or not the row still points at it) is removed from object storage **after** the transaction
  commits, best effort — a failed blob delete leaves an unreferenced object, never a dangling
  database reference.
- `BlobStore` gains `delete(key)` (MinIO adapter).
- Flyway **V23**: `stamp_intake_audit.agreement_id` becomes `ON DELETE SET NULL`. Staff intake
  refusals against a not-yet-finalised agreement record its id, so a draft can carry these audit
  rows; the row keeps its `submitted_reference` and outcome and loses only the link. V23 also
  adds `agreement_deletion`: one PII-free row per delete (agreement id, tracking reference, owner
  identity id, time), written in the delete transaction, so support can tell "the owner deleted it"
  from "it never existed".
- Frontend: a **Delete** action on deletable rows of the My agreements screen, behind a new
  reusable accessible `ConfirmDialog` that says the draft is removed and cannot be restored and that copies already emailed to the parties cannot
  be recalled. The row disappears on success; a `409` explains the agreement is no longer a draft.
- Terms of Service §10 (and §12's "no deletion control" sentence) updated to describe the control,
  edited in `frontend/src/content/termsOfService.ts` and regenerated into `docs/TERMS-OF-SERVICE.md`.

Out of scope:
- Deleting unclaimed anonymous drafts (tracked by the `anonymous-draft-retain-and-purge` register
  row), and any purge job.
- Deleting or withdrawing finalised (even if unpaid), paid, signed or closed agreements — retention rules in
  `agreement-closure` and ToS §12 still apply to those.
- A delete action on the edit (capture) screen.
- Soft delete / undo.

## Capabilities

### New Capabilities
<!-- none -->

### Modified Capabilities
- `agreement-management`: adds the owner's delete of an unpaid draft (endpoint, deletability
  rule, what is removed) and the Delete action on the My agreements screen; modifies "Resume lists
  the caller's agreements" to add the `deletable` flag.
- `estamp-intake`: the intake audit record keeps its row, with the target agreement cleared, when
  an owner deletes the draft it referenced.

## Impact

- **Backend** (`signing` module only): `AgreementController` (new handler), `AgreementService`
  (`deletable` via `PaymentOrderQuery`), the new `AgreementDeletion` entity + repository, `DraftService`
  (delete under the agreement row's write lock), a new root seam `PaymentOrderQuery` (implemented in `payment`)
  for "has an order", `BlobStore` + `MinioBlobStore`, `ConflictException` (new kind) +
  `GlobalExceptionHandler` mapping, `SecurityConfig` allowlist (`DELETE /api/agreements/*`,
  authenticated). No `RouteClassifier` entry: authenticated routes fall to its default class, as
  `PUT /api/agreements/{id}` does.
- **Migration**: `V23__draft_deletion.sql`.
- **List DTO**: `AgreementSummaryResponse` gains `deletable` (MODIFIED list requirement).
- **Frontend**: `src/api/agreements.ts` (`deleteAgreement`), `src/api/problems.ts`,
  `components/ConfirmDialog.vue` (new), `views/MyAgreements.vue`.
- **Docs**: `frontend/src/content/termsOfService.ts` §10/§12 → regenerated `docs/TERMS-OF-SERVICE.md`;
  `docs/DEPLOYMENT.md` (unversioned bucket + backup assumption); `docs/COUNSEL-BRIEF.md` (b) (retention of
  the deletion record).
- **Signing-status FSM**: none — no `SignatureStatus` transition is added or changed; a deletable
  agreement by definition has no signing request.
- **PII / security**: the change *removes* PII (party names, contacts, property address) rather
  than moving it; the new `agreement_deletion` row holds no party data but is pseudonymous personal data
  (owner identity id + a reference the parties were emailed) — purpose, readers and retention in design D10.
  Two new log lines (debug on delete, warn on blob failure) carry the agreement id only via
  `AgreementIds.redact`. No Aadhaar/OTP/VID or secret flow is involved. CSRF applies (unsafe
  method). Sandbox + dummy data only is unchanged.
