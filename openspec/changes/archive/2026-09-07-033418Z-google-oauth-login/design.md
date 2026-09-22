## Context

The signing vertical slice is complete but there is **no user/account concept**: the root
`SecurityConfig` is a deny-by-default posture (not an authenticator) and the `identity` module is a
`package-info`-only stub. This CR adds **only the login layer** -- an authenticated identity and an
opaque session -- so a later change (**CR-B `agreement-ownership`**) can attach ownership, save,
resume, and edit to that identity. Login is **optional**: this CR changes no existing agreement
route; it only adds the ability to authenticate.

Per the "options login" intent the identity layer is **provider-agnostic** so the parked
`mobile-otp-auth` change can attach a second credential to the **same** identity later. The opaque-
session model mirrors that parked design's D4 so the two converge.

**Grounding (verified against the code):**
- `backend/src/main/java/in/agreementmitra/SecurityConfig.java` -- stateless, CSRF-disabled,
  `anyRequest().denyAll()`, business routes `permitAll` as "TEMPORARY -- tighten when auth lands."
  We add the filter + two authenticated `/api/auth/*` matchers here; **existing matchers unchanged**.
- `backend/src/main/java/in/agreementmitra/identity/package-info.java` -- the stub module we activate.
- `backend/src/main/java/in/agreementmitra/signing/agreement/Agreement.java` -- app-assigned UUID via
  a factory, `Persistable<UUID>` with transient `isNew`; the pattern we copy for `Identity`.
