## 1. Client-IP chain and body ceilings (land first)

- [ ] 1.1 Set `server.forward-headers-strategy: framework` in `application.yml`. This is a
      production bug fix as well as a prerequisite: without it every per-source limit below
      is one shared bucket (design D2).
- [ ] 1.2 Confirm `RecoveryController`'s source resolution now yields the client address
      behind a proxy, and that a client-supplied forwarding header arriving directly is
      ignored.
- [ ] 1.3 Add `POST /api/agreements` and `/api/templates/document/preview` to
      `PayloadSizeLimitConfig`'s path list.
- [ ] 1.4 Add a per-path request-body ceiling in `deploy/Caddyfile`: the smaller ceiling on
      `/api/*`, with the draft-upload route keeping its 11 MB allowance so uploads do not
      break.

## 2. Reusable limiter

- [ ] 2.1 Extract `RecoveryRateLimiter`'s algorithm into a reusable component beside
      `SecurityConfig` in the root package -- sliding window, lockout, two dimensions, both
      recorded even when one refuses -- preserving its existing javadoc rationale (design
      D3).
- [ ] 2.2 Add TTL eviction so an entry whose window and lockout have elapsed is dropped,
      closing the unbounded-map vector. Decide Caffeine vs a scheduled sweep; prefer the
      sweep absent another reason for the dependency.
- [ ] 2.3 Re-point recovery at the shared component with its current limits, so its
      behaviour changes only in the source key and in eviction.
- [ ] 2.4 Make every limit and window configurable in `application.yml` with no value
      compiled in.

## 3. Applying the limits

- [ ] 3.1 Add the filter that classifies a request by route class and applies the matching
      limit, ordered after `ForwardedHeaderFilter` (design D4).
- [ ] 3.2 Key each class per design D4's table: source only for render, anonymous write and
      webhook; source plus agreement id for capability writes and polled reads; no limit for
      the catalog, jurisdictions and health.
- [ ] 3.3 Set the starting values from design D5, all configurable.
- [ ] 3.4 Refuse with `429` as RFC 9457 problem+json with a distinct type and `Retry-After`,
      via `GlobalExceptionHandler`, echoing no submitted value and revealing nothing about
      whether the named resource exists.
- [ ] 3.5 Confirm the limiter runs before any expensive work -- no render started, no row
      written, no blob read for a refused request.

## 4. Render admission control

- [ ] 4.1 Replace `GotenbergClient`'s unbounded semaphore wait with a short bounded wait
      (design D6).
- [ ] 4.2 Refuse with `503` + `Retry-After` in the same problem+json shape when no slot is
      available in time, keeping the existing render timeout unchanged for admitted renders.

## 5. Security-event logging

- [ ] 5.1 Emit a redacted security event on webhook signature-verification failure: event,
      route, redacted source, count. No payload, verbatim or in part.
- [ ] 5.2 Emit a redacted security event on rate-limit lockout.
- [ ] 5.3 Ensure no event carries an agreement id; where a resource key is needed, record a
      salted hash with the salt from the environment (design D8).

## 6. Close the signing permit

- [ ] 6.1 Change `/api/signing/*/request` from `permitAll` to the STAFF authority in
      `SecurityConfig`, keeping the exact-path matcher (no `/api/signing/**`).
- [ ] 6.2 Delete the dead `requestSignature` client from `frontend/src/api/client.ts` and its
      test; confirm no `.vue` referenced it.
- [ ] 6.3 Fix the stale "unauthenticated today (no auth mechanism exists yet)" javadoc on
      `SigningController` and the matching comments in `SecurityConfig`.

## 7. Frontend

- [ ] 7.1 Raise the live-preview debounce in `CaptureForm.vue` from 250 ms to ~600 ms (design
      D5). This is part of this change, not a follow-up.
- [ ] 7.2 Handle `429` and `503` with a written retry message honouring `Retry-After`, rather
      than surfacing a raw status.

## 8. Edge configuration and documentation

