# google-oauth-login Specification

## Purpose

Backend-mediated Google sign-in and the server-side session it produces. Covers the
provider-agnostic `Identity` aggregate with pluggable `IdentityCredential` records, the
Authorization-Code-with-PKCE handshake against Google OpenID Connect, full ID-token
validation, the single-use handoff that the redirect carries back to the SPA, and the
opaque, hashed, revocable sessions minted from it and delivered only as an HttpOnly
cookie -- together with the
never-log-tokens-or-PII and no-account-enumeration guarantees. Distinct from
`backend-security-baseline`, which owns the filter chain and route authorization that
consume these sessions.
## Requirements
### Requirement: Provider-agnostic identity with pluggable credentials

The system SHALL model an application user as a provider-agnostic `Identity` aggregate with a
server-assigned UUID, an optional display name, and an optional email. An `Identity` SHALL hold
one or more `IdentityCredential` records, each carrying a `provider` and a `providerSubject`,
with a uniqueness constraint on `(provider, providerSubject)`. The first supported provider
SHALL be `GOOGLE`. Authentication SHALL **find-or-create** the identity by
`(provider, providerSubject)`: the first successful login for a subject creates the identity and
its credential; every later login for the same subject reuses them. The identity id SHALL be the
only value handed to other modules for ownership (never the credential, email, or subject).

#### Scenario: First Google login creates an identity, repeat login reuses it

- **GIVEN** no identity exists for a Google subject
- **WHEN** that subject completes login for the first time
- **THEN** the system creates exactly one `Identity` and one `GOOGLE` `IdentityCredential` for the
  subject
- **AND** when the same subject logs in again the system reuses the same identity and creates no
  new credential row

#### Scenario: Two distinct Google subjects are two identities

- **WHEN** two different Google subjects each log in
- **THEN** the system holds two distinct identities, each with its own credential

### Requirement: Google OAuth login is backend-mediated, Authorization-Code with PKCE

The system SHALL authenticate a user via Google OpenID Connect using the Authorization-Code flow
with PKCE, mediated entirely by the backend so that Google tokens are never exposed to the SPA.
`GET /api/auth/google/start` SHALL create a single-use, short-lived login state holding the PKCE
`code_verifier` and a random `state` (stored only as hashes) and SHALL respond with a `302`
redirect to Google's authorization endpoint requesting the `openid email profile` scopes.
`GET /api/auth/google/callback` SHALL validate the returned `state` against an unconsumed,
unexpired login state, exchange the authorization `code` for tokens using the Google client id
and client secret sourced from the environment together with the PKCE `code_verifier`, and then
proceed to ID-token validation. The Google client secret SHALL come from an environment variable
only and SHALL NOT be committed; a missing secret SHALL fail fast rather than fall back to a real
project.

#### Scenario: Login begins with a redirect to Google

- **WHEN** a client calls `GET /api/auth/google/start`
- **THEN** the system persists a single-use login state (PKCE verifier + state, hashed) and
  responds `302` to Google's authorization endpoint with the `openid email profile` scopes, a
  `state`, and a PKCE `code_challenge`

#### Scenario: A callback with an unknown or reused state is rejected

- **WHEN** `GET /api/auth/google/callback` presents a `state` that matches no unconsumed, unexpired
  login state
- **THEN** the system rejects the callback and materializes no identity and no session

### Requirement: The Google ID token is fully validated before any identity is materialized

Before creating or reusing an identity, the system SHALL validate the Google ID token: its
signature against Google's published JWKS, its issuer as an accepted Google issuer, its audience
as the configured client id, its expiry as unexpired (allowing only a small clock skew), and its
`email_verified` claim as `true`. If any check fails, the system SHALL abort the login and SHALL
NOT create an identity, credential, handoff, or session. The authorization `code` and the Google
ID/access tokens SHALL NOT be returned to the SPA and SHALL NOT be logged at any level.

#### Scenario: An unverified email is refused

- **WHEN** a Google ID token is otherwise valid but carries `email_verified == false`
- **THEN** the system aborts the login and creates no identity or session

#### Scenario: A wrong audience or expired token is refused

- **WHEN** a Google ID token has an audience other than the configured client id, or is expired
- **THEN** the system aborts the login and creates no identity or session

### Requirement: The redirect delivers a single-use handoff, not the session

On a successful callback the system SHALL create a single-use, short-lived `login_handoff` bound
to the resolved identity (stored only as a hash) and SHALL `302`-redirect the browser back to the
SPA callback route carrying only that one-time handoff code -- never a Google token and never a
durable session value.

`POST /api/auth/session/exchange` SHALL:
- accept the handoff, verify it is unconsumed and unexpired, and mark it consumed atomically;
- mint the new session;
- **only after the new session is minted**, revoke any other session named by a session cookie the
  request already carries;
