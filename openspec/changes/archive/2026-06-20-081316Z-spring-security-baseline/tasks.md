<!-- Unit-test exemption (per the project Testing rule): a Spring Security SecurityFilterChain
     is declarative configuration with no pure-unit-testable logic — a mocked-HttpSecurity test
     cannot build a real chain and only verifies that framework methods were called (tautological).
     The authoritative coverage is the full-context integration slice test (task 3.1). This is the
     same config-exemption mechanism used by earlier harness CRs; integration coverage is present. -->

## 1. Dependencies & lockfile

- [x] 1.1 Add `spring-boot-starter-security` and `spring-boot-starter-actuator` to `backend/build.gradle.kts` (implementation). Do NOT add `spring-security-test` — the test asserts raw HTTP status, not authenticated principals
- [x] 1.2 Regenerate the lockfile: `./gradlew dependencies --write-locks`; confirm only the intended new transitives appear
- [x] 1.3 Run the OSV gate (`./gradlew securityScan` or `check`); if new transitives surface CVEs, remediate by bump (preferred) or time-boxed suppression with reason+expiry per the security-scan policy — do not leave the gate red

## 2. Security configuration

- [x] 2.1 Create `SecurityConfig` (`@Configuration`) in the application **root** package `in.agreementmitra` (alongside `AgreementMitraApplication`, NOT a sub-package), exposing one `SecurityFilterChain` bean via the lambda DSL
- [x] 2.2 Configure posture: `SessionCreationPolicy.STATELESS`, CSRF disabled (`AbstractHttpConfigurer::disable`)
- [x] 2.3 Configure `authorizeHttpRequests`: `permitAll` on `/error` (so a permitted endpoint that errors surfaces its real status instead of the /error re-dispatch being masked as 403), `/actuator/health`, `/api/webhooks/esign`, and the exact signing path `/api/signing/*/request` (NOT a `/api/signing/**` wildcard); terminate with `.anyRequest().denyAll()`
- [x] 2.4 Add to `application.yml`: `management.endpoints.web.exposure.include: health` + `management.endpoint.health.show-details: never` (actuator lockdown), and `server.error.include-{message,stacktrace,binding-errors,exception}` pinned off (so the now-reachable /error leaks nothing)

## 3. Test (integration slice — see exemption note for the unit-level rationale)

- [x] 3.1 Integration test exercising the **real** servlet pipeline over a real port: `@SpringBootTest(webEnvironment = RANDOM_PORT)` + `TestRestTemplate` against the Testcontainers harness (`@Import(HarnessTestConfig.class)`, `@ActiveProfiles("test")`, `@Testcontainers(disabledWithoutDocker = true)`). **Do NOT use MockMvc** — it rethrows handler exceptions and skips Boot's `/error` dispatch, so it false-passes the permitted-endpoint cases (this was caught the hard way at manual-test). Assert:
  - an unmapped path → **403**; a non-`*/request` signing sub-path → 403
  - `POST /api/webhooks/esign` and `POST /api/signing/{id}/request` are permitted and reach their controllers → **500** (stub throws), proving the filter + /error dispatch did NOT block them, and no CSRF token was needed
  - `GET /actuator/health` → 200 with status only (no `components`); `GET /actuator/env` denied/unexposed (in {401,403,404}, never 200 with data)
  - Spring Security's default hardening headers present (`X-Content-Type-Options: nosniff`)
  - exactly one `SecurityFilterChain` bean registered
- [x] 3.2 Confirm `ModularityTests` stays green (config lives in the app root package, no new module introduced)

## 4. Verify

- [x] 4.1 Run `./gradlew check` (or `./run-tests.sh`) — all gates green: tests, JaCoCo, `securityScan` (OSV + SpotBugs/FindSecBugs), ModularityTests
- [x] 4.2 Sanity-check no signing logic / state machine / `EsignProvider` / webhook handler was changed (config-only diff outside `SecurityConfig`, `build.gradle.kts`, lockfile, `application.yml`, the new test)
