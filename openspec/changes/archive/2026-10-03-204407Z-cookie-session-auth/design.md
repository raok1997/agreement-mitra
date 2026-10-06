## Context

`google-oauth-login` (archived 2026-09-07) shipped opaque, hashed, revocable, Postgres-backed
sessions: the `auth_session` table (V11), the `AuthSession` entity and `SessionService`. That CR
deliberately sent the session as `Authorization: Bearer` from SPA memory. Its D4 and Risks sections
name "HttpOnly; Secure; SameSite cookie + CSRF defence" as the production follow-up. Commit `c79549d`
then mirrored the value into `sessionStorage` so a reload keeps the login, which widened XSS
exposure. It was labelled a stopgap.

Current state (grounded in round 1):

- **Security chain** (`SecurityConfig`, root package):
  - `csrf(disable)` at `:97` and `STATELESS` at `:98`.
  - No CORS. No `@Value`. Matchers are `HttpMethod` + String paths.
  - `logout` is `authenticated()` at `:331`.
  - The entry point returns `401` on `/api/staff/` and `403` elsewhere (`:346-354`).
  - The only machine callers are the two webhooks.
- **Session filter** (`identity.api.SessionAuthenticationFilter`, public, with a package-private
  constructor built by `AuthWebConfig`) parses Bearer at `:78-85`.
- **Session service** (`identity.session.SessionService`, Modulith-internal):
  - computes `expires_at` inline at `:70` but does not return it in `SessionIssued(value, me)`;
  - `authenticate` writes `last_seen_at` on every call.
- **`AuthController`:**
  - exchange returns `SessionResponse(session, me)`;
  - logout reads the `Authorization` header;
  - failures map to a `urn:agreementmitra:problem:login-failed` ProblemDetail.
- **No controller parses the transport.** They all take `@AuthenticationPrincipal UUID`.
- **SPA:**
  - There are 27 `fetch(` calls, all in `src/api/`.
  - `authHeader()` is spread by agreements, payments, signingProgress, stampQuote and staffQueue.
    `auth.ts` builds the Bearer header itself.
  - `auth.session` is read synchronously by `App.vue` (`:95`, `:149`, `:209`, `:221`, `:252`, the
    header at `:302-343`, `isStaff` at `:70`/`:359-365`), `CaptureForm.vue` (`:662`, `:707`) and
    `AgreementStatus.vue:51`.
  - There is no cookie helper and no vitest `setupFiles`.
- **Backend test wiring:**
  - `HarnessTestConfig` is opt-in through `@Import`, on 40 classes.
  - 31 test classes autowire Boot's `TestRestTemplate`, which is built from the `RestTemplateBuilder`.
    All of them `@Import` `HarnessTestConfig`.
  - Two `@AutoConfigureMockMvc` suites run the real chain: `DocumentProjectionApiIntegrationTest`
    (10 POSTs) and `AgreementDocumentFormatE2EIntegrationTest` (4 POSTs).
  - `spring-security-test` is not a dependency.
  - `setBearerAuth` appears in 18 files.
- **Spring Security 6.5.11** (verified in the locked jar):
  - no `csrf().spa()`;
  - `CsrfFilter.doFilterInternal` calls `requestHandler.handle(...)` **before**
    `requireCsrfProtectionMatcher`, so it runs for every request;
  - `CookieCsrfTokenRepository` has `withHttpOnlyFalse()` and `setCookieCustomizer`;
  - `XorCsrfTokenRequestAttributeHandler` is final, while `CsrfTokenRequestAttributeHandler` is not.
- **Deployment:** production is single-origin (Caddy serves the SPA and proxies `/api/*`). Dev is
  same-origin via the Vite proxy. There is no `prod` Spring profile.

## Goals / Non-Goals

**Goals:**
- Make the session value unreachable from JavaScript by putting it in an HttpOnly, `__Host-`
  prefixed cookie.
- Make the cookie the single session transport: remove Bearer rather than deprecate it.
- Defend every unsafe browser request against CSRF, anonymous ones included, with no regression for
  first-time anonymous visitors. A CSRF refusal must be distinguishable from an authorization
  refusal.
- Keep local dev working on `http://localhost`, and allow plain-http LAN testing without weakening
  any https deployment.
