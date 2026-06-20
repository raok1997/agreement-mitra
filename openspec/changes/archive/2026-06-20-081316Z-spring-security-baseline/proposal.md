## Why

The backend ships with no Spring Security and no Actuator on the classpath, so
**every endpoint is wide open** — including the eSign webhook intake and any future
business endpoint. For identity/legal infra this is a fail-open default. We need a
fail-closed security baseline in place *before* the signing feature work lands, so new
endpoints inherit deny-by-default rather than being retrofitted later.

## What Changes

- Add `spring-boot-starter-security` and `spring-boot-starter-actuator` dependencies.
- Introduce **one** `SecurityFilterChain` config bean (`SecurityConfig` in the application
  **root** package `in.agreementmitra`, alongside `AgreementMitraApplication` — a sub-package
  would be classified by Spring Modulith as a 5th module) establishing a **default-deny /
  fail-closed** posture:
  - **Stateless** (`SessionCreationPolicy.STATELESS`); **CSRF disabled** for the JSON API
    (no browser-form/session flow; the SPA + webhook are token/HMAC-driven).
  - `permitAll` on `/api/webhooks/esign` — the aggregator cannot bearer-authenticate, so this
    endpoint is *intended* to be authorized at the application layer via HMAC. **That HMAC
    verifier is NOT implemented yet** (it throws; deferred — see non-goals). Until it lands, the
    webhook is reachable but the handler does nothing useful (it stub-errors), so there is no
    exploitable side-effect — but no webhook side-effects may ship before the HMAC CR.
  - `permitAll` on `/error` — Spring Security re-authorizes Boot's internal dispatch to `/error`
    when a handler throws; without this, a *permitted* endpoint that errors has its `/error`
    dispatch masked as 403 instead of returning its real status. Boot's `server.error.include-*`
    are pinned off so direct `/error` access leaks nothing.
  - `permitAll` on `/actuator/health` only.
  - `permitAll` scoped to the **exact** signing stub path `/api/signing/*/request` (not a broad
    `/api/signing/**` wildcard) — kept callable until a future auth CR wires a real
    authentication mechanism. The narrow matcher avoids silently birthing future signing
    sub-paths unauthenticated.
  - `denyAll` on everything else, including all non-health actuator endpoints. Denied requests
    return **403** (there is no `AuthenticationEntryPoint`, so no 401 challenge).
- **Actuator lockdown**: expose only `health` over HTTP
  (`management.endpoints.web.exposure.include: health`) **and** pin
  `management.endpoint.health.show-details: never` so health cannot leak DB/disk component
  detail to unauthenticated callers.
- **Security headers**: adding the starter enables Spring Security's default response headers
  (`X-Content-Type-Options`, `Cache-Control`, etc.) — an intended security gain, asserted by the test.

### Explicit non-goals (out of scope — deferred)

- **No** webhook HMAC implementation. `LeegalityEsignProvider.verifyWebhook` stays throwing
  `UnsupportedOperationException`; real HMAC verification is deferred to the
  `create-signing-request` feature CR. CR-5 only ensures the filter chain does not block the
  webhook endpoint — it does not change signing logic.
- **No** authentication mechanism: no login, no user store, no JWT/OAuth. Default-deny is the
  posture; wiring a real authenticator is a separate future CR.
- **No** change to the signing state machine, `EsignProvider`, or any eSign flow.

## Capabilities

### New Capabilities
- `backend-security-baseline`: the runtime HTTP security posture for the backend — a
  default-deny Spring Security filter chain, the per-path allow rules (webhook, actuator
  health, signing stub), stateless/CSRF policy, and actuator endpoint exposure. Distinct from
  `backend-security-scanning` (build-time dependency/SAST gates).

### Modified Capabilities
<!-- None. The webhook intake behavior (HMAC verification) is unchanged by this CR —
     it stays stubbed; this CR only governs whether the filter chain reaches the endpoint. -->

## Impact

- **Dependencies**: +`spring-boot-starter-security`, +`spring-boot-starter-actuator`
  (`backend/build.gradle.kts`); regenerate `gradle.lockfile` (`./gradlew dependencies
  --write-locks`) and re-run the OSV gate (new deps must scan clean).
- **Code**: new `SecurityConfig` `@Configuration` in the application **root** package
  `in.agreementmitra` (not a sub-package — keeps it boundary-neutral) exposing one
  `SecurityFilterChain` bean. No changes to the `signing`, `documents`, `identity`, or `rules`
  modules. Must keep `ModularityTests` green.
- **Config**: `application.yml` gains `management.endpoints.web.exposure.include: health` and
  `management.endpoint.health.show-details: never`.
- **Runtime behavior change**: requests to undeclared paths and non-health actuator endpoints
  now return **403** instead of 200/404. Permitted paths (webhook, the signing-request stub,
  health) remain reachable. *Assumption*: actuator shares the main server port; if
  `management.server.port` is ever split, that port gets its own chain and must be secured
  separately.
- **Tests**: one real-port integration test (`@SpringBootTest(RANDOM_PORT)` + `TestRestTemplate`
  against the Testcontainers harness, Docker-gated like the rest of the suite) asserting the filter
  chain end-to-end (denied paths → 403, permitted paths reach their controllers → 500 stub, actuator
  locked, security headers present). MockMvc was deliberately rejected — it rethrows handler
  exceptions and skips Boot's `/error` dispatch, so it false-passed the permitted-endpoint cases.
  `ModularityTests` stays green. No unit test — see the tasks.md exemption note (declarative config,
  no pure-unit-testable logic).

## PII / Security review checklist

- **New/moved Aadhaar/OTP/VID/PII or secret flow?** **None.** This CR adds no data flow — it
  only restricts HTTP access. It reads no secrets (the webhook secret is already env-wired and
  unused by this CR since HMAC is out of scope) and logs no request bodies.
- **Redaction/securing:** N/A — no payloads are read or logged by the filter chain. The
  webhook controller's existing "never log payload verbatim" contract is untouched.
- **Sandbox + dummy data only:** preserved — no provider calls, no real data, no new secrets.
- **Net effect on PII exposure:** strictly *reduces* surface (default-deny closes previously
  open endpoints).
- **Signing-status FSM transitions touched:** none. CR-5 does not advance or alter any
  `DRAFT → PDF_GENERATED → SIGN_REQUESTED → SIGNED | FAILED | EXPIRED` transition; it is
  config-only and the webhook handler logic is unchanged.