- [ ] 8.1 Enable Cloudflare Bot Fight Mode.
- [ ] 8.2 Add a Cloudflare rate-limiting rule on `POST /api/*` excluding `/api/webhooks/*`;
      confirm the current free-plan rule quota in the dashboard first.
- [ ] 8.3 Record both in `docs/DEPLOYMENT.md` with a verification step, noting that the
      origin firewall (Cloudflare ranges only, failing closed) is what makes them real
      controls rather than advice.
- [ ] 8.4 Note in `docs/DEPLOYMENT.md` that the edge controls are manual until
      `prod-readiness-preflight` gives them a production-gate row.

## 9. Tests -- unit

- [ ] 9.1 Limiter: window slides, limit refuses, lockout expires, both dimensions recorded
      when one refuses, and an unrelated key is unaffected.
- [ ] 9.2 Eviction: an entry whose window and lockout have elapsed is dropped, and a
      rotating-source workload leaves the retained count bounded.
- [ ] 9.3 Source resolution: a forwarded address from a trusted proxy is used; a
      client-supplied header arriving directly is ignored; an unresolvable chain falls back
      to the peer rather than to no key.
- [ ] 9.4 Route classification: each route maps to the intended class, and the catalog,
      jurisdiction and health routes map to no limit.
- [ ] 9.5 Refusal shape: `429` problem+json with a distinct type and `Retry-After`, identical
      for an existing and a non-existent resource, echoing no submitted value.
- [ ] 9.6 Render admission: no slot within the bound yields `503` + `Retry-After`; a slot
      available renders as before.
- [ ] 9.7 Security events: no agreement id in any event, resource keys hashed, no webhook
      payload content present.
- [ ] 9.8 Frontend: the debounce fires at the new interval; a `429` renders a retry message
      carrying the server's wait, not a raw status.

## 10. Tests -- integration

- [ ] 10.1 Slice test: a flood on the stateless preview is refused `429` after the configured
      rate, and requests from a different source still succeed.
- [ ] 10.2 Two callers behind the same forwarded proxy occupy different buckets, and one's
      lockout does not refuse the other -- the defect this change fixes.
- [ ] 10.3 An oversized body on `POST /api/agreements` is refused before anything is
      persisted.
- [ ] 10.4 With render slots saturated, a render route returns `503` while a non-render route
      continues to serve normally (the thread-exhaustion case).
- [ ] 10.5 `POST /api/signing/*/request` is rejected anonymously and accepted for STAFF; the
      stamp-intake path still creates the signing request server-side with no anonymous call.
- [ ] 10.6 A webhook with an invalid signature is refused, emits a security event, and logs
      no payload content.
- [ ] 10.7 The legitimate end-to-end flow (create, preview, finalise, pay, poll) completes
      with no limit triggered at the configured defaults.
- [ ] 10.8 `ModularityTests` stays green with the limiter in the root package.

## 11. Close-out

- [ ] 11.1 Run `./run-tests.sh` and report the wall-clock duration against the 3-minute
      `check` budget.
- [ ] 11.2 Run the frontend gates (`npm run build`, `npm run lint`).
- [ ] 11.3 Manual test: walk the full flow as a customer against the configured defaults and
      confirm nothing throttles; then exceed one limit deliberately and confirm the retry
      message reads sensibly.
- [ ] 11.4 Verify in production that a request's resolved source is the client address, not
      Caddy's -- without logging an agreement id to do so.
- [ ] 11.5 Record in `docs/ROADMAP.md`'s follow-up register before archiving: **no CSP is
      set** (`SecurityConfig` uses `Customizer.withDefaults()`), a distributed limiter store
      when the app scales out, alerting on security events (this change only logs them), and
      per-id access logging for leaked-capability detection. Verify with
      `flow-journal.mjs followups --change anonymous-surface-abuse-controls`.
- [ ] 11.6 Update the `signing-auth` register row: this change absorbs the rate limiting, the
      verify-failure logging and the javadoc fix, and closes the signing permit. State what,
      if anything, remains of that row -- and close it if nothing does.
- [ ] 11.7 `openspec validate --strict anonymous-surface-abuse-controls`, then archive with
      `openspec archive -y anonymous-surface-abuse-controls`.
