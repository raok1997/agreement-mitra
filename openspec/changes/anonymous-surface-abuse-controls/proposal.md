## Why

The product is deliberately no-login, so 25 of 43 API routes are anonymous. That
is a sound design -- reads are guarded by a 122-bit agreement id acting as a
bearer capability, and `docs/DEPLOYMENT.md` already states the intended posture:
*"Rate limiting is the compensating control, not authentication."*

**That compensating control does not exist.** The only rate limiter in the backend
is `RecoveryRateLimiter`, and three specific findings make the current state worse
than "not yet built":

1. **Two endpoints need no agreement id at all.**
   `POST /api/templates/document/preview` is anonymous, takes no id, and consumes
   one of only **four** Gotenberg render permits for up to **30 seconds**
   (`GOTENBERG_MAX_CONCURRENT_RENDERS:4`, `GOTENBERG_REQUEST_TIMEOUT:PT30S`). A
   handful of concurrent scripted calls stall live document preview -- the core
   product feature -- for every real customer, from anywhere, with no id and no
   account. `POST /api/agreements` is the same exposure for unbounded row
   creation.
2. **The one rate limiter we have is bucketing every customer together in
   production.** `RecoveryController` reads `request.getRemoteAddr()`, and
   `server.forward-headers-strategy` is not set, so behind Caddy the source is the
   proxy's address rather than the client's. `deploy/Caddyfile` already does its
   half correctly (`trusted_proxies` + `client_ip_headers CF-Connecting-IP`) and
   its comment anticipates exactly this; the application never reads the result.
   So today any 30 recovery attempts in 15 minutes lock out **every** customer for
   30 minutes, and the `agreement-recovery` requirement that the endpoint "SHALL
   limit the rate of recovery requests per source" is not actually met.
3. **The limiter is itself a memory-exhaustion vector.** `RecoveryRateLimiter`
   adds a map entry per source and per reference and **never removes one** -- the
   sliding window empties the deque but the key remains, and `clear()` is
   test-only. An attacker rotating source addresses grows both maps without bound
   on an 8 GB box.

The threat model here is **abuse, not breach**: denial of service against document
preview, junk row creation, and disk fill -- exactly what DEPLOYMENT.md predicted.
No new authentication is proposed, and the no-login product is unchanged.

## What Changes

**Correct the client-IP chain (the prerequisite for everything else).**

- Set `server.forward-headers-strategy: framework` so the application sees the
  client address Caddy already recovers. Without this every per-source limit in
  this change would be a single shared bucket, which is worse than no limit.

**Bound request bodies on the two uncapped routes.**

- `PayloadSizeLimitConfig` guards bodies at 1 MiB for the webhooks,
  `/api/signing/*` and recovery -- but **not** `POST /api/agreements` or the
  stateless preview, and `CreateAgreementRequest` has `@NotBlank` with no maximum
  length, so a multi-megabyte property address is accepted today. Add both paths.
- Add the same ceiling at the edge in `deploy/Caddyfile`, **per path**: the draft
  upload route must keep its 11 MB allowance
  (`spring.servlet.multipart.max-request-size`) or uploads break.

**Rate-limit the anonymous surface.**

- Generalize `RecoveryRateLimiter` into a reusable limiter with **TTL eviction**,
  keeping its two-dimensional design (per-source *and* per-resource, both
  recorded even when one has already failed) and its documented rationale that an
  IP is not a person -- carrier NAT puts many unrelated customers behind one
  address.
- Apply it to the id-free routes (stateless preview, agreement create), to the
  render routes (`POST /api/agreements/*/document`), to the mutating capability
  routes (draft upload, contacts, finalise, payment order) and to the polled
  reads the status page hits every 20 s.
- Refuse with `429 Too Many Requests` as RFC 9457 problem+json carrying
  `Retry-After`, in the shape `api-error-handling` already mandates.

**Stop a render flood from exhausting the servlet threads.**

- `GotenbergClient` currently parks a request thread waiting for a render permit.
  Under a flood the Tomcat pool is consumed, not merely the renderer, so
  unrelated endpoints fail too. Fail fast with `503` + `Retry-After` when no
  permit is available within a short bounded wait.

**Make abuse visible.**

- Redacted security-event logging for webhook signature-verification failures and
  for rate-limit lockouts: event, route, redacted source, count. **Never the
  agreement id** -- the id is the credential, and DEPLOYMENT.md already forbids
  logging it. Where a per-resource key must be logged, log a salted hash.

**Close one permit instead of guarding it.**

- `POST /api/signing/*/request` moves from `permitAll` to STAFF. It is a retry
  hatch, not a customer path: signing is already initiated server-side by
  `StampIntakeService` after the e-stamp is attached, and **no Vue view calls
  `requestSignature`** -- it is dead code in the SPA (`src/api/client.ts` and its
  test are the only references). Deleting the permit is strictly better than
  rate-limiting it.

**Raise the preview debounce.**

