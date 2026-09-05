## 1. Schema (Flyway)

- [ ] 1.1 Add `backend/src/main/resources/db/migration/V7__mobile_identity_auth.sql`
  creating `mobile_identity(id uuid pk, mobile_e164 text not null unique, created_at
  timestamptz not null)`; `otp_challenge(id uuid pk, mobile_e164 text not null, code_hash
  text not null, expires_at timestamptz not null, attempts int not null default 0,
  consumed_at timestamptz, created_at timestamptz not null)` with an index on
  `mobile_e164`; `auth_session(id uuid pk, identity_id uuid not null references
  mobile_identity(id), value_hash text not null unique, created_at timestamptz not null,
  last_seen_at timestamptz, expires_at timestamptz not null)` with an index on
  `identity_id`. Forward-only; do not edit V1 through V6.
- [ ] 1.2 In the same migration, `alter table agreement add column owner_identity_id uuid
  references mobile_identity(id)` (nullable) and add an index on `owner_identity_id`.

## 2. Identity domain (module `in.agreementmitra.identity`)

- [ ] 2.1 Add the module `package-info` declaring the module and its public `api`
  sub-package (mirror `signing`), so `identity.otp` / `identity.session` are internal.
- [ ] 2.2 Add package-private `MobileIdentity` aggregate `@Entity` (table
  `mobile_identity`): app-assigned UUID via factory, `mobileE164`, `createdAt`;
  `Persistable<UUID>` with a `@Transient isNew` flag (`@PostPersist`/`@PostLoad`);
  id-based equals/hashCode; **id-only `toString()`** (no mobile number). Repository with a
  `findByMobileE164` finder.
- [ ] 2.3 Add package-private `OtpChallenge` `@Entity` (table `otp_challenge`):
  app-assigned UUID, `mobileE164`, `codeHash`, `expiresAt`, `attempts`, `consumedAt`;
  behaviour methods `matches(codeHash)`, `isLive(now)`, `recordAttempt()`, `consume(now)`;
  id-only `toString()`.
- [ ] 2.4 Add package-private `AuthSession` `@Entity` (table `auth_session`): app-assigned
  UUID, `identityId`, `valueHash`, `createdAt`, `lastSeenAt`, `expiresAt`; `isLive(now)`,
  `revoke()`; id-only `toString()`.
- [ ] 2.5 Add a package-private `OtpCodec` helper: generate a code with a
  `SecureRandom`; compute the peppered keyed hash (HMAC-SHA256 under a pepper read from an
  env-backed property); compute the session-value hash (SHA-256); expose a
  **constant-time** compare. Pepper is env-only; never logged.
- [ ] 2.6 Add a package-private `MobileRedaction` helper (mask a mobile number to its last
  two digits) reused by every log statement in the module.
- [ ] 2.7 Add the `OtpSender` seam (Java-public interface, in a `@NamedInterface`-free
  internal package) plus the default `SandboxOtpSender` (`@Component`): performs no network
  call and logs nothing about the code or number. A dev-only fixed-code path is gated by an
  env flag that defaults off and is inert outside the `local` profile.
- [ ] 2.8 Add a package-private `RateLimiter` (per-mobile request window + per-challenge
  attempt cap), env-tunable, single-instance for the sandbox (documented).
- [ ] 2.9 Add `AuthService` (Java-public so `identity.api` can inject it; Modulith-
  internal): `requestOtp(mobile)` (normalize to E.164, rate-check, create + persist
  challenge, dispatch via `OtpSender`, return challenge id); `verifyOtp(challengeId,
  code)` (load challenge, check live + attempt cap, constant-time compare, consume,
  find-or-create identity, open session, return the session value once);
  `authenticate(sessionValue)` (hash + lookup live session -> identity id or empty);
  `logout(sessionValue)` (revoke, idempotent). Transaction boundaries per D3/D4; map to
  DTOs inside the transaction (no entity crosses the boundary).

## 3. HTTP surface (public package `in.agreementmitra.identity.api`)

- [ ] 3.1 Add `AuthController`: `POST /api/auth/otp/request` (`@Valid OtpRequest{mobile}`
  -> `202` with `OtpChallengeResponse{challengeId}`); `POST /api/auth/otp/verify`
  (`@Valid OtpVerifyRequest{challengeId, otp}` -> `200` with `SessionResponse{session}` or
  `401`); `POST /api/auth/logout` (`204`, idempotent). Records only; no id/timestamp
  fields accepted beyond the inputs above.
