## Context

**CR-A `google-oauth-login`** delivers an authenticated identity + opaque session; an authenticated
request now carries an **identity-id (UUID) principal**. But the `agreement` aggregate is still
**owner-less**: no `owner_identity_id`, fetched **by UUID only** (no list endpoint), and not editable
after create (only draft re-generate / upload, frozen once a signing request exists via
`DraftService.attachDraft()` -> `SigningRequestQuery.existsForAgreement` -> `409 draftFrozen`).

This CR attaches **ownership, save, resume, and edit** to that identity, keeping login **optional**:
create + draft-upload + capability read stay anonymous; only save / list / edit require a session. It
mirrors the parked `mobile-otp-auth` change's ownership decisions (its D5/D7/D12/D13) so the models
converge regardless of credential.

**Grounding (verified against the code):**
- `backend/.../signing/agreement/Agreement.java` -- app-assigned UUID, `Persistable<UUID>`, **no owner
  field**; holds template UUIDs as plain values (never a `documents.template` type) -- the pattern we copy
  for `owner_identity_id` (a UUID, never an `identity` type).
- `backend/.../signing/agreement/AgreementService.java` / `AgreementRepository.java` -- `create` + `findById`;
  bare `JpaRepository` with no custom finders. We add `claim`, `listOwnedBy`, `update`, and an owner finder.
- `backend/.../signing/agreement/DraftService.java` + `signing/SigningRequestQuery.java` -- the draft-freeze
  gate. Edit reuses `existsForAgreement`; the status projection extends this seam to return a status.
- `backend/.../signing/api/AgreementController.java` -- `@RequestMapping("/api/agreements")`, `POST` create +
  `GET /{id}`; **no list, no edit**. We add `claim`, `GET` list, `PUT`.
- `backend/.../SecurityConfig.java` -- CR-A added the session filter + `/api/auth/*`; we add three
  agreement matchers, ordered before the existing `/api/agreements/*` permits.
- Latest migration after CR-A is `V11`; this CR is **`V12`**.

Constraints (unchanged): Java 21 + Spring Boot 3.5.x + Spring Modulith; records for DTOs; constructor
injection; package-private by default, `public` only on the module API; Flyway single source
(`ddl-auto: validate`); keep `ModularityTests` green; **sandbox + dummy data only**; anti-mass-assignment.

## Goals / Non-Goals

**Goals:**
- Keep **anonymous drafting fully usable with no login** (create + draft-upload + capability read stay open).
- **Save (claim)** an unowned draft into the caller's identity; **resume** via a list of the caller's
  agreements (in-progress and signed) with a derived status; **edit** an in-progress one.
- Owner-scope a draft's reads only **after** claim; edit only while pre-signing-request.
- No enumeration oracle; ownership server-sourced only.
- Unit-testable core (claim state machine, status projection, edit-window gate) with no Spring context;
  integration-tested endpoints on Testcontainers Postgres.

**Non-Goals:**
- No login mechanism (CR-A owns it). No ownership-authZ for signing/stamping (separate follow-up). No
  retain-and-purge of unclaimed drafts (tracked separately). No edit after signing is requested.

## Decisions

### D1: Agreement-to-identity linkage is a UUID set at claim -- no cross-module type leak

`agreement` gains a **nullable** `owner_identity_id uuid` column referencing CR-A's `identity(id)`.
**Create leaves it null** (anonymous draft); it is set only by **claim** (D2). The authenticated identity
id is resolved in the `signing.api` layer (from the Security context CR-A populates) and passed **inward**
to `AgreementService.claim(agreementId, ownerIdentityId)` / `.update(...)`. `GET /api/agreements` filters
by it; `GET /api/agreements/{id}` and `PUT` use it for owner-vs-capability access (D5). The `signing`
module thus holds only a **UUID**, never an `identity` type -- Modulith stays clean, matching how
`Agreement` already holds template UUIDs. Owner is **never** a request field (anti-mass-assignment).
Nullable by design: an unclaimed draft is legitimately owner-less. **Alternative rejected:** a Modulith
`ApplicationEvent` to associate owner asynchronously -- overkill; the id is known synchronously at claim.

### D2: Claim == Save, guarded, non-oracular

