## Why

Google login is not bound to the browser that started it. Neither the OAuth `state` row nor the
single-use handoff records who began the login, and `AuthCallback.vue` exchanges any
`#handoff=` it is given. An attacker can therefore send a victim either:

- a `/auth/callback#handoff=<attacker's>` link (live for the 60 s handoff TTL), or
- their own unfollowed `/api/auth/google/callback?code=…&state=…` URL (live for the 5 min state TTL),

and the victim's browser is signed in **as the attacker**. Anything the victim then drafts --
party names, addresses, PII -- lands under the attacker's identity, where the attacker can read it.
This is login CSRF on identity/legal infra. `cookie-session-auth` put CSRF on the exchange, which
does not help: the victim's own browser makes the exchange with its own valid CSRF token.

It is register row `login-browser-binding` (High) and sits first in the release sequence. It must
land before `mobile-otp-auth`, whose design names this gap as a precondition.

## What Changes

- `GET /api/auth/google/start` additionally sets a short-lived **login-binding cookie**
  (`__Host-am_login`, `HttpOnly; Secure; SameSite=Lax; Path=/`; `am_login` without `Secure` in
  insecure dev mode) holding a random nonce. Only the nonce's hash is stored, on the login-state row.
- `GET /api/auth/google/callback` consumes the state **only if** the request carries the same
  nonce. A missing or different nonce is refused exactly like an unknown state: no handoff is minted.
  Every refused callback now 302s to the SPA's sign-in error screen rather than returning a raw JSON
  401 to a top-level navigation. The minted handoff is bound to the same nonce.
- `POST /api/auth/session/exchange` consumes the handoff **only if** the request carries the same
  nonce. A refused exchange still leaves the browser's existing session untouched. A successful
  exchange, and logout, expire the login-binding cookie.
- Forward-only migration: a nullable `browser_binding_hash` column on `oauth_login_state` and
  `login_handoff`. Rows written before the deploy have no binding and can never be consumed, so a
  login in flight at deploy time fails once and is retried.
- `AuthCallback.vue` error copy tells the user to start sign-in again **from this browser**, since a
  login opened in one browser and finished in another now fails by design.

**BREAKING** (behavioural, beta only): a login started in one browser and completed in another --
for example, a callback URL copied between devices -- is refused. That is the attack's shape too,
so it cannot be told apart.

## Capabilities

### New Capabilities
<!-- none -->

### Modified Capabilities
- `google-oauth-login`: adds one requirement -- a login is bound to the browser that started it, at
  both the callback and the exchange. Modifies the logging requirement to add the login-binding nonce
  to the never-logged list.

## Impact

- **Backend, `identity` module only**: `GoogleLoginService`, `HandoffService`, `SessionService`,
  `OauthLoginState`/`LoginHandoff` and their repositories, `AuthController`, `SessionCookies`
  (the single cookie owner). No cross-module API change; `ModularityTests` unaffected.
  `CrossSiteRequestGuard` gains a javadoc line (why `/start` stays non-exempt).
- **Migration**: `V24__login_browser_binding.sql` (number re-checked at apply time -- another CR may
  claim V24 first).
- **Frontend**: copy-only change in `AuthCallback.vue`. No API-shape change; the exchange already
  sends cookies same-origin.
- **Host topology**: the cookie is host-only. In prod the callback and the API share
  `agreementmitra.com` (Caddy proxies `/api/*`), and locally `localhost` cookies ignore the port, so
  both the callback and the exchange see it.
- **Sibling CR**: `mobile-otp-auth`'s D4 note is corrected at archive. This CR does not bind OTP.
- **Signing FSM**: none -- no `SignatureStatus` transition is touched.
- **PII/security checklist**: no Aadhaar, OTP, VID or signer PII is introduced or moved. One new
  secret-ish value, the login nonce: random, carried only in an HttpOnly cookie, stored only as a
  hash, never logged and never placed in a response body or error. Sandbox + dummy data only is
  preserved.