- Put "establish a browser session" behind one identity-module operation (`SessionCookies.establish`:
  revoke the prior session, set the cookie, rotate CSRF) that `mobile-otp-auth`, an intra-module
  caller, reuses whole.

**Non-Goals:**
- **A durable session store.** It already exists (V11); the stale register row is deleted.
- **Purging expired auth rows and the per-request `last_seen_at` write.** Both go to the register
  as `auth-expired-row-purge`.
- **Sliding or idle expiry, CORS, and Content-Security-Policy.**
- **Binding the OAuth `state` and handoff to the initiating browser.** Neither is bound today. This
  CR's exchange-CSRF does **not** close that gap; see Risks. It is split out to
  `login-browser-binding` (user decision, 2026-10-04).
- **Any change to signing, payment or webhook authorization**, beyond browser calls now sending the
  CSRF header.

## Decisions

### D1 — Session cookie: `__Host-am_session`, `HttpOnly; Secure; SameSite=Lax; Path=/`, Max-Age from the row

The `__Host-` prefix makes the browser reject the cookie unless it is `Secure` and `Path=/` with no
`Domain`. A sibling subdomain therefore cannot set or overwrite it (cookie tossing), and the cookie
is never sent to `www.` or any other host.

`SameSite=Lax`, not `Strict`:
- Lax withholds the cookie from cross-site POST, iframe and fetch requests.
- Strict would also drop it on top-level navigations from email links, so the owner-scoped
  `GET /api/agreements/{id}/signed-document` link in delivery emails would look logged-out.
- Lax is defence in depth, not the CSRF control. Subdomains count as same-site, so D3's token is
  still required.
- Lax is safe only while **GET handlers stay side-effect-free.** Round 1 checked every
  `@GetMapping` and found none that change state. The single exception is
  `GET /api/auth/google/start`, which writes an anonymous `oauth_login_state` row and is harmless.
  That rule becomes load-bearing, so it is added to CLAUDE.md Conventions.

`Max-Age` is derived from the session row's own `expires_at`:
- `SessionIssued` gains `Instant expiresAt`.
- `Max-Age` is `expiresAt − now`, floored at 0.
- This gives one source of truth instead of a second `now + ttl` computation. The server row stays
  authoritative: an expired row behind a live cookie is unauthenticated.

*Rejected:* a session cookie with no Max-Age. Closing the browser would sign the user out, which
regresses the reload and Back behaviour `c79549d` fixed.

### D2 — Bearer is removed, not tolerated

`SessionAuthenticationFilter` reads only the cookie and ignores `Authorization`. One credential
path means one place to harden, and it avoids the classic CSRF bypass of skipping the check when
an `Authorization` header is present.

The 18 test files that call `setBearerAuth` move to a cookie helper (D9).

### D3 — CSRF: double-submit cookie, header-only resolution, distinguishable refusal

Spring Security 6.5.11 has no `csrf().spa()`, so the configuration assembles the same parts by
hand. **It departs from the reference SPA recipe in two ways, explained below:** token resolution
is header-only, and the Xor (BREACH-masking) half is dropped.

```java
http.csrf(c -> c
    .csrfTokenRepository(csrfTokenRepository)   // from identity.api (D6)
    .csrfTokenRequestHandler(new HeaderOnlyCsrfTokenRequestHandler())
    .ignoringRequestMatchers(
        PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/webhooks/esign"),
        PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/webhooks/razorpay")))
  .exceptionHandling(e -> e.accessDeniedHandler(csrfAwareAccessDeniedHandler));
```

**Repository.** `CookieCsrfTokenRepository.withHttpOnlyFalse()`:
- The cookie is named `__Host-XSRF-TOKEN`, or `XSRF-TOKEN` in insecure mode (D5).
- The header is `X-XSRF-TOKEN`.
- A customizer sets `secure`, `sameSite("Lax")` and `path("/")`.
- It is stateless and needs no `HttpSession`, so `STATELESS` is unchanged.

**`HeaderOnlyCsrfTokenRequestHandler`** (root package, package-private, extends the non-final
`CsrfTokenRequestAttributeHandler`):
- Its `resolveCsrfTokenValue` returns **only** the `X-XSRF-TOKEN` header. It never falls back to the
  `_csrf` request parameter.