- [ ] 3.2 Add validation on the request records: `mobile` `@NotBlank` + an E.164 pattern;
  `challengeId` `@NotNull` UUID; `otp` `@NotBlank` + digits-only + expected length. A
  bad body is a `400` (no challenge created / no session).
- [ ] 3.3 Ensure the OTP code never appears in a response body, an exception message, or a
  log line (assert in tests, task 5/6).

## 4. Security wiring (root `SecurityConfig` + filter)

- [ ] 4.1 Add a `SessionAuthenticationFilter` (`OncePerRequestFilter`): read
  `Authorization: Bearer`, call `AuthService.authenticate`, set the Spring Security
  `Authentication` (principal = identity id) on success; do nothing on absence/failure
  (let authorization rules decide).
- [ ] 4.2 Update `SecurityConfig`: `permitAll` the three `/api/auth/**` handshake paths and
  register the filter. **Keep anonymous drafting open** -- leave `POST /api/agreements`,
  `POST /api/agreements/{id}/draft`, and `GET /api/agreements/{id}` `permitAll`. Add
  `authenticated()` for **only** `GET /api/agreements` (list mine) and
  `POST /api/agreements/*/claim`, and ensure those two matchers are ordered **before** the
  broader `/api/agreements/*` permit so the capability read stays open. Leave the existing
  `POST /api/signing/*/request` permit as-is (signing-authZ is a separate CR). Keep
  `/api/webhooks/esign` permitted and actuator lockdown unchanged. Update the in-code
  comments to describe the anonymous-drafting + optional-save posture.

## 5. Agreement ownership + claim (module `signing`)

- [ ] 5.1 Add a nullable `ownerIdentityId` (UUID) to the `Agreement` aggregate + mapping
  (matches `owner_identity_id`); set only via a server-side `claim(ownerIdentityId)` method
  (guards: settable only when currently null), never client-settable. **Create leaves it
  null** (anonymous draft) -- do not add an owner param to the create path.
- [ ] 5.2 `AgreementService`: `claim(agreementId, ownerIdentityId)` -- set owner in one
  transaction only if currently unowned (locked read or a conditional
  `UPDATE ... WHERE owner_identity_id IS NULL`); idempotent for the same owner; signal
  not-found for an unknown id or one owned by a different identity. Add
  `findByIdCapabilityOrOwner(id, callerIdentityIdOrNull)` (return an unowned draft to any
  caller; return an owned agreement only to its owner; else not-found) and
  `listForOwner(ownerIdentityId)` (summary projection: id, propertyAddress, createdAt, and
  current signing status where a signing request exists), all mapped inside the
  transaction.
- [ ] 5.3 `AgreementController`: `POST /api/agreements` stays anonymous (no owner);
  `GET /api/agreements/{id}` resolves the caller identity if present and uses
  `findByIdCapabilityOrOwner` (200 for an unowned draft or the owner; `404` otherwise, no
  `403`); add `GET /api/agreements` (authenticated -> `listForOwner` -> `200` list,
  possibly empty); add `POST /api/agreements/{id}/claim` (authenticated -> `claim` -> `200`;
  `404` for unknown/foreign-owned; `401` if unauthenticated).
- [ ] 5.4 Add a scheduled **retention purge** of unclaimed (owner-null) agreements past the
  env-tunable window: delete the agreement, its signers, and any uploaded draft blob
  (object storage); never touch an owned agreement. Reuse the existing scheduling setup;
  disable in the test profile (like reconciliation).

## 6. Config

- [ ] 6.1 Add env-tunable properties (safe sandbox defaults) for OTP length, TTL, verify
  attempt cap, request rate window/limit, and session lifetime; the hash pepper and any
  future SMS credential are env-only (never committed). Document them in
  `application.yml` comments alongside the existing config blocks.

## 7. Frontend (`frontend/src`)

- [ ] 7.1 Add an auth API module in `src/api/` (request OTP, verify OTP, logout) and an
  auth store holding the in-memory session; attach `Authorization: Bearer` to API calls.
- [ ] 7.2 Add a **Landing** view where "Rental Agreement" goes **straight into anonymous
  drafting** (no login) and "Sign In / Register" is optional; a **Mobile + OTP** view
  (enter number -> enter code -> verified); a **Save** action that runs the login handshake
  (if needed) then claims the current draft; and a **My Agreements** view (list from
  `GET /api/agreements`, with "resume"/"review" affordances). **Do not gate drafting** --
  only "My Agreements" and "Save" require a session; unauthenticated drafting is the default
  path. Tailwind utilities for layout.

