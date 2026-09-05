> **STATUS: PARKED (2026-07-12) — not on the near-term path; do not start.** Optional login /
> save-resume is deprioritized. This blocks nothing shipping now: the end-user templating flow
> (CR-2/M0–M6) is anonymous, and Admin authoring (M7), which needs the admin role that would ride on
> this, is itself parked as a consequence. Unpark only when save-resume or admin authoring becomes a
> real priority.

## Why

The signing backend is complete, but an agreement is owned by no one and there is no way
for a user to come back and find the one they started yesterday. We want the lowest
possible friction: a visitor can **draft a rental agreement anonymously** — no signup
wall — and only needs to **log in when they want to save it and resume later** (across
sessions or devices). Login is therefore a **nice-to-have convenience layer**, not a gate.

This CR adds that layer: **mobile-number + OTP** authentication, plus the ability to
**claim** an anonymous draft into a durable identity. From the landing screen a user can
go straight into drafting; at any point they may log in (enter mobile number, receive a
one-time code, verify) and **Save**, which binds the current draft to their mobile
identity. On any later login they see and resume their saved agreements.

**How this relates to the roadmap.** `docs/ROADMAP.md` (Track A) commits to a "no-login
self-serve UI". This CR **keeps self-serve anonymous drafting** and only **adds optional
login** for save/resume — so the no-login promise is preserved for the create path, not
reversed. It also gives the roadmap's planned ownership-authZ a subject: once a draft is
claimed, ownership checks have an identity to compare against.

**Sequencing (this is a nice-to-have, not the foundation).** Because login is optional,
the **anonymous capture and template CRs are the priority** and do not depend on this one:
- **CR-1** — guided capture: tenant and owner each with first, last, and father name plus
  current address; property; rental value; advance; start and end dates with a computed
  duration (extends `agreement-management`).
- **CR-2** — a searchable rental-agreement template catalog.
- **CR-3** — template rendering and preview (implements the `documents` module).
- **CR-4 (this change)** — optional mobile-OTP login to **save/resume**, and claim of an
  anonymous draft. Mostly independent of CR-1..3; can land after them.

## What Changes

- Introduce **mobile-OTP authentication** as a new capability in a dedicated `identity`
  module (`in.agreementmitra.identity`), activating that previously-stub module. A mobile
  number in E.164 form is the identity; a `MobileIdentity` aggregate is created on first
  successful verification and reused thereafter.
- Add the login handshake (all unauthenticated):
  - `POST /api/auth/otp/request` — body `{ mobile }`; creates a short-lived, single-use
    OTP challenge, dispatches the code via an `OtpSender` seam (sandbox sender: no real
    SMS, never logs the code or the mobile number), and returns `202 Accepted` with a
    challenge id. The response is identical for known and unknown numbers.
  - `POST /api/auth/otp/verify` — body `{ challengeId, otp }`; on a correct, unexpired,
    unconsumed code within the attempt limit, consumes the challenge, find-or-creates the
    `MobileIdentity`, opens a session, and returns `200 OK` with an opaque session value.
  - `POST /api/auth/logout` — revokes the caller's session (`204`).
- **Anonymous drafting stays open**: `POST /api/agreements` and
  `POST /api/agreements/{id}/draft` **require no authentication**. An agreement created
  anonymously has `owner_identity_id = NULL` and is addressed by its **unguessable UUID**
  (a capability-style handle, as today). This is the default, no-login path.
- Add **Save == claim**: `POST /api/agreements/{id}/claim` (**authenticated**) associates
  an **unowned** draft with the caller's `MobileIdentity`. It is idempotent for the same
  owner; an unknown id, or one already owned by a different identity, returns `404` (no
  ownership oracle). Owner is server-sourced from the session, never a client field
  (anti-mass-assignment).
- Add **Resume == list mine**: `GET /api/agreements` (**authenticated**) returns a summary
  of the caller's **claimed** agreements, most-recent first — the "review earlier /
  continue in-progress" surface.
- **Read scoping**: `GET /api/agreements/{id}` for an **unowned** draft is readable by
  anyone holding the id (capability, unchanged from today); once **claimed**, it becomes
  **owner-scoped** (a non-owner or unauthenticated caller gets `404`, indistinguishable
  from unknown).
- Introduce an **opaque, server-side session** (random value returned once, persisted only
  as a hash; a Spring Security filter authenticates by hashing the presented value and
  looking up a live, unexpired session; revocable and expiring; no PII in the value).
- Add Flyway `V7__mobile_identity_auth.sql`: `mobile_identity`, `otp_challenge`,
  `auth_session` tables, plus a nullable `owner_identity_id` FK column on `agreement`
  (set on **claim**, not create).
- **Rate-limit** OTP request and verify per mobile and per challenge.
- **Retain-and-purge** unclaimed anonymous drafts: because a draft persists server-side
  before any login, abandoned unclaimed drafts are cleaned up after a retention window
  (see Open Questions).
