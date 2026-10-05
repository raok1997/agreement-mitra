## ADDED Requirements

### Requirement: A login is bound to the browser that started it

The system SHALL complete a Google login only in the browser that started it, by checking a login-binding nonce at both the callback and the session exchange.

**Binding cookie.** `GET /api/auth/google/start` SHALL generate a fresh random nonce, store only its
hash on the new login-state row, and set it in a login-binding cookie:
- when `auth.cookie.secure` is `true`, named `__Host-am_login` with `HttpOnly`, `Secure`,
  `SameSite=Lax`, `Path=/` and no `Domain`;
- when `auth.cookie.secure` is `false`, named `am_login` with `HttpOnly`, `SameSite=Lax`, `Path=/`
  and no `Domain`;
- with a `Max-Age` of at least the login-state TTL plus the handoff TTL, so it is still present at
  the exchange.

`SameSite` SHALL be `Lax`, not `Strict`: Google's redirect to the callback is a cross-site top-level
GET navigation, on which a `Strict` cookie is not sent.

The system SHALL read the login-binding cookie only under the name for the configured mode. In
secure mode a presented `am_login` SHALL be ignored, because only the `__Host-` name is protected
against being planted by a sibling host or over plain http.

**Callback.** `GET /api/auth/google/callback` SHALL consume the login state only when the request
carries a login-binding cookie whose hash equals the hash stored on that state, checked in the same
atomic statement that marks the state consumed, with no prior read of the row. A missing or
different cookie SHALL be refused identically to an unknown state: no token exchange with Google, no
identity, no handoff, and the state left unconsumed. Every login refusal at the callback -- a
missing, unknown, reused, expired or unbound state, a missing or different binding, or a failed
token exchange or ID-token validation -- SHALL respond with a `302` to the SPA callback route with
the fixed fragment `#error`, identical for every cause. The browser therefore lands on the SPA's
sign-in error screen rather than a raw error body, and an explicit fragment replaces any fragment the
original request carried, so the SPA never exchanges a handoff on this path. An infrastructure
failure (for example a database outage) is not a login refusal and keeps its server-error response.
On success the minted handoff SHALL be bound to the same nonce.

**Exchange.** `POST /api/auth/session/exchange` SHALL consume the handoff only when the request
carries a login-binding cookie whose hash equals the hash stored on that handoff, checked in the same
atomic statement that marks the handoff consumed. A missing or different cookie SHALL be refused
identically to an unknown handoff: the fixed `401` login-failed response, no session minted, no
session cookie set, the handoff left unconsumed, and every cookie the browser already holds -- its
session and its own login-binding cookie -- left untouched. A successful exchange SHALL expire the
login-binding cookie.

**Logout.** `POST /api/auth/logout` SHALL also expire the login-binding cookie, so an unexchanged
handoff from the previous user of a shared browser cannot be completed after logout.

A login-state or handoff row with no stored binding hash SHALL never be consumed.