- That fallback would make `CsrfFilter` call `getParameter`, which parses form or multipart bodies
  (draft upload allows about 11 MB) **before** authorization. The SPA always sends the header, and
  there are no server-rendered forms.
- Xor masking only protects a token rendered into a compressed response body. Nothing renders one,
  and `/api/auth/csrf` returns no body. If a token is ever rendered, reintroduce Xor at that point.

**Distinguishable refusal.** `CsrfAwareAccessDeniedHandler` (root package):
- For a `CsrfException` (missing or invalid token) it writes `403` `application/problem+json` with
  type `urn:agreementmitra:problem:csrf`. This follows the `login-failed` and
  `GlobalExceptionHandler` URN pattern.
  - The body has a fixed title and detail. The exception message is **never** put in the body,
    `instance`, or logs. `InvalidCsrfTokenException`'s message embeds the attacker-supplied header
    value.
- Every other `AccessDeniedException` is delegated to `AccessDeniedHandlerImpl`, unchanged, so the
  existing bare `403`s that baseline tests pin stay exactly as they are.

**Precedence (an explicit contract change).**
- `CsrfFilter` runs before authentication and authorization. An unsafe request with no valid token
  is therefore refused with the CSRF `403` before the staff `401` entry point or any role check.
- With a valid token, the existing `401`/`403` contracts are untouched.
- This is written into the MODIFIED "Role-based authorization for staff operations" and "Signing
  stub" requirements, not left to test edits.

**Why double-submit is enough here.**
- The token is not bound to the session, so its integrity rests on an attacker being unable to
  write the CSRF cookie.
- The `__Host-` prefix closes the subdomain-write path, and single-origin hosting leaves no other
  origin to read it.
- A planted non-prefixed `XSRF-TOKEN` from a sibling host could make the SPA echo the wrong value.
  Recovery works like this:
  - The server reads only the configured name, so the request is refused.
  - That same `403` response carries `Set-Cookie: __Host-XSRF-TOKEN`, because the eager handler
    generates a token whenever the configured cookie is absent.
  - The SPA's single retry (D8) re-reads the cookies, prefers `__Host-`, and succeeds.
  - `GET /api/auth/csrf` cannot rotate an *existing* cookie, so do not rely on it for this.
  - A stale `__Host-` cookie left over from a mode switch is **not** recoverable this way, which is
    why D5 documents clearing cookies.

**Exemptions** are exactly the two webhooks, matched by method and exact path. They come from
servers with no browser cookie and are authorized by HMAC or key before any side effect. Two
things are **not** exempt, and tests pin both:
- `POST /api/agreements/*/payment/callback`, because the SPA calls it after Razorpay Checkout;
- near paths and other methods on the webhook paths.

### D4 — Eager token issuance, and rotation

Spring 6 defers CSRF token generation. Without a forcing step, an anonymous visitor whose first API
call is `POST /api/agreements` would hold no cookie and be refused. Two layers handle this:

1. **Every request issues it.** `HeaderOnlyCsrfTokenRequestHandler` calls
   `setCsrfRequestAttributeName(null)`. That is Spring's documented opt-out of deferred tokens: the
   handler loads the token during `handle`, which `CsrfFilter` invokes on every request before the
   protection check. A request with no cookie therefore gets `Set-Cookie`.
   - Verified in the 6.5.11 bytecode: with a null attribute name, `handle` calls
     `getParameterName()` on the supplier-backed token, and that forces the deferred load.
   - This includes webhook and `/actuator/health` responses. That is intentional and harmless.
     Do not "fix" it.
   - An integration test (5.2) exercises this exact handler: a GET with no cookie must receive
     `Set-Cookie`.
2. **An explicit bootstrap.** `GET /api/auth/csrf` (`permitAll`, `204`, empty body) gives the SPA
   a deterministic way to obtain the cookie.

The SPA uses both:
- On boot it calls `GET /api/auth/me` (D8), which lays down the cookie as a side effect.
- Before any unsafe call, the fetch wrapper checks for the cookie. If it is absent, it awaits one
  de-duplicated `GET /api/auth/csrf`.
- The CSRF cookie has no Max-Age (browser-session lifetime), while the session cookie persists. So
  after a browser restart the **expected** first-unsafe-call path is `ensureCsrf()` bootstrapping.
  That is the normal case, not an edge case.

