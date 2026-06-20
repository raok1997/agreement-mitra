# Backend Security Baseline

## Purpose

The backend's runtime HTTP security posture: a single, default-deny (fail-closed)
Spring Security `SecurityFilterChain` and the actuator/error lockdown around it.
Defines which paths are permitted (the eSign webhook, actuator health, the signing
stub, and the error dispatch), that everything else is denied with 403, the
stateless/CSRF-disabled JSON-API posture, and the no-leak guarantees for actuator
health and the error endpoint. Distinct from `backend-security-scanning` (build-time
dependency/SAST gates). No authentication mechanism exists yet — this is a posture,
not an authenticator.

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

### Requirement: Signing stub endpoint permitted pending an auth mechanism

The filter chain SHALL permit only the exact signing-request stub path (the matcher
`/api/signing/*/request`) so the stub remains callable while no authentication mechanism exists.
The permit MUST be scoped to that path only and MUST NOT use a broad `/api/signing/**` wildcard,
so that any future signing sub-path is denied by default rather than born unauthenticated. This
allowance is explicitly temporary and MUST be tightened (removed or replaced with authenticated
access) when an authentication mechanism or real signing logic is introduced.

#### Scenario: Signing-request stub passes the filter
- **WHEN** `POST /api/signing/{agreementId}/request` is received
- **THEN** the security filter chain does not reject it with 403, and the request reaches the controller (which currently throws its own stub error)

#### Scenario: Other signing sub-paths are denied by default
- **WHEN** a request is made to a different `/api/signing/**` path that is not `*/request` (e.g. `GET /api/signing/list`)
- **THEN** the system responds 403 (the wildcard is not open; new sub-paths must be consciously permitted)

### Requirement: Stateless, CSRF-disabled JSON API posture

The security configuration SHALL use stateless session management
(`SessionCreationPolicy.STATELESS`) and SHALL disable CSRF protection, because the API is a
stateless JSON API with no server-side session or browser-form flow, and the webhook is a
server-to-server POST authorized by HMAC.

#### Scenario: No HTTP session is created
- **WHEN** any request is processed by the filter chain
- **THEN** no server-side `HttpSession` is created for authentication purposes

#### Scenario: State-changing POST is not blocked by CSRF
- **WHEN** `POST /api/webhooks/esign` is received without a CSRF token
- **THEN** the request is not rejected for a missing CSRF token

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