This requirement extends "Google OAuth login is backend-mediated, Authorization-Code with PKCE" (the
callback's refusal response), "The redirect delivers a single-use handoff, not the session" (the
exchange's consume condition) and "Sessions are opaque, server-side, hashed, revocable, and
expiring" (logout's cookie effects). A later change to any of those SHALL keep this requirement
true or modify it in the same change.

The nonce SHALL NOT appear in any response body, redirect URL, or error; only the `Set-Cookie`
header of the start response carries it.

#### Scenario: A login completed in the same browser succeeds

- **GIVEN** a browser that called `GET /api/auth/google/start` and received the login-binding cookie
- **WHEN** Google redirects that browser to the callback with a valid `code` and `state`, and the SPA
  then exchanges the handoff with a valid CSRF token
- **THEN** the callback redirects with a handoff, the exchange sets the session cookie and returns
  `{ me }`
- **AND** the exchange response expires the login-binding cookie

#### Scenario: The start response sets a hardened login-binding cookie

- **GIVEN** `auth.cookie.secure` is `true`
- **WHEN** a browser calls `GET /api/auth/google/start`
- **THEN** the response sets `__Host-am_login` with `HttpOnly`, `Secure`, `SameSite=Lax`, `Path=/`,
  no `Domain`, and a `Max-Age` of at least the login-state TTL plus the handoff TTL
- **AND** the stored login-state row holds only a hash of the cookie's value

#### Scenario: Insecure dev mode drops Secure and the prefix from the login-binding cookie

- **GIVEN** `auth.cookie.secure` is `false`
- **WHEN** a browser calls `GET /api/auth/google/start`
- **THEN** the response sets `am_login` with `HttpOnly`, `SameSite=Lax`, `Path=/`, no `Domain`, and
  no `Secure`

#### Scenario: Secure mode ignores an unprefixed login-binding cookie

- **GIVEN** `auth.cookie.secure` is `true` and a valid handoff bound to nonce N
- **WHEN** a request presents that handoff with N in an `am_login` cookie and no `__Host-am_login`
- **THEN** the exchange is refused and the handoff remains unconsumed

#### Scenario: An attacker's unfollowed callback URL is refused in the victim's browser

- **GIVEN** an attacker started a login in their own browser and stopped before following Google's
  redirect, holding a valid `code` and `state`
- **WHEN** the victim's browser -- carrying no login-binding cookie, or its own from a login it
  started -- requests that callback URL
- **THEN** the response is a `302` to the SPA callback route with the fragment `#error`, no code is
  exchanged with Google, no handoff is minted, and the state remains unconsumed

#### Scenario: An attacker's handoff link is refused in the victim's browser

- **GIVEN** a valid unconsumed handoff minted for the attacker's browser
- **WHEN** the victim's browser -- carrying a valid CSRF token and either no login-binding cookie or
  its own from a login it started -- presents it to `POST /api/auth/session/exchange`
- **THEN** the exchange is refused with the fixed `401` login-failed problem, no session is minted,
  no session cookie is set, and the handoff remains unconsumed
- **AND** the victim's own login-binding cookie is not expired

#### Scenario: A refused binding leaves the victim's own session intact

- **GIVEN** a browser carrying a live session cookie for identity A and a valid CSRF token
- **WHEN** it presents a handoff bound to a different browser to `POST /api/auth/session/exchange`
- **THEN** the exchange is refused and A's session still authenticates

#### Scenario: A refused callback never exchanges a fragment it inherited

- **GIVEN** a link to the callback with an invalid `state` and a trailing `#handoff=<value>`
- **WHEN** the browser follows it
- **THEN** the response `Location` ends in `#error`, and the SPA shows its sign-in error without
  calling the exchange

#### Scenario: A row without a binding is never consumed

- **GIVEN** a login-state or handoff row whose binding hash is null
- **WHEN** a callback or exchange presents its `state` or handoff, with or without a login-binding
  cookie
- **THEN** it is refused and the row remains unconsumed

#### Scenario: Logout expires the login-binding cookie

- **GIVEN** a browser holding a login-binding cookie
- **WHEN** it calls `POST /api/auth/logout`
- **THEN** the response expires the login-binding cookie with the same name, `Path` and `Secure`
  attribute it was set with

#### Scenario: The nonce appears in no response body or redirect

- **WHEN** a full login runs start, callback and exchange
- **THEN** the login-binding nonce appears in no response body and no redirect `Location`; only the
  start response's `Set-Cookie` header carries it

## MODIFIED Requirements

### Requirement: Login never logs tokens or PII and never enumerates accounts

The system SHALL NOT log Google ID/access tokens, the authorization `code`, the session value, the
handoff, the login-binding nonce, or the PKCE verifier at any level. Any email in a log line SHALL be
redacted (local part masked). `Identity`, `IdentityCredential`, `AuthSession`, and the
login-state/handoff records SHALL have id-only `toString()`. The login handshake SHALL NOT reveal
whether an identity already existed for a subject (the response shape is identical for first and
returning logins).

#### Scenario: No secret or PII appears in logs during a full login

- **WHEN** a user completes a full login (start, callback, exchange) under DEBUG logging
- **THEN** no token, code, session value, handoff, login-binding nonce, verifier, or unredacted email
  appears in any log line
