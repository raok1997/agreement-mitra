# google-oauth-login Specification

## Purpose

Backend-mediated Google sign-in and the server-side session it produces. Covers the
provider-agnostic `Identity` aggregate with pluggable `IdentityCredential` records, the
Authorization-Code-with-PKCE handshake against Google OpenID Connect, full ID-token
validation, the single-use handoff that the redirect carries back to the SPA, and the
opaque, hashed, revocable bearer sessions minted from it -- together with the
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
durable session value. `POST /api/auth/session/exchange` SHALL accept the handoff, verify it is
unconsumed and unexpired, mark it consumed atomically, mint the session, and return the session
value once together with the caller's identity summary. A handoff SHALL be usable at most once;
a second exchange, or an expired handoff, SHALL be refused and SHALL mint no session.

#### Scenario: Handoff is exchanged once for a session

- **GIVEN** a valid unconsumed handoff from a successful callback
- **WHEN** the SPA calls `POST /api/auth/session/exchange` with it
- **THEN** the system marks the handoff consumed, mints a session, and returns the session value
  once with the identity summary

#### Scenario: A reused or expired handoff mints nothing

- **WHEN** a handoff that has already been exchanged, or one past its expiry, is presented to
  `POST /api/auth/session/exchange`
- **THEN** the system refuses it and mints no session

### Requirement: Sessions are opaque, server-side, hashed, revocable, and expiring

A session value SHALL be a high-entropy random token generated with a cryptographically strong
RNG, returned to the SPA exactly once, and persisted only as a SHA-256 hash alongside its
identity id, creation time, last-seen time, and an absolute expiry. The system SHALL authenticate
a request by reading the `Authorization: Bearer` value, hashing it, and looking up a live,
unexpired session in constant time; a present, valid session SHALL authenticate the caller as its
identity, and its absence or invalidity SHALL leave the request unauthenticated. `GET /api/auth/me`
(authenticated) SHALL return the caller's identity summary. `POST /api/auth/logout` (authenticated)
SHALL revoke the caller's session and respond `204`. The session value SHALL contain no PII.

#### Scenario: A minted session authenticates subsequent requests

- **GIVEN** a session minted at exchange
- **WHEN** the SPA calls an authenticated endpoint with `Authorization: Bearer <session>`
- **THEN** the system resolves the session by its hash and authenticates the caller as the session's
  identity

#### Scenario: Logout revokes the session

- **GIVEN** an authenticated session
- **WHEN** the caller calls `POST /api/auth/logout`
- **THEN** the system deletes the session and responds `204`, and the same value no longer
  authenticates

#### Scenario: An expired or unknown session does not authenticate

- **WHEN** a request presents a `Bearer` value that hashes to no live, unexpired session
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
