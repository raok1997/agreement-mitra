> **Landing order.** §1 → **§2 + §3 + §4 as one green unit.** Removing Bearer (§2) and enabling
> CSRF (§3) turn most integration tests red until §4 lands, so land them together → §5 → §6 → §7.
> **Deploy backend and frontend together.** An old SPA bundle sends no CSRF header, so every unsafe
> call it makes fails (design Risks).

## 1. Backend — cookie config and the identity-owned cookie surface

- [x] 1.1 `identity/AuthProperties.java`: add a nested `Cookie(boolean secure)` record, where a null
  block means `true`.
  - Add a compact-constructor guard. It throws when `secure=false` and either
    `google.spaCallbackUri` or `google.redirectUri` has `URI.getScheme()` equal to `https`
    (compared case-insensitively, after trimming). It is null-safe for a missing `google` block or
    URI, **fails closed** on an unparseable URI while `secure=false`, and the message names
    `AUTH_COOKIE_SECURE`.
  - Add `auth.cookie.secure: ${AUTH_COOKIE_SECURE:true}` to `application.yml`.
  - Update the 6 `new AuthProperties(` test constructions (HandoffServiceTest,
    GoogleTokenValidatorTest, TokenHasherTest ×2, GoogleLoginConfiguredGuardTest,
    SessionServiceTest) (D5).
- [x] 1.2 Unit test `AuthPropertiesTest`:
  - secure when the block is absent;
  - `false` with http URIs is accepted;
  - `false` with an `https` spa-callback is refused, and so is `false` with an `HTTPS` redirect URI;
  - a null `google` block does not throw;
  - `false` with an unparseable URI is refused;
  - `false` with a scheme-less relative URI (`/auth/callback`) is refused.
- [x] 1.3 `identity/session/SessionService.java`: `SessionIssued` gains `Instant expiresAt`, the same
  value persisted on the row. Extend `SessionServiceTest` to assert that the returned `expiresAt`
  equals the row's (D1).
- [x] 1.4 Create the identity-owned cookie surface:
  - `identity/api/SessionCookies.java`, a **package-private** class constructor-injected with
    `AuthProperties`, `SessionService` and `CsrfTokenRepository`;
  - `establish(req, res, SessionIssued)` is called only after a successful mint. It sets the new cookie,
    rotates CSRF, then **last** revokes any *different* session named by the request's session
    cookie;
  - it also exposes `clear(req, res)` and `read(req)`.
  - No new public type.
  - In `AuthWebConfig`, add `@Bean`s for `SessionCookies` and for a `CookieCsrfTokenRepository`
    (`withHttpOnlyFalse`, named `__Host-XSRF-TOKEN` / `XSRF-TOKEN` per mode, header `X-XSRF-TOKEN`,
    customizer setting `Secure`-per-mode, `SameSite=Lax`, `Path=/`).
  - Rotation is a single `saveToken(generateToken(req), req, res)` (D4, D6).
- [x] 1.5 Unit test `SessionCookiesTest`:
  - `establish` sets the attributes in secure mode and in insecure mode, `Max-Age` comes from
    `expiresAt` (floored at 0), and the CSRF repository's `saveToken` is called with a new token;
  - `establish` revokes a different prior cookie's session, and does not revoke when there is no
    prior cookie;
  - `clear` expires the cookie with the same name, path and attributes and rotates CSRF;
  - `read` returns only the configured name and treats blank as absent;
  - the repository bean's cookie name and attributes are correct per mode.

## 2. Backend — session transport (identity)

- [x] 2.1 `identity/api/SessionAuthenticationFilter.java`: authenticate from `SessionCookies.read`
  only. Delete the Bearer parsing, update the class javadoc, and update the `AuthWebConfig` wiring
  (D2).
- [x] 2.2 Unit test `SessionAuthenticationFilterTest`:
  - a cookie with a live session gives a `UUID` principal and `ROLE_<role>`;
  - `Authorization: Bearer <live value>` with no cookie stays unauthenticated;
  - an unknown cookie is a no-op.
- [x] 2.3 `identity/api/AuthController.java` + `AuthDtos.java`:
  - **exchange:** call `SessionService.exchange` first, which consumes and mints in one
    transaction. Only on success call `SessionCookies.establish`, which revokes the prior session.
    Return `{ me }`. `SessionResponse` drops `session`. A thrown `InvalidLoginException` reaches the
    existing 401 handler with no cookie or session touched.
  - **logout:** revoke the cookie's session if present, call `SessionCookies.clear`, return `204`.
    Drop the `Authorization` parameter and `BEARER_PREFIX`.
  - **new `GET /api/auth/csrf`:** returns `204` (D7).