**Rotation** happens through `SessionCookies` (D6), in the same module as the repository, so it
cannot be silently skipped:
- **At login:** `establish` generates and saves a fresh token, so a token planted before login does
  not survive into the signed-in session.
- **At logout:** `clear` does the same, so the token does not carry into the next user's use of a
  shared browser.
- It is a single `saveToken(generateToken(...))`, not `saveToken(null)` followed by a save. If
  layer 1 already emitted a `Set-Cookie` for a cookieless request, the rotated one is the later
  header and the browser keeps the last. Tests assert on the **last** `Set-Cookie` for that name.
- The request's CSRF attribute still holds the pre-rotation token. That is harmless because
  nothing reads it after the controller.

### D5 — Cookie security switch for local dev

`auth.cookie.secure` (env `AUTH_COOKIE_SECURE`) defaults to `true` in **every** profile. It is
bound only through `AuthProperties.cookie`; no `@Value` reads it anywhere.

- **`http://localhost` works with `true`.** Chrome treats `localhost` as a secure context and
  accepts `Secure` and `__Host-` cookies from it. Current Firefox does too, but this varies by
  version, so the manual-test gate checks it on the team's Firefox.
- **`false` is for the cases that do not work:**
  - a LAN device hitting `http://192.168.x.x:5173`, which the browser does not treat as a secure
    context, so it silently drops `Secure` cookies;
  - Safari.

  It drops `Secure` and the `__Host-` prefix from both cookies, giving `am_session` and
  `XSRF-TOKEN`. Both still have no `Domain` and `Path=/`.
- **Switching modes leaves a stale cookie of the other name.** Clear site cookies when switching.
  This is documented next to the switch.
- **LAN testing *requires* `false`, even for anonymous drafting.** With `true`, a LAN browser drops
  the `Secure` CSRF cookie, so every anonymous POST fails after the single retry. Google sign-in
  cannot work from a LAN IP anyway, because the redirect URI is localhost.
- **Guard (`AuthProperties` compact constructor).** Startup fails when `cookie.secure` is `false`
  and either `google.spaCallbackUri` or `google.redirectUri` parses (via `URI.getScheme()`,
  case-insensitively, after trimming) to `https`. The message names `AUTH_COOKIE_SECURE`.
  - It is null-safe: a missing `cookie` block means `secure=true`, and a missing `google` block or
    URI simply supplies no https signal.
  - It **fails closed on an unparseable URI**: while `secure=false`, a non-null URI that throws
    `URISyntaxException`, or that parses with a null or blank scheme (for example a relative
    `/auth/callback`), refuses startup. Neither counts as "no https signal".
  - **Accepted proxy risk.** With no `prod` profile, deployment-ness is inferred from Google's
    URIs. If a deployment omitted both `GOOGLE_OAUTH_*_URI` vars **and** set
    `AUTH_COOKIE_SECURE=false`, the guard would pass. Production env already sets both to https
    (`backend.env.example:125-126`), and the switch defaults to secure.
  - `mobile-otp-auth` must not weaken this. If it adds a provider-neutral public-origin property,
    the guard should move to that property; this is noted in the D10 amendment.

### D6 — `SessionCookies` (package-private, `identity.api`): the one owner of browser-session cookies

Browser-session transport belongs to identity, so identity owns **both** cookies: the session and
CSRF. This is a deliberate choice: the CSRF *enforcement* stays in the root chain, but the cookie it
reads is defined beside the session cookie it protects.

- **`AuthWebConfig` (package-private) defines two beans** from `AuthProperties`:
  - `SessionCookies` (a **package-private** class in `identity.api`; every caller is in that
    package);
  - `CsrfTokenRepository`, a `CookieCsrfTokenRepository` named and attributed per D3/D5.
