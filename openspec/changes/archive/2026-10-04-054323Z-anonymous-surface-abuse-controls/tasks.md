## 1. Client-IP chain (land first; both halves deploy together -- design D2)

- [x] 1.1 In `deploy/Caddyfile`'s `/api/*` `reverse_proxy`, add `header_up X-Forwarded-For {client_ip}`
      (overwrite, not append), `header_up X-Forwarded-Proto https`, and strip `Forwarded`,
      `X-Forwarded-Host`, `X-Forwarded-Prefix` and `X-Forwarded-Port`.
- [x] 1.2 In `deploy/docker-compose.prod.yml`, declare the compose network's subnet and give `caddy` a
      static `ipv4_address` on it.
- [x] 1.3 In `application.yml`, set `server.forward-headers-strategy: native`,
      `server.tomcat.remoteip.remote-ip-header: x-forwarded-for`, and
      `server.tomcat.remoteip.internal-proxies` to Caddy's static address only (env-overridable). The
      test profile trusts loopback, so tests can simulate distinct clients with `X-Forwarded-For`
      (TEST-NET addresses, `203.0.113.x`).
- [x] 1.4 Add a root-package source resolver: the resolved remote address (IP literals only -- never a
      lookup that could hit DNS), IPv6 aggregated to its `/64` (configurable), falling back to one
      shared bucket when nothing resolves (in effect the peer's -- see D2). Use it in `RecoveryController` in place of the raw
      `getRemoteAddr()`.
- [x] 1.5 Correct the `RecoveryIntegrationTest` comment that says production already keys on a real
      client address.

## 2. Body guard (design D9)

- [x] 2.1 Replace `signing/api/PayloadSizeLimitConfig` with a root-package filter ordered ahead of the
      security chain, applying the 1 MiB ceiling to every unsafe `/api/**` request except
      `POST /api/agreements/*/draft` and `POST /api/staff/estamp` (multipart, own ceilings).
- [x] 2.2 Enforce the ceiling on the stream as well as the declared `Content-Length`. The filter writes
      `413` problem+json of the existing `payload-too-large` type for a declared over-limit length. For
      the streamed case the guard throws a dedicated exception, and
      `GlobalExceptionHandler.handleHttpMessageNotReadable` walks the cause chain and returns the same
      `413` when it finds it, before its `400 malformed-request` fallback.
- [x] 2.3 Add `request_body max_size` in `deploy/Caddyfile`: 1 MB on `/api/*` through a matcher that
      **excludes** the draft-upload and staff e-stamp upload paths, and 11 MB on those two paths.

## 3. Reusable limiter (design D3)

- [x] 3.1 Add a root-package limiter: sliding window per key, keys scoped (class, dimension, value), two
      dimensions both recorded even when one refuses, lockout configurable per dimension and held per
      class, Caffeine storage with `expireAfterAccess(window + lockout)` and `maximumSize` (one cache
      per limiter configuration), per-key updates via atomic `compute`, and a public `reset()` test
      seam. Take the `Clock` through the constructor defaulting to `Clock.systemUTC()` -- **no new
      `Clock` bean** -- and bridge the cache's `Ticker` from it. Carry over `RecoveryRateLimiter`'s
      rationale javadoc (an IP is not a person; both dimensions recorded).
- [x] 3.2 Add Caffeine (Boot-BOM managed) to `build.gradle.kts`, regenerate `gradle.lockfile`
      (`./gradlew dependencies --write-locks`), and confirm `osvScan` stays clean.
- [x] 3.3 Add a `@Validated` properties record: one `abuse.limits.enabled` switch for the route classes,
      and per class the per-source and per-resource limit, window and lockout, plus the cache
      `maximumSize`. Binding fails when a class's per-resource limit is not higher than its per-source
      limit. No value compiled in.
- [x] 3.4 Move recovery onto the shared limiter with its current values (30/source, 5/reference,
      15 min window, 30 min lockout on both dimensions) and delete `RecoveryRateLimiter`. Move
      `RecoveryIntegrationTest` and `RecoveryWithNoEnabledChannelIntegrationTest` from `clear()` to
      `reset()`.

## 4. Applying the limits (design D4, D5)

- [x] 4.1 Add the route-class filter in the root package and register it in `SecurityConfig` with
      `addFilterAfter(…, CsrfFilter.class)`; disable its servlet auto-registration; count
      `DispatcherType.REQUEST` only.
- [x] 4.2 Implement design D4's class table as (method, `PathPattern`) entries written with `{id}`: each
      entry supplies the class, the `route` label for events, and (via `matchAndExtract`) the `{id}`.
      Include the bootstrap class (600/min per source, no lockout), the not-limited health and error
      routes, recovery and both webhooks as excluded, and the default class for anything unlisted.
- [x] 4.3 Derive the resource key by parsing `{id}` as a UUID and canonicalizing it; a value that does
      not parse gets no per-resource bucket.
- [x] 4.4 Set design D5's starting values with lockout on the per-source dimension only (none for
      bootstrap). Set `abuse.limits.enabled: false` in the test profile (recovery's own limiter stays
      on).