- [x] 2.4 Unit test `AuthControllerTest` (no Spring context; mocked `SessionService`,
  `IdentityService` and `SessionCookies`):
  - exchange returns no session field and calls `establish` with the `SessionIssued`;
  - a failing exchange (`InvalidLoginException`) never calls `establish` or revoke;
  - a successful exchange calls `establish` after `exchange` (verified with an `InOrder`);
  - logout with no cookie still calls `clear` and does not call revoke;
  - logout with a cookie revokes that value, then calls `clear`.

## 3. Backend — CSRF posture (root)

- [x] 3.1 `HeaderOnlyCsrfTokenRequestHandler.java` (root package, package-private):
  - extends `CsrfTokenRequestAttributeHandler`;
  - calls `setCsrfRequestAttributeName(null)` for eager loading;
  - its `resolveCsrfTokenValue` returns only the `X-XSRF-TOKEN` header (D3, D4).
- [x] 3.2 `CsrfAwareAccessDeniedHandler.java` (root package, package-private):
  - for a `CsrfException`, writes `403` `application/problem+json` with type
    `urn:agreementmitra:problem:csrf`, a fixed title and a fixed detail;
  - never puts the exception message in the body, the `instance`, or a log line;
  - delegates everything else to `AccessDeniedHandlerImpl` unchanged (D3).
- [x] 3.3 `SecurityConfig.java`:
  - replace `csrf(disable)` with the repository from `ObjectProvider<CsrfTokenRepository>`. Only when
    it is absent (module slices), fall back to an explicitly configured `CookieCsrfTokenRepository`:
    `withHttpOnlyFalse`, `__Host-XSRF-TOKEN`, `Secure`, `SameSite=Lax`, `Path=/`;
  - set `HeaderOnlyCsrfTokenRequestHandler`;
  - ignore exactly `POST /api/webhooks/esign` and `POST /api/webhooks/razorpay` via
    `PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, …)`;
  - set the `accessDeniedHandler` and keep `STATELESS`;
  - change `POST /api/auth/logout` to `permitAll` and add `GET /api/auth/csrf` as `permitAll`;
  - rewrite the Bearer-referencing comments. Add no `@Value`.
- [x] 3.4 Unit tests:
  - `HeaderOnlyCsrfTokenRequestHandlerTest`: the header resolves; a `_csrf` parameter alone resolves
    to null; `handle` loads the token eagerly.
  - `CsrfAwareAccessDeniedHandlerTest`:
    - a `CsrfException` produces the problem body and type;
    - an `InvalidCsrfTokenException` built with a distinctive token value does not reflect that value
      in the body, and does not appear in captured log output (`OutputCaptureExtension`);
    - a plain `AccessDeniedException` gets the delegate's bare `403`.

## 4. Backend — test harness and existing tests

- [x] 4.1 Test support:
  - `support/CsrfTestInterceptor.java`, plus a `RestTemplateCustomizer` `@Bean` in
    `HarnessTestConfig`. On unsafe methods it adds the fixed `__Host-XSRF-TOKEN` cookie and the
    matching header, merged with any existing `Cookie` header.
  - Verify with a grep that every class autowiring `TestRestTemplate` `@Import`s `HarnessTestConfig`,
    and add the import where it is missing (D9).
- [x] 4.2 More test support:
  - `support/CsrfMockMvc.java`: a hand-written `RequestPostProcessor` that sets the cookie and
    header. Apply it to the 10 POSTs in `DocumentProjectionApiIntegrationTest` and the 4 in
    `AgreementDocumentFormatE2EIntegrationTest`.
  - No `spring-security-test` dependency, and no CSRF-disable property or profile anywhere.
- [x] 4.3 More test support:
  - `support/SessionCookie.java` with `header(value)`, producing `Cookie: __Host-am_session=<value>`;
  - `support/RawClient.java`: a random-port `RestTemplate` with a non-throwing error handler, pinned
    to `JdkClientHttpRequestFactory`, and built **without** the CSRF customizer;
  - update the `StaffSessions` javadoc to say it returns the session cookie value.
