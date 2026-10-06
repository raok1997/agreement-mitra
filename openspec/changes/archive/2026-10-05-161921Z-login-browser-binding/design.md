## Context

Google login is a three-hop handshake, all in the `identity` module:

1. `GET /api/auth/google/start`: `GoogleLoginService.start()` persists an `oauth_login_state` row
   (state hash + PKCE verifier) and 302s to Google.
2. `GET /api/auth/google/callback`: `handleCallback(code, state)` consumes the state with one
   conditional UPDATE (`OauthLoginStateRepository.consume`). It then exchanges the code, validates
   the ID token, and `HandoffService.issue` mints a `login_handoff`. The browser is 302'd to
   `/auth/callback#handoff=…`. A refused callback currently returns the controller-wide fixed `401`
   problem body (`AuthController.handleInvalidLogin`).
3. `POST /api/auth/session/exchange`: `SessionService.exchange(handoff)` consumes the handoff (one
   conditional UPDATE, `LoginHandoffRepository.consume`) and mints a session.
   `SessionCookies.establish` then sets the cookie, rotates CSRF and revokes any prior session
   (cookie-session-auth D7).

Nothing in steps 2 or 3 checks that the browser is the one from step 1. That is the login-CSRF gap
(proposal). `cookie-session-auth` split it out on 2026-10-04 and named the fix.

**Existing control.** `CrossSiteRequestGuard` refuses any `/api` request whose `Sec-Fetch-Site` is
`cross-site`. Only `GET /api/auth/google/callback` and the vendor webhooks are exempt.
`CrossSiteRequestGuardTest:53` already asserts that a cross-site `GET /api/auth/google/start` is a
`403`. The callback must stay exempt, since it is cross-site by nature. That is why the binding has
to be checked there.

**Host topology,** which a host-only cookie depends on:
- Prod serves the SPA and `/api/*` on `agreementmitra.com` (`deploy/Caddyfile` redirects to the
  apex; `GOOGLE_OAUTH_REDIRECT_URI` and `GOOGLE_OAUTH_SPA_CALLBACK_URI` both sit on that host).
- Locally the callback is `localhost:8090` and the SPA is `localhost:5173`. Cookies ignore the port,
  so one `localhost` cookie reaches both.

## Goals / Non-Goals

**Goals:**
- A callback or exchange succeeds only in the browser that called `/google/start`.
- Keep the existing race-safety: each consume stays one conditional UPDATE.
- Keep existing guarantees: no enumeration oracle, the D7 ordering (a refused exchange leaves the
  prior session intact), and no secret in logs.
- A legitimately refused login (two tabs, lost cookie) lands on the SPA's error screen, not a raw
  error body.

**Non-Goals:**
- Converting `/google/start` to POST (see D5).
- Binding `mobile-otp-auth`'s verify step. See Open Questions: this CR does **not** cover it.
- Purging expired rows. That is `auth-expired-row-purge`.
- Supporting a login that starts in one browser and finishes in another. It has the attack's shape.

## Decisions

### D1 — One login-binding cookie: `__Host-am_login`

The cookie is `__Host-am_login` (`HttpOnly; Secure; SameSite=Lax; Path=/`, no `Domain`). With
`auth.cookie.secure=false` it is `am_login` without `Secure`, mirroring the session cookie's mode
switch.
- **Read under one name only.** In secure mode `readLoginBinding` reads only `__Host-am_login` and
  never falls back to `am_login`. The unprefixed name can be planted by a sibling host or over plain
  http. `SessionCookies.read` already reads only the configured name, and this follows it.
- **Lax, not Strict.** Google's redirect to `/callback` is a cross-site top-level GET, and a Strict
  cookie is not sent on it. This holds only while Google answers with `response_mode=query` (the
  default; `start()` sets no `response_mode`). A future `form_post` would be a cross-site POST that
  carries no Lax cookie, and every login would fail closed. A comment at the `start()` URL builder
  says so.