- Frontend: a **landing** screen ("Rental Agreement" leads straight into anonymous
  drafting; "Sign In / Register" is optional), a **mobile + OTP** screen, a **Save**
  action (claim), and a **My Agreements** list (resume). Drafting works with no login.

Only `GET /api/agreements` (list) and `POST /api/agreements/{id}/claim` become
authenticated. Create, draft-upload, and capability reads stay anonymous. The signing FSM,
`EsignProvider`/webhook flow, stamping, storage, and the existing
`/api/signing/*/request` permit are **untouched** (ownership-authZ for signing remains a
separate follow-up).

## Capabilities

### New Capabilities
- `mobile-otp-auth`: mobile-number + OTP authentication as an optional identity layer —
  the OTP challenge lifecycle (request, verify, consume), the `MobileIdentity` aggregate,
  opaque server-side sessions (issue, authenticate, revoke), rate-limiting, and the
  sandbox `OtpSender` seam.

### Modified Capabilities
- `agreement-management`: agreements may be **created anonymously** (owner null,
  capability-addressed) and later **claimed** by an authenticated identity ("Save").
  Reads are capability-open while unowned and owner-scoped once claimed; a new
  `GET /api/agreements` lists the caller's claimed agreements; a new
  `POST /api/agreements/{id}/claim` performs the save. The create body and its
  anti-mass-assignment guarantee are unchanged (owner is server-sourced, never a client
  field). Unclaimed drafts are retained then purged.

## Impact

- **New module** `in.agreementmitra.identity` (activated from stub): `MobileIdentity`
  aggregate + repository, `OtpChallenge` + `AuthSession` entities, an application service,
  the `OtpSender` seam (+ sandbox sender), a rate-limiter, and the public `identity.api`
  surface — mirroring the `signing` module's internal/`api` split. `ModularityTests` stays
  green.
- **Security**: `/api/auth/otp/*` stays `permitAll`; `POST /api/agreements`,
  `POST /api/agreements/{id}/draft`, and `GET /api/agreements/{id}` **stay `permitAll`**
  (anonymous drafting); only `GET /api/agreements` and `POST /api/agreements/{id}/claim`
  become `authenticated()`. A session-authentication filter is added. The
  `/api/signing/*/request` permit is left as-is.
- **Cross-module linkage**: the `signing` module gains an `owner_identity_id` (UUID) set at
  **claim**; it holds only a UUID, never an `identity` type (Modulith-clean).
- **Schema**: new `V7__mobile_identity_auth.sql` (forward-only; never edits V1–V6). Three
  new tables + one nullable FK column on `agreement`. JPA stays `ddl-auto: validate`.
- **Retention job**: a scheduled purge of unclaimed anonymous drafts past the retention
  window (small, reuses the existing scheduling setup).
- **Frontend**: landing / OTP / save / my-agreements views + an auth store in `src/api/`;
  anonymous drafting requires no login.
- **No** change to: the signing FSM, `EsignProvider`/`LeegalityEsignProvider`, webhook
  intake, stamping, object storage, the reconciliation job, or the signing permit.

## PII / security review checklist

- **Introduces or moves Aadhaar/OTP/VID/PII or secrets?** **Yes — login OTP codes and
  mobile numbers**, and it changes when **party PII** (signer names/emails, and in later
  CRs addresses) is held **without an owner**. The OTP is our own login credential (not an
  Aadhaar/eKYC code). No Aadhaar number, virtual id, biometric, or government identifier is
  collected or stored.
- **How redacted/secured?**
  - The **OTP code** is stored only as a salted, keyed hash, never returned by any
    endpoint, never logged at any level, and never placed in an exception message; verify
    is constant-time and attempt-capped; the challenge is single-use and short-lived.
  - The **mobile number** is stored to key the identity but redacted to the last two
    digits in every log line; never echoed beyond the caller's own session.
  - The **session value** is high-entropy random, returned once, persisted only as a hash,
    server-side, revocable, and expiring.
  - **Anonymous drafts** hold party PII with no owner; they are addressed only by an
    **unguessable UUID** (not enumerable), never listed to anyone but a claiming owner, and
    **purged after a retention window** if never claimed — bounding how long un-owned PII
    lives server-side.
  - **Enumeration** is denied: OTP-request responses are uniform; unknown, not-yours, and
    already-claimed all return `404`; `429` is uniform.
  - Secret material (hash pepper, any future SMS credential) comes from **env vars only**.
- **Sandbox + dummy data only?** Preserved. The default `OtpSender` sends no real SMS; no
  live provider, no real PII, no production credentials. A dev-only fixed code is env-gated,
  off by default, and inert outside the `local` profile.
- **Signing-status FSM transitions touched?** **None.**
- **Async signing / webhook flow touched?** **None** — no signing sequence diagram
  required. (An OTP request, verify, and session handshake sequence is in `design.md`.)