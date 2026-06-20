## Context

The backend (`backend/build.gradle.kts`) pulls `spring-boot-starter-web` but **not**
`spring-boot-starter-security` or `spring-boot-starter-actuator`. With Spring Security
absent from the classpath there is **no filter chain at all** — every mapped endpoint is
reachable unauthenticated, and there is no actuator surface yet either. The mapped
endpoints today are `POST /api/webhooks/esign` (real, HMAC-gated in-controller — though
the verifier is still a stub) and `POST /api/signing/{id}/request` (a stub throwing
`UnsupportedOperationException`).

This CR is the last item of the security-hardening roadmap and is **signing-adjacent**, so
it is deliberately scoped to *configuration only*: it must not alter any signing logic, the
state machine, or webhook handling. It establishes the fail-closed default that future
feature CRs inherit.

The app-root package is `in.agreementmitra` (where the `@SpringBootApplication` lives).
Spring Modulith treats the root as outside the named modules; `ModularityTests` verifies
the `signing` / `documents` / `identity` / `rules` module boundaries. Placing security
config at the application root (not inside a module) keeps it boundary-neutral.

## Goals / Non-Goals

**Goals:**
- Fail-closed (default-deny) HTTP authorization for the whole app, in one `SecurityFilterChain`.
- Keep the three currently-needed paths reachable: `/api/webhooks/esign`, `/actuator/health`,
  `/api/signing/**`.
- Lock the actuator surface to `health` only.
- Stateless, CSRF-disabled posture appropriate for a JSON API + HMAC webhook.
- A slice/integration test that pins the posture so a later careless mapping can't silently open up.

**Non-Goals:**
- No authentication mechanism (no login, user store, JWT, OAuth, API keys). Default-deny is
  the posture; an authenticator is a separate future CR. Until then `/api/signing/**` is
  explicitly permitted so the stub stays callable.
- No webhook HMAC implementation — `LeegalityEsignProvider.verifyWebhook` stays throwing;
  deferred to the `create-signing-request` feature CR.
- No change to signing FSM, `EsignProvider`, or eSign flow.
- No CORS policy (the SPA is same-origin in dev; a CORS CR can follow if needed).

## Decisions

**D1 — One `SecurityFilterChain` bean in the application root *package*, default-deny.**
A single `@Configuration` class `SecurityConfig` in `in.agreementmitra` (the root package, next
to `AgreementMitraApplication`) exposes one `SecurityFilterChain` bean using the lambda DSL.
`authorizeHttpRequests` ends with `.anyRequest().denyAll()` so anything not explicitly permitted
is rejected.
- *Why the root package, not `in.agreementmitra.config`*: Spring Modulith derives modules from
  the **direct sub-packages** of the application root, so a `config` sub-package would be
  classified as a 5th application module (and could trip `ModularityTests`). The root package
  itself is treated as shared/open — placing `SecurityConfig` there is guaranteed
  boundary-neutral. Verified: the root currently holds only `AgreementMitraApplication` + the
  four module sub-packages.
- *Why deny-all, not `authenticated()`*: there is no authenticator yet, so `authenticated()`
  would 401 every path equally — functionally similar today but semantically wrong. `denyAll()`
  states intent: "nothing reaches here until a rule or a future authenticator allows it."
  Because there is **no `AuthenticationEntryPoint`**, denied requests return **403** (not a 401
  challenge) — acceptance criteria pin 403 for determinism.
- *Alternative considered*: per-module security config — rejected; Spring Security wants one
  chain for this app, and splitting it across modules would muddy the Modulith boundaries.

**D2 — Explicit permit list; the signing permit is scoped to the exact stub path.**
`permitAll` on `/error`, `/actuator/health`, `/api/webhooks/esign`, and the **exact** signing path
`/api/signing/*/request`; everything else (including `/actuator/**` non-health and any other
`/api/signing/**` sub-path) falls through to `denyAll`.
- *Why `/error` is permitted* (discovered at manual-test): Spring Security 6 re-authorizes the
  internal dispatch Boot performs to `/error` when a handler throws. Without permitting `/error`,
  a **permitted** endpoint that errors (e.g. the webhook stub throwing) has its `/error` dispatch
  re-evaluated against `anyRequest().denyAll()`, so the real status (500) is **masked as 403**.
  Permitting the `/error` path fixes this. Direct client access to `/error` then renders only a
  generic error — `server.error.include-{message,stacktrace,binding-errors,exception}` are pinned
  off (Boot defaults, made explicit) so nothing leaks. This is the reviewer's "/error
  double-handling" finding, made concrete by the real-container test.
- *Why the narrow signing matcher, not `/api/signing/**`*: a wildcard would silently permit any
  future signing sub-path unauthenticated — the single most dangerous line in this CR for
  legal/identity infra. Scoping to `/api/signing/*/request` (the only mapped stub) means new
  signing endpoints are born **denied** and must be consciously opened. The temporary nature is
  also captured as a testable spec requirement so the auth CR cannot forget to replace it.
- The webhook is `permitAll` at the filter because the aggregator cannot present credentials;
  its authorization is *meant* to be the **HMAC check inside the controller**. **That HMAC
  verifier is deferred (still throws) — it is NOT an active control today.** The filter must not
  pre-empt the endpoint, but the spec must not present HMAC as live: until the HMAC CR lands the
  webhook's only "protection" is that the handler does nothing useful, and no webhook
  side-effects may ship before then.