- **Max-Age = `loginStateTtl + handoffTtl + 60 s`.** The cookie must outlive the state and then the
  handoff, because the exchange reads it too. The handoff's TTL starts at `issue()`, after the Google
  token exchange and ID-token validation. A callback that consumes the state just before it expires
  would otherwise get a handoff that outlives the cookie by that round trip. The fixed 60 s margin
  absorbs a normal round trip. The Google token POST and JWKS fetch are bounded by `GoogleHttp`
  (3 s connect / 7 s read per call, at most three calls counting a JWKS refresh on key rotation;
  shipped as a separate change set during apply), so the round trip
  fits the margin. Should it not, the cookie expires first, the exchange fails closed and the user
  retries. The spec states the bound as "at least" the sum.
- **The name is not Google-specific,** but only `/google/start` sets it. If `mobile-otp-auth` binds
  its challenge to the same cookie, it needs its own setter, and the two flows overwrite each other's
  binding when started together. That CR decides whether to accept the overwriting or use its own
  name.
- **Alternative rejected: a separate cookie per hop** (one checked at callback, a new one set at
  callback for the exchange). That doubles the cookie handling and adds nothing, because the binding
  already travels on the handoff row.

### D2 — Store only the hash; check it inside the consume UPDATE

Both tables gain a nullable `browser_binding_hash`, hashed with the same `TokenHasher` as state and
handoff. Each repository's `consume` adds `and x.browserBindingHash = :bindingHash` to its WHERE
clause. A mismatch then returns `0` rows, the same as an unknown, reused or expired value. That
gives:
- **Race-safety unchanged.** There is no separate read-then-check window, and the refusal path must
  never read the row first.
- **No oracle.** A wrong cookie and a wrong state are indistinguishable to the caller.
- **The row stays unconsumed on mismatch.** A victim landing on the attacker's URL burns nothing,
  and the attacker's own value stays usable only by the attacker's browser.

A missing or blank cookie is rejected in Java before the query, with the same
`InvalidLoginException`. This keeps a null out of JPQL equality. A null in the column never equals
anything, so pre-deploy rows can never be consumed. The order of checks in `handleCallback` is:
configured → blank code/state → blank nonce → consume. That keeps `GoogleLoginConfiguredGuardTest`'s
"refuse before touching any collaborator" property.

**Alternative rejected:** read the row, then compare the hash in Java. That reopens the TOCTOU window
the single UPDATE was written to close.

### D3 — Raw nonce in, hash inside: a symmetric `HandoffService` API

Both `HandoffService` methods take the **raw nonce** and hash it internally:
`issue(identityId, bindingNonce)` and `consume(handoff, bindingNonce)`. `issue` rejects a null or
blank nonce, so a handoff that can never be consumed is never written. The blank-nonce check on the
exchange path lives only in `HandoffService.consume`. `SessionService.exchange` passes the nonce
through without its own copy of the rule.

`handleCallback` already holds the raw nonce. The consume UPDATE has just proved that its hash equals
the state row's hash, so passing the same raw value to `issue` binds the handoff to the same browser.
No hash is copied between rows.

Rejected: `issue(identityId, bindingHash)` alongside `consume(handoff, rawNonce)`. Both parameters
would be `String`, and passing the raw nonce to `issue` would compile and persist a dead handoff.

The symmetric shape also makes the test helper easy. `support/StaffSessions` generates a random nonce
with `new SecretTokens().newToken()` (the class has a public no-arg constructor) and passes it to
both calls. Its signature, its 32 call sites, and its "no
hand-forged hash" contract all stay as they are.

### D4 — Cookie I/O stays in `api`; services stay HTTP-free

`SessionCookies` is already "the single owner of the browser-session cookies". It gains
`setLoginBinding`, `readLoginBinding` and `clearLoginBinding`. Set and clear go through one writer,
so the deletion carries the same name, `Path=/` and `Secure` the browser needs to accept it for a
`__Host-` cookie. `SessionCookies.clear` (logout) also clears the login binding.

