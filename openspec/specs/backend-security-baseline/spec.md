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

### Requirement: Agreement ids are redacted in logs, exception messages and named toString output
The backend SHALL never write a raw agreement id to an application log line or to the message of an exception it constructs, nor to the `toString()` of `Agreement`, `SigningCompletionView`, `StaffAgreementView`, `StampInfo`, `StampQueueEntry`, `PaymentConfirmation`, `PaymentConfirmedEvent`, `RazorpayClient.ProviderOrder` or `SignRequest`; it SHALL render the id instead as its first 8 hexadecimal characters followed by `…`. The agreement id is a bearer capability (holding it grants anonymous read, draft upload, finalise, contact edit and payment), so a raw id in a log is a working credential in the hands of anyone with log access. The 8-character prefix leaves 90 of the UUID's 122 random bits unknown; an operator finds the row with a prefix match, which can return more than one row and is disambiguated by timestamp or tracking reference. The rule applies to an agreement id embedded in a larger string as well — an object-storage key such as `drafts/<agreementId>.pdf` is logged and put into exception messages in redacted form. The named `toString()` types are those with a hand-written override plus the internal records that carry the id; response DTO records serialised to JSON and never logged are outside this requirement. Ids that are not capabilities (signing-request, delivery, identity, provider ids, tracking references) are outside it too; provider ids keep their existing last-4 redaction.

#### Scenario: An id-bearing log statement prints the redacted form
- **GIVEN** an agreement whose id is `1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d`
- **WHEN** the backend logs an event about that agreement at any level (draft stored, order placed, payment recorded or waived, e-Stamp attached, signing request created, signing window extended, invitations re-sent, stamping fallback, closed as completed or abandoned)
- **THEN** the log line contains `1a2b3c4d…`
- **AND** it does not contain the full id

#### Scenario: INFO-level closure and fallback lines are redacted
- **GIVEN** the `in.agreementmitra` logger at its production default `INFO`
- **WHEN** an agreement closes as completed or as abandoned, or is stamped onto its stored draft without a re-render
- **THEN** the emitted INFO line contains the redacted id and not the full id

#### Scenario: A stored-object log line redacts the id inside the key
- **GIVEN** a draft PDF stored under the key `drafts/1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d.pdf`
- **WHEN** the blob store logs the write
- **THEN** the line shows `drafts/1a2b3c4d….pdf`
- **AND** a failed read or write raises an exception whose own message shows the same redacted key

#### Scenario: A constructed exception message carries no raw id
- **GIVEN** a lookup for an agreement id that does not exist, an agreement with no signed document, or an agreement that vanishes between two reads
- **WHEN** the signing module throws `ResourceNotFoundException` or `IllegalStateException` for it
- **THEN** the exception message contains the 8-character redacted id and not the full id
- **AND** the HTTP problem detail is unchanged (it never carried the message)

#### Scenario: A unique-constraint violation does not log the key value
- **GIVEN** the shipped default datasource URL
- **WHEN** an insert violates a unique constraint keyed on the agreement id (e.g. two racing checkout starts)
- **THEN** the URL carries `logServerErrorDetail=false`, so the driver message Hibernate logs at ERROR omits the `Key (agreement_id)=(…)` detail

#### Scenario: Named toString output redacts the id
- **WHEN** `toString()` is called on any of the named types carrying an agreement id or an id-bearing blob key
- **THEN** the output shows only the redacted form

#### Scenario: A full lifecycle at DEBUG leaks no agreement id
- **GIVEN** the `in.agreementmitra` logger is set to `DEBUG` and every log event reaching the root logger is captured
- **WHEN** an agreement is created, given a draft, finalised (order placed and signing request created), has its payment waived by staff, receives its e-Stamp, and has eSign initiated
- **THEN** the capture includes the draft-stored, stored-object and signing-request-created lines, each showing the redacted id
- **AND** the signing request reached `SIGN_REQUESTED`
- **AND** no captured formatted message, nor any message in a captured throwable's cause chain, contains the agreement's full id

#### Scenario: A new raw agreement id in a log call or exception message fails the build
- **GIVEN** a log statement, or a `new …Exception(…)` / `new …Error(…)` construction, in `backend/src/main/java` whose arguments include an agreement-id expression (`agreementId`, `.agreementId()`, `agreement.id()`, `agreement.getId()`) not wrapped in `AgreementIds.redact` or `AgreementIds.redactIn`
- **WHEN** the unit test suite runs
- **THEN** a source-scan test fails and names the file and line