- `CaptureForm.vue` debounces the live preview at **250 ms**, so a customer
  filling a form legitimately generates tens of renders a minute. Raise it to
  ~600 ms so a sane limit does not throttle real use. Tuning the limit without
  this would either be ineffective or hit paying customers.

**Record the edge controls as gates, not prose.**

- Cloudflare Bot Fight Mode, a rate-limiting rule on `POST /api/*` excluding
  `/api/webhooks/*`, and the Caddy body ceiling are configuration outside the
  JVM. They are recorded in `docs/DEPLOYMENT.md` with a verification step, and
  the origin already refuses non-Cloudflare traffic (the `DOCKER-USER` chain
  restricted to Cloudflare ranges, failing closed), which is what makes an edge
  control real rather than advisory.

**Not in scope:** any authentication or login requirement; ownership authZ on the
capability routes (the id remains the credential -- that trade is
`claim-bound-to-initiator`'s and `signing-auth`'s to revisit); a shared/distributed
limiter store (single instance today, tracked by `shared-limiter-store`); and
Turnstile or any in-app CAPTCHA, since Cloudflare Bot Fight Mode covers the same
ground at the edge with no third-party script, no npm dependency and no DPDP
processor disclosure.

**No signing-status FSM change**, and no change to the async signing/webhook flow
beyond refusing verification-failure floods earlier and logging them. No sequence
diagram is owed.

## Capabilities

### New Capabilities
- `anonymous-abuse-controls`: rate limiting and admission control across the
  anonymous API surface -- source identification, the per-source/per-resource
  limits and their refusal shape, request-body ceilings, render admission
  control, and redacted security-event logging.

### Modified Capabilities
- `backend-security-baseline`: the "Signing stub endpoint permitted pending an
  auth mechanism" requirement -- the temporary permit it describes is removed and
  the route becomes STAFF-only, which is the tightening that requirement itself
  demands.
- `agreement-recovery`: the "recovery request endpoint is rate limited and
  audited" requirement -- the source a limit is keyed on SHALL be the client
  address, not the proxy's, so the requirement holds behind a reverse proxy.

## Impact

**Backend**: `application.yml` (`forward-headers-strategy`, limiter settings),
`PayloadSizeLimitConfig` (two paths), a reusable rate limiter plus a filter or
interceptor applying it, `RecoveryRateLimiter` (generalized, with eviction),
`RecoveryController` (source resolution), `GotenbergClient` (permit fast-fail),
`GlobalExceptionHandler` (a `429` problem type), `SecurityConfig` (the signing
permit removed), and the webhook controllers (verify-failure logging).

**Frontend**: `CaptureForm.vue` (debounce), a `429`/`503` handling path with a
retry message rather than a raw status, and removal of the dead `requestSignature`
client.

**Infrastructure**: `deploy/Caddyfile` (per-path body ceiling), Cloudflare
dashboard configuration, `docs/DEPLOYMENT.md`.

**Sequencing**: independent of `byo-document-upload`, but it **raises that
change's floor** -- BYO adds a LibreOffice conversion behind the same anonymous
upload endpoint, so landing these controls first is what keeps that acceptable.

### PII / security review

- **No new PII flow, and one reduced.** This change adds no new data collection.
  It removes exposure: bodies are capped, floods are refused before they reach the
  renderer, and one anonymous write endpoint is closed entirely.
- **No Aadhaar, OTP, VID or eKYC data is introduced, moved, or logged.**
- **The new logging is the one place to be careful.** Security events carry an
  event name, a route, a redacted source and a count. The **agreement id is never
  logged** -- it is a bearer credential (DEPLOYMENT.md, and
  `agreement-recovery`'s referrer requirement), so a per-resource key is recorded
  as a salted hash. No webhook payload is echoed, verbatim or otherwise.
- **Client-supplied headers stay untrusted.** The client address is taken from
  the proxy chain only because `deploy/Caddyfile` pins `trusted_proxies` to
  Cloudflare's ranges and the origin firewall refuses everything else. A
  client-supplied `X-Forwarded-For` MUST NOT be trusted directly: doing so would
  let an attacker mint unlimited buckets and bypass every limit here.
- **No new secret and no new outbound integration.** Sandbox and dummy data only
  is preserved; the limiter holds coarse counters, not identities.

> **Note (2026-10-04, from `cookie-session-auth`):** the per-instance limiter is now tracked by
> the `shared-limiter-store` register row; `session-store-not-durable` was deleted as stale.
> This change is next in line after `cookie-session-auth`, and it should be **re-reviewed once
> `cookie-session-auth` merges**: every unsafe request now needs the `X-XSRF-TOKEN` CSRF header,
> the session is the `__Host-am_session` cookie (not Bearer), and logout is `permitAll`.
> Its `backend-security-baseline` delta MODIFIES "Signing stub endpoint permitted pending an auth
> mechanism", which `cookie-session-auth` also modified (adding "Like every unsafe request, a call to
> the permitted path SHALL still carry a valid CSRF token" and a CSRF qualifier on its scenario). A
> MODIFIED block replaces the whole requirement, so rebase this delta on the archived text first, or
> that CSRF clause is silently dropped.
