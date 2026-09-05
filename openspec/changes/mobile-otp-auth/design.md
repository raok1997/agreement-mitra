## Context

The signing vertical slice is complete and the `agreement` aggregate exists, but it is
**owner-less**: `agreement` rows are keyed only by a server UUID, and there is no way for a
returning user to find "my" agreements.

We want the lowest-friction flow: **anonymous drafting is first-class** (no signup wall),
and **login is an optional convenience** used only to **save** a draft and **resume** it
later. This CR adds that optional layer -- a **mobile number authenticated by OTP** plus a
**claim** step that binds an anonymous draft to an identity. It does **not** gate drafting:
create, draft-upload, and capability reads stay anonymous; only "list my saved agreements"
and "save (claim)" require a session. This keeps the roadmap's no-login self-serve promise
for the create path while adding save/resume on top. Because login is optional, this is a
**nice-to-have** that the anonymous capture and template CRs do not depend on.

Constraints: Java 21 plus Spring Boot 3.5.x plus Spring Modulith modular monolith;
records for DTOs; constructor injection; package-private by default, `public` only on the
module API; Flyway is the single schema source (`ddl-auto: validate`); keep
`ModularityTests` green; **sandbox plus dummy data only**; never log OTP codes or full
mobile numbers; secrets via env only.

## Goals / Non-Goals

**Goals:**
- Keep **anonymous drafting fully usable with no login** (create + draft-upload +
  capability read stay open).
- Mobile-number plus OTP login: request a code, verify it, get an authenticated session.
- A durable `MobileIdentity` so a returning user can **save (claim)** a draft and later
  **resume** it via a list of their claimed agreements.
- Persist anonymous drafts with **no owner** (addressed by an unguessable id); owner-scope
  a draft's reads only **after** it is claimed; retain-then-purge unclaimed drafts.
- Strong handshake security: hashed one-time codes, single-use plus TTL plus attempt cap,
  constant-time compare, opaque hashed sessions, rate-limiting, no enumeration.
- Unit-testable core (code hashing, challenge lifecycle, session resolution) without a
  Spring context; integration-tested endpoints on Testcontainers Postgres.

**Non-Goals:**
- No KYC, DigiLocker, or Aadhaar eKYC (the `identity` module's future charter; this CR
  only adds login). No real SMS provider (sandbox sender only; real one is a later swap).
- No password or email login, no social login, no multi-device session management UI.
- No change to the signing FSM, `EsignProvider`, webhook intake, stamping, or storage.
- No CR-B, CR-C, or CR-D behaviour (rich capture, templates, rendering) -- those are
  separate.
- No distributed rate-limiter or shared session store (single-instance sandbox; multi-
  instance hardening is a flagged follow-up).

## Decisions

### D1: Auth lives in a new `identity` module, activated from its stub

Mobile-OTP authentication goes in `in.agreementmitra.identity` (currently a
`package-info`-only stub reserved for "KYC/DigiLocker"). Rationale: "who is this user"
is exactly the identity module's charter; login is the first identity concern and KYC
folds into the same module later. Structure mirrors `signing`: internal domain packages
(`identity.otp`, `identity.session`) plus a public `identity.api` (`AuthController`,
request and response records). Only the `api` package is module-public; entities,
repositories, the `OtpSender`, and the rate-limiter stay package-private and Modulith-
internal. **Alternatives rejected:** a separate `auth` module (splits identity across two
modules for no benefit); auth inside `signing` (wrong boundary -- signing must not own
identity). `ModularityTests` stays green because cross-module contact is only the
authenticated **identity id (a UUID)** handed inward at the API layer (see D5).

### D2: The mobile number (E.164) is the identity key

A `MobileIdentity` aggregate carries an app-assigned UUID (factory, like `Agreement` --
stable identity from birth, `Persistable<UUID>` plus a transient `isNew` to skip the
phantom SELECT) and the mobile number **normalized to E.164** with a UNIQUE constraint.
Normalize at the boundary (reject non-E.164 with 400) so the same human number never
yields two identities. Find-or-create on successful verify: first login creates it, later
logins reuse it. **Alternative rejected:** a surrogate "user" separate from the number --
no value yet, and the number is the natural key.

### D3: OTP codes are stored as a peppered one-way hash, never plaintext

