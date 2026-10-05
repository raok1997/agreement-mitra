> **Apply note:** sections 2, 3 and 5 only compile together, because repository, service and
> controller signatures change in one step and the test harness follows them. Work them as one
> atomic step; expect a red build until 5 lands.

## 1. Schema

- [x] 1.1 Re-check the highest migration number, then add `V<next>__login_browser_binding.sql` adding
      `browser_binding_hash TEXT NULL` to `oauth_login_state` and `login_handoff`. An SQL comment
      says the column is nullable for rollback and that "null never matches" is the invariant
      (design D7)
- [x] 1.2 Map the column on `OauthLoginState` and `LoginHandoff` (factory parameter). Keep
      `toString()` id-only and extend `OauthEntitiesToStringTest` to assert the binding hash is
      absent

## 2. Binding at the repositories and services

- [x] 2.1 `OauthLoginStateRepository.consume` and `LoginHandoffRepository.consume` take a
      `bindingHash` and add `and browserBindingHash = :bindingHash` to the conditional UPDATE. The
      refusal path never reads the row first (D2)
- [x] 2.2 `GoogleLoginService.start()`, after `requireConfigured`, generates a nonce with
      `SecretTokens`, stores its hash on the state row, and returns it on `StartRedirect`, whose `toString()` omits it. Add a comment at the
      authorization-URL builder that `response_mode` must stay `query` for the Lax cookie (D1, D4)
- [x] 2.3 `GoogleLoginService.handleCallback(code, state, bindingNonce)`: check order configured →
      blank code/state → blank nonce → consume with the hashed nonce; then pass the same raw nonce
      to `HandoffService.issue(identityId, bindingNonce)`. The blank-nonce refusal message is
      "login binding missing". Drop `spaCallbackUri` from `HandoffIssued` (D2, D3, D4)
- [x] 2.4 `HandoffService.issue(identityId, bindingNonce)` rejects a null or blank nonce and stores
      its hash. `HandoffService.consume(handoff, bindingNonce)` rejects a blank nonce before the
      query and consumes with the hashed nonce. `SessionService.exchange(handoff, bindingNonce)` only
      passes the nonce through; the rule lives in `consume` alone (D3)

## 3. Cookie I/O in the controller

- [x] 3.1 `SessionCookies`: add `setLoginBinding`, `readLoginBinding`, `clearLoginBinding` through one
      writer: `__Host-am_login` / `am_login` by `auth.cookie.secure`; `HttpOnly`, `SameSite=Lax`,
      `Path=/`, no `Domain`; `Max-Age = loginStateTtl + handoffTtl + 60 s`. `readLoginBinding` reads
      only the configured mode's name, with no fallback. `clear` (logout) also clears the binding
      (D1, D4)
- [x] 3.2 `AuthController` (inject `AuthProperties`): `start` sets the cookie. `callback` passes
      `readLoginBinding(request)`, redirects to `google().spaCallbackUri()` on success, and on
      `InvalidLoginException` logs `ex.getMessage()` at DEBUG and 302s to `spaCallbackUri + "#error"`.
      `exchange` passes the nonce and clears the cookie only after `establish` succeeds. Update the
      class and `handleInvalidLogin` javadocs: the fixed 401 now covers the exchange and `/start`
      only (D4)
- [x] 3.3 `CrossSiteRequestGuard` javadoc: one line on why `/api/auth/google/start` must never be
      exempt (D5)
- [x] 3.4 `OauthConfig` logs a startup WARN when the Google callback host and the SPA callback host
      differ (the host-only binding cookie cannot reach both); a WARN, not a refusal, because login
      is optional. `CallbackHostCheckTest` (raised by code review, Stage 4c) (D1)

## 4. Frontend copy

- [x] 4.1 `AuthCallback.vue` error copy: "We couldn't complete sign-in. Please start again from this
      browser." `AuthCallback.test.ts` asserts the new "start again from this browser" text (the
      existing `toContain("couldn't complete sign-in")` passes unchanged and proves nothing), and
      that arriving with `#error` shows the error without calling the exchange (D6)