- **`SessionCookies`** is constructor-injected with `AuthProperties`, `SessionService` (same module)
  and that repository. It exposes:
  - `void establish(HttpServletRequest, HttpServletResponse, SessionIssued)`. It is called **only
    after** a session was minted successfully, and in order it:
    1. sets the new session cookie, with Max-Age from `expiresAt` (D1);
    2. rotates the CSRF token (D4);
    3. **last**, revokes any *different* session named by the request's existing session cookie
       (D7).

    Revoke goes last so that a database error there cannot cost the user the new login. At worst
    it leaves an orphan prior row, which nobody can use once its cookie is overwritten, and which
    expires at its TTL.
  - `clear` does **not** revoke. Logout's revoke stays in the controller (D7), because logout has
    no new session to establish first.
  - `void clear(HttpServletRequest, HttpServletResponse)` — expires the session cookie (`Max-Age=0`,
    same name, path and attributes) and rotates the CSRF token;
  - `Optional<String> read(HttpServletRequest)` — reads the configured session cookie name only, and
    treats blank as absent.
- **`SecurityConfig`** takes `ObjectProvider<CsrfTokenRepository>`, the same pattern it already uses
  for the filter. When identity is absent (module slices), it falls back to a default secure
  `CookieCsrfTokenRepository`, configured explicitly as `withHttpOnlyFalse`, named
  `__Host-XSRF-TOKEN`, `Secure`, `SameSite=Lax`, `Path=/`, with a comment naming `AuthWebConfig`'s
  bean and `CsrfTestInterceptor` as the copies it must match. That matches the test interceptor, so a
  slice test that POSTs behaves like the full app.
  - The fallback is safe because a slice without identity has no session to protect.
  - The full app always has identity's bean: `AuthWebConfig` is unconditional, and 5.2's cookie-name
    assertion pins it.
- **No new public type.** `SessionIssued` (`identity.session`) gains `expiresAt` and is used
  directly. Every caller, including `mobile-otp-auth`'s verify controller, is inside the identity
  module, so exporting cookie-minting on the module's `@NamedInterface("api")` would add surface no
  other module needs.
- **No new cross-module dependency.** `SecurityConfig` already imports `identity.api`, and
  `CsrfTokenRepository` is a Spring type.

### D7 — Endpoint changes

**`POST /api/auth/session/exchange`** stays `permitAll` and now **requires CSRF**.
- What CSRF here *does* stop: a cross-site page forging the exchange POST.
- What it does *not* stop: handoff injection, where a victim opens `/auth/callback#handoff=<attacker's>`
  and their own SPA posts it with a valid token. That gap pre-dates this CR (it exists today under
  Bearer); see Risks.
- **Order is load-bearing.** The controller first calls `SessionService.exchange`, which consumes
  the handoff and mints the session in one transaction. Only if that succeeds does it call
  `SessionCookies.establish`, which revokes the browser's prior session.
  - A refused exchange (unknown, reused or expired handoff → `401` login-failed) therefore leaves
    the prior session and its cookie untouched.
  - So a link to `/auth/callback#handoff=garbage` cannot log a signed-in user out.
  - A *valid* attacker handoff still replaces the victim's session. That is the
    `login-browser-binding` gap, not a new one.
  - Revoking the prior session means a browser switching accounts leaves no orphan live credential.
- It returns `{ me }`; `SessionResponse` drops `session`.
- The CSRF filter rejects a request before the controller runs, so a refused exchange leaves the
  handoff unconsumed.

**`POST /api/auth/logout`** changes from `authenticated` to `permitAll`, and still requires CSRF so
it cannot be used for forced logout.
- It revokes the cookie's session if one is present, then calls `SessionCookies.clear`, and returns
  `204`.
- JavaScript cannot delete an HttpOnly cookie, so the server must always be able to.

**`GET /api/auth/csrf`** is new: `permitAll`, `204`.

**`GET /api/auth/me`** is unchanged: `authenticated`, and anonymous callers get the existing `403`.

### D8 — SPA: one fetch wrapper, readiness-aware sign-in state, reconciliation

**`src/api/cookies.ts`** exports `readCookie(name)`. It is its **own module** so tests can `vi.mock`
it: an export spied inside the module that calls it would not be intercepted under ESM, and jsdom
will not reliably hold `__Host-` cookies on `http://localhost`.

**`src/api/http.ts`** exports:
- `apiFetch(input, init)`, with `credentials: "same-origin"`;
- `ensureCsrf()`, which bootstraps only when **neither** CSRF cookie is present.

What `apiFetch` does:
- **Unsafe methods.** It awaits `ensureCsrf()` and sets `X-XSRF-TOKEN` from `__Host-XSRF-TOKEN`,
  falling back to `XSRF-TOKEN` when the prefixed cookie is absent.