`OtpChallenge` stores a keyed hash of the code (HMAC-SHA256 of the code under a server
pepper taken from an env var, never committed), plus `expires_at`, `attempts`, and
`consumed_at`. Verify recomputes the hash and compares with `MessageDigest.isEqual`
(**constant-time**). Rationale: a low-entropy numeric code must never be recoverable from
the DB; the env pepper defeats an offline dictionary attack if the table leaks, while
short TTL, attempt cap, and rate-limit (D6) defeat the online attack. **Alternatives:**
plaintext (rejected outright); bcrypt or argon2 (rejected -- deliberate slowness buys
little for a numeric space already bounded by attempt and TTL limits, and adds latency to
every verify). The code length and TTL are env-tunable (safe sandbox defaults). The code
is generated with a cryptographically strong RNG and is **never** returned or logged (D9).

### D4: Sessions are opaque, server-side, hashed, and revocable

On successful verify the server mints a high-entropy random session value (256-bit,
strong RNG), returns it **once** to the SPA, and persists an `auth_session` row holding
only a SHA-256 hash of the value (plus `identity_id`, `expires_at`, `created_at`,
`last_seen_at`). A `OncePerRequestFilter` reads the value from the `Authorization: Bearer`
header, hashes it, looks up a live unexpired session in constant time, and sets the
Spring Security `Authentication` (principal is the identity id). **Alternatives rejected:**
a self-contained signed JWT (not server-revocable; tempts encoding PII; key-management
overhead) -- an opaque hashed handle is revocable, leaks nothing, and matches this
platform's audit-first ethos. **Deferred hardening:** delivering the session as an
`HttpOnly; Secure; SameSite` cookie (with CSRF defence) instead of a bearer value in SPA
memory -- noted as a follow-up (bearer-in-memory is acceptable for the sandbox; XSS risk
flagged in Risks).

### D5: Agreement-to-identity linkage is a UUID set at claim -- no cross-module type leak

`agreement` gains a **nullable** `owner_identity_id uuid` column. **Create leaves it
null** (anonymous draft); it is set only by the **claim** step (D13), where the
authenticated identity id is resolved in the `api` layer (from the Security context) and
passed **inward** to `AgreementService.claim(agreementId, ownerIdentityId)`.
`GET /api/agreements` filters by it; `GET /api/agreements/{id}` uses it to decide
capability-vs-owner access (D12). The `signing` module thus holds only a **UUID**, never an
`identity`-module type -- so Modulith boundaries stay clean (no `signing` to `identity`
package dependency). Owner is **never** a request field (anti-mass-assignment preserved,
consistent with the existing id and createdAt handling). The column is nullable by design,
not just for legacy rows: an unclaimed draft is legitimately owner-less. **Alternative
rejected:** a Modulith `ApplicationEvent` to associate owner asynchronously -- overkill;
the id is known synchronously at claim time.

### D6: Rate-limiting -- per-mobile request cap plus per-challenge attempt cap, windowed

Two bounds: (a) a per-mobile-number cap on OTP-request within a rolling window (SMS-pump
guard), and (b) a per-challenge attempt cap on verify (brute-force guard), enforced
alongside the challenge's own `attempts` counter. Sandbox implementation is a simple
in-process or DB-backed counter with env-tunable limits and window. Over-limit returns
`429 Too Many Requests`, uniformly (no enumeration signal). **Accepted limitation:** the
counter is single-instance; a multi-instance deployment needs a shared store (a Redis or
DB lease) -- flagged, not built (sandbox is single-instance).

### D7: Uniform responses -- no account-enumeration, no existence oracle

`POST /api/auth/otp/request` returns `202` with a challenge id **identically** for known
and unknown numbers (the challenge is created either way; identity is only materialized
at verify). Owner-scoped reads return `404` for both "unknown id" and "not yours" -- never
`403` -- so ownership can't be probed. `429` is uniform. These are spec requirements, not
just implementation notes.

### D8: `OtpSender` seam plus sandbox sender; dev-only fixed code is env-gated and off

Delivery is behind an `OtpSender` interface (mirrors `EsignProvider` and `StampProvider`).
The default `SandboxOtpSender` performs **no** network call and logs nothing about the
code or number. A real SMS adapter is a one-adapter swap taking its credential from env.
For local manual testing, a **dev-only fixed code** may be enabled via an env flag that is
**off by default and inert outside the `local` profile** -- documented as dev-only, never
a production path. (This is the one place a known code exists; it is guarded so it cannot
leak into a real profile.)

