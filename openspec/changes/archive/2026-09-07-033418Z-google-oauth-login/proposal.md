## Why

An agreement is owned by no one today, there is no user/account concept (the root
`SecurityConfig` is a deny-by-default posture, not an authenticator; the `identity` module is a
`package-info`-only stub), and there is no way for a returning visitor to prove "this is me." We
want the lowest-friction identity most Indian users already have: **Sign in with Google**.

This CR delivers **only the login layer** -- the authenticated identity and session that later
work builds on. It is the **first of two changes** for optional Google login:

- **CR-A (this change) -- Google login + session.** A user can sign in with Google and get an
  authenticated session; `GET /api/auth/me` and logout work. No agreement behavior changes yet.
- **CR-B -- Owned agreements (`agreement-ownership`).** Builds on this: `owner_identity_id`,
  Save (claim), list-mine with derived status, and edit of in-progress agreements, plus the
  My-Agreements UI. It depends on this CR's session/principal and its `identity` table.

Login stays **optional** end-to-end: this CR changes **no** existing agreement route -- anonymous
drafting is untouched. It only **adds** the ability to authenticate. Per the "options login"
intent, the identity layer is built **provider-agnostic**: a generic `Identity` aggregate carries
**credentials**, with Google as the first one, so the parked mobile-OTP change can attach a second
credential to the same identity later without a schema rewrite. The opaque server-side session is
shared infra that a future credential reuses.

## What Changes

- Activate the previously-stub `identity` module (`in.agreementmitra.identity`) with a
  **provider-agnostic** `Identity` aggregate (app-assigned UUID, display name, email) carrying one
  or more `IdentityCredential` rows, each `{provider, providerSubject}`; the first provider is
  `GOOGLE`. Login find-or-creates the identity by `(provider, providerSubject)`.
- Add the **Google OAuth (OIDC) handshake**, backend-mediated, Authorization-Code + PKCE (all
  handshake routes unauthenticated):
  - `GET /api/auth/google/start` -- begins login; stores a single-use `state` + PKCE verifier in a
    short-lived `oauth_login_state` row (hashes only) and `302`-redirects to Google's consent screen.
  - `GET /api/auth/google/callback?code=&state=` -- Google's redirect target; validates the `state`,
    exchanges the `code` for tokens (Google `client_secret` from env), **validates the ID token**
    (JWKS signature, `iss`, `aud`, `exp`, `email_verified == true`), find-or-creates the `Identity`
    + Google credential, mints a **single-use handoff code**, and `302`-redirects back to the SPA
    callback route with that code (never a token, never the durable session, in the URL).
  - `POST /api/auth/session/exchange` -- body `{ handoff }`; consumes the single-use handoff,
    **mints the opaque server-side session**, and returns `200 OK` with the session value (once) and
    the caller's identity summary.
  - `GET /api/auth/me` (**authenticated**) -- returns the current identity summary (email, display
    name); `POST /api/auth/logout` (**authenticated**) -- revokes the caller's session (`204`).
- Introduce an **opaque, server-side, hashed session** (256-bit random value returned once,
  persisted only as a SHA-256 hash; a Spring Security `OncePerRequestFilter` authenticates the
  `Authorization: Bearer` value by hashing and looking up a live, unexpired session; revocable and
  expiring; no PII in the value). This is **shared, provider-agnostic** infra.
- Update `SecurityConfig`: register the session-authentication filter; `permitAll` the handshake +
  session-exchange routes; add `authenticated()` for `GET /api/auth/me` and `POST /api/auth/logout`.
  **Every existing route is left exactly as-is** -- anonymous create, draft-upload, capability read,
  signing, and webhook permits are untouched.