- [x] 4.5 Have the filter write the refusal itself: `429` problem+json, type
      `urn:agreementmitra:problem:rate-limited`, `instance` equal to the type URN, `Retry-After` in
      seconds, identical for an existing and a non-existent resource, echoing no path or body.
- [x] 4.6 Confirm a refused request does no expensive work: no session lookup, no render, no row
      written, no blob read.

- [x] 4.7 Add a `CrossSiteRequestGuard` in the chain between `CsrfFilter` and the limiter, always on:
      refuse an `/api` request carrying `Sec-Fetch-Site: cross-site` with `403` problem+json
      (`urn:agreementmitra:problem:cross-site`), uncounted, except `GET /api/auth/google/callback`
      and both webhooks. *(Added at apply from code review; user-approved 2026-10-04.)*
- [x] 4.8 Classify the stateless preview's HTML variant (`Accept: text/html`) as a separate
      live-preview class (300/min, no lockout); its PDF variant stays in the render class.
      *(Added at apply from code review; user-approved 2026-10-04.)*
- [x] 4.9 In the limiter, do not count a request the source dimension refused against the resource
      dimension (a resource refusal still counts against the source). *(Added at apply from code
      review; user-approved 2026-10-04.)*

## 5. Render admission control (design D6)

- [x] 5.1 Add `gotenberg.admission-wait` (default `PT2S`), `gotenberg.max-waiters` (default 2) and
      `gotenberg.reserved-renders` (default 1) to `GotenbergProperties`, with a startup check that
      `reserved-renders` is less than `max-concurrent-renders` and that slots plus waiters leave
      connections in the Hikari pool. Update every construction of the record, including
      `GotenbergClientFooterTest`.
- [x] 5.2 In `GotenbergClient`, replace the blocking `acquire()` with admission over a general pool and a
      reserved pool behind a bounded waiting room: refuse at once when every eligible slot is busy and
      the waiting room is full, otherwise `tryAcquire` for at most `admission-wait`. Refuse with a new
      public `RenderCapacityException` (distinct from `DocumentRenderException`). *(As built: in the
      root package beside `DocumentDataInvalidException`, not in `documents` -- `GlobalExceptionHandler`
      importing a `documents` type made `ModularityTests` report a root <-> documents cycle.)* Keep
      the render timeout unchanged for admitted renders.
- [x] 5.3 Add a public render priority (`STANDARD` default, `FULFILMENT`) through the two-argument
      `DocumentProjectionApi.generate(request, systemValues)` overload, `DocumentProjectionService` and
      `HtmlPdfRenderer.toPdf`. *(As built: a three-argument `generate(request, systemValues, priority)`
      and `toPdf(html, reference, priority)` beside the existing forms, which stay `STANDARD`.)* A `FULFILMENT` render may take the reserved or a general slot; a
      `STANDARD` render only a general one. Pass `FULFILMENT` only from
      `AgreementDocumentService.renderForStamp`.
- [x] 5.4 Map `RenderCapacityException` in `GlobalExceptionHandler` to `503` problem+json,
      `urn:agreementmitra:problem:render-busy`, with `Retry-After`.
- [x] 5.5 In `renderForStamp`, catch `RenderCapacityException` alongside `DocumentRenderException` and
      raise `StampRenderUnavailableException`, so a refused fulfilment render is audited
      `OUTCOME_RENDER_UNAVAILABLE` and staff see the existing problem.