### D9: PII-logging hygiene under module-wide DEBUG

`logging.level: in.agreementmitra: DEBUG` is on. Therefore: OTP codes are **never**
logged at any level; mobile numbers are **redacted** to the last two digits by a shared
helper before any log line; `MobileIdentity`, `OtpChallenge`, and `AuthSession` get
**id-only `toString()`** (no number, no hash, no session value). No request body is echoed
to logs. Reuses the redaction discipline already established for signer PII and Leegality
document ids.

### D10: SecurityConfig -- permit the handshake and anonymous drafting, authenticate only save/list

Update the root `SecurityConfig`: `permitAll` for `POST /api/auth/otp/request`,
`POST /api/auth/otp/verify`, and logout (no-ops without a session); register the
session-authentication filter. **Keep anonymous drafting open** -- `POST /api/agreements`,
`POST /api/agreements/{id}/draft`, and `GET /api/agreements/{id}` stay `permitAll`. Add
`authenticated()` for exactly the two new save/resume paths: `GET /api/agreements` (list
mine) and `POST /api/agreements/{id}/claim`. The public webhook endpoint
(`/api/webhooks/esign`) stays permitted (HMAC-authenticated, not session). The existing
`/api/signing/*/request` permit is **left as-is** -- ownership-authZ for signing is a
separate follow-up, not folded here. Matcher order matters: the exact `GET /api/agreements`
and `POST /api/agreements/*/claim` authenticated matchers must precede the broader
`permitAll` for `/api/agreements/*` so the capability read stays open while list and claim
are gated. Actuator lockdown is unchanged.

### D11: `V7__mobile_identity_auth.sql`, schema matched to the JPA mapping

Forward-only, never edits V1 through V6:
- `mobile_identity(id uuid pk, mobile_e164 text not null unique, created_at timestamptz not null)`
- `otp_challenge(id uuid pk, mobile_e164 text not null, code_hash text not null, expires_at timestamptz not null, attempts int not null default 0, consumed_at timestamptz, created_at timestamptz not null)` with an index on `mobile_e164`
- `auth_session(id uuid pk, identity_id uuid not null references mobile_identity(id), value_hash text not null unique, created_at timestamptz not null, last_seen_at timestamptz, expires_at timestamptz not null)` with an index on `identity_id`
- `alter table agreement add column owner_identity_id uuid references mobile_identity(id)` (nullable) with an index on `owner_identity_id`

Columns rely on Boot's default snake_case naming so `ddl-auto: validate` lines up; a
boot-and-validate integration test catches drift.

### D12: Anonymous drafts persist server-side, addressed by an unguessable id (capability)

`POST /api/agreements` persists immediately with `owner_identity_id = NULL` (matches the
existing backend -- create already persists). While unowned, the draft is readable by any
caller that presents its **UUID**: the unguessable id acts as a bearer capability, exactly
as `GET /api/agreements/{id}` behaves today. This lets a user (or the SPA) reload and
continue an anonymous draft, and lets a draft link be shared, without a login. Once
claimed (D13), reads become owner-scoped and the bare-id capability no longer grants a
non-owner access. **Alternative rejected** (client-only drafts held in `localStorage`
until first save): considered and declined by the product decision -- server persistence
is wanted so drafts survive independent of one browser and are claim-able.

### D13: Claim == Save, guarded and non-oracular

`POST /api/agreements/{id}/claim` (authenticated) sets `owner_identity_id` to the caller's
identity **only if the agreement is currently unowned**. Idempotent for the same owner
(re-claim returns `200`). If the id is unknown, or already owned by a **different**
identity, it returns `404` -- the same response for both, so claim is not an ownership or
existence oracle. The service performs the check-and-set in one transaction with the row
locked (or a conditional `UPDATE ... WHERE owner_identity_id IS NULL`) so two concurrent
claims cannot both win. Owner is taken from the session, never the body (anti-mass-
assignment). **Alternative rejected** (implicit claim by passing draft ids at verify time):
an explicit `claim` is clearer, testable, and lets the SPA claim exactly the draft in hand.

### D14: Unclaimed drafts are retained then purged by a scheduled job