- [x] 4.4 Replace `setBearerAuth(...)` in the **identity and root** test files:
  - `AuthSecurityConfigIntegrationTest`:
    - `logoutRequiresASession` becomes "anonymous logout with CSRF → 204";
    - the invalid-Bearer case becomes an invalid-cookie case.
  - `GoogleLoginHandshakeIntegrationTest`: also, `:189` reads `session` from the exchange body. It
    must parse the `__Host-am_session` value from `Set-Cookie` instead.
  - `SecurityBaselineIntegrationTest`:
    - fix the `:68` "no CSRF token needed" comment;
    - `staffStampIntakeChallengesAnAnonymousCallerWith401` keeps 401 under the interceptor (valid
      token).
- [x] 4.5 Replace `setBearerAuth(...)` in the **signing** test files:
  - AgreementCapturePersistence, AgreementOwnership, SigningRequestApi, StampDutyFromCertificate,
    StampIntakeApi;
  - contact/ContactGate, delivery/SignedDelivery;
  - payment/PaymentGateOptional, RazorpayPaymentGate, RazorpayPayment, StampDutyCheckout;
  - signingrequest/PaymentGate, SigningCompletion, SigningProgressApi, StampQueueFulfilment,
    ZoopSigning.
  - Gate: `grep -rn "setBearerAuth\|Bearer " backend/src/test` returns nothing.
    Exception (apply, 2026-10-04): two deliberate negative tests send a Bearer header to prove it
    is ignored -- `SessionAuthenticationFilterTest` (required by 2.2) and
    `SessionCookieIntegrationTest.theCookieAuthenticatesButBearerAndUnknownCookiesDoNot` (5.1).

## 5. Backend — integration tests for new behaviour

- [x] 5.1 `identity/SessionCookieIntegrationTest` (`RawClient`, real handshake via `HandoffService`):
  - exchange with CSRF → `200`, `{ me }` with no session field, a hardened `Set-Cookie`
    (`Max-Age` ≈ TTL), and a last `__Host-XSRF-TOKEN` value different from the presented token;
  - exchange without CSRF → `403` with the csrf problem type, after which the handoff is still
    exchangeable;
  - a reused or expired handoff → refused, with no session `Set-Cookie`;
  - exchange while carrying A's cookie → A revoked, and the new cookie authenticates as B;
  - a bad handoff while carrying A's cookie → `401`, and A still authenticates;
  - the cookie authenticates `/me`;
  - `Bearer` with the same value → unauthenticated;
  - an unknown or expired cookie → unauthenticated;
  - logout with the cookie → `204`, cookie expired, CSRF rotated, value dead;
  - logout with a stale cookie → `204`, cookie expired;
  - anonymous `/me` is rejected, and anonymous logout with CSRF → `204`.
- [x] 5.2 `CsrfProtectionIntegrationTest` (`RawClient`):
  - `POST /api/agreements` with no token → `403`, csrf problem type, no row created;
  - a mismatched token → `403` with the type;
  - token in a `_csrf` parameter only → `403`;
  - an invalid token with a distinctive value → that value is absent from the `403` body;
  - only a planted `XSRF-TOKEN` cookie, echoed → `403` csrf type, the response sets
    `__Host-XSRF-TOKEN`, and a retry with that value succeeds;
  - a matching cookie and header → `201`;
  - `GET /api/auth/csrf` with no cookie → `204` and `Set-Cookie: __Host-XSRF-TOKEN` (non-HttpOnly,
    `SameSite=Lax`, `Path=/`, `Secure`);
  - a permitted GET API with no cookie → `Set-Cookie` present (eager issuance through the configured
    handler);
  - no `JSESSIONID` is created;
  - anonymous `POST /api/staff/estamp` with no token → `403` csrf type, and with a token → `401`;
  - a CUSTOMER session calling a staff endpoint with a token → bare `403` (not the csrf type);
  - `POST /api/signing/{id}/request` with a token is not refused by the chain.
- [x] 5.3 Webhook exemption in `CsrfProtectionIntegrationTest` (`RawClient`, no cookie or header):
  - `POST /api/webhooks/esign` → the existing HMAC rejection, not the csrf type;
  - `POST /api/webhooks/razorpay` → the existing signature rejection, not the csrf type;
  - negative controls: `POST /api/agreements/{id}/payment/callback` and `POST /api/webhooks/esign/x`
    with no token → `403` csrf type.
  - Existing webhook success paths in SigningCompletion and RazorpayPayment stay green under the
    interceptor.
