## Why

Once a user can sign in with Google (**CR-A `google-oauth-login`**), an authenticated identity exists
-- but agreements are still owned by no one. An agreement is keyed only by its server UUID, fetched
**by id only** (no "my agreements" list), and cannot be edited after create (the only post-create
mutations are draft re-generate / upload, frozen once a signing request exists). So a logged-in user
still has no home for their work.

This CR -- the **second of two** for optional Google login -- gives a logged-in user that home:

- **Save (claim):** bind an anonymous draft to the caller's identity.
- **Resume (list mine):** `GET /api/agreements` returns **all the caller's agreements -- in-progress
  and signed -- in one list**, each with a derived status.
- **Edit in-progress:** `PUT /api/agreements/{id}` fully edits an owned agreement's terms and parties,
  **only while no signing request exists** (reusing the existing draft-freeze rule).

Login stays **optional**: anonymous drafting is untouched (`POST /api/agreements` and draft-upload need
no auth; an anonymous draft has `owner_identity_id = NULL` and is addressed by its unguessable UUID).
Google login only unlocks Save / resume / edit on top.

**Depends on CR-A.** This CR consumes CR-A's authenticated **identity-id principal** (from the session
filter) and its `identity` table (the `owner_identity_id` FK references it). CR-A must be applied first;
this CR's migration is `V12` (CR-A shipped `V11`).

## What Changes

- Add a nullable, server-managed `owner_identity_id` (UUID) to `agreement`, referencing `identity(id)`.
  **Create leaves it null** (anonymous draft); it is set only by **claim**. Owner is **server-sourced
  from the session, never a request field** (anti-mass-assignment, consistent with existing
  id/createdAt/duration handling).
- Add **Save == claim**: `POST /api/agreements/{id}/claim` (**authenticated**) binds an **unowned**
  draft to the caller's identity. Idempotent for the same owner; an unknown id, or one already owned by
  a **different** identity, returns `404` (no ownership oracle). The check-and-set is atomic.
- Add **Resume == list mine**: `GET /api/agreements` (**authenticated**) returns the caller's agreements
  -- **both in-progress and signed** -- most-recent first, each as a summary with a **derived status**
  (`DRAFT` / `IN_PROGRESS` / `SIGNED` / `EXPIRED` / `ACTION_NEEDED`) and an `editable` flag.
- Add **Edit in-progress**: `PUT /api/agreements/{id}` (**authenticated, owner-scoped**) fully replaces
  the mutable terms (property, rent, deposit, start/end dates, party list) of an agreement the caller
  owns, **only while no signing request exists** -- reusing the create-time validation and the
  anti-mass-assignment guarantee. If a signing request exists the agreement is frozen: `409` (the same
  rule the draft obeys). A non-owner or unknown id is `404`. A successful edit clears the pinned draft
  reference so the next `POST /{id}/document` regenerates a fresh PDF.
- **Read scoping**: `GET /api/agreements/{id}` for an **unowned** draft stays readable by anyone holding
  the id (capability, unchanged); once **claimed**, it becomes **owner-scoped** (a non-owner or
  unauthenticated caller gets `404`, indistinguishable from unknown).
- Update `SecurityConfig`: add `authenticated()` for `GET /api/agreements`,
  `POST /api/agreements/*/claim`, and `PUT /api/agreements/*`, **ordered before** the broad
  `/api/agreements/*` permits so list/claim/edit are gated while anonymous create and capability read
  stay open. CR-A's filter + handshake permits are unchanged.
- Add Flyway `V12__agreement_owner.sql`: `alter table agreement add column owner_identity_id uuid
  references identity(id)` (nullable, indexed). Depends on CR-A's `V11` (the `identity` table).
- Frontend: a **My Agreements** list (`GET /api/agreements`, status badges, "Edit" when `editable`,
  "View/Download" when signed), a **Save** action (claim) on a drafted agreement, and an **Edit** flow
  that reopens an in-progress agreement in the capture form and submits `PUT`. A lightweight guard sends
  the authenticated views to login when there is no session. Anonymous drafting is unchanged.

**Out of scope (deferred, tracked):**
- Ownership-authZ for the **signing** endpoints and stamping (the `/api/signing/*/request` permit is
  left as-is) -- a separate follow-up.
- Retain-and-purge of unclaimed anonymous drafts (a PII-minimization job; tracked with anonymous-capture
  work, not folded here).
- Editing an agreement after signing is requested (re-stamp/re-sign) -- the freeze is a safety invariant.

## Capabilities

### Modified Capabilities
- `agreement-management`: agreements may be **created anonymously** (owner null, capability-addressed)
  and later **claimed** ("Save") by an authenticated identity. A new `GET /api/agreements` lists the
  caller's agreements -- **in-progress and signed** -- with a derived status; a new
  `PUT /api/agreements/{id}` lets an **owner fully edit an in-progress (pre-signing-request) agreement**,
  reusing create-time validation and anti-mass-assignment; reads become owner-scoped once claimed. The
  create body and its anti-mass-assignment guarantee are otherwise unchanged.
- `backend-security-baseline`: `GET /api/agreements`, `POST /api/agreements/*/claim`, and
  `PUT /api/agreements/*` become `authenticated()`, ordered before the broad `/api/agreements/*` permits;
  anonymous create, draft-upload, and capability read stay `permitAll`. The signing and webhook permits,
  and CR-A's filter + handshake permits, are unchanged.

## Signing-status FSM

**No new states and no changed transitions.** The `SignatureStatus` FSM
(`PDF_GENERATED -> STAMPED | STAMP_FAILED`, `STAMPED -> SIGN_REQUESTED`,
`SIGN_REQUESTED -> SIGNED | FAILED | EXPIRED`) is untouched and still lives on the `SigningRequest`
aggregate. Ownership (`owner_identity_id`) is **orthogonal** to the FSM: it is set at **claim** and never
gates a transition. Editing an in-progress agreement is permitted **only in the pre-signing-request
window** -- the same boundary the existing draft-freeze already enforces (`DraftService` via
`SigningRequestQuery.existsForAgreement`); once a signing request exists, edit is frozen (`409`), exactly
as the draft is today. The list's **derived status** is a read-only **projection** of the existing FSM (no
new state is persisted on the agreement).

## PII / security checklist

- **Introduces or moves Aadhaar/OTP/VID/PII or secrets?** **No new PII category and no new secret.** This
  CR associates existing agreement/party PII (names, emails) with an identity id and scopes who can read
  it; it collects nothing new. The identity/session/credential data and the Google secret are owned by
  CR-A. No Aadhaar/OTP/VID.
- **How redacted/secured?**
  - Ownership is **server-sourced** from the session; `owner_identity_id` is **never** a request field
    (anti-mass-assignment), so a client cannot claim or reassign ownership by body.
  - **Enumeration is denied**: `claim`, owner-scoped read, and non-owner edit all return `404` for both
    "unknown id" and "not yours" (never `403`), so ownership cannot be probed.
  - Claiming is an **atomic** set-if-unowned so two concurrent claims cannot both win.
  - Access **tightens on claim**: an unowned draft is a bare-id capability (unchanged), but a claimed
    agreement is readable only by its owner.
  - No new logging of PII; the existing signer-PII redaction discipline is unchanged.
- **Sandbox + dummy data only?** Preserved -- dummy data only; no new external flow.
- **Signing-status FSM transitions touched?** **None** (status is read-only projected).
- **Async signing / webhook flow touched?** **None** -- no signing sequence diagram required.