- **CSRF retry: exactly once, only on the CSRF problem type.** If the response is `403` with
  `type: urn:agreementmitra:problem:csrf`, it re-runs `ensureCsrf()` (bootstrapping only if no
  CSRF cookie is present), re-reads the cookie, and retries the same request once. The `403` itself
  normally carries the fresh cookie (D3).
  - This is safe because `CsrfFilter` refused the request before any handler ran.
  - A bare `403` is **never** retried. Payment-order and signing-request POSTs are not idempotent.
- **Reconciliation.** While the store says signed in, after any `401` or non-CSRF `403` from a call
  outside `/api/auth/*`, it signals the auth store to re-check `/me` once (de-duplicated).
  - On a network error the re-check keeps the current state, so a network blip does not sign the
    user out.
  - So a session that expired or was revoked mid-use (TTL reached, or logout in another tab) flips
    the header to signed-out instead of leaving `me` stale.
  - Excluding `/api/auth/*` prevents a loop, and stops a failed exchange or logout from triggering
    a re-check.

**Every raw `fetch(` in `src/api/*.ts` moves to `apiFetch`**, including the anonymous modules.
- `authHeader()` is deleted.
- A guard test fails if any `src/api` module other than `http.ts` calls a bare `fetch(`,
  `window.fetch(` or `globalThis.fetch(`. The matcher is `(?<![\w$])fetch\(` plus the two qualified
  forms, so `apiFetch(` does not count.

**`authStore.ts`:**
- The state is `{ me, ready }`.
- `isSignedIn` is a computed, `me !== null`.
- `whenReady()` returns a promise **created at module load**. Child views call it before `App`'s
  `onMounted` runs `init()`, because Vue mounts children first.
- **A generation counter guards against stale `/me` results.** The rule is asymmetric:
  - **`completeLogin` and `logout` are authoritative.** When their server call succeeds they apply
    their result unconditionally and **bump** the generation. They are never staleness-checked.
  - **`init` and `reconcile` capture the generation** when they send `/me`. They discard their result
    if the generation has moved by the time it lands.
  - Real callback order: Vue mounts the child first, so `AuthCallback` starts `completeLogin`
    **before** `App` calls `init()`. The boot `/me` (sent with the pre-exchange cookie) captured the
    old generation. If it resolves after the exchange, it is discarded. If it resolves before, the
    exchange then overwrites it.
  - `completeLogin` also resolves `ready`.
- `init()`:
  - runs once per module instance;
  - purges `sessionStorage` `am.session`;
  - **skips `/me` entirely if `completeLogin` has already settled the state**, and just resolves
    `ready`;
  - otherwise calls `/me`: `200` means signed in; `401`, `403` or a network error mean signed out;
  - resolves `ready`.
  - A boot network error showing signed-out while a cookie may be live is **accepted**. It is not
    the shared-browser hazard of the logout rule, because nothing is cleared server-side and the
    next successful call or reload restores the state. Auto-claim acts only when signed in, and
    agreement create does not bind the principal.
- `completeLogin` sets `me` from the exchange body.
- `logout()`:
  - calls the server first, because only the server can clear the cookie, and clears `me` only on
    `204`;
  - on a network error or any other failure it **keeps** `me` and surfaces "We couldn't sign you
    out — try again". Showing signed-out while the HttpOnly cookie lives would let the next person
    on a shared browser reload into the previous user's account.
- `reconcile()` is the de-duplicated `/me` re-check.

**Every `auth.session` consumer** moves to `isSignedIn`, with an explicit pre-`ready` rule:

| Site | Pre-`ready` rule |
|---|---|
| `App.vue` header (`:302-343`), `:252` | render no account controls until `ready` (no "Sign in" flash) |
| `App.vue:95` boot restore | replaced by `init()` |
| `App.vue:149` `showMyAgreements` | `await whenReady()` before deciding to redirect |
| `App.vue:209` `linkNeedsSignIn` (`openFromLink`) | `await whenReady()` before deciding |
| `App.vue:221` `openForEdit` | `await whenReady()` |
| `App.vue:70`/`:359-365` `isStaff` / staff console | show a neutral loading state until `ready`, not the "for staff" refusal |
| `CaptureForm.vue:662` `canSaveToAccount` | reactive on `isSignedIn` (re-renders when ready) |
| `CaptureForm.vue:707` auto-claim after create | `await whenReady()` before deciding whether to claim |
| `AgreementStatus.vue:51` `signedIn` | reactive on `isSignedIn` |