- [x] 5.4 `CookieSecureGuardTest` (`ApplicationContextRunner` +
  `@EnableConfigurationProperties(AuthProperties.class)`). Its distinct purpose from 1.2: it proves
  the guard fires through real property binding, and that the message survives as the cause.
  - `secure=false` with an https callback → the context fails, and the cause names
    `AUTH_COOKIE_SECURE`;
  - `secure=false` with http URIs → the context starts.
- [x] 5.5 `InsecureCookieModeIntegrationTest` (`@SpringBootTest` with `auth.cookie.secure=false`,
  `RawClient`):
  - `GET /api/auth/csrf` sets `XSRF-TOKEN` without `Secure`;
  - exchange sets `am_session` without `Secure` and with no `Domain`;
  - `/me` with `am_session` → `200`.
- [x] 5.6 Run `./run-tests.sh check` from `backend/`.
  - Everything must be green: `ModularityTests`, coverage, `securityScan`.
  - Report wall-clock time against the 3-minute budget. 5.5 adds one Spring context, so call out any
    increase.
  - Closed 2026-10-04: 1490 tests green, coverage green, 72s. `osvScan` is red only on advisories
    published against the unchanged lockfile (jackson 2.21.5, bcprov 1.84). Descoped to the register
    row `dependency-advisories-2026-10`.

## 6. Frontend

- [x] 6.1 Create `src/api/cookies.ts` (`readCookie`, its own module so tests can `vi.mock` it) and
  `src/api/http.ts`:
  - `apiFetch` with `credentials: "same-origin"`;
  - `ensureCsrf()`: a de-duplicated `GET /api/auth/csrf` when neither CSRF cookie exists;
  - unsafe methods set `X-XSRF-TOKEN` from `__Host-XSRF-TOKEN`, falling back to `XSRF-TOKEN`;
  - one retry, and only on `403` with type `urn:agreementmitra:problem:csrf`: re-run `ensureCsrf()`,
    re-read the cookie, and resend;
  - after a `401` or non-CSRF `403` on a path outside `/api/auth/*`, call a registered reconcile
    hook. The hook acts only while signed in (D8).
- [x] 6.2 Unit test `http.test.ts`:
  - GET: no header, no bootstrap;
  - POST with the cookie: header set, no bootstrap;
  - POST without the cookie: one bootstrap, and two concurrent POSTs share it;
  - the `__Host-` name is preferred;
  - a csrf-typed `403` → exactly one retry, using the re-read cookie, never more than once;
  - a bare `403` is never retried;
  - a `401` or bare `403` outside `/api/auth/*` invokes the reconcile hook;
  - `/api/auth/me`, `/api/auth/session/exchange` and `/api/auth/logout` do not invoke it.
- [x] 6.3a Migrate the authed modules (agreements, payments, signingProgress, stampQuote, staffQueue)
  to `apiFetch`, delete `authHeader()`, and update `agreements.test.ts`, `payments.test.ts`,
  `signingProgress.test.ts` and `staffQueue.test.ts`. Each test does `vi.mock("./cookies")`
  returning a token, so call sequences gain no bootstrap `GET`.
- [x] 6.3b Migrate `auth.ts`:
  - `exchangeHandoff` returns `{ me }`;
  - `fetchMe()` and `logout()` take no session argument;
  - drop the Bearer construction and the `SessionView.session` field;
  - `logout()` throws `AuthHttpError` on any non-`204`, so the store can tell success from failure
    (today it ignores `res.ok`, `auth.ts:66-70`).
- [x] 6.3c Migrate the anonymous modules (client, documentPreview, jurisdictions, recovery,
  templateCatalog, templateForm) to `apiFetch`, and update `client.test.ts`,
  `documentPreview.test.ts`, `templateCatalog.test.ts` and `templateForm.test.ts`, each with
  `vi.mock("./cookies")`.
- [x] 6.4 Unit test `apiFetchGuard.test.ts`. It scans `src/api/*.ts`, excluding tests and `http.ts`,
  and fails on `(?<![\w$])fetch\(`, `window.fetch(` or `globalThis.fetch(`. It passes on `apiFetch(`.