### Requirement: Application logging defaults to INFO outside the local profile
The backend SHALL default the `in.agreementmitra` logger to `INFO`, and only the `local` profile SHALL raise it to `DEBUG`; an operator MAY still override it with `LOGGING_LEVEL_IN_AGREEMENTMITRA`. DEBUG output on identity/legal infrastructure is opt-in per deployment, never the shipped default. Tests that assert the absence of sensitive values in DEBUG lines SHALL pin their logger to `DEBUG` themselves, so the default change cannot turn them into assertions over an empty capture.

#### Scenario: A deployment without an override logs at INFO
- **GIVEN** the backend's shipped configuration with a profile other than `local` and no `LOGGING_LEVEL_IN_AGREEMENTMITRA`
- **WHEN** the configured level of the `in.agreementmitra` logger is read
- **THEN** it is `INFO`

#### Scenario: The local profile keeps DEBUG
- **GIVEN** the `local` profile's configuration
- **WHEN** the configured level of the `in.agreementmitra` logger is read
- **THEN** it is `DEBUG`

#### Scenario: Existing DEBUG redaction tests still observe the DEBUG lines
- **GIVEN** the Razorpay webhook, Leegality and Zoop redaction tests that assert sensitive values are absent from DEBUG log lines
- **WHEN** they run under the new `INFO` default
- **THEN** each pins its logger to `DEBUG` for the test and asserts at least one `DEBUG`-level event was captured before asserting absence

### Requirement: The drafting surface is owner-scoped once an agreement is claimed

The system SHALL answer `POST /api/agreements/{id}/document` (generate), `POST /api/agreements/{id}/draft` (draft upload), `GET /api/agreements/{id}/preview` and `POST /api/agreements/{id}/finalise` for an **unclaimed** agreement to any caller presenting its id, and for a **claimed** agreement only to its owner. This extends "Owner-route authorization for agreements", whose `permitAll` filter-chain posture for these routes is unchanged: the owner check is made in the request path below the filter chain, because only that path can see the row's owner, as for `GET /api/agreements/{id}`.

Any other caller (anonymous, or authenticated as a different identity, including STAFF) SHALL receive the same `404 Not Found` problem response as for an unknown id, with the same problem type, title and detail, so that ownership cannot be probed through these routes. A request refused by the owner check SHALL make no write after the refusal: no draft is stored or replaced, no template pin is written, no signing request is created, and the agreement's last-edited time is unchanged. Every write a generate request makes, the template pin included, SHALL re-check the owner under the same row lock that a claim takes. A claim that commits partway through a link holder's generate request therefore stops that request's remaining writes and can never be overwritten by them.

The owner check SHALL be evaluated before every refusal that depends on the agreement's existence or state: the draft freeze (`409`), the closed-agreement refusal, the no-draft-to-finalise refusal and the jurisdiction gate. It SHALL also be evaluated before any document is rendered. Refusals that depend only on the request itself are answered identically for every agreement id, reveal nothing about a particular agreement, and MAY precede the owner check. These include the non-UUID `400`, CSRF, request-size limits, the multipart part-count check, checks on the content of uploaded bytes, and rate limiting.

The rule "unowned, or owned by the caller" SHALL be defined once, on the agreement aggregate. Every route that applies it SHALL use that one definition: the drafting surface, the capability read, the contacts route, the payment surface, the stamp quote, signing progress and the signed-document download.

#### Scenario: An unclaimed agreement stays open to any link holder

- **GIVEN** an agreement nobody has claimed
- **WHEN** an anonymous caller generates its draft, uploads a draft, previews it and finally finalises it
- **THEN** each request is served as before

#### Scenario: The owner of a claimed agreement is served

- **GIVEN** an agreement claimed by identity A
- **WHEN** A, with a valid session, generates, uploads, previews or finalises it
- **THEN** each request is served as before, and A's finalise returns the same tracking reference and status as it did before this change

#### Scenario: A non-owner gets the unknown-agreement 404 and changes nothing

- **GIVEN** an agreement claimed by identity A, with a stored draft
- **WHEN** an anonymous caller, identity B with a valid session, or a STAFF session generates, uploads, previews or finalises it
- **THEN** each request is refused `404 Not Found` with the same problem type, title and detail as a request for an unknown id
- **AND** the stored draft, its template pin and the last-edited time are unchanged, and no signing request exists

#### Scenario: A non-owner cannot learn a claimed agreement's state from the status code

- **GIVEN** an agreement claimed by identity A whose order has already been placed
- **WHEN** an anonymous caller or identity B uploads a valid PDF, generates, previews or finalises it
- **THEN** each request is refused `404 Not Found`, never `409`

#### Scenario: A write racing a claim cannot land on the claimed agreement

- **GIVEN** an unclaimed agreement
- **WHEN** an anonymous upload, or the template-pin write of an anonymous generate, races a claim by identity A
- **THEN** the write either completes before the claim takes effect, or is refused `404`
- **AND** the claim, and any edit A makes after it, is never overwritten by that write