`POST /api/agreements/{id}/claim` (authenticated) sets `owner_identity_id` to the caller's identity **only
if currently unowned**. Idempotent for the same owner (re-claim returns `200`). Unknown id, or one owned by
a **different** identity, returns `404` -- same response for both, so claim is not an ownership/existence
oracle. The service does the check-and-set in one transaction with the row locked (or a conditional
`UPDATE ... WHERE owner_identity_id IS NULL`) so two concurrent claims cannot both win. Owner is from the
session, never the body. **Alternative rejected:** implicit claim by passing draft ids at login -- an
explicit `claim` is clearer and testable, and lets the SPA claim exactly the draft in hand.

### D3: Resume == `GET /api/agreements` lists mine with a *derived* status and `editable` flag

The list returns the caller's agreements (filtered by `owner_identity_id`), most-recent first, each as a
summary `{ id, propertyAddress, monthlyRent, startDate, endDate, duration, createdAt, status, editable }`.
**`status` is derived, not stored** (the agreement stays status-less): the projection joins to the signing
side via the `signing.SigningRequestQuery` seam (extended to return the `SignatureStatus` for an agreement,
or none) and maps: no request -> `DRAFT` (`editable = true`); a non-terminal request
(`PDF_GENERATED`/`STAMPED`/`SIGN_REQUESTED`) -> `IN_PROGRESS` (`editable = false`, frozen); `SIGNED` ->
`SIGNED`; `EXPIRED` -> `EXPIRED`; `FAILED`/`STAMP_FAILED` -> `ACTION_NEEDED`. So a user sees **both
in-progress and signed** in one list, and `editable` drives whether "Edit" is offered. The seam hands only
plain values across the boundary (a status enum + a UUID), never an aggregate. **Alternative rejected:**
persisting a status column on `agreement` -- duplicates the FSM's source of truth and risks drift; a read
projection cannot drift.

### D4: Edit == `PUT /api/agreements/{id}`, owner-scoped, pre-signing-request only, validation reused

`PUT /api/agreements/{id}` (authenticated) **fully replaces** the mutable terms of an agreement the caller
**owns**: property address, monthly rent, security deposit, start/end dates, and the **party list** (same
body shape as create, minus server-managed fields). It is allowed **only while no signing request exists**
-- reusing the exact draft-freeze gate (`SigningRequestQuery.existsForAgreement`): if a request exists,
`409` (frozen), the same rule `DraftService` enforces for the draft. Access is owner-scoped: a non-owner or
unknown id is `404` (never `403`, no oracle); an **unowned** (anonymous) draft is **not** editable via this
route (no owner to check against -- the anonymous SPA edits its working set client-side before Save,
unchanged). Validation and mapping **reuse the create path**: the same validator (blank-name, role-set,
duplicate-contact, positive-rent, end-after-start, party-max) and the same anti-mass-assignment (id,
`owner_identity_id`, `createdAt`, derived duration never client-settable). Parties are **replaced
wholesale** (delete-and-reinsert children in the transaction) -- simplest correct semantics for a full-
document edit; child ids are server-reassigned (safe pre-signing: no invitee exists yet). A successful edit
**clears the pinned draft reference** (`draft_pdf_key` + the template pin) so the next `POST /{id}/document`
regenerates from the edited terms; a stale draft is never served as if it matched the new terms.
**Alternative rejected:** `PATCH` field-level merge -- more endpoints/edge cases (partial party edits,
add/remove-by-id) for a form submitted whole; a full `PUT` matching the create body is simpler and matches
the SPA's "reopen the form, resubmit" UX. **Alternative rejected:** allowing edit after signing is requested
-- out of scope; the freeze is a safety invariant.

### D5: Read scoping -- capability-open while unowned, owner-scoped once claimed

`GET /api/agreements/{id}` for an **unowned** agreement stays readable by any caller presenting the id (the
unguessable id is a bearer capability, unchanged from today). Once **claimed**, it is readable only by its
owner; a non-owner or unauthenticated caller gets `404` (indistinguishable from unknown, so ownership can't
be probed). The check is in the handler (not the filter chain), because it depends on the row's
`owner_identity_id`, which the chain cannot see. **Alternative rejected:** making the read always require
auth -- breaks the anonymous resume/share of a draft link, which is a product goal.