Test style (matching how the suites are wired today):
- **`App.test.ts`** mocks `./api/auth` file-wide. The file-level default for `fetchMe` must
  **reject with a 401 `AuthHttpError`**. Otherwise `init()` resolves `undefined` and every anonymous
  test flips to signed in.
- Tests that need `ready` pending use `vi.resetModules()` and a pending `fetchMe`, the pattern
  already used at `:536-549`. The store is a module singleton, and `ready` must not leak between
  tests.
- The staff-console `beforeEach` (`App.test.ts:504-505`) and `signInAs` in
  `StaffConsoleRoute.test.ts:35-46` call the real `completeLogin` **before** `mount(App)`. They rely
  on `init()` skipping `/me` when the state is already settled.
  - `StaffConsoleRoute.test.ts` uses the real store and mocks `../api/auth`, so it also needs a
    `fetchMe` default that rejects with a 401.
- **`AgreementStatus.test.ts`** mocks `../api/authStore` directly. Its factory must export
  `isSignedIn` as a `ref`/`computed` (not a boolean).
- **`AuthCallback.test.ts`** also mocks the store, but `AuthCallback.vue` imports only
  `completeLogin`, so its factory is unchanged. The "no session value in storage" assertion lives in
  `authStore.test.ts`.
- **`CaptureForm.test.ts`** mocks neither, so it gains a `../api/authStore` mock whose `whenReady`
  resolves immediately. Otherwise the create path's `await whenReady()` hangs every save test.
- **Module tests** (`agreements`, `payments`, `client`, …) `vi.mock("./cookies")` to return a token,
  so unsafe calls do not insert a `GET /api/auth/csrf` into their `fetch` call sequences.
- `http.test.ts` and `authStore.test.ts` mock at the network boundary.

There is no user-visible copy change. The Terms of Service make no session or cookie promise.

### D9 — Test harness

- **TestRestTemplate.** `support/CsrfTestInterceptor`, registered through a `RestTemplateCustomizer`
  `@Bean` in **`HarnessTestConfig`**:
  - Every class that autowires `TestRestTemplate` already `@Import`s it; task 4.1 verifies that
    with a grep.
  - On unsafe methods it adds a fixed `__Host-XSRF-TOKEN` cookie and the matching header, merged with
    any `Cookie` header the test set.
- **MockMvc.** `support/CsrfMockMvc.csrf()` is a hand-written `RequestPostProcessor` that sets the
  cookie and header. It is applied to the 14 POSTs in `DocumentProjectionApiIntegrationTest` and
  `AgreementDocumentFormatE2EIntegrationTest`. **No `spring-security-test` dependency**, so
  "Dependencies: none" holds.
- **No CSRF-disable switch may exist in main code**: no property, no profile, and no test-only
  `SecurityFilterChain`. Tests supply tokens; they never turn the check off.
- **Enforcement tests bypass the interceptor.** They use `support/RawClient`: a `RestTemplate` on the
  random port with a non-throwing error handler, built without the customizer, and **pinned to
  `JdkClientHttpRequestFactory`**. The default `SimpleClientHttpRequestFactory` streams bodies, and
  `HttpURLConnection` throws `HttpRetryException` on a `401` in streaming mode. Several assertions
  here expect a `401` on a POST. These cover CSRF
  missing or mismatched, the webhook exemption and its negative controls, eager issuance, the
  problem type, insecure mode, and the staff precedence. `CsrfProtectionIntegrationTest` and
  `SessionCookieIntegrationTest` must not apply the interceptor.
- **Session cookie.** `support/SessionCookie.header(value)` produces `Cookie: __Host-am_session=<value>`.
  `StaffSessions` keeps minting through the services (not over HTTP), so it is unaffected by CSRF.
- **Startup guard.** Tested with an `ApplicationContextRunner` plus
  `@EnableConfigurationProperties(AuthProperties.class)`, not a new `@SpringBootTest` context, to
  protect the 3-minute budget.
