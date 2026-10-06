## Why

The browser session is an opaque value that the SPA holds in JavaScript and sends as
`Authorization: Bearer`. Since `c79549d` it is also mirrored to `sessionStorage` as a stopgap so a
reload keeps the login. Any XSS on the origin can therefore read and exfiltrate a live session.
`google-oauth-login` (D4, Risks) accepted that for the sandbox and flagged "HttpOnly cookie + CSRF"
as the production follow-up. This CR is that follow-up, and it lands before `mobile-otp-auth` so the
second login method mints into the cookie from day one.

The other half of the original ask — "back sessions with a durable Postgres store" — **already
exists**. `V11__identity_oauth.sql` created `auth_session` (hash-only, with an absolute
`expires_at`), and `SessionService` resolves sessions through the JPA entity `AuthSession`. Nothing
is held in memory, so restarts, redeploys and multiple instances already preserve logins. The
register row `session-store-not-durable` is stale, and this CR deletes it with that evidence.

## What Changes

- **BREAKING (browser contract):** the session moves from a JS-held Bearer value to a
  `__Host-am_session` cookie (`HttpOnly; Secure; SameSite=Lax; Path=/`, `Max-Age` = the session's remaining lifetime).
  `POST /api/auth/session/exchange` sets the cookie and returns only `{ me }`. The session value
  never appears in a response body again.
- **BREAKING:** `Authorization: Bearer` is **no longer accepted anywhere**. The session filter reads
  only the cookie, so the cookie is the single authentication path.
- **CSRF protection is enabled.** It uses Spring Security's double-submit pattern for SPAs: a
  JS-readable `__Host-XSRF-TOKEN` cookie that the SPA echoes back as `X-XSRF-TOKEN`.
  - It applies to every unsafe method, anonymous or authenticated, **including
    `/api/auth/session/exchange` and `/api/auth/logout`**.
  - The only exemptions are the two HMAC-authenticated server-to-server webhooks
    (`POST /api/webhooks/esign`, `POST /api/webhooks/razorpay`).
- **Eager CSRF token:** every API response carries the XSRF cookie if the browser lacks one.
  `GET /api/auth/csrf` (permitAll, `204`) also lets the SPA obtain one before its first mutating
  call, so an anonymous visitor's first `POST /api/agreements` works.
- **Logout becomes `permitAll`.** It revokes the session named by the cookie (if any) and always
  expires the cookie. JS cannot clear an HttpOnly cookie, so logout must work even with a stale or
  expired session.
- **Cookie security is configurable for local dev:** `auth.cookie.secure` (env `AUTH_COOKIE_SECURE`,
  default `true`).
  - `false` drops the `Secure` attribute and the `__Host-` prefix, for LAN-device or Safari testing
    over plain http.
  - Startup refuses `false` when either Google URI (SPA callback or redirect) is `https`, or cannot
    be parsed, so a deployed site cannot run with an insecure cookie.
- **SPA:**
  - All `src/api/` calls go through one fetch wrapper that attaches `X-XSRF-TOKEN` to unsafe
    methods.
  - "Signed in" is derived from `GET /api/auth/me`, which is called on every boot.
  - The `sessionStorage` stopgap is removed, and any legacy `am.session` key is purged.
- **Docs and register:**
  - Correct `deploy/Caddyfile` and `docs/DEPLOYMENT.md`, which describe a cookie that did not exist.
  - Document `AUTH_COOKIE_SECURE`.
  - Delete the `session-store-not-durable` row.
  - Add `auth-expired-row-purge`: there is no purge for expired `auth_session`, `login_handoff` and
    `oauth_login_state` rows, and `last_seen_at` is written on every authenticated request.
- **Cross-CR:** amend `mobile-otp-auth`'s design so its cookie deferral (D4, Risks, Open questions)
  points at this CR's cookie and CSRF contract.

## Capabilities

### New Capabilities

None. Session transport belongs to `google-oauth-login`, and CSRF posture belongs to
`backend-security-baseline`.

### Modified Capabilities

- `google-oauth-login`:
  - The exchange sets a cookie instead of returning the value.
  - Sessions authenticate from the cookie, not Bearer.
  - Logout always clears the cookie.
  - New requirement: cookie attributes, the dev-mode `secure` switch and its startup guard, and that
    the SPA never holds the session value.
- `backend-security-baseline`:
  - The "Stateless, CSRF-disabled JSON API posture" requirement is replaced by a stateless posture
    **with** double-submit CSRF protection (webhooks exempt, eager token issuance, `GET /api/auth/csrf`).
  - The session-filter requirement and the owner-route scenario change from Bearer to cookie.
  - Logout changes from `authenticated` to `permitAll`.

## Impact

- **Backend (`identity` module):**
  - Change `SessionAuthenticationFilter`, `AuthController` (exchange/logout/csrf) and `AuthProperties`
    (`cookie.secure`).
  - Add a package-private `SessionCookies` in `identity.api` that owns both cookies and the
    "establish a session" step (revoke the prior session, set the cookie, rotate CSRF).
    `mobile-otp-auth` reuses it from inside the same module.
  - No new module dependencies.
- **Backend (root):**
  - `SecurityConfig` enables CSRF using identity's `CookieCsrfTokenRepository`, a header-only eager
    request handler, and a CSRF-aware access-denied handler (problem type
    `urn:agreementmitra:problem:csrf`), plus the two webhook exemptions.
  - `STATELESS` is unchanged.
  - An unsafe request without a token now gets the CSRF `403` before the staff `401`. This
    contract change is written into the baseline spec.
- **Schema:** none. `auth_session` is unchanged, so there is no migration.
- **Dependencies:** none. Spring Security 6.5 already ships everything used. It has no
  `csrf().spa()`, so the SPA pattern is wired by hand. Design D3 departs from the reference recipe:
  header-only token resolution and no Xor masking.
- **Tests:**
  - 18 backend integration test files switch from `setBearerAuth` to the session cookie.
  - The test `TestRestTemplate` gains a CSRF double-submit interceptor (through `HarnessTestConfig`),
    and the two MockMvc suites get a hand-written CSRF post-processor. The tests that prove CSRF
    enforcement and the webhook exemption deliberately bypass both.
  - No CSRF-disable switch exists for tests.
  - Frontend `authStore`, `App`, `AuthCallback`, `CaptureForm`, `AgreementStatus`,
    `StaffConsoleRoute` and API-module tests are updated.
- **Frontend:** `src/api/` (new `http.ts` wrapper; every module migrates to it), `authStore.ts`,
  `auth.ts`, `App.vue`, `CaptureForm.vue`, `AgreementStatus.vue`, `AuthCallback.vue`.
- **Deploy and docs:** `deploy/Caddyfile`, `docs/DEPLOYMENT.md`,
  `deploy/env/backend.env.example`, `CLAUDE.md` (Commands), `docs/ROADMAP.md`,
  `openspec/changes/mobile-otp-auth/design.md`.
- **Signing FSM:** no transition touched. Signing and payment routes keep their authorization; they
  only gain the CSRF header requirement from the SPA. The webhooks are unchanged and exempt.
- **PII/security checklist:**
  - No Aadhaar, OTP, VID, KYC data or new secret is introduced or moved.
  - The change reduces exposure: the session value leaves JS-reachable storage, and `HttpOnly`
    takes it out of reach of XSS exfiltration.
  - The cookie value is never logged, matching the existing no-log rule for the session value.
  - Sandbox and dummy data only, unchanged.