`GoogleLoginService.start()` generates the nonce and returns it on `StartRedirect`. The controller
sets the cookie, and the services take the raw nonce as a parameter:
- `handleCallback(code, state, bindingNonce)`
- `SessionService.exchange(handoff, bindingNonce)`
- `HandoffService.consume` (D3)

`StartRedirect` is a record, and a record's generated `toString()` would print the nonce. It
therefore overrides `toString()` to omit it. This keeps "never logged" true by construction, as
`OauthLoginState.toString()` already does.

**Callback refusal.** The `callback` handler catches `InvalidLoginException` itself and 302s to
`spaCallbackUri + "#error"`. This is a top-level navigation, so a JSON problem body would render as
raw text, and the binding check makes this the normal failure path for a legitimate user.
- **Why a fixed `#error` and not an empty fragment.** A `Location` without a fragment inherits the
  original request's fragment (RFC 9110 §10.2.2). A link to
  `/api/auth/google/callback?…#handoff=<attacker's>` would otherwise land on the SPA with the
  attacker's handoff and trigger an exchange. The binding refuses that exchange, but the redirect
  should not invite it.
- **Where the URI comes from.** `AuthController` injects `AuthProperties` and reads
  `google().spaCallbackUri()` on both the success and the refusal path, so the two cannot diverge.
  `HandoffIssued.spaCallbackUri` is then redundant and is dropped. `spa-callback-uri` always has a
  default (`application.yml`), so no fallback is needed.
- **Logging.** The catch keeps `handleInvalidLogin`'s `log.debug("Login/exchange rejected: {}",
  ex.getMessage())`, so a callback refusal still leaves a trace. The blank-nonce check has its own
  message ("login binding missing"), which separates "the cookie never arrived" from a bad state
  during the manual test and the beta. The message never carries the nonce or the state.
- **Scope.** Only login refusals (`InvalidLoginException`) redirect. An infrastructure failure keeps
  its 500 so that monitoring sees it. `GET /api/auth/google/start` with Google unconfigured still
  returns the JSON 401. It is not a regression, and `/start` sits outside this CR's binding path.
- The class javadoc and the `handleInvalidLogin` javadoc both say that "every" login failure gets the
  fixed 401. They are updated to say this now covers the exchange (and `/start`) only.

The redirect is identical for every cause, so no oracle opens. The exchange keeps the controller-wide
fixed `401`, which the SPA's `fetch` handles.

`clearLoginBinding` runs in the exchange handler **after** `establish` succeeds, so D7's order is
untouched: consume + mint, then cookies. A refused exchange throws before any cookie is written. The
browser's own login cookie survives, so its own login can still complete if the refused attempt was
an attacker's link.

### D5 — `/google/start` stays a GET that sets a cookie

CLAUDE.md requires GET handlers to be side-effect-free, because a cross-site GET carries Lax cookies.
`/google/start` already writes a login-state row, and it now also sets a cookie. It is a deliberate,
justified exception:
- `CrossSiteRequestGuard` already refuses a cross-site `/start` from every browser that sends Fetch
  Metadata (`CrossSiteRequestGuardTest:53` pins this). Forcing it is only possible from a client
  without `Sec-Fetch-Site`.
- Even then, the forced call replaces the victim's nonce with one the attacker never sees (it is
  HttpOnly and only its hash is stored). The worst outcome is aborting one in-flight login, which the
  victim retries. It grants the attacker nothing.
- **`/start` must never join the guard's exemption list.** Exempting it would open the
  forced-abort path for every browser. The existing test is the regression guard, and the
  `CrossSiteRequestGuard` javadoc gains one line saying why `/start` is not exempt.

Converting start to POST would mean a form post from the SPA and a CSRF bootstrap ahead of it, for no
security gain. Rejected as scope creep.

### D6 — Frontend: copy only

A refused callback now 302s to `/auth/callback#error` (D4). `AuthCallback.vue` already shows its
error state and skips the exchange when no `handoff` parameter is present (`AuthCallback.vue:14`), so
both failure paths land on one screen with no new frontend logic. The copy changes to "We couldn't complete sign-in. Please
start again from this browser." That gives a legitimate user whose cookie was lost a next step
without saying why. Typical causes are two tabs, an in-app browser handing off to the system browser,
or cleared cookies. No API change: `apiFetch` already sends cookies same-origin.