## 8. Tests -- unit (no Spring context)

- [ ] 8.1 `OtpCodec`: generated code has the configured length and is numeric; the keyed
  hash is deterministic for a given code+pepper and differs across pepper; the
  constant-time compare returns true only for the matching code. Assert the code is never
  embedded in any thrown exception message.
- [ ] 8.2 `OtpChallenge` lifecycle: `isLive` is false after expiry, after consume, and
  once attempts exceed the cap; `consume` is single-use (a second consume is rejected);
  `recordAttempt` increments and enforces the cap.
- [ ] 8.3 `MobileRedaction`: masks all but the last two digits; is null/blank-safe (no
  NPE) and never returns the full number.
- [ ] 8.4 Session hashing: a session value maps to a stable hash; `authenticate` logic
  treats an expired or revoked session as not-authenticated (pure logic, mocked repo).
- [ ] 8.5 Request-record validation via a plain `jakarta.validation.Validator`: a blank or
  non-E.164 mobile, a non-UUID `challengeId`, and a non-numeric or wrong-length `otp` each
  fail; a well-formed body passes.

## 9. Tests -- integration (Testcontainers Postgres slice)

- [ ] 9.1 Full handshake: `POST /api/auth/otp/request` returns `202` + a challengeId and
  persists one live challenge; capturing the code via a **test `OtpSender`** (never from
  logs), `POST /api/auth/otp/verify` returns `200` + a session and creates exactly one
  `mobile_identity`; a second login for the same number reuses that identity.
- [ ] 9.2 Negative paths: wrong code -> `401` and increments attempts; over the attempt
  cap -> `401` even with the correct code; expired challenge -> `401`; re-verifying a
  consumed challenge -> `401`. Assert no `mobile_identity`/`auth_session` row is created on
  failure.
- [ ] 9.3 Enumeration + rate-limit: request responses for a known vs unknown number are
  indistinguishable; exceeding the per-mobile request limit -> `429`; exceeding the verify
  window -> `429`.
- [ ] 9.4 Session filter + authZ: a protected endpoint (`GET /api/agreements`) returns
  `401` with no/invalid/expired session and `200` with a live one; `POST /api/auth/logout`
  -> `204` and the same session then yields `401`.
- [ ] 9.5 Anonymous + claim + ownership: an **unauthenticated** `POST /api/agreements`
  succeeds (`201`, owner null) and its id reads back via `GET /api/agreements/{id}` with no
  session (capability). Identity A claims it (`200`); after claim, an unauthenticated or
  identity-B read of that id returns `404`, and `GET /api/agreements` as A lists it while as
  B it does not. Claiming an A-owned draft as B returns `404` (indistinguishable from
  unknown); re-claiming as A returns `200` (idempotent). `GET /api/agreements` and
  `POST /api/agreements/{id}/claim` with no session return `401`.
- [ ] 9.6 Retention purge: an owner-null draft older than the window is deleted (agreement,
  signers, and draft blob) by the purge job; a claimed agreement of the same age is left
  intact.
- [ ] 9.7 Log hygiene: capture logs across a full request/verify and assert **no** line
  contains the OTP code or the full mobile number (only redacted forms appear).
- [ ] 9.8 Boot/validate: the app boots against the `V7`-migrated schema under
  `ddl-auto: validate`; `flyway_schema_history` shows `V7` success; `ModularityTests`
  stays green (identity is a clean module; signing depends only on the UUID).

## 10. Frontend tests (Vitest)

- [ ] 10.1 Unit-test the auth API module (request/verify/logout call the right paths and
  attach the bearer header) with a mocked fetch, and a component test that the Mobile+OTP
  view moves enter-number -> enter-code -> verified and surfaces a `401` as an error
  without leaking the code.

## 11. Verify

- [ ] 11.1 Backend: run `./run-tests.sh` (or `./gradlew check` with Docker) -- full suite
  green, including `securityScan`, the JaCoCo gate, and `ModularityTests`.
- [ ] 11.2 Frontend: `npm run test` and `npm run security:scan` green.
- [ ] 11.3 Manual (documented in the change): run the daily stack; **without logging in**,
  create an agreement (draft) and confirm it reads back by id; then log in with a dummy
  number via the dev-only code, **Save (claim)** the draft, log out, log back in, and confirm
  it appears under "My Agreements" and that a different identity cannot read it.