- Add Flyway `V11__identity_oauth.sql`: `identity`, `identity_credential`, `auth_session`,
  `oauth_login_state`, `login_handoff` tables. **No change to the `agreement` table in this CR**
  (the `owner_identity_id` column is added by CR-B's `V12`).
- Frontend: a **landing** screen ("Create a rental agreement" leads straight into anonymous
  drafting, unchanged; "Sign in with Google" is optional), a **`/auth/callback`** route that
  exchanges the handoff for a session, and an **auth store** in `src/api/` that holds the session in
  memory, attaches `Authorization: Bearer`, exposes `me`, and clears on logout/401. Drafting still
  needs no login.

**Out of scope (this CR):**
- Anything that reads or writes agreement ownership -- `owner_identity_id`, claim/Save, list-mine,
  edit, read-scoping, and the My-Agreements/Edit UI all live in **CR-B (`agreement-ownership`)**.
- Mobile-OTP as a second credential (the parked `mobile-otp-auth` change; this CR only leaves room).
- Ownership-authZ for signing/stamping (separate follow-up; the signing permit is untouched).
- `HttpOnly; Secure; SameSite` cookie session + CSRF (bearer-in-memory accepted for sandbox).
- A distributed/shared session store (single-instance sandbox).

## Capabilities

### Added Capabilities
- `google-oauth-login`: optional login via **Google OAuth (OIDC), Authorization-Code + PKCE**,
  backed by a **provider-agnostic `Identity` aggregate** and `IdentityCredential` (Google first),
  **opaque server-side hashed sessions** (issue, authenticate, revoke, expire), and the login
  handshake + session-exchange + me + logout endpoints. No enumeration oracle; ID token fully
  validated; no PII or token ever logged.

### Modified Capabilities
- `backend-security-baseline`: the deny-by-default posture gains a **session-authentication
  filter**; the Google handshake and session-exchange routes are `permitAll`; `GET /api/auth/me`
  and `POST /api/auth/logout` are `authenticated()`. All existing agreement/signing/webhook
  matchers are unchanged (agreement-route gating arrives in CR-B).

## Signing-status FSM

**No new states, no changed transitions, and no interaction with the FSM at all.** This CR adds
authentication only; the `SignatureStatus` FSM on the `SigningRequest` aggregate is untouched.

## Google OAuth login sequence

```
SPA                     AuthController            OAuth/OidcService          Google        DB
 | click "Sign in"          |                          |                       |            |
 | GET /auth/google/start   |                          |                       |            |
 |------------------------->| create state+PKCE ------->| ---------------------------------->| insert oauth_login_state
 |    302 -> Google consent |<-------------------------|                       |            |
 |<-------------------------|                          |                       |            |
 |  (user consents at Google) ------------------------------------------------>|            |
 |  302 back: /auth/google/callback?code=&state=       |                       |            |
 |------------------------->| verify state; exchange   |                       |            |
 |                          | code -> tokens --------------------------------->| token +    |
 |                          |                          |   validate ID token   |  JWKS      |
 |                          |                          |   (sig/iss/aud/exp,   |            |
 |                          |                          |    email_verified)    |            |
 |                          | find-or-create identity ------------------------------------->| upsert identity(+credential)
 |                          | mint single-use handoff ------------------------------------->| insert login_handoff
 |   302 -> SPA /auth/callback?handoff=<one-time>      |                       |            |
 |<-------------------------|                          |                       |            |
 | POST /auth/session/exchange { handoff }             |                       |            |
 |------------------------->| consume handoff -------------------------------------------->| update login_handoff (consumed)
 |                          | mint opaque session -------------------------------------->  | insert auth_session (hash only)
 |   200 { sessionValue, me }|<------------------------|                       |            |
 |<-------------------------|                          |                       |            |
 | (subsequent) Authorization: Bearer <session>  -> SessionAuthFilter -> hash + lookup      |
```

The durable session value is **minted only at exchange** and stored **only as a hash**; the handoff
carried in the redirect is **single-use, short-lived, and carries no session material**. No token,
code, or session value ever appears in a log line.

## PII / security checklist

- **Introduces or moves Aadhaar/OTP/VID/PII or secrets?** **Yes -- a Gmail address and a Google
  account subject (`sub`)** are collected and stored to key the identity, and a Google OAuth
  **`client_id` + `client_secret`** are new secrets. **No** Aadhaar number, virtual id, biometric,
  government identifier, or eKYC/OTP code is collected or stored (this is application login, not KYC).
- **How redacted/secured?**
  - **Google ID/access tokens and the authorization `code`** are used server-side only and are
    **never returned to the SPA, never logged at any level, never placed in an exception message**.
    The ID token is validated (JWKS signature, `iss == https://accounts.google.com`,
    `aud == our client id`, `exp`, `email_verified == true`) before any identity is materialized.
  - The **email address** is stored to display the identity but **redacted** (local-part masked,
    e.g. `a***@gmail.com`) in every log line; `Identity`, `IdentityCredential`, `AuthSession`, and
    the handoff/state rows get **id-only `toString()`**.
  - The **session value** is high-entropy random (256-bit, strong RNG), returned **once**, persisted
    only as a SHA-256 hash, server-side, revocable, and expiring. The **handoff** and **PKCE state**
    are single-use, short-lived, and stored only as hashes.
  - **Enumeration is denied**: the login handshake never reveals whether an identity pre-existed
    (identical response for first and returning logins).
  - Secret material (`GOOGLE_OAUTH_CLIENT_SECRET`, the session/handoff hash pepper) comes from
    **env vars only**; never committed.
- **Sandbox + dummy data only?** Preserved. A **sandbox Google OAuth client** (test users) is used;
  no real end-user PII, no production credentials. A missing client secret fails fast rather than
  falling back to a real project.
- **Signing-status FSM transitions touched?** **None.**
- **Async signing / webhook flow touched?** **None** -- no signing sequence diagram required. (The
  OAuth login handshake sequence is above and in `flow-journal.md`.)