- **Insecure mode end to end.** One `@SpringBootTest` with `auth.cookie.secure=false` asserts the
  `am_session` and `XSRF-TOKEN` names and attributes, and that the filter reads `am_session`. It uses
  `RawClient`.

### D10 — Docs, register, and cross-CR amendment

- **Docs:**
  - `deploy/Caddyfile` and `docs/DEPLOYMENT.md` keep their wording but become true. They name
    `__Host-am_session` and its dependence on the apex-only single origin.
  - Add to DEPLOYMENT.md: **never edge-cache `/api/*`**. Eager issuance puts `Set-Cookie` on public
    GETs. The real control is this rule, not Spring's `no-store` header. `GET /api/templates/form`
    sets its own `Cache-Control: public, max-age` (`TemplateFormController.java:45-53`), and Spring
    Security then writes no cache header, so that response is publicly cacheable while it carries an
    XSRF `Set-Cookie`.
  - Add a Caddyfile comment: never log request or response cookies.
- **CLAUDE.md:**
  - Commands: the `AUTH_COOKIE_SECURE=false` line (LAN or Safari; clear cookies when switching; an
    https callback refuses to boot).
  - Conventions: **GET handlers must be side-effect-free**, because `SameSite=Lax` depends on it.
- **`docs/ROADMAP.md`:**
  - Delete `session-store-not-durable`. The journal cites V11, `AuthSession` and `SessionService` as
    the evidence that sessions were already durable.
  - Add `auth-expired-row-purge`: expired rows are never purged, `last_seen_at` is written on every
    authenticated request, and the boot `/me` call adds one more write per page load.
- **`openspec/changes/mobile-otp-auth/design.md`.** Replace the cookie deferral (D4 `:96-99`, Risks
  `:242-244`, Open questions `:275-276`) with a pointer to this CR. The pointer says:
  - verify mints through `SessionService`, then calls `SessionCookies.establish` only on success
    (prior-session revoke + cookie + CSRF rotation), and requires CSRF;
  - the OTP flow inherits the login-binding gap below;
  - the startup guard should move to a provider-neutral origin property if one is added.

  Its spec deltas are left for its own review.

## Risks / Trade-offs

- **[Handoff injection / OAuth login CSRF, pre-existing]** Neither the OAuth `state` nor the
  handoff is bound to the browser that started the login (`GoogleLoginService.java:71-88`,
  `:113-116`; `AuthCallback.vue:12-19` exchanges any `#handoff=`). An attacker can send a victim a
  `#handoff=` link (valid for 60 s), or their own uncompleted `/callback?code&state` URL, and sign
  the victim into the attacker's account.
  - The harm: the victim drafts an agreement containing their PII under the attacker's identity.
  - The exchange-CSRF in this CR does not close this. It is split out to `login-browser-binding`,
    which lands after this CR and before `mobile-otp-auth`.
- **[Old SPA bundles in open tabs after deploy]** These send no CSRF header, so *every* unsafe call
  fails, not just the signed-in ones, until the tab reloads.
  - Deploy backend and frontend together.
  - Acceptable during the beta: founding team only, no real customers (as of 2026-09-07).
  - Users signed in under the old build are signed out once. Their `am.session` is purged and never
    sent.
- **[Browsers that drop `Secure` cookies on http]** The D5 opt-out switch, documented, with a
  fail-closed guard. The guard's proxy failure modes are listed in D5.
- **[Any XSS can still *ride* the session]** Same-origin fetch carries the cookie and can read the
  CSRF cookie. This is accepted as inherent: HttpOnly prevents *exfiltration*, not same-origin abuse
  while the page is open. A CSP is the mitigation and is out of slice; it is tracked as `spa-content-security-policy`.
- **[Double-submit is not session-bound]** The `__Host-` prefix and single origin close the
  cookie-write vector. Rotation at login and logout stops a planted token from surviving. The CSRF
  retry recovers from a planted non-prefixed cookie.
- **[A future Spring upgrade changes eager-token behaviour]** Pinned by the 5.2 integration test.
- **[A future `src/api` call bypasses `apiFetch`]** The D8 guard test fails the build.
- **[A test-wide interceptor hides a regression]** Enforcement tests use `RawClient` (D9).
- **[The boot `/me` call adds one request per page load]** Cheap, and it doubles as the CSRF
  bootstrap. Its write load is recorded in `auth-expired-row-purge`.
