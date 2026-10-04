# Backend Security Baseline

## Purpose

The backend's runtime HTTP security posture: a single, default-deny (fail-closed)
Spring Security `SecurityFilterChain` and the actuator/error lockdown around it.
Defines which paths are permitted (the eSign webhook, actuator health, the signing
stub, and the error dispatch), that everything else is denied with 403, the
stateless JSON-API posture with double-submit CSRF protection (webhooks exempt), and the
no-leak guarantees for actuator health and the error endpoint. Distinct from
`backend-security-scanning` (build-time dependency/SAST gates). This capability owns the
filter chain and route authorization; the authenticator itself — the Google handshake and
the opaque cookie sessions the filter consumes — lives in `google-oauth-login`.
## Requirements
### Requirement: Default-deny HTTP authorization

The backend SHALL register exactly one Spring Security `SecurityFilterChain` that denies
every request by default. Any request path not matched by an explicit allow rule SHALL be
rejected with HTTP **403** — the system MUST fail closed. Because no authentication mechanism
(and therefore no `AuthenticationEntryPoint`) exists yet, denied requests SHALL NOT return a
401 challenge.

#### Scenario: Unmapped path is denied with 403
- **WHEN** a request is made to a path with no explicit permit rule (e.g. `GET /api/anything`)
- **THEN** the system responds with **403** (not 200, not 404, not 401)

#### Scenario: Exactly one filter chain is present
- **WHEN** the application context starts
- **THEN** exactly one `SecurityFilterChain` bean is registered and Spring Security is on the classpath

#### Scenario: Error dispatch does not mask a permitted endpoint's real status
- **WHEN** a permitted endpoint (e.g. the webhook stub) is reached and throws, so Spring Boot performs an internal dispatch to `/error`
- **THEN** the `/error` dispatch is permitted (the filter chain permits the `/error` path) so the real status (e.g. 500) is returned, NOT re-evaluated against `denyAll` and masked as 403

#### Scenario: Direct error path access leaks nothing
- **WHEN** a client requests `/error` directly
- **THEN** the response is a generic error body with no stack trace, exception class, message, or binding detail (Boot `server.error.include-*` pinned off)

### Requirement: Webhook endpoint is reachable through the filter; HMAC is the deferred control

The eSign webhook endpoint `/api/webhooks/esign` SHALL be permitted by the security filter
chain without Spring Security authentication, because the aggregator cannot present
credentials. Authorization for this endpoint is intended to be performed at the application
layer via HMAC verification in the controller. That HMAC verifier is **NOT implemented by this
change** (it currently throws) and is therefore **not an active control**; this requirement
does not implement or alter it. No webhook side-effect (FSM advance, persistence) may be shipped
before the HMAC verification CR lands.

#### Scenario: Webhook POST passes the filter to the controller
- **WHEN** `POST /api/webhooks/esign` is received
- **THEN** the security filter chain does not reject it with 403, and the request reaches the controller (which currently stub-errors because the HMAC verifier is unimplemented — the filter passing it through, not a success response, is what this scenario asserts)

### Requirement: Actuator surface limited to health, no detail leak

Only the `health` actuator endpoint SHALL be reachable. All other actuator endpoints SHALL be
both unexposed over HTTP and denied by the filter chain (defense-in-depth). The exposed health
endpoint SHALL NOT reveal component details to unauthenticated callers
(`management.endpoint.health.show-details: never`).

#### Scenario: Health endpoint is reachable without detail
- **WHEN** `GET /actuator/health` is requested
- **THEN** the system responds 200 with only overall status (`UP`/`DOWN`), exposing no DB/disk/component breakdown, and the filter chain does not deny it

#### Scenario: Non-health actuator endpoint is not reachable
- **WHEN** `GET /actuator/env` (or `/actuator/beans`, `/actuator/heapdump`) is requested
- **THEN** the system does not return that endpoint's data (it is denied/unexposed, returning 403/404 — never 200 with actuator data)