- [x] 6.5 `authStore.ts`:
  - state `{ me, ready }`;
  - computed `isSignedIn`;
  - a `whenReady()` promise created at module load;
  - a generation counter (asymmetric, D8): `completeLogin` and `logout` apply unconditionally on
    success and bump it; `init` and `reconcile` capture it and discard a stale `/me` result;
  - `init()` runs once, and skips `/me` when `completeLogin` already settled the state;
  - `init()`: purge `am.session`, call `/me` (`200` → me; `401`, `403` or error → null), resolve
    `ready`;
  - `completeLogin` sets `me` from the body and resolves `ready`;
  - `logout()` calls the server first and clears `me` only on `204`. On a network error or a
    non-`204` it **keeps** `me` and reports "couldn't sign you out — try again";
  - `reconcile()`: a de-duplicated `/me` re-check, registered as `http.ts`'s reconcile hook. It
    acts only while signed in, and keeps the current state on a network error;
  - remove all session-value code.
- [x] 6.6 Unit test `authStore.test.ts` (rewrite):
  - init with `/me 200` → signed in and ready;
  - init with `/me 403` → signed out and ready;
  - init purges `am.session` and never sends it;
  - completeLogin sets `me`, and no session value is stored anywhere;
  - logout posts, then clears on `204`;
  - logout on a network error or a `500` keeps `me` and reports;
  - reconcile after a revoked session → signed out;
  - reconcile on a network error keeps the state;
  - concurrent reconciles share one `/me` call;
  - the **real mount order**: start `completeLogin` first, then `init()`; resolve the exchange,
    then resolve `/me` with 403 → still signed in. Also the reverse resolution order → signed in;
  - `completeLogin` settled before `init()` → `init()` makes no `/me` call and `ready` resolves;
  - no session value is stored anywhere after `completeLogin`.
- [x] 6.7a `App.vue`:
  - `auth.init()` on mount, replacing the `:95` restore;
  - every `auth.session` read (`:149`, `:209`, `:221`, `:252`, header `:302-343`) becomes
    `isSignedIn`;
  - the header renders no account controls until `ready`;
  - `showMyAgreements`, `openFromLink`/`linkNeedsSignIn` and `openForEdit` `await whenReady()`;
  - the staff console (`isStaff`, `:70`/`:359-365`) shows a neutral loading state until `ready`.
- [x] 6.7b `CaptureForm.vue`:
  - `:662` `canSaveToAccount` becomes reactive on `isSignedIn`;
  - `:707` auto-claim `await whenReady()` before deciding.
- [x] 6.7c `AgreementStatus.vue:51` `signedIn` becomes reactive on `isSignedIn`.
  `AuthCallback.vue` keeps calling `completeLogin`, now over `apiFetch`.
- [x] 6.8 Component tests, wired as in design D8 "Test style":
  - **`App.test.ts`:**
    - the file-level `fetchMe` default rejects with a 401 `AuthHttpError`;
    - the `:492` fixture drops `session`;
    - pending-`ready` tests use `vi.resetModules()` plus a pending `fetchMe`;
    - on the callback route, an exchange that resolves before a pending boot `/me` ends signed in
      (the `App` + `AuthCallback` race);
    - a reload with `/me 200` shows the signed-in header, replacing the sessionStorage-restore
      tests at `:475` and `:536-549`;
    - no account control renders before `/me` resolves;
    - My agreements and an agreement link opened while `/me` is pending do not redirect to sign-in
      prematurely;
    - the staff console shows loading, not the refusal, while pending.
  - **`AuthCallback.test.ts`:** unchanged apart from the exchange fixture shape. The storage
    assertion lives in 6.6.
  - **`CaptureForm.test.ts`:** add a `../api/authStore` mock whose `whenReady` resolves immediately,
    so existing save tests do not hang. Auto-claim waits for `ready` and claims when signed in.
  - **`AgreementStatus.test.ts`:** its `authStore` mock factory exports `isSignedIn` as a
    `ref`/`computed` and seeds `me` instead of `session`.
  - **`StaffConsoleRoute.test.ts`** (real store, mocked `../api/auth`):
    - add a `fetchMe` default that rejects with a 401;
    - the `:37` fixture drops `session`;
    - `signInAs` before mount still renders staff, because `init()` skips `/me`.
- [x] 6.9 Run `npm run build` and `npm run lint` from `frontend/`. Both must be green.
  - Closed 2026-10-04: 321 tests, `vue-tsc -b`, `vite build`, lint 0 errors. `security:scan` is red only
    on dev-only advisories (brace-expansion, braces) against the unchanged lockfile. Descoped to
    `dependency-advisories-2026-10`.

## 7. Docs, register, cross-CR