**D3 — Stateless + CSRF disabled.**
`SessionCreationPolicy.STATELESS` and `csrf(AbstractHttpConfigurer::disable)`.
- *Why*: no server-side session or browser-form posting; the SPA calls a JSON API and the
  webhook is a server-to-server POST authorized by HMAC. CSRF tokens protect cookie/session
  auth, which we don't use. Leaving CSRF on would break the webhook POST for no security gain.
- *Trade-off*: when a cookie/session auth mechanism is ever introduced, CSRF posture must be
  revisited — noted as a follow-up flag, not a today problem.

**D4 — Actuator exposure locked at config, not just the filter; health detail suppressed.**
`management.endpoints.web.exposure.include: health` **and**
`management.endpoint.health.show-details: never` in `application.yml`, in addition to the filter
`denyAll` on non-health actuator paths. Defense-in-depth: the endpoints aren't even exposed over
HTTP, *and* the filter would deny them if they were, *and* the one exposed endpoint cannot leak
component detail.
- *Why all three*: exposure controls which endpoints exist on the web; the filter controls who
  can reach what exists; `show-details: never` ensures the unauthenticated `/actuator/health`
  returns only `UP`/`DOWN`, never DB/disk/component recon. Belt, suspenders, and a tucked-in
  shirt for an actuator surface that can leak env/beans/heap.
- *Assumption — single port*: this chain secures the **main server port**. If
  `management.server.port` is ever set (splitting actuator onto its own port, common in prod),
  Spring gives that port a **separate** filter chain that this bean does NOT cover — it would
  have to be secured independently. Recorded so a later prod-hardening CR doesn't assume this
  chain protects a split management port.

**D5 — New deps re-lock and re-scan.**
Adding the two starters changes the dependency graph, so `gradle.lockfile` must be regenerated
(`./gradlew dependencies --write-locks`) and the OSV gate re-run — the new transitive deps must
scan clean or the `check` build fails (per the CR-4/CR-8/CR-9 security-scan policy). Only the two
production starters are added — **no `spring-security-test`** (the test asserts raw HTTP status,
not authenticated principals, so `@WithMockUser`/security-test post-processors have no consumer;
keeping it out keeps the lockfile + OSV surface minimal).

**D6 — Test wiring: real port + TestRestTemplate (NOT MockMvc), no unit test.**
The coverage must exercise the *real* servlet pipeline, so:
- Use `@SpringBootTest(webEnvironment = RANDOM_PORT)` + `TestRestTemplate` against the Testcontainers
  harness (`@Import(HarnessTestConfig.class)`, `@ActiveProfiles("test")`,
  `@Testcontainers(disabledWithoutDocker = true)` so it skips cleanly without Docker, like the rest
  of the integration suite).
- **MockMvc was tried first and gave a FALSE POSITIVE — do not use it here.** The original plan
  (`@SpringBootTest` MOCK + `@AutoConfigureMockMvc`, DB auto-config excluded to avoid Docker)
  reported the permitted POST endpoints as passing, but the **real container returned 403**.
  Cause: MockMvc *rethrows* a handler exception instead of performing Boot's internal `/error`
  dispatch, so it never sees that the `/error` re-authorization masks the real status (see D2). A
  faithful full-pipeline test (real port, real error dispatch) is required to catch this class of
  bug — fidelity beats the convenience of a no-Docker run. (`@WebMvcTest` is also unsuitable: it
  skips `SecurityConfig` and may not register the actuator handler mapping.)
- **No unit test.** A `SecurityFilterChain` is declarative config; a mocked-`HttpSecurity` unit
  test can't `build()` a real chain and verifying "csrf().disable() was called" only tests that
  we called the framework (tautological). Per the project testing rule this is recorded as a
  one-line unit-level exemption atop `tasks.md`; the real-port integration test is the
  authoritative coverage.

## Risks / Trade-offs

- **[Default-deny locks out a future endpoint silently]** → A new controller added later without
  a matching permit rule will 403 unexpectedly. *Mitigation*: the integration test documents the
  posture; the deny-all default is the intended, discoverable behavior (fail-closed beats
  fail-open for legal/identity infra). New endpoints must add an explicit rule — that's the point.
- **[Signing-request permitAll is temporary]** → It leaves the (currently stub)
  `/api/signing/*/request` open with no auth. *Mitigation*: scoped to the exact stub path (not a
  `/**` wildcard) so no *other* signing path is open, and acceptable now because the endpoint only
  throws `UnsupportedOperationException`; a **testable** spec requirement obliges the auth /
  `create-signing-request` CR to replace this allowance before real signing logic ships, so it
  can't be silently forgotten.
- **[Webhook is permitAll while HMAC is still deferred]** → For the interval between this CR and
  the HMAC CR, `/api/webhooks/esign` is reachable, CSRF-exempt, and not actually HMAC-verified
  (the verifier throws). *Mitigation*: not exploitable today — the handler advances no FSM state
  and persists nothing (it stub-errors). The spec states explicitly that HMAC is the real control,
  is **not yet active**, and no webhook side-effects may ship before the HMAC CR lands.
- **[New transitive CVEs from the two starters]** → The OSV gate may surface findings on
  spring-security/actuator transitives. *Mitigation*: remediate by bump (preferred) per existing
  policy; only time-boxed-suppress with reason+expiry if no fix exists. This is caught at `check`,
  not in prod.
- **[CSRF-disabled assumption]** → Correct only while auth is token/HMAC and stateless. *Mitigation*:
  documented in D3; revisit when/if session-cookie auth is introduced.
- **[Modulith boundary]** → Config in the wrong package could trip `ModularityTests`. *Mitigation*:
  place it in the application root **package** `in.agreementmitra` itself (NOT a `config`
  sub-package, which would become a module) alongside `AgreementMitraApplication`; run the
  modularity test.