## 5. Test harness and existing callers

- [x] 5.1 `support/StaffSessions`: generate a nonce with `new SecretTokens().newToken()` and pass it
      to both `issue` and `exchange`. Keep its public signature, so its 21 call-site files do not
      change (D3)
- [x] 5.1a `CsrfTestInterceptor.apply` joins every `Cookie` header value instead of keeping only the
      first. Otherwise a request sending the session and the binding as separate headers silently
      loses the binding, and the negative tests pass because it is missing. Add one positive control:
      session and binding sent as separate headers, and the exchange succeeds
- [x] 5.2 Route the direct issue+exchange callers through a bound nonce:
      `AgreementOwnershipIntegrationTest`, `AgreementCapturePersistenceIntegrationTest`,
      `ContactGateIntegrationTest`, `SessionCookieIntegrationTest`,
      `InsecureCookieModeIntegrationTest`. Tests that call the exchange over HTTP send the matching
      binding cookie for their mode
- [x] 5.3 **Every existing negative test sends the matching binding cookie**, so its own rule is the
      only reason left for the refusal. Covers `aReusedHandoffMintsNoSecondSession`,
      `aReusedOrExpiredHandoffIsRefusedWithNoSessionCookie` and
      `aBadHandoffWhileSignedInLeavesThePriorSessionIntact`. That last test sends a handoff no row
      is bound to, so for it "matching" means a non-blank binding cookie present. Without the cookie
      they would pass because it is missing, and prove nothing
- [x] 5.4 Fix the remaining compile-broken callers: `GoogleLoginConfiguredGuardTest` (three-argument
      `handleCallback`), `SessionServiceTest` (`consume`/`exchange` stubs), and `AuthControllerTest`
      (`exchange` stubs, a `readLoginBinding` stub on the mocked `SessionCookies`, the new
      `AuthProperties` constructor argument, and `GoogleLoginService` as a field mock so 6.4 can stub
      it). `GoogleLoginHandshakeIntegrationTest.fullLoginHandshake…` and
      `aReusedHandoffMintsNoSecondSession` call the callback without the cookie, and would NPE on
      the missing `#handoff` fragment: carry the binding cookie from `start`

## 6. Unit tests

- [x] 6.1 `HandoffServiceTest`: `issue` rejects a null or blank nonce, and the saved row's
      `browserBindingHash()` equals `hasher.hash(nonce)` (a package-private accessor; `toString()`
      proves nothing here);
      `consume` passes `hash(nonce)` to the repository and throws on a `0` return or a blank nonce.
      This checks the argument wiring only; the WHERE clause is proven in 7.4
- [x] 6.2 New `GoogleLoginServiceBindingTest`: `start` stores `hasher.hash(nonce)` on the state row
      (asserted on the column value) and returns the raw nonce; `StartRedirect.toString()` omits it; a callback with a blank nonce throws without
      touching the repository; a `0` consume throws without calling `GoogleTokenExchange`; the
      handoff is issued with the same raw nonce
- [x] 6.3 `SessionCookiesTest`: login-binding cookie attributes in secure and insecure mode; `Max-Age`
      equals the sum plus 60 s; the cleared cookie carries the same name, `Path` and `Secure`;
      secure mode ignores a presented `am_login`; `clear` (logout) also expires the binding
- [x] 6.4 `AuthControllerTest`: `start` sets the cookie; a refused callback 302s to
      `spaCallbackUri + "#error"`; `exchange` clears the cookie only on success; a refused exchange writes no
      cookie, leaving the browser's own binding intact

## 7. Integration tests