- [x] 7.1 Deploy docs:
  - `deploy/Caddyfile`: make the cookie wording accurate (name `__Host-am_session`, apex-only
    single-origin dependency) and add a comment never to log request or response cookies;
  - `docs/DEPLOYMENT.md`: the same cookie wording, plus "never edge-cache `/api/*`";
  - `deploy/env/backend.env.example`: a commented `AUTH_COOKIE_SECURE` line (production leaves it
    unset or `true`).
- [x] 7.2 `CLAUDE.md`:
  - Commands: `AUTH_COOKIE_SECURE=false` for LAN or Safari over http, **required on a LAN IP even for
    anonymous drafting**; clear site cookies when switching; an https callback refuses to boot.
  - Conventions: GET handlers must be side-effect-free, because `SameSite=Lax` depends on it.
- [x] 7.3 `docs/ROADMAP.md`:
  - delete the `session-store-not-durable` row;
  - add an `auth-expired-row-purge` row: expired `auth_session`, `login_handoff` and
    `oauth_login_state` rows are never purged; `last_seen_at` is written on every authenticated
    request, plus once more per page load from the boot `/me`; raised by `cookie-session-auth`,
    2026-10-04, Low–Medium.
- [x] 7.4 `openspec/changes/mobile-otp-auth/design.md`: replace the cookie deferral (`:96-99`,
  `:242-244`, `:275-276`) with a pointer to `cookie-session-auth`. The pointer says:
  - verify mints via `SessionCookies.establish` and requires CSRF;
  - the flow inherits the login-binding gap;
  - the startup guard should move to a provider-neutral origin property if one is added.
- [x] 7.5 Update the `## Purpose` paragraph (Purpose text only, never requirement text) in
  `openspec/specs/backend-security-baseline/spec.md` and `openspec/specs/google-oauth-login/spec.md`:
  - replace "stateless/CSRF-disabled JSON-API posture" with the CSRF-enabled posture;
  - replace "bearer sessions" with cookie sessions.

  Delta specs cannot change a Purpose, and archiving keeps the baseline file's own Purpose.

## Coverage