The same screen also appears when a user presses Cancel on Google's consent screen: Google returns
`error=access_denied` with no code, which is a login refusal. "Start again" is the right next step
there too, though it does not name the cause. Accepted; the manual tester should not file it as a
bug.

### D7 — Migration: nullable columns, no backfill

`V24__login_browser_binding.sql` (renumber at apply time if another CR has taken V24) adds
`browser_binding_hash TEXT NULL` to `oauth_login_state` and `login_handoff`. The type matches the
existing `state_hash` and `handoff_hash` (`V11__identity_oauth.sql`).

The column is nullable **for rollback**. The previous build inserts rows without the column, which a
NOT NULL would break. Deleting the ephemeral in-flight rows would be cheap, so that was not the
constraint. "Null never matches" (D2) gives the security property regardless. An SQL comment records
both points, so a later CR does not "fix" the column to NOT NULL with a default. Tightening to NOT
NULL once rollback past this migration is off the table belongs with `auth-expired-row-purge`, which
already owns these tables' lifecycle.

## Risks / Trade-offs

- **[Two tabs start a login]** The second `/start` overwrites the cookie, so the first tab's callback
  is refused. → Accepted: it lands on the SPA error screen (D4/D6), and the newer tab completes.
- **[In-app browsers / cross-browser handoff]** A login begun in a WhatsApp or Instagram in-app
  browser that Google finishes in the system browser loses the cookie. → Refused by design, since it
  is indistinguishable from the attack. The copy gives the next step. Revisit only if beta usage
  shows it.
- **[Cookie blocked]** A browser that blocks first-party cookies cannot log in. → The session cookie
  already requires cookies, so nothing that works today breaks.
- **[LAN-IP dev]** `GOOGLE_OAUTH_REDIRECT_URI` points at `localhost`, so a SPA opened on
  `192.168.x.x` has its cookie on a different host from the callback. → Google login from a LAN IP
  does not work today either (Google will not redirect to a private IP). Documented here, no change.
- **[Forced `/start` aborts a login]** Only from clients without Fetch Metadata (D5). → Accepted:
  denial of one login attempt, no gain to the attacker.
- **[Real-browser cookie behaviour]** MockMvc and WireMock cannot prove that browsers send the Lax
  cookie across Google's consent chain. → The manual-test gate runs a real Google login, including on
  Safari/iOS (ITP), plus one deliberate cross-browser attempt.

## Migration Plan

1. Deploy backend and frontend together (as `cookie-session-auth` did). The migration runs on
   startup.
2. Logins in flight at deploy time fail once and are retried.
3. Rollback: redeploy the previous build. The old code ignores the nullable columns, and JPA
   `validate` tolerates extra columns, so there is no down-migration. **A rollback is a security
   regression, not a neutral redeploy.** The previous build ignores the binding, so login CSRF
   reopens immediately.

## Open Questions

None blocking this CR. One correction is owed to `mobile-otp-auth`, and task 9.2 records it in that
CR's D4. This CR does **not** close the gap for OTP. CSRF on the verify POST is not the test, as this
CR's own exchange shows. The test is whether attacker-chosen verify inputs (a challenge id and code,
or any magic/WhatsApp link) can reach an SPA route that submits them in the victim's browser.
- If any SPA route consumes verify inputs from a URL or fragment, the OTP challenge must be bound to
  the browser that requested it, with its own setter (D1).
- If no route does, the gap does not apply to OTP, and the sequencing note can be dropped.