- deliver the session value **only** as the session cookie (per "The session travels only in an
  HttpOnly cookie");
- return the caller's identity summary as `{ me }`.

The session value SHALL NOT appear in the response body. A handoff SHALL be usable at most once; a
second exchange, or an expired handoff, SHALL be refused, SHALL mint no session, and SHALL set no
session cookie.

The exchange is a state-changing request and SHALL require a valid CSRF token (per
`backend-security-baseline`), so a cross-site page cannot forge an exchange request. A successful
exchange SHALL also replace the CSRF token with a freshly generated one, so a token planted before
login does not survive into the signed-in session.

#### Scenario: Handoff is exchanged once for a session cookie

- **GIVEN** a valid unconsumed handoff from a successful callback and a valid CSRF token
- **WHEN** the SPA calls `POST /api/auth/session/exchange` with it
- **THEN** the system marks the handoff consumed, mints a session, sets the session cookie, and
  returns `{ me }` with the identity summary
- **AND** the response body contains no session value
- **AND** the last CSRF cookie the response sets has a value different from the token presented

#### Scenario: A reused or expired handoff mints nothing

- **WHEN** a handoff that has already been exchanged, or one past its expiry, is presented to
  `POST /api/auth/session/exchange`
- **THEN** the system refuses it, mints no session, and sets no session cookie

#### Scenario: An exchange without a CSRF token is refused

- **GIVEN** a valid unconsumed handoff
- **WHEN** `POST /api/auth/session/exchange` is sent without a matching CSRF token
- **THEN** the system responds `403` with the CSRF problem type, the handoff stays unconsumed, and
  no session is minted

#### Scenario: A refused exchange leaves the prior session intact

- **GIVEN** a browser carrying a live session cookie for identity A and a valid CSRF token
- **WHEN** it presents an unknown, reused, or expired handoff to `POST /api/auth/session/exchange`
- **THEN** the exchange is refused and A's session still authenticates

#### Scenario: Exchanging while signed in revokes the prior session

- **GIVEN** a browser carrying a live session cookie for identity A
- **WHEN** it exchanges a valid handoff for identity B
- **THEN** A's session is revoked and no longer authenticates, and the new cookie authenticates as B

### Requirement: Sessions are opaque, server-side, hashed, revocable, and expiring

A session value SHALL be a high-entropy random token generated with a cryptographically strong
RNG. It SHALL be delivered to the browser exactly once, as the session cookie, and persisted only
as a hash alongside its identity id, creation time, last-seen time, and an absolute expiry.

The system SHALL authenticate a request by reading the session cookie, hashing its value, and
looking up a live, unexpired session. A present, valid session SHALL authenticate the caller as its
identity; its absence or invalidity SHALL leave the request unauthenticated. The system SHALL NOT
authenticate from an `Authorization: Bearer` header -- the cookie is the only session transport.

`GET /api/auth/me` (authenticated) SHALL return the caller's identity summary.

`POST /api/auth/logout` SHALL be reachable without a live session and SHALL:
- revoke the session named by the cookie, when one is presented;
- always expire the session cookie;
- replace the CSRF token with a freshly generated one;
- respond `204`, or `500` when revoking the presented session failed. The cookie SHALL be expired
  and the CSRF token replaced in either case, and a failed logout SHALL NOT be reported as success.

The session value SHALL contain no PII.

#### Scenario: A minted session authenticates subsequent requests

- **GIVEN** a session minted at exchange
- **WHEN** the browser calls an authenticated endpoint carrying the session cookie
- **THEN** the system resolves the session by its hash and authenticates the caller as the session's
  identity

#### Scenario: A Bearer header no longer authenticates

- **GIVEN** a live session value
- **WHEN** a request presents it as `Authorization: Bearer <value>` with no session cookie
- **THEN** the request is treated as unauthenticated

#### Scenario: Logout revokes the session and clears the cookie

- **GIVEN** an authenticated session cookie and a valid CSRF token
- **WHEN** the caller calls `POST /api/auth/logout`
- **THEN** the system deletes the session, responds `204` with the session cookie expired and a
  fresh CSRF cookie set, and the same value no longer authenticates

#### Scenario: Logout with a stale cookie still clears it

- **GIVEN** a session cookie whose session has expired or was already revoked, and a valid CSRF
  token
- **WHEN** the caller calls `POST /api/auth/logout`
- **THEN** the system responds `204` with the session cookie expired

#### Scenario: A failed revoke still clears the cookie and is not reported as success

- **GIVEN** an authenticated session cookie and a valid CSRF token
- **WHEN** the caller calls `POST /api/auth/logout` and revoking the session fails server-side
- **THEN** the system responds `500` with the session cookie expired and a fresh CSRF cookie set

#### Scenario: An expired or unknown session does not authenticate

- **WHEN** a request presents a session cookie whose value hashes to no live, unexpired session
- **THEN** the request is treated as unauthenticated

### Requirement: Login never logs tokens or PII and never enumerates accounts

The system SHALL NOT log Google ID/access tokens, the authorization `code`, the session value, the
handoff, or the PKCE verifier at any level. Any email in a log line SHALL be redacted (local part
masked). `Identity`, `IdentityCredential`, `AuthSession`, and the login-state/handoff records SHALL
have id-only `toString()`. The login handshake SHALL NOT reveal whether an identity already existed
for a subject (the response shape is identical for first and returning logins).

#### Scenario: No secret or PII appears in logs during a full login

- **WHEN** a user completes a full login (start, callback, exchange) under DEBUG logging
- **THEN** no token, code, session value, handoff, verifier, or unredacted email appears in any log
  line

### Requirement: The session travels only in an HttpOnly cookie

The system SHALL deliver the session value only in a cookie that JavaScript cannot read, whose
`Max-Age` SHALL equal the time remaining until the session row's own expiry.

**Cookie attributes by mode:**
- When `auth.cookie.secure` is `true` (the default in every profile), the cookie SHALL be named
  `__Host-am_session` and SHALL carry `HttpOnly`, `Secure`, `SameSite=Lax`, `Path=/` and no
  `Domain`.
- When `auth.cookie.secure` is `false` -- a local-development switch for plain-http testing -- the
  cookie SHALL be named `am_session`, with `HttpOnly`, `SameSite=Lax`, `Path=/` and no `Domain`.
  The `__Host-` prefix requires `Secure`, so it SHALL be dropped together with `Secure`.

**Startup guard.** The application SHALL refuse to start when `auth.cookie.secure` is `false` and
either configured Google URI (`spa-callback-uri` or `redirect-uri`) has the scheme `https`, compared
case-insensitively. A deployed site therefore cannot serve an insecure session cookie.

**SPA behaviour.** The SPA SHALL NOT store, read, or transmit the session value itself.
- It SHALL derive the signed-in state from `GET /api/auth/me`.
- It SHALL make no signed-in or signed-out decision before that first check has resolved.
- It SHALL re-check `GET /api/auth/me` after a request other than `/me` is refused as `401`, or as a
  non-CSRF `403`.
- It SHALL remove any session value an earlier build left in browser storage.
- A session check that started before a login completed SHALL NOT overwrite that login's result.
- It SHALL show the signed-out state after logout only when the server confirmed it; a failed
  logout SHALL keep the signed-in state and tell the user to try again.

#### Scenario: The exchange sets a hardened session cookie

- **GIVEN** `auth.cookie.secure` is `true`
- **WHEN** a handoff is exchanged successfully
- **THEN** the response sets `__Host-am_session` with `HttpOnly`, `Secure`, `SameSite=Lax`,
  `Path=/`, no `Domain`, and `Max-Age` equal to the session's remaining lifetime

#### Scenario: Insecure dev mode drops Secure and the prefix

- **GIVEN** `auth.cookie.secure` is `false`
- **WHEN** a handoff is exchanged successfully
- **THEN** the response sets `am_session` with `HttpOnly`, `SameSite=Lax`, `Path=/`, no `Domain`,
  and no `Secure`
- **AND** a request carrying `am_session` is authenticated

#### Scenario: An https deployment refuses an insecure cookie

- **GIVEN** `auth.cookie.secure` is `false` and the SPA callback URI or the redirect URI uses the
  `https` scheme in any letter case
- **WHEN** the application starts
- **THEN** startup fails with a message naming `AUTH_COOKIE_SECURE`

#### Scenario: The SPA restores the login from the cookie, not storage

- **GIVEN** a browser holding a live session cookie
- **WHEN** the SPA boots
- **THEN** it calls `GET /api/auth/me` and shows the signed-in state from the response
- **AND** it holds no session value in memory or in browser storage

#### Scenario: No sign-in decision is made before the session check resolves

- **GIVEN** a signed-in browser whose boot-time `GET /api/auth/me` has not yet resolved
- **WHEN** the user opens an agreement link, opens My agreements, or creates an agreement that would
  be auto-claimed
- **THEN** the SPA waits for the check before deciding to show sign-in or to skip the claim, and no
  "Sign in" control flashes in the header

#### Scenario: A session that ends mid-use flips the SPA to signed out

- **GIVEN** the SPA shows the signed-in state and the session has since expired or been revoked
- **WHEN** an API call other than `/api/auth/me` is refused with `401`, or with a non-CSRF `403`
- **THEN** the SPA re-checks `GET /api/auth/me` once and shows the signed-out state

#### Scenario: A login completing during the boot check stays signed in

- **GIVEN** the SPA boots on the login callback route, so the boot `GET /api/auth/me` and the
  handoff exchange are in flight together
- **WHEN** the exchange resolves first and the boot check resolves afterwards as signed out
- **THEN** the SPA shows the identity from the exchange

#### Scenario: A failed logout does not pretend the user is signed out

- **GIVEN** a signed-in SPA
- **WHEN** `POST /api/auth/logout` fails with a network error or a non-`204` response
- **THEN** the SPA keeps the signed-in state and tells the user the sign-out did not complete

#### Scenario: A legacy stored session value is purged

- **GIVEN** a browser whose `sessionStorage` still holds `am.session` from an earlier build
- **WHEN** the SPA boots
- **THEN** the `am.session` entry is removed and is never sent to the server