Because anonymous drafts hold party PII with no owner, an owner-null agreement is kept only
for a configured retention window, after which a scheduled job deletes it, its signers, and
any uploaded draft blob (object storage). A claimed agreement is exempt. This reuses the
existing scheduling setup (as the reconciliation job does) and bounds how long un-owned PII
lives server-side (a PII-minimization control, not just housekeeping). **Alternative
rejected** (keep unclaimed drafts forever): unbounded un-owned PII accumulation -- declined.
The retention window is env-tunable (see Open Questions).

## OTP handshake sequence

```
SPA                         AuthController            OtpService              DB and OtpSender
 |  POST /auth/otp/request      |                        |                         |
 |----------------------------->|  request(mobile)       |                         |
 |                              |----------------------->| create challenge        |
 |                              |                        |  (hash code, TTL) ----->| insert otp_challenge
 |                              |                        |  send(code) ----------->| SandboxOtpSender (no SMS)
 |         202 { challengeId }  |<-----------------------|                         |
 |<-----------------------------|                        |                         |
 |  POST /auth/otp/verify       |                        |                         |
 |  { challengeId, otp }        |  verify(...)           |                         |
 |----------------------------->|----------------------->| load challenge; check   |
 |                              |                        |  ttl, consumed, attempts|
 |                              |                        |  const-time hash compare|
 |                              |                        |  find-or-create identity|-> upsert mobile_identity
 |                              |                        |  open session --------->| insert auth_session (hash)
 |     200 { sessionValue }     |<-----------------------|                         |
 |<-----------------------------|                        |                         |
 |  (subsequent) Authorization: Bearer <session>  -> SessionAuthFilter -> hash and lookup
```

## Risks / Trade-offs

- **Low-entropy OTP** -- a short numeric code has a small space; mitigated by short TTL
  plus attempt cap plus per-mobile and per-challenge rate limits plus the peppered hash.
  Residual online-guess risk is bounded by the caps; residual offline risk (DB leak) is
  bounded by the env pepper.
- **Bearer value in SPA memory is XSS-exposed** -- accepted for sandbox; the `HttpOnly`
  cookie plus CSRF hardening (D4) is the flagged production follow-up. No PII is in the
  value.
- **Single-instance rate-limiter and session store** -- correct only for one instance;
  multi-instance needs a shared store (D6). Sandbox is single-instance; flagged.
- **Un-owned PII lives server-side** -- anonymous drafts hold party names/emails (and, in
  later CRs, addresses) with no owner. Mitigated by: addressed only by an unguessable id,
  never listed to anyone, and **purged after a retention window** (D14). Residual exposure
  is bounded by that window; a shorter window trades convenience for less data-at-rest.
- **Capability id as bearer** -- while unowned, anyone holding a draft's UUID can read it
  (D12). Accepted (a draft link is meant to be resumable/shareable); the id is unguessable
  and access tightens to owner-only on claim. Not used for anything irreversible.
- **No SMS deliverability** -- sandbox sender can't prove a real number receives a code;
  live delivery is a Track-B swap behind `OtpSender`.
- **Schema and mapping drift** -- `ddl-auto: validate` plus a boot integration test catch
  it at build time.

## Migration Plan

Forward-only Flyway `V7__mobile_identity_auth.sql` applied on startup after V6: three new
(empty) tables plus one nullable column added to `agreement`. No data migration and no
backfill (new columns and tables; existing rows keep `owner_identity_id` NULL). Rollback
in this pre-production sandbox phase is: drop the three tables and the column and remove
V7 manually (`flyway.clean` stays disabled). No deployed consumers.

## Open Questions

- **OTP code length plus TTL defaults** -- proposing a six-digit code with a roughly
  five-minute TTL and a small attempt cap; confirm the exact sandbox numbers (env-tunable
  regardless).
- **Session lifetime, idle vs absolute expiry** -- proposing an absolute expiry with a
  `last_seen_at` touch; confirm whether idle-timeout sliding expiry is wanted now or
  deferred.
- **Cookie vs bearer for the session** -- D4 ships bearer-in-memory for the sandbox and
  defers the `HttpOnly` cookie; confirm that ordering is acceptable.
- **Unclaimed-draft retention window** -- how long an owner-null draft is kept before the
  purge job (D14) deletes it. Proposing a conservative default (for example 30 days,
  env-tunable); confirm the window and whether the purge should run in this CR or land as a
  tiny follow-up alongside the anonymous-capture CR that first produces real drafts.