- [x] 7.1 `GoogleLoginHandshakeIntegrationTest`:
      - the full handshake carries the cookie from start to exchange, and the exchange expires it;
      - an unfollowed callback URL replayed with no cookie, and again with the victim's own
        different binding, 302s to the SPA route with `#error`;
      - in that replay, no WireMock token POST carries that test's unique `code`, and the state row,
        looked up by `TokenHasher.hash(state)`, keeps `consumed_at` null;
      - the attacker's handoff, exchanged under no cookie and under a different one, is refused, mints
        no session, and leaves the handoff unconsumed
- [x] 7.2 `SessionCookieIntegrationTest`: a browser with a live session for A presents a handoff
      bound to another browser; it is refused, and A still authenticates
- [x] 7.3 `InsecureCookieModeIntegrationTest`: `start` sets `am_login` without `Secure`. In the secure
      profile (7.1's class), an exchange presenting the right nonce as `am_login` is refused
- [x] 7.4 Repository-level, against Postgres, in a new test in `identity.oauth` (the repositories are
      package-private). Copy `IdentitySchemaValidateIntegrationTest`'s exact `@SpringBootTest` /
      `@Import(HarnessTestConfig.class)` / `@ActiveProfiles("test")` set so the cached context is
      reused. Do not add a `@DataJpaTest` slice, which would start a context of its own. Run each case
      in a `TransactionTemplate`, because `@Modifying` JPQL needs a transaction, and read `consumed_at`
      with `JdbcTemplate`. Null-binding rows are written by raw JDBC insert, since `issue` rightly
      refuses a blank nonce. `consume` with the right hash returns 1; with a wrong hash or against a
      null-binding row it returns 0 and `consumed_at` stays null. Covers both repositories
- [x] 7.5 No-secret check in `GoogleLoginHandshakeIntegrationTest`: capture the `in.agreementmitra`
      logger tree at DEBUG with `support/LogCapture` across a full login, then assert the nonce
      appears in no log line, no response body and no `Location`. `Set-Cookie` is excluded by
      design, and Spring/Hibernate loggers are out of the scan's scope

## 8. Gates

- [x] 8.1 `./run-tests.sh check` from `backend/` (report wall-clock; `ModularityTests` green)
- [x] 8.2 `npm run build` and `npm run lint` from `frontend/`

## 9. Housekeeping (at archive)

- [x] 9.1 `docs/ROADMAP.md`: delete the `login-browser-binding` register row and remove it from the
      release sequence (item 1). Append to the `auth-expired-row-purge` row: tighten
      `browser_binding_hash` to NOT NULL once rollback past this migration is off the table
- [x] 9.2 `openspec/changes/mobile-otp-auth/design.md` D4 and Risks: replace "inherits the
      `login-browser-binding` gap" with this CR's Open Questions finding: not covered here, and the
      test is whether verify inputs can arrive from a URL

## Coverage

| Scenario | Disposition | Where |
|---|---|---|
| A login completed in the same browser succeeds | COVERED | 7.1 |
| The start response sets a hardened login-binding cookie | COVERED | 6.3 (attributes, Max-Age), 6.2 (hash-only storage) |
| Insecure dev mode drops Secure and the prefix from the login-binding cookie | COVERED | 7.3, 6.3 |
| Secure mode ignores an unprefixed login-binding cookie | COVERED | 6.3, 7.3 |
| An attacker's unfollowed callback URL is refused in the victim's browser | COVERED | 7.1, 6.4 |
| An attacker's handoff link is refused in the victim's browser | COVERED | 7.1, 6.4 |
| A refused binding leaves the victim's own session intact | COVERED | 7.2 |
| A refused callback never exchanges a fragment it inherited | COVERED | 6.4 (`Location` ends `#error`), 4.1 (no exchange on `#error`) |
| A row without a binding is never consumed | COVERED | 7.4 |
| Logout expires the login-binding cookie | COVERED | 6.3 |
| The nonce appears in no response body or redirect | COVERED | 7.5 |
| No secret or PII appears in logs during a full login (MODIFIED) | COVERED | 7.5 |