### D6: SecurityConfig -- authenticate save/list/edit, ordered before the broad permits

Add `authenticated()` for exactly `GET /api/agreements` (list mine), `POST /api/agreements/*/claim`, and
`PUT /api/agreements/*`. **Matcher order matters:** these three authenticated matchers must precede the
broader `permitAll` for `POST`/`GET /api/agreements/*` so the capability read + anonymous create stay open
while list/claim/edit are gated. CR-A's session filter + `/api/auth/*` permits and the `/api/signing/*` +
webhook permits are left as-is. **Alternative rejected:** method-level `@PreAuthorize` -- the filter chain is
already the single authZ surface; keep it there. (Owner-vs-capability read scoping, D5, is enforced in the
handler, not the chain -- the chain can't see the row's owner.)

### D7: `V12__agreement_owner.sql` -- one nullable FK column

Forward-only, never edits V1..V11: `alter table agreement add column owner_identity_id uuid references
identity(id)` (nullable) with an index on `owner_identity_id`. Depends on CR-A's `V11` having created
`identity`. Boot's default snake_case naming lines up with `ddl-auto: validate`; a boot-and-validate test
catches drift. No backfill (existing rows keep NULL). **Alternative rejected:** a join table for ownership
-- one-to-many owner-to-agreements needs only a column; a join table adds nothing.

### D8: Frontend -- My Agreements, Save, Edit; drafting stays no-login

On top of CR-A's auth store, add a **My Agreements** list (`GET /api/agreements`, badges the derived status,
offers "Edit" when `editable`, "View/Download" when signed, most-recent first), a **Save** action (claim) on
a drafted agreement, and an **Edit** flow that loads `GET /api/agreements/{id}` into the existing capture form
and submits `PUT`. A lightweight guard redirects the authenticated views to login when there is no session.
`POST /api/agreements` (anonymous), the capture form, and preview require **no login**. **Alternative
rejected:** forcing vue-router now -- the existing view-switch (extended in CR-A) can carry this.

## Risks / Trade-offs

- **Un-owned PII lives server-side** -- anonymous drafts hold party names/emails with no owner and no purge
  job in this CR (deferred). Mitigated for now by the unguessable-id capability (never listed to anyone) but
  unbounded in time until the retain-and-purge follow-up lands.
- **Capability id as bearer (while unowned)** -- anyone holding a draft's UUID can read it until it is claimed;
  accepted (a draft link is meant to be resumable), and access tightens to owner-only on claim. Unchanged from
  today.
- **Edit replaces parties wholesale** -- child ids are reassigned on edit, so any external reference to a party
  id is invalidated; acceptable pre-signing (edit is blocked once a signing request exists, so no invitee
  exists yet).
- **Derived status adds a read-time join** -- the list projection queries the signing side per request; fine at
  this scale, and correctness (no drift) beats caching a status column. Revisit only if the list grows hot.
- **Ordering dependency on CR-A** -- `V12` and the identity-id principal require CR-A applied first; a deploy
  that skips CR-A fails fast (FK to a missing table, unresolved principal). Documented in Migration Plan.

## Migration Plan

Forward-only Flyway `V12__agreement_owner.sql` after CR-A's `V11`: one nullable column + index on
`agreement`. No backfill (existing rows keep `owner_identity_id` NULL). Rollback in this sandbox phase: drop
the column and remove V12 (`flyway.clean` stays disabled). **Hard dependency: CR-A's `V11` (the `identity`
table) must be applied first** -- the FK references it. No deployed consumers.

## Open Questions

- **Unclaimed-draft retention/purge** -- out of scope here; confirm it rides with the anonymous-capture work
  rather than this CR.
- **Should the list paginate?** -- proposing an unpaginated most-recent-first list for the sandbox (a user has
  few agreements); confirm whether pagination is wanted now or deferred.
- **Signed-agreement download surface** -- the list marks `SIGNED` read-only; confirm whether "Download" reuses
  the existing signed-artifact retrieval or needs a new owner-scoped endpoint (likely a tiny follow-up).