## 6. Security-event logging (design D8)

- [x] 6.1 Add a security-event emitter on logger `in.agreementmitra.security` with fields `event`,
      `route` (the classifier's pattern, `default` for the default class, the controller mapping for a
      webhook -- never the URI), `class`, `source` (IPv4 `/24`, IPv6 `/48`) and `count`.
- [x] 6.2 Hash a per-resource lockout key (in practice recovery's per-reference lockout) with HMAC-SHA-256
      under a random key generated at startup and held only in memory; log a truncated digest.
- [x] 6.3 Emit `rate_limit_lockout` on the transition into lockout only, never per refused request, for
      the route classes and for recovery alike.
- [x] 6.4 Emit `webhook_verification_failed` from `WebhookController` and `RazorpayWebhookController`:
      immediately on a source's first failure, then once on the next failure after a minute, carrying
      the count since the last event. Keep that per-source state in a size-capped cache. Leave the
      adapters' existing WARN lines unchanged. Log no payload, verbatim or in part.

## 7. Close the signing permit (design D7)

- [x] 7.1 Change `POST /api/signing/*/request` from `permitAll` to `hasRole("STAFF")` in
      `SecurityConfig`, keeping the exact-path matcher (no `/api/signing/**`). CSRF still applies.
- [x] 7.2 Move **every** test call site that posts to `/api/signing/*/request` without a STAFF session
      onto one (`support/StaffSessions`) -- find them with a grep, not a list (at review time: 22 sites
      in 10 files, including `ZoopSigningIntegrationTest` and `SigningCompletionIntegrationTest`).
      Rewrite `SecurityBaselineIntegrationTest.signingRequestStubIsPermittedAndReachesTheController`
      and `CsrfProtectionIntegrationTest.theSigningRequestStubWithATokenIsNotRefusedByTheChain` to the
      STAFF semantics in 11.6. Re-run the grep after; it must find no anonymous caller.
- [x] 7.3 Delete the dead `requestSignature` client from `frontend/src/api/client.ts` and its test;
      confirm no `.vue` referenced it.
- [x] 7.4 Fix the stale text: the "unauthenticated today" javadoc on `SigningController`, the
      `SecurityConfig` comments on the signing permit and on the preview route's "owed" rate limiting,
      and `docs/DEPLOYMENT.md`'s note that the signing and draft routes have no application limit.

## 8. Frontend (design D5)

- [x] 8.1 Raise the live-preview debounce in `CaptureForm.vue` from 250 ms to ~600 ms.
- [x] 8.2 In `apiFetch` (`src/api/http.ts`), throw a `ServiceBusyError` carrying `Retry-After` in seconds
      (default 5 when absent) for a `429`, and for a `503` whose problem type is `render-busy`. Every
      other response, including `stamp-render-unavailable` and bodyless edge `5xx`, is returned
      unchanged. Do not retry the refusal and do not call `reconcileHook` for it.
- [x] 8.3 Handle `ServiceBusyError` with a written retry message in the capture-form preview, agreement
      create, finalise, payment, status and staff console views, in place of a raw status or a generic
      error.
- [x] 8.4 Make `usePolling` receive the error and wait its `Retry-After` instead of doubling its own
      interval; make `AgreementStatus`'s `Promise.allSettled` surface a `ServiceBusyError` in preference
      to any other rejection.

## 9. Edge configuration and documentation

- [x] 9.1 Enable Cloudflare Bot Fight Mode. *(Descoped 2026-10-04 to the `prod-readiness-preflight` register row in `docs/ROADMAP.md`: a dashboard/production gate, verification steps in `docs/DEPLOYMENT.md` §5.6.)*
- [x] 9.2 Add a Cloudflare rate-limiting rule on `POST /api/*` excluding `/api/webhooks/*`; confirm the
      current free-plan rule quota in the dashboard first. *(Descoped 2026-10-04 to the `prod-readiness-preflight` register row in `docs/ROADMAP.md`: a dashboard/production gate, verification steps in `docs/DEPLOYMENT.md` §5.6.)*
- [x] 9.3 Record in `docs/DEPLOYMENT.md`, each with a verification step: the two Cloudflare controls;
      Cloudflare "Pseudo IPv4" kept Off (never "Overwrite headers"); the Caddy forwarded-header overwrite
      and strip and the per-path body ceilings; Caddy's static address and `internal-proxies`, and that
      the two must deploy together. Note that the origin firewall (Cloudflare ranges only, failing
      closed) is what makes the edge controls real rather than advice, that it admits any Cloudflare
      tenant, and that Authenticated Origin Pulls would close that.
- [x] 9.4 Note in `docs/DEPLOYMENT.md` that the edge controls are manual until
      `prod-readiness-preflight` gives them a production-gate row.

## 10. Tests -- unit

- [x] 10.1 Limiter: window slides; the limit refuses; a per-source lockout expires; a per-resource
      refusal sets no lockout and clears once the window moves; both dimensions are recorded when one
      refuses; a lockout in one class does not refuse the same source in another; an unrelated key is
      unaffected.
- [x] 10.2 Eviction, driven by a test clock bridged to the cache `Ticker` with a same-thread executor (or
      `cleanUp()` before asserting): an entry whose window and lockout have elapsed is dropped; distinct
      keys beyond `maximumSize` leave the retained count at or below the cap.
- [x] 10.3 Source resolver: IPv6 addresses in one `/64` yield one key; an unresolvable or non-literal
      address falls back to the immediate peer.
- [x] 10.4 Route classification: each route in D4 maps to its class; every `/api` endpoint in
      `RequestMappingHandlerMapping` is either in the table or on the explicit default-class list (a new
      endpoint fails the test); no two entries for one method overlap; the bootstrap routes carry the
      high ceiling with no lockout; recovery and both webhooks are excluded; an unlisted route maps to
      the default class and is limited.
- [x] 10.5 Resource key: the same id in different letter case is one bucket; an unparseable `{id}` gets
      no resource bucket; `/api/signing/{id}/progress` and `/api/agreements/{id}/payment` extract the id.
- [x] 10.6 Refusal shape: `429` problem+json with the rate-limited type, `instance` equal to the type and
      `Retry-After`, identical for an existing and a non-existent resource, echoing no path or body.
- [x] 10.7 Configuration checks: binding rejects a class whose per-resource limit is not higher than
      its per-source limit; startup rejects `reserved-renders` not below `max-concurrent-renders`.
- [x] 10.8 Render admission against a blocking WireMock upstream: a `STANDARD` render with every general
      slot held and the waiting room full is refused at once; one that waits past the bound is refused
      after it; a `FULFILMENT` render is admitted to the reserved slot; with the reserved slot also held,
      `renderForStamp` raises `StampRenderUnavailableException`; a render with a slot free proceeds as
      before.
- [x] 10.9 Security events (log capture): no agreement id and no UUID-shaped substring in any event,
      including a default-class lockout on an id-bearing path (route logged as `default`); a recovery
      per-reference lockout carries the resource only as a hashed digest; the source is redacted to its
      prefix; a lockout emits exactly one event however many requests it refuses; repeated verification
      failures from one source emit the first at once and then one per minute with a count; no webhook
      payload content appears.
- [x] 10.11 Cross-site guard (refused, exempt routes, same-origin and headerless pass); live-preview
      classification by `Accept`; a source refusal spends no resource budget while a resource refusal
      still counts against the source.
- [x] 10.10 Frontend: the preview debounce fires at the new interval; `apiFetch` throws
      `ServiceBusyError` with `Retry-After` for a `429` and for a `render-busy` `503`, defaults it when
      absent, returns a `stamp-render-unavailable` `503` unchanged, and does not call `reconcileHook`; a
      view renders the retry message, not a raw status; `usePolling` waits `Retry-After`;
      `AgreementStatus` surfaces the busy error in preference to another rejection.

## 11. Tests -- integration

Every test in this section that exercises the route-class limits runs in the limits-on context (D10);
11.2, 11.3 and 11.6 do not need it.

- [x] 11.1 Limits on, render limit lowered for the test: a flood on `POST /api/templates/document/preview`
      is refused `429` after the configured rate, and requests carrying a different forwarded client
      address still succeed. *(As built: at the true default of 60, using the HTML preview, which
      renders no PDF and is cheap enough to drive 61 times; so one limits-on context serves 11.9 too.)*
- [x] 11.2 Two recovery callers behind the trusted proxy (different `X-Forwarded-For`, loopback peer)
      occupy different buckets, and one's lockout does not refuse the other -- the defect this change
      fixes.
- [x] 11.3 Untrusted-peer context (`RANDOM_PORT`, `internal-proxies` a regex loopback cannot match): a
      request carrying `X-Forwarded-For` is keyed on the peer, not the header.
- [x] 11.4 Body guard: an over-ceiling `POST /api/agreements` (body between 1 MiB and 2 MiB) is refused
      `413` problem+json of the `payload-too-large` type with nothing persisted, both with a declared
      `Content-Length` and chunked; a draft upload above 1 MiB but within its own ceiling is accepted.
- [x] 11.5 Render saturation with a blocking WireMock upstream and small slot counts: a render route
      returns `503` with `Retry-After` (at once when the waiting room is full) while
      `GET /api/agreements/{id}` (DB-backed) keeps serving; the staff e-stamp attach still renders
      through the reserved slot.
- [x] 11.6 Signing permit: an anonymous `POST /api/signing/*/request` is refused `403`; a signed-in
      customer (non-STAFF) session is refused; a STAFF request with a valid CSRF token reaches the
      controller; the same STAFF request without one does not; the stamp-intake path still creates the
      signing request server-side with no anonymous call.
- [x] 11.7 CSRF before the limiter: tokenless unsafe requests to a limited route beyond its rate are all
      refused for CSRF, after which the same source still gets its full budget of valid requests.
- [x] 11.8 Exclusions: recovery requests beyond any route-class limit still answer recovery's uniform
      response, never `429`; correctly signed webhooks above the default rate are never refused; a webhook
      with an invalid signature is refused `401`, emits a verification-failure event, and logs no
      payload content.
- [x] 11.9 At the true defaults, the legitimate end-to-end flow (create, preview, finalise, pay), with four
      viewers polling the status page at the client's interval and the client's catalog and CSRF
      fetches, triggers no limit.
- [x] 11.11 Limits on: cross-site GETs to a capability route are refused `403` and spend none of the
      visitor's budget; 100 HTML live previews from one source in a minute are all served.
- [x] 11.10 `ModularityTests` stays green with the limiter, source resolver and body guard in the root
      package and the render priority on the documents API.

## 12. Close-out

- [x] 12.1 Run `./run-tests.sh` and report the wall-clock duration against the 3-minute `check` budget;
      say what the limits-on and untrusted-peer contexts added. *(2026-10-04: `check` 84 s warm, 1548
      tests, 0 skipped; the four new contexts -- limits-on, untrusted-peer, render-saturation, plus the
      body-guard class sharing the baseline context -- kept the run well inside the budget.)*
- [x] 12.2 Run the frontend gates (`npm run build`, `npm run lint`).
- [x] 12.3 Manual test: walk the full flow as a customer against the configured defaults and confirm
      nothing throttles; then exceed one limit deliberately and confirm the retry message reads sensibly. *(User confirmed and asked to archive, 2026-10-04.)*
- [x] 12.4 Verify in production, from outside: send a forged `X-Forwarded-For` and a forged `Forwarded`
      header naming an address in a **different `/24`** from the tester's, deliberately trip a per-source
      lockout on a cheap route, and confirm the event's redacted source is the tester's `/24` -- not the
      forged one, and not a Cloudflare or Docker gateway address. Log no agreement id doing so. *(Descoped 2026-10-04 to the `prod-readiness-preflight` register row in `docs/ROADMAP.md`: a dashboard/production gate, verification steps in `docs/DEPLOYMENT.md` §5.6.)*
- [x] 12.5 Confirm `docs/ROADMAP.md`'s follow-up register holds `render-outside-transaction`,
      `anonymous-upload-byte-budget`, `agreement-id-debug-logging`, `security-event-alerting` and
      `per-id-access-logging`; verify with
      `flow-journal.mjs followups --change anonymous-surface-abuse-controls`.
- [x] 12.6 Update the `signing-auth` register row: this change closes the signing permit, adds rate limits
      to create and the status-page reads, adds verify-failure logging and fixes the stale javadoc;
      webhook rate limiting is deliberately not done (HMAC is the control). State what, if anything,
      remains of that row, and close it if nothing does.
- [x] 12.7 `openspec validate --strict anonymous-surface-abuse-controls`, then archive with
      `openspec archive -y anonymous-surface-abuse-controls`.

## Coverage

Scenario → test contract (rebuilt in review round 2, 2026-10-04).

| # | Capability — Scenario | Disposition | Covered by |
|---|---|---|---|
| 1 | anonymous-abuse-controls — A forwarded address from the trusted proxy is used | COVERED | 11.2 |
| 2 | anonymous-abuse-controls — A forwarding header arriving directly is not trusted | COVERED | 11.3 |
| 3 | anonymous-abuse-controls — A client-seeded header does not survive the proxy | MANUAL | 12.4, descoped to the `prod-readiness-preflight` register row (Caddy behaviour in production; procedure in `docs/DEPLOYMENT.md` §5.6) |
| 4 | anonymous-abuse-controls — IPv6 addresses in one /64 share a bucket | COVERED | 10.3 |
| 5 | anonymous-abuse-controls — An unresolvable chain fails to a shared bucket, not to none | COVERED | 10.3 |
| 6 | anonymous-abuse-controls — A flood from one source is refused | COVERED | 10.1, 11.1 |
| 7 | anonymous-abuse-controls — A flood from many sources onto one resource is refused without locking it out | COVERED | 10.1 |
| 8 | anonymous-abuse-controls — Tripping one dimension does not refund the other | COVERED | 10.11 (`SlidingWindowRateLimiterTest.aResourceRefusalStillCountsAgainstTheSource`) |
| 52 | anonymous-abuse-controls — One holder of a link cannot starve the others | COVERED | 10.11 (`SlidingWindowRateLimiterTest.oneSourceAtItsOwnLimitCannotExhaustAnAgreement`), 10.7 |
| 49 | anonymous-abuse-controls — A refused caller does not starve other holders of the link | COVERED | 10.11 (`SlidingWindowRateLimiterTest.aRequestTheSourceRefusesDoesNotSpendTheResourcesBudget`) |
| 50 | anonymous-abuse-controls — A cross-site request is refused without spending budget | COVERED | 10.11, 11.11 (`AbuseLimitsIntegrationTest.crossSiteRequestsAreRefusedAndSpendNoBudget`) |
| 51 | anonymous-abuse-controls — A slow typist's live preview is not throttled | COVERED | 10.11 (`RouteRateLimitFilterTest.theLivePreviewRefusesWithinItsWindowButNeverLocksOut`), 11.11 (`AbuseLimitsIntegrationTest.theHtmlLivePreviewIsNotHeldToTheRenderLimitAndNeverLocksOut`) |
| 9 | anonymous-abuse-controls — A non-canonical agreement id does not mint a fresh resource bucket | COVERED | 10.5 |
| 10 | anonymous-abuse-controls — The legitimate client is not throttled | COVERED | 11.9 (+ 12.3 manual) |
| 11 | anonymous-abuse-controls — Several parties viewing one agreement are not throttled | COVERED | 11.9 |
| 12 | anonymous-abuse-controls — Catalog reads and the CSRF bootstrap are never refused at legitimate rates | COVERED | 10.4, 11.9 |
| 13 | anonymous-abuse-controls — A lockout in one class does not refuse another | COVERED | 10.1 |
| 14 | anonymous-abuse-controls — A request refused for CSRF consumes no budget | COVERED | 11.7 |
| 15 | anonymous-abuse-controls — An unclassified route falls into the default class | COVERED | 10.4 |
| 16 | anonymous-abuse-controls — Recovery is never answered by this limiter | COVERED | 11.8 |
| 17 | anonymous-abuse-controls — A verified webhook is never refused by the limiter | COVERED | 11.8 |
| 18 | anonymous-abuse-controls — A throttled caller is told when to retry | COVERED | 10.6 |
| 19 | anonymous-abuse-controls — A refusal is not an existence oracle | COVERED | 10.6 |
| 20 | anonymous-abuse-controls — The client shows a retry message rather than a raw status | COVERED | 10.10 |
| 21 | anonymous-abuse-controls — Stale entries are evicted | COVERED | 10.2 |
| 22 | anonymous-abuse-controls — Rotating sources do not grow memory without bound | COVERED | 10.2 |
| 23 | anonymous-abuse-controls — An oversized create body is rejected | COVERED | 11.4 |
| 24 | anonymous-abuse-controls — An oversized body without a declared length is rejected | COVERED | 11.4 |
| 25 | anonymous-abuse-controls — A document upload keeps its larger ceiling | COVERED | 11.4 |
| 26 | anonymous-abuse-controls — A render flood does not take down unrelated endpoints | COVERED | 11.5 |
| 27 | anonymous-abuse-controls — A render within capacity is unaffected | COVERED | 10.8 |
| 28 | anonymous-abuse-controls — The paid stamp render proceeds during an anonymous render flood | COVERED | 11.5, 10.8 (11.5 drives the `FULFILMENT` render through the renderer bean during an HTTP render flood; that `renderForStamp` passes `FULFILMENT` and wraps a refusal is pinned in `AgreementDocumentServiceTest`) |
| 29 | anonymous-abuse-controls — A verification failure is recorded | COVERED | 11.8, 10.9 |
| 30 | anonymous-abuse-controls — A lockout is recorded once | COVERED | 10.9 |
| 31 | anonymous-abuse-controls — No log line carries an agreement id | COVERED | 10.9 |
| 32 | anonymous-abuse-controls — A default-class lockout on an id-bearing path logs no id | COVERED | 10.9 |
| 33 | agreement-recovery — Repeated requests are throttled | GROUPED | existing `RecoveryIntegrationTest.throttlingIsShapeIdenticalToASuccessfulRequest` (kept green through 3.4) |
| 34 | agreement-recovery — One source's lockout does not refuse another customer | COVERED | 11.2 |
| 35 | agreement-recovery — Throttling is not an oracle | GROUPED | existing `RecoveryIntegrationTest.throttlingIsShapeIdenticalToASuccessfulRequest` |
| 36 | agreement-recovery — Recipients are redacted in audit output | GROUPED | existing `RecoveryIntegrationTest.theAuditNeverStoresAFullRecipientAddress` |
| 37 | backend-security-baseline — An anonymous signing request is rejected | COVERED | 11.6 |
| 38 | backend-security-baseline — Signing-request stub passes the filter | COVERED | 11.6 |
| 39 | backend-security-baseline — Other signing sub-paths are denied by default | GROUPED | existing `SecurityBaselineIntegrationTest` (`GET /api/signing/list` denied) |
| 40 | backend-security-baseline — Customer-driven signing is unaffected | COVERED | 11.6 |

| 41 | signing-request — Signing request is created for a valid agreement | GROUPED | existing `SigningRequestApiIntegrationTest` (now posting as STAFF via `support/SigningRequests`) |
| 42 | signing-request — Stamped PDF, not the raw draft, is submitted to the provider | GROUPED | existing `SigningRequestApiIntegrationTest` |
| 43 | signing-request — Agreement without an uploaded draft is rejected | GROUPED | existing `SigningRequestApiIntegrationTest.createWithoutAnUploadedDraftReturns409AndCallsNoProvider` |
| 44 | signing-request — Provider succeeds but persistence-update fails leaves a recoverable record | GROUPED | existing `SigningRequestApiIntegrationTest` |
| 45 | signing-request — Request thread does not block on signing | GROUPED | existing `SigningRequestApiIntegrationTest` |
| 46 | signing-request — Oversized request body is rejected | GROUPED | existing `SigningRequestApiIntegrationTest` (413 now from the root body guard, ahead of the chain) |
| 47 | signing-request — Unknown agreement id returns 404 | GROUPED | existing `SigningRequestApiIntegrationTest` |
| 48 | signing-request — Non-UUID agreement id returns 400 | GROUPED | existing `SigningRequestApiIntegrationTest.nonUuidAgreementIdReturns400` |

52 scenarios — 39 COVERED, 12 GROUPED, 1 MANUAL, 0 WAIVED, 0 UNMAPPED. Rows 49-52 were added at
apply from code review (2026-10-04). Rows 41-48 were added at
apply: the `signing-request` spec of record still described the endpoint as unauthenticated and
deferred to `signing-auth`, so a MODIFIED delta replaces that note; its scenarios are unchanged
behaviour, already covered.