### Requirement: Default security response headers are applied

With Spring Security on the classpath, its default security response headers SHALL be applied to
responses (an intended hardening gain over the prior no-security state).

#### Scenario: Hardening headers present on a permitted response
- **WHEN** a permitted request (e.g. `GET /actuator/health`) is served
- **THEN** the response carries Spring Security's default headers (e.g. `X-Content-Type-Options: nosniff`, a no-cache `Cache-Control`)

### Requirement: Module boundaries preserved

The security configuration SHALL live in the application **root** package `in.agreementmitra`
(alongside `AgreementMitraApplication`), NOT in a sub-package, so Spring Modulith does not
classify it as a module and module boundaries remain intact. `ModularityTests` SHALL stay green.

#### Scenario: Modularity verification passes
- **WHEN** `ModularityTests` runs after this change
- **THEN** it passes with no module-boundary violations and no new module is introduced

### Requirement: Role-based authorization for staff operations

The system SHALL carry a **role** on the authenticated principal and SHALL support at least
two roles: **CUSTOMER** (the default for a self-service signup) and **STAFF** (an
AgreementMitra operator). Role SHALL be a server-managed property of the account: it SHALL NOT
be settable by the client at signup, on login, or through any request body or header, and
SHALL NOT be derived from any claim supplied by the external identity provider.

Every newly provisioned account SHALL default to **CUSTOMER**. Granting STAFF SHALL be an
out-of-band administrative action.

