# Tasks -- google-oauth-login (CR-A: login + session)

First of two CRs for optional Google login. This CR delivers **only** the authenticated identity and
session; ownership/claim/list/edit live in **CR-B (`agreement-ownership`)**. Grouped back-to-front:
schema, then the `identity` module (login + session + filter), then SecurityConfig, then the login
frontend, then the test pyramid, then verify. Decisions (D#) live in `design.md`. Keep `ModularityTests`
green (this CR adds no cross-module dependency; CR-B is where a UUID crosses the boundary).

## 1. Schema (Flyway V11)

- [x] 1.1 Add `backend/src/main/resources/db/migration/V11__identity_oauth.sql` creating `identity`,
  `identity_credential` (unique `(provider, provider_subject)`, index on `identity_id`), `auth_session`
  (unique `value_hash`, index on `identity_id`), `oauth_login_state`, and `login_handoff`. Forward-only;
  do not edit V1..V10; **do not touch the `agreement` table** (that is CR-B's V12) (D7).
- [x] 1.2 Regenerate the Gradle lockfile after the new dependency (task 2.1)
  (`./gradlew dependencies --write-locks`).

## 2. identity module -- login + session (D1, D2, D3, D4, D5)

- [x] 2.1 Add `spring-boot-starter-oauth2-client` (token-response client + Nimbus `JwtDecoder`) to
  `backend/build.gradle.kts`; keep it on the shipping classpath so the OSV gate scopes it.
- [x] 2.2 Activate the `identity` module: replace the stub `identity/package-info.java` doc; add internal
  packages `identity.oauth`, `identity.session` and the public `identity.api` (`@NamedInterface("api")`),
  mirroring `signing`'s internal/api split (D1).
- [x] 2.3 `Identity` aggregate (app-assigned UUID via factory, `Persistable<UUID>` + transient `isNew`,
  `displayName`, `email`) + `IdentityRepository`; id-only `toString()` (D3, D7).
- [x] 2.4 `IdentityCredential` child (`provider`, `providerSubject`, `email`, `emailVerified`) + repository
  with `findByProviderAndProviderSubject`; a find-or-create service method (D3).
- [x] 2.5 `OauthLoginState` + `LoginHandoff` entities/repositories: create single-use short-TTL rows (store
  only hashes of `state`/`handoff`), consume-atomically methods (D5, D7).
- [x] 2.6 `GoogleOidcService`: build the authorization request (scopes `openid email profile`, random
  `state`, PKCE `code_challenge`); exchange the `code`; **validate the ID token** (JWKS signature, `iss`,
  `aud == client id`, `exp`, `email_verified == true`) via Nimbus `JwtDecoder` against Google's cached
  JWKS; return `{sub, email, name}` (D2). Client id/secret from env; never log tokens/code (D7).
- [x] 2.7 `AuthSession` entity + `SessionService`: mint a 256-bit random value (strong RNG), persist only
  its SHA-256 hash, resolve by hashing a presented value, touch `last_seen_at`, revoke on logout; id-only
  `toString()` (D4, D7).
- [x] 2.8 `SessionAuthenticationFilter` (`OncePerRequestFilter`): read `Authorization: Bearer`, hash + look
  up a live session, set the Spring Security `Authentication` (principal = identity id); no-op when absent (D4).
- [x] 2.9 `AuthController` (`identity.api`): `GET /api/auth/google/start` (302 to Google),
  `GET /api/auth/google/callback` (verify state, exchange, find-or-create, mint handoff, 302 to SPA),
  `POST /api/auth/session/exchange` (consume handoff, mint session, return value once + me),
  `GET /api/auth/me` (identity summary), `POST /api/auth/logout` (revoke, 204). Records for all DTOs; email
  redacted in any log (D2, D5, D7).
- [x] 2.10 Config: `application.yml` Google client id/secret + redirect uri from env
  (`GOOGLE_OAUTH_CLIENT_ID`/`_SECRET`), session + handoff TTLs, hash pepper from env; `local` profile safe
  defaults; fail fast if the secret is missing (do not fall back to a real project) (D2).

## 3. SecurityConfig (D6)

- [x] 3.1 Register `SessionAuthenticationFilter`; `permitAll` `GET /api/auth/google/start`,
  `GET /api/auth/google/callback`, `POST /api/auth/session/exchange`; add `authenticated()` for
  `GET /api/auth/me` and `POST /api/auth/logout`. **Leave every existing matcher unchanged** (D6).

## 4. Frontend (D8)

- [x] 4.1 Auth store in `frontend/src/api/` (session value in memory, `Bearer` header on authenticated
  calls, `me`, clear on logout/401) + an `auth.ts` API module (start URL, exchange, me, logout).
- [x] 4.2 Landing view (primary "Create a rental agreement" -> anonymous capture, unchanged; secondary
  "Sign in with Google" -> start URL) + a `/auth/callback` view that reads the handoff and calls exchange,
  then returns to the app. Anonymous drafting unchanged.

## 5. Tests (pyramid -- required)

**Unit (identity, no Spring context):**
- [x] 5.1 ID-token claim validation: accepts a well-formed token (good `iss`/`aud`/`exp`,
  `email_verified == true`) and rejects each defect (bad `aud`, expired, `email_verified == false`, wrong
  `iss`) with a stubbed/mock `JwtDecoder`, no network.
- [x] 5.2 Session hashing/resolution: mint -> only the hash is persisted (value never stored plaintext);
  resolve by hash; expired/revoked -> no auth; constant-time compare.
- [x] 5.3 Handoff + login-state lifecycle: single-use (second consume fails), TTL expiry, hash-only storage.
- [x] 5.4 Find-or-create identity: first `(GOOGLE, sub)` inserts; repeat reuses; a different `sub` makes a
  distinct identity.
- [x] 5.5 Redaction/`toString`: email masked by the shared helper; `Identity`/`IdentityCredential`/
  `AuthSession`/handoff/state `toString()` are id-only (no email/sub/hash/value).

**Integration (Testcontainers Postgres + Spring slice):**
- [x] 5.6 Boot-and-validate: app starts against V1..V11 under `ddl-auto: validate` (schema/mapping match).
- [x] 5.7 Login handshake end-to-end with Google stubbed (a mock OIDC token endpoint + JWKS): start ->
  callback (find-or-create + handoff) -> exchange -> `Bearer` session authenticates a subsequent
  `GET /api/auth/me`; logout revokes.
- [x] 5.8 SecurityConfig: unauthenticated `GET /api/auth/me` -> `401`/`403`; the handshake + exchange routes
  are permitted; an existing route (e.g. `POST /api/agreements`, `GET /api/agreements/{id}`) is still open
  (proves no existing matcher regressed).
- [x] 5.9 `ModularityTests` stays green.

**Frontend (component/e2e-lite):**
- [x] 5.10 Auth store attaches `Bearer` and clears on 401; the `/auth/callback` view exchanges the handoff.
  The anonymous drafting path needs no session.

## 6. Verify + wrap-up

- [ ] 6.1 Live-drive against the running backend (`:8090`) + SPA: anonymous draft still works with no login;
  Sign in with Google (sandbox client / stubbed) -> `/auth/callback` -> `GET /api/auth/me` returns the
  identity -> logout. Confirm no token/PII/session value appears in logs.
  NOTE: the full handshake (start -> callback -> exchange -> Bearer `me` -> logout) is covered
  end-to-end by `GoogleLoginHandshakeIntegrationTest` (stubbed Google via WireMock + a Nimbus-signed
  ID token, real Postgres). A manual browser live-drive against a real sandbox Google client is still
  owed and left unchecked.
- [x] 6.2 `./gradlew spotlessApply` (done) then the test suite via gradle directly with
  `TESTCONTAINERS_RYUK_DISABLED=true` on Windows (full `test` task GREEN, incl. `ModularityTests`) plus
  `spotbugsMain` SAST (GREEN). NOTE: the OSV dependency-scan gates (backend `securityScan` OSV over the
  refreshed lockfile; `npm run security:scan` in `frontend/`) could NOT run here -- `osv-scanner` is not
  installed on this machine and both gates are fail-closed by design. Run them on a host with
  `osv-scanner` (`brew install osv-scanner`) before merge; the lockfile was regenerated (task 1.2).
- [x] 6.3 Note that CR-B (`agreement-ownership`) is the follow-up that consumes this CR's session/identity.