- Latest Flyway migration is `V10__template_production_layer_set.sql`; this CR is **`V11`** (identity
  tables only; the `agreement.owner_identity_id` column is CR-B's `V12`).
- Frontend `frontend/src/api/client.ts` -- a plain `fetch` wrapper, `BASE = "/api"`, **no auth header
  today**; `frontend/src/App.vue` uses a `ref` view-switch, **no vue-router**.

Constraints (unchanged): Java 21 + Spring Boot 3.5.x + Spring Modulith; records for DTOs; constructor
injection; package-private by default, `public` only on the module API; Flyway is the single schema
source (`ddl-auto: validate`); keep `ModularityTests` green; **sandbox + dummy data only**; never log
tokens/PII; secrets via env only.

## Goals / Non-Goals

**Goals:**
- **Sign in with Google** (OIDC): begin, consent, validate, and open an authenticated session.
- A durable **provider-agnostic `Identity`** (Google credential first) that CR-B can own agreements by.
- An **opaque, hashed, revocable, expiring** session + a Spring Security filter that authenticates it.
- Strong handshake security: validated ID token, single-use short-lived state/handoff, no enumeration.
- Unit-testable core (ID-token claim validation, session hashing/resolution, handoff lifecycle) with no
  Spring context; integration-tested handshake on Testcontainers with a stubbed Google.

**Non-Goals:**
- **No agreement behavior** -- ownership, claim, list-mine, edit, read-scoping, and their UI are CR-B.
- No mobile-OTP credential yet (left room for; parked change). No KYC/DigiLocker/Aadhaar eKYC.
- No password/magic-link login; no multi-device session-management UI.
- No `HttpOnly` cookie session; no distributed session store (sandbox, single-instance).

## Decisions

### D1: Login lives in the `identity` module, activated from its stub, provider-agnostic

Optional login goes in `in.agreementmitra.identity` (today a stub reserved for KYC). "Who is this
user" is exactly this module's charter; login is its first concern and KYC folds in later. Structure
mirrors `signing`: internal domain packages (`identity.oauth`, `identity.session`) plus a public
`identity.api` (`AuthController`, request/response records, and the authenticated-principal view).
Entities, repositories, the OAuth client, and the session filter stay package-private. The only value
this module hands outward (in CR-B) is the authenticated **identity id (a UUID)**, so `ModularityTests`
stays green. **Alternative rejected:** a separate `auth` module (splits identity for no benefit); login
inside `signing` (wrong boundary -- signing must not own identity).

### D2: Google OAuth via backend-mediated Authorization-Code + PKCE, our own session at the end

The SPA never handles Google tokens. `GET /api/auth/google/start` builds the authorization request
(scopes `openid email profile`, a random `state`, a PKCE `code_challenge`) and `302`s to Google.
`GET /api/auth/google/callback` verifies `state`, exchanges the `code` at Google's token endpoint
(`client_id` + `client_secret` from env, plus the PKCE `code_verifier`), and **validates the ID token**:
JWKS signature (Google's published keys, cached), `iss == https://accounts.google.com` (or
`accounts.google.com`), `aud == our client id`, unexpired, and `email_verified == true`. Only then is an
identity materialized. Implementation uses Spring Security's OAuth2 Client primitives
(`NimbusJwtDecoder` against Google's JWKS + a token-response client) rather than hand-rolled JOSE, but
we **drive the redirect ourselves** and mint **our own** opaque session (D4) so sessions are uniform
across providers and revocable. **Alternatives rejected:** (a) **client-side GIS ID-token** POSTed to
the backend -- simpler but exposes the token to JS; the code flow keeps tokens server-side and is the
OAuth-recommended path; (b) **Spring `oauth2Login()`** with its default `HttpSession` login -- we are
stateless and want our own opaque-session model.

### D3: A generic `Identity` with pluggable `IdentityCredential` (Google first)

`Identity` is an aggregate with an app-assigned UUID (factory + `Persistable<UUID>` + transient `isNew`,
like `Agreement`), a display name, and an email. Credentials live in a child
`IdentityCredential { id, identityId, provider, providerSubject, email, emailVerified }` with a UNIQUE
`(provider, provider_subject)`. Login **find-or-creates** by `(GOOGLE, sub)`. This is the "provider-
agnostic" shape: a future mobile-OTP login adds a `(MOBILE, e164)` credential -- optionally to an
existing identity (account linking) -- with no change to the session model or (in CR-B) to
`owner_identity_id`. **Alternative rejected:** keying the identity directly on the Google `sub` -- simplest
but forecloses a second credential and would force a migration to generalize later; the child table costs
one join and buys the "options login" the request asked for.

### D4: Sessions are opaque, server-side, hashed, and revocable (shared infra)

On session-exchange (D5) the server mints a 256-bit random value (strong RNG), returns it **once**, and
persists an `auth_session` row holding only its **SHA-256 hash** (plus `identity_id`, `created_at`,
`last_seen_at`, `expires_at`). A `OncePerRequestFilter` reads the `Authorization: Bearer` value, hashes
it, looks up a live unexpired session in constant time, and sets the Spring Security `Authentication`
(principal = identity id). Logout deletes the row. Provider-agnostic: mobile-OTP would mint the identical
session. **Alternative rejected:** a self-contained signed JWT -- not server-revocable, tempts encoding
PII, adds key management; an opaque hashed handle is revocable and leaks nothing. **Deferred hardening:**
an `HttpOnly; Secure; SameSite` cookie with CSRF defence (bearer-in-memory accepted for sandbox; XSS risk
in Risks).

### D5: The redirect delivers a single-use handoff, not the session; exchange mints the session

Because the callback is a browser `302` back to the SPA, we must not put the durable session in the URL
(history/referer leak). Instead the callback stores a `login_handoff { id, handoff_hash, identity_id,
created_at, expires_at (short, ~60s), consumed_at }` and redirects with the **raw one-time handoff code**
in the URL fragment. The SPA immediately `POST /api/auth/session/exchange { handoff }`; the server looks
it up by hash, checks unexpired + unconsumed, marks it consumed **atomically**, mints the session (D4), and
returns the value once. The handoff carries **no** session material and is single-use, so a leaked redirect
is inert after first use/expiry. **Alternative rejected:** putting the session value in the redirect fragment
-- simpler but leaks a durable credential into history/referer; the extra hop is cheap and much safer.
**Alternative rejected:** a cookie at callback -- see D4 (deferred).

### D6: SecurityConfig -- permit the handshake, authenticate only me/logout, leave everything else

Update the root `SecurityConfig`: `permitAll` for `GET /api/auth/google/start`,
`GET /api/auth/google/callback`, and `POST /api/auth/session/exchange` (no session yet); register the
session-authentication filter (D4); add `authenticated()` for `GET /api/auth/me` and
`POST /api/auth/logout`. **Every existing matcher is left exactly as-is** -- anonymous create,
draft-upload, capability read, the webhook (HMAC), and the `/api/signing/*/request` permit are untouched
(agreement-route gating is CR-B). Deny-by-default remains the fallback; actuator lockdown unchanged.
**Alternative rejected:** method-level `@PreAuthorize` -- the filter chain is already the single authZ
surface; keep it there.

### D7: `V11__identity_oauth.sql`, schema matched to the JPA mapping; PII-logging hygiene

Forward-only, never edits V1..V10; **does not touch `agreement`** (CR-B's `V12` adds the owner column):
- `identity(id uuid pk, display_name text, email text, created_at timestamptz not null)`
- `identity_credential(id uuid pk, identity_id uuid not null references identity(id), provider text not
  null, provider_subject text not null, email text, email_verified boolean not null default false,
  created_at timestamptz not null)` with `unique(provider, provider_subject)` + index on `identity_id`
- `auth_session(id uuid pk, identity_id uuid not null references identity(id), value_hash text not null
  unique, created_at timestamptz not null, last_seen_at timestamptz, expires_at timestamptz not null)` +
  index on `identity_id`
- `oauth_login_state(id uuid pk, state_hash text not null unique, code_verifier text not null, redirect_uri
  text, created_at timestamptz not null, expires_at timestamptz not null, consumed_at timestamptz)`
- `login_handoff(id uuid pk, handoff_hash text not null unique, identity_id uuid not null references
  identity(id), created_at timestamptz not null, expires_at timestamptz not null, consumed_at timestamptz)`

Boot's default snake_case naming lines up with `ddl-auto: validate`; a boot-and-validate test catches
drift. **Logging (DEBUG is on):** Google ID/access tokens and the `code` are **never logged**; the email is
redacted (local-part masked) before any log line; `Identity`, `IdentityCredential`, `AuthSession`,
`OauthLoginState`, `LoginHandoff` get **id-only `toString()`**; no request/token-response body is echoed.
(`code_verifier` is a short-lived per-login PKCE secret, deleted on consume; encrypt-at-rest is flagged, not
required for sandbox.)

### D8: Frontend -- optional-login shell + auth store; drafting stays no-login

Introduce a minimal client-side route (or extend the existing `ref` view-switch) with a **landing** view
(primary "Create a rental agreement" -> anonymous capture, unchanged; secondary "Sign in with Google" ->
`GET /api/auth/google/start`) and a **`/auth/callback`** view that reads the handoff from the URL and calls
`POST /api/auth/session/exchange`, then lands the user back on the (unchanged) app. An **auth store** in
`src/api/` holds the session value in memory, attaches `Authorization: Bearer` to authenticated calls,
exposes `me`, and clears on logout/401. `POST /api/agreements` (anonymous), the capture form, and preview
require **no login**. The My-Agreements list, Save, and Edit UI are **CR-B**. **Alternative rejected:**
forcing vue-router now -- the existing view-switch can carry this; router adoption is an independent refactor.

## Risks / Trade-offs

- **Bearer value in SPA memory is XSS-exposed** -- accepted for sandbox; `HttpOnly` cookie + CSRF is the
  flagged production follow-up (D4). No PII is in the value.
- **Single-instance state/handoff** -- the `auth_session` lookup is DB-backed (multi-instance-safe), but the
  OAuth state/handoff single-use guarantees assume one instance for their windows. Flagged; sandbox is
  single-instance.
- **Google as the only provider at launch** -- a user without a Google account cannot log in yet; acceptable
  because login is **optional** (they can still draft anonymously) and D3 lets mobile-OTP join without rework.
- **ID-token clock/JWKS dependencies** -- validation depends on Google's JWKS reachability and reasonable
  clock skew; mitigated by JWKS caching + a small skew allowance; a hard Google outage blocks login only
  (drafting unaffected).
- **Schema/mapping drift** -- `ddl-auto: validate` + a boot integration test catch it at build.

## Migration Plan

Forward-only Flyway `V11__identity_oauth.sql` applied on startup after V10: five new (empty) tables; the
`agreement` table is untouched. No data migration/backfill. Rollback in this sandbox phase: drop the five
tables and remove V11 manually (`flyway.clean` stays disabled). No deployed consumers. Regenerate the Gradle
lockfile after adding the OAuth2-client dependency (`./gradlew dependencies --write-locks`) and re-run the
OSV scan gate. **CR-B's `V12` adds `agreement.owner_identity_id` and depends on this migration having run.**

## Open Questions

- **Session lifetime (idle vs absolute)** -- proposing an absolute expiry with a `last_seen_at` touch;
  confirm whether a sliding idle-timeout is wanted now or deferred.
- **Cookie vs bearer for the session** -- D4/D5 ship bearer-via-handoff for the sandbox and defer the
  `HttpOnly` cookie; confirm that ordering is acceptable.
- **Account linking (future)** -- when a later mobile-OTP login's verified email matches an existing Google
  identity, auto-link to the same `Identity` or keep separate until explicit link? Proposing: separate by
  default, explicit link later. Confirm (only bites when mobile-OTP lands).
- **`code_verifier` at rest** -- plaintext (short-lived, deleted on consume) or encrypted (D7)? Proposing
  plaintext for the sandbox given the ~60-90s TTL; confirm.