| # | Capability | Scenario | Disposition | By |
|---|---|---|---|---|
| 1 | google-oauth-login | Handoff is exchanged once for a session cookie | COVERED | 5.1, 2.4 |
| 2 | google-oauth-login | A reused or expired handoff mints nothing | COVERED | 5.1 |
| 3 | google-oauth-login | An exchange without a CSRF token is refused | COVERED | 5.1 |
| 4 | google-oauth-login | A refused exchange leaves the prior session intact | COVERED | 5.1, 2.4 |
| 5 | google-oauth-login | Exchanging while signed in revokes the prior session | COVERED | 5.1, 1.5 |
| 6 | google-oauth-login | A minted session authenticates subsequent requests | COVERED | 5.1, 2.2 |
| 7 | google-oauth-login | A Bearer header no longer authenticates | COVERED | 5.1, 2.2 |
| 8 | google-oauth-login | Logout revokes the session and clears the cookie | COVERED | 5.1, 2.4, 1.5 |
| 9 | google-oauth-login | Logout with a stale cookie still clears it | COVERED | 5.1, 2.4 |
| 9a | google-oauth-login | A failed revoke still clears the cookie and is not reported as success | COVERED | 2.4 (`AuthControllerTest.aFailingRevokeStillExpiresTheCookieAndReports500`); 6.6 (logout on a `500` keeps `me`) |
| 10 | google-oauth-login | An expired or unknown session does not authenticate | COVERED | 5.1, 2.2 |
| 11 | google-oauth-login | The exchange sets a hardened session cookie | COVERED | 5.1, 1.5 |
| 12 | google-oauth-login | Insecure dev mode drops Secure and the prefix | COVERED | 5.5, 1.5 |
| 13 | google-oauth-login | An https deployment refuses an insecure cookie | COVERED | 5.4, 1.2 |
| 14 | google-oauth-login | The SPA restores the login from the cookie, not storage | COVERED | 6.6, 6.8 |
| 15 | google-oauth-login | No sign-in decision is made before the session check resolves | COVERED | 6.8 |
| 16 | google-oauth-login | A session that ends mid-use flips the SPA to signed out | COVERED | 6.2, 6.6 |
| 17 | google-oauth-login | A login completing during the boot check stays signed in | COVERED | 6.6, 6.8 |
| 18 | google-oauth-login | A failed logout does not pretend the user is signed out | COVERED | 6.6 |
| 19 | google-oauth-login | A legacy stored session value is purged | COVERED | 6.6 |
| 20 | backend-security-baseline | No HTTP session is created | COVERED | 5.2 |
| 21 | backend-security-baseline | An unsafe request without a CSRF token is refused | COVERED | 5.2 |
| 22 | backend-security-baseline | A mismatched CSRF token is refused | COVERED | 5.2 |
| 23 | backend-security-baseline | A refusal does not reflect the presented token | COVERED | 5.2, 3.4 |
| 24 | backend-security-baseline | A planted non-prefixed token recovers on retry | COVERED | 5.2, 6.2 |
| 25 | backend-security-baseline | A token in a request parameter is not accepted | COVERED | 5.2, 3.4 |
| 26 | backend-security-baseline | A matching CSRF token is accepted | COVERED | 5.2 |
| 27 | backend-security-baseline | Webhooks are exempt from CSRF | COVERED | 5.3 |
| 28 | backend-security-baseline | The exemption is exact | COVERED | 5.3 |
| 29 | backend-security-baseline | A first-time anonymous visitor can obtain a token | COVERED | 5.2 |
| 30 | backend-security-baseline | Any API response issues the token eagerly | COVERED | 5.2, 3.4 |
| 31 | backend-security-baseline | Insecure mode names the CSRF cookie without the prefix | COVERED | 5.5, 1.5 |
| 32 | backend-security-baseline | Signing-request stub passes the filter | COVERED | 5.2; `SecurityBaselineIntegrationTest.signingRequestStubIsPermittedAndReachesTheController` (under interceptor) |
| 33 | backend-security-baseline | Other signing sub-paths are denied by default | GROUPED | `SecurityBaselineIntegrationTest.nonRequestSigningSubPathIsDeniedWith403` (unchanged, kept green in 4.4) |
| 34 | backend-security-baseline | New accounts default to CUSTOMER | GROUPED | `SessionServiceTest` (CUSTOMER summary, `:58`), unchanged by this CR |
| 35 | backend-security-baseline | Role cannot be self-assigned | WAIVED | Unchanged by this CR; carried into the delta only because the requirement's 401 scenario changed. Its pre-existing coverage is out of this slice. Re-decide when this requirement's role rules change |
| 36 | backend-security-baseline | Staff-only endpoint denies a customer | COVERED | 5.2; `StampIntakeApiIntegrationTest.customerUploadIs403AndNothingIsPersisted` (4.5) |
| 37 | backend-security-baseline | Staff-only endpoint denies an anonymous caller | COVERED | 5.2; `StampIntakeApiIntegrationTest.anonymousUploadIs401AndNothingIsPersisted` (4.5) |
| 38 | backend-security-baseline | A staff call without a CSRF token gets the CSRF refusal | COVERED | 5.2 |
| 39 | backend-security-baseline | Authorization precedes resource lookup | GROUPED | `StampIntakeApiIntegrationTest.refusalIsIdenticalWhetherOrNotTheAgreementExists` (4.5) |
| 40 | backend-security-baseline | Default-deny still covers unlisted endpoints | GROUPED | `SecurityBaselineIntegrationTest.unmappedPathIsDeniedWith403` / `unlistedStaffSubPathsRemainDeniedByDefault` (4.4) |
| 41 | backend-security-baseline | The login handshake is reachable without a session | GROUPED | 5.1 (exchange), 5.2 (csrf), `AuthSecurityConfigIntegrationTest` start case (4.4) |
| 42 | backend-security-baseline | me requires a session, logout does not | COVERED | 5.1, 4.4 |
| 43 | backend-security-baseline | Existing routes are not regressed | GROUPED | `AuthSecurityConfigIntegrationTest.existingAgreementRoutesAreNotRegressed` (4.4); MockMvc suites (4.2) |
| 44 | backend-security-baseline | Anonymous drafting stays open while save, list, and edit are gated | GROUPED | `AgreementOwnershipIntegrationTest` (4.5) |
| 45 | backend-security-baseline | A valid session authenticates the gated routes | GROUPED | `AgreementOwnershipIntegrationTest` (4.5) |
| 46 | backend-security-baseline | Matcher order keeps the capability read open | GROUPED | `AgreementOwnershipIntegrationTest` (4.5) |

Waived: 1 of 47 (2%). No row touches money, PII or the document's legal validity.