Staff-only endpoints SHALL be authorized under the existing **default-deny** posture: the
endpoint is denied unless the caller is authenticated **and** holds the required role. For a
request that carries a valid CSRF token, an unauthenticated caller SHALL receive `401` and an
authenticated caller lacking the role SHALL receive `403`. An unsafe request without a valid CSRF
token SHALL instead receive the CSRF `403` (per "Stateless JSON API posture with double-submit CSRF
protection"), which precedes these checks. Authorization SHALL be evaluated **before** any resource
lookup, so a refusal reveals nothing about whether the referenced resource exists.

Role checks SHALL NOT replace ownership checks: an endpoint scoped to a customer's own
resources SHALL still verify ownership even for a STAFF caller, unless a requirement explicitly
grants staff cross-customer access (as `estamp-intake` does for stamp upload).

#### Scenario: New accounts default to CUSTOMER

- **WHEN** a new account is provisioned through the login flow
- **THEN** it is assigned the CUSTOMER role

#### Scenario: Role cannot be self-assigned

- **WHEN** a client supplies a role value in a request body, header, or identity-provider claim
- **THEN** the supplied value is ignored and the server-managed role is unchanged

#### Scenario: Staff-only endpoint denies a customer

- **WHEN** a CUSTOMER-role caller invokes a staff-only endpoint with a valid CSRF token
- **THEN** the response is `403` (not the CSRF problem type) and no side effect occurs

#### Scenario: Staff-only endpoint denies an anonymous caller

- **WHEN** an unauthenticated caller invokes a staff-only endpoint with a valid CSRF token
- **THEN** the response is `401` and no side effect occurs

#### Scenario: A staff call without a CSRF token gets the CSRF refusal

- **WHEN** an unauthenticated caller sends an unsafe request to a staff-only endpoint without a CSRF
  token
- **THEN** the response is `403` with type `urn:agreementmitra:problem:csrf` and no side effect
  occurs

#### Scenario: Authorization precedes resource lookup

- **WHEN** an unauthorized caller invokes a staff-only endpoint naming a resource that does
  not exist
- **THEN** the response is the authorization failure (`401`/`403`), not `404`, so the endpoint
  is not an existence oracle

#### Scenario: Default-deny still covers unlisted endpoints

- **WHEN** the role model is introduced
- **THEN** endpoints not explicitly permitted remain denied by default, and the existing
  default-deny verification stays green

### Requirement: Session authentication filter and login-handshake authorization

The security baseline SHALL include a session-authentication filter while leaving every existing
route authorization unchanged except where this requirement says otherwise. The filter SHALL
authenticate a request from the opaque session cookie (per the `google-oauth-login` capability). It
SHALL ignore any `Authorization` header, and it SHALL be a no-op when the cookie is absent or invalid,
leaving the request unauthenticated for the deny-by-default chain to handle.

The Google login handshake, the session exchange, the CSRF bootstrap, and logout SHALL be reachable
without a session: `GET /api/auth/google/start`, `GET /api/auth/google/callback`,
`POST /api/auth/session/exchange`, `GET /api/auth/csrf`, and `POST /api/auth/logout` SHALL be
`permitAll`. Logout is permitted so that a browser holding a stale cookie can still have it cleared.
`GET /api/auth/me` SHALL require authentication.

All other existing route authorization SHALL be unchanged by this capability: anonymous agreement
create, draft upload, capability read (`GET /api/agreements/{id}`), the signing routes, and the
HMAC-authenticated webhooks remain exactly as before (agreement-route gating is introduced by a
separate change). The chain SHALL remain deny-by-default for any unmatched route, and the actuator
lockdown SHALL be unchanged.

#### Scenario: The login handshake is reachable without a session

- **WHEN** an unauthenticated client calls `GET /api/auth/google/start`, `GET /api/auth/csrf`, or
  `POST /api/auth/session/exchange` (with a valid CSRF token)
- **THEN** the chain permits the request (no session is required to log in)

#### Scenario: me requires a session, logout does not

- **WHEN** an unauthenticated client calls `GET /api/auth/me`
- **THEN** the request is rejected as unauthenticated
- **AND** with a valid session cookie the filter authenticates the caller and the request is allowed
- **AND** an unauthenticated `POST /api/auth/logout` with a valid CSRF token responds `204`

#### Scenario: Existing routes are not regressed

- **WHEN** an unauthenticated client calls `POST /api/agreements` (with a valid CSRF token) or
  `GET /api/agreements/{id}` for an existing agreement
- **THEN** the request is permitted exactly as before this change

### Requirement: Owner-route authorization for agreements

The security baseline SHALL gate the save / resume / edit routes behind authentication while keeping
anonymous drafting open. `GET /api/agreements` (list mine), `POST /api/agreements/*/claim`, and
`PUT /api/agreements/*` (edit) SHALL require authentication (via the session filter introduced by the
`google-oauth-login` capability). Anonymous agreement create (`POST /api/agreements`), draft upload
(`POST /api/agreements/*/draft`), and capability read (`GET /api/agreements/{id}`) SHALL remain
`permitAll`; owner-scoping for a claimed agreement's read is enforced in the handler, not the filter chain.

The authenticated matchers for `GET /api/agreements`, `POST /api/agreements/*/claim`, and
`PUT /api/agreements/*` SHALL be ordered **before** the broader `permitAll` matchers for
`/api/agreements/*`, so that list, claim, and edit are gated while anonymous create and capability read
remain open. The signing routes, the HMAC-authenticated webhook, and the `google-oauth-login` filter and
handshake permits SHALL be unchanged. The chain SHALL remain deny-by-default for any unmatched route.

#### Scenario: Anonymous drafting stays open while save, list, and edit are gated

- **WHEN** an unauthenticated client calls `POST /api/agreements` (with a valid CSRF token) and
  `GET /api/agreements/{id}` for an unowned agreement
- **THEN** both are permitted
- **AND** an unauthenticated `GET /api/agreements`, `POST /api/agreements/{id}/claim`, or
  `PUT /api/agreements/{id}` is rejected as unauthenticated

#### Scenario: A valid session authenticates the gated routes

- **WHEN** a client calls `GET /api/agreements`, `POST /api/agreements/{id}/claim`, or
  `PUT /api/agreements/{id}` with a valid session cookie (and a valid CSRF token on the unsafe methods)
- **THEN** the session filter authenticates the caller and the request is allowed

#### Scenario: Matcher order keeps the capability read open

- **GIVEN** the authenticated `GET /api/agreements` matcher and the `permitAll`
  `GET /api/agreements/{id}` matcher
- **WHEN** an unauthenticated client GETs `/api/agreements/{id}` for an unowned agreement
- **THEN** the request is permitted (the list matcher does not shadow the capability read)

### Requirement: Stateless JSON API posture with double-submit CSRF protection

The security configuration SHALL keep stateless session management (`SessionCreationPolicy.STATELESS`,
no `HttpSession`) and SHALL enable CSRF protection with a cookie-based double-submit token.

**Which requests need the token.** A request with an unsafe method (`POST`, `PUT`, `PATCH`,
`DELETE`) SHALL be refused unless its `X-XSRF-TOKEN` header matches the CSRF cookie. The token SHALL
be read from that header only, never from a request parameter or body. The requirement SHALL apply
to anonymous and authenticated requests alike, including `POST /api/auth/session/exchange` and
`POST /api/auth/logout`.

**The CSRF cookie.**
- It SHALL be named `__Host-XSRF-TOKEN` when `auth.cookie.secure` is `true`, and `XSRF-TOKEN` when
  it is `false`.
- It SHALL be readable by JavaScript and SHALL carry `SameSite=Lax` and `Path=/`.
- It SHALL carry `Secure` under the same switch as the session cookie.

**Exemptions.** Exactly two routes SHALL be exempt, matched by method and exact path:
`POST /api/webhooks/esign` and `POST /api/webhooks/razorpay`. They are server-to-server calls with no
browser cookie, authorized by their own HMAC or key. No other route SHALL be exempt -- in
particular, not `POST /api/agreements/*/payment/callback`. The configuration SHALL offer no
property, profile, or alternate filter chain that disables CSRF protection.

**The refusal.**
- A CSRF refusal SHALL respond `403` with an `application/problem+json` body of type
  `urn:agreementmitra:problem:csrf`, with a fixed title and detail. Neither the body nor any log
  line SHALL contain the presented token or the exception message.
- When the request lacked the configured CSRF cookie, the refusal SHALL itself set a fresh one.
- Every other access-denied response SHALL be unchanged.
- CSRF verification SHALL precede authentication and authorization. An unsafe request without a
  valid token is therefore refused with the CSRF `403` before any `401` or role-based `403` is
  decided.

**Eager issuance.**
- Any API response to a browser that does not yet hold the CSRF cookie SHALL set it.
- `GET /api/auth/csrf` SHALL be `permitAll` and SHALL respond `204` with the CSRF cookie set, so a
  client can obtain a token before its first unsafe request.

#### Scenario: No HTTP session is created

- **WHEN** any request is processed by the filter chain
- **THEN** no server-side `HttpSession` is created

#### Scenario: An unsafe request without a CSRF token is refused

- **WHEN** an anonymous client sends `POST /api/agreements` with no `X-XSRF-TOKEN` header
- **THEN** the system responds `403` with type `urn:agreementmitra:problem:csrf` and creates no
  agreement

#### Scenario: A mismatched CSRF token is refused

- **WHEN** a client sends an unsafe request whose `X-XSRF-TOKEN` header differs from the CSRF cookie
- **THEN** the system responds `403` with type `urn:agreementmitra:problem:csrf`

#### Scenario: A refusal does not reflect the presented token

- **WHEN** an unsafe request presents an `X-XSRF-TOKEN` header with a distinctive invalid value
- **THEN** the `403` body does not contain that value

#### Scenario: A planted non-prefixed token recovers on retry

- **GIVEN** `auth.cookie.secure` is `true` and a browser holding only a non-prefixed `XSRF-TOKEN`
  cookie
- **WHEN** it sends an unsafe request echoing that cookie's value
- **THEN** the system responds `403` with the CSRF problem type and sets `__Host-XSRF-TOKEN`, and the
  request succeeds when retried with that cookie's value

#### Scenario: A token in a request parameter is not accepted

- **GIVEN** a client holding the CSRF cookie
- **WHEN** it sends an unsafe request carrying the token as a `_csrf` parameter and no header
- **THEN** the system responds `403` with type `urn:agreementmitra:problem:csrf`

#### Scenario: A matching CSRF token is accepted

- **GIVEN** a client holding the CSRF cookie
- **WHEN** it sends `POST /api/agreements` with `X-XSRF-TOKEN` equal to the cookie value
- **THEN** the request proceeds as it did before CSRF was enabled

#### Scenario: Webhooks are exempt from CSRF

- **WHEN** `POST /api/webhooks/esign` or `POST /api/webhooks/razorpay` is received without a CSRF
  token or cookie
- **THEN** the request is not refused for CSRF and reaches its HMAC or key verification

#### Scenario: The exemption is exact

- **WHEN** a client without a CSRF token sends `POST /api/agreements/{id}/payment/callback`, or an
  unsafe request to a path under `/api/webhooks/esign/`
- **THEN** the system responds `403` with type `urn:agreementmitra:problem:csrf`

#### Scenario: A first-time anonymous visitor can obtain a token

- **GIVEN** a browser holding no CSRF cookie
- **WHEN** it calls `GET /api/auth/csrf`
- **THEN** the system responds `204` and sets the CSRF cookie

#### Scenario: Any API response issues the token eagerly

- **GIVEN** a browser holding no CSRF cookie
- **WHEN** it calls any permitted `GET` API endpoint
- **THEN** the response sets the CSRF cookie

#### Scenario: Insecure mode names the CSRF cookie without the prefix

- **GIVEN** `auth.cookie.secure` is `false`
- **WHEN** a browser without a CSRF cookie calls `GET /api/auth/csrf`
- **THEN** the response sets `XSRF-TOKEN` without `Secure`

### Requirement: Signing-request endpoint restricted to staff

The filter chain SHALL require the STAFF authority on the signing-request path (the matcher
`/api/signing/*/request`), and SHALL NOT permit it anonymously. The permit MUST remain scoped to
that path only and MUST NOT use a broad `/api/signing/**` wildcard, so that any future signing
sub-path is denied by default rather than born unauthenticated. Like every unsafe
request, a call to the path SHALL still carry a valid CSRF token, in addition to the STAFF authority.

This replaces the temporary anonymous allowance the requirement previously described, and is the
tightening that allowance itself demanded ("MUST be tightened ... when an authentication mechanism
or real signing logic is introduced"). Both conditions now hold: session authentication and the
STAFF authority exist, and signing is real.

The route is a **retry hatch, not a customer path**. Signing is initiated server-side once the
e-stamp is attached, so no customer request creates a signing request. Making it STAFF-only
therefore removes an anonymous route that egresses signer personal data to the eSign vendor and
draws on paid provider quota, without removing any customer capability -- which is strictly better
than rate-limiting it.

#### Scenario: An anonymous signing request is rejected
- **WHEN** `POST /api/signing/{agreementId}/request` is received without a STAFF session
- **THEN** the security filter chain rejects it with 403, and the request does not reach the
  controller

#### Scenario: Signing-request stub passes the filter
- **WHEN** `POST /api/signing/{agreementId}/request` is received from a caller holding the STAFF
  authority and with a valid CSRF token
- **THEN** the filter chain does not reject it and the request reaches the controller
- **AND** the same request without that authority does not reach the controller
- **AND** the same STAFF request without a valid CSRF token does not reach the controller

#### Scenario: Other signing sub-paths are denied by default
- **WHEN** a request is made to a different `/api/signing/**` path that is not `*/request` (e.g. `GET /api/signing/list`)
- **THEN** the system responds 403 (the wildcard is not open; new sub-paths must be consciously permitted)

#### Scenario: Customer-driven signing is unaffected
- **WHEN** staff attach an e-stamp to a paid agreement
- **THEN** the signing request is created server-side exactly as before, with no anonymous call
  involved

