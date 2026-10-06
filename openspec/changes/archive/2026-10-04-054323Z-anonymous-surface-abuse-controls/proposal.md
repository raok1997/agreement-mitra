## Why

The product is deliberately no-login, so most API routes are anonymous. That is a
sound design -- reads are guarded by a 122-bit agreement id acting as a bearer
capability, and `docs/DEPLOYMENT.md` already states the intended posture:
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
   proxy's address rather than the client's. `deploy/Caddyfile` recovers the
   client address for its own use (`trusted_proxies` + `client_ip_headers
   CF-Connecting-IP`), but that only sets Caddy's `{client_ip}` placeholder: the
   `X-Forwarded-For` it forwards is the client-influenced chain, passed through
   and appended to. So today any 30 recovery attempts in 15 minutes lock out
   **every** customer for 30 minutes, and the `agreement-recovery` requirement
   that the endpoint "SHALL limit the rate of recovery requests per source" is not
   actually met.
3. **The limiter is itself a memory-exhaustion vector.** `RecoveryRateLimiter`
   adds a map entry per source and per reference and **never removes one** -- the
   sliding window empties the deque but the key remains, and `clear()` is
   test-only. An attacker rotating source addresses grows both maps without bound
   on an 8 GB box.

The threat model here is **abuse, not breach**: denial of service against document
preview, junk row creation, and disk fill -- exactly what DEPLOYMENT.md predicted.
No new authentication is proposed, and the no-login product is unchanged.

This change builds on `cookie-session-auth` (archived 2026-10-04): the session is the
`__Host-am_session` cookie, every unsafe request carries a double-submit CSRF token,
and `GET /api/auth/csrf` and `POST /api/auth/logout` are anonymous. The limiter is
placed with that in mind (see "Rate-limit the anonymous surface").

## What Changes

**Correct the client-IP chain, on both sides of the proxy (the prerequisite for everything else).**

- **Caddy overwrites, rather than appends.** `deploy/Caddyfile`'s `/api/*` proxy
  sets `header_up X-Forwarded-For {client_ip}` -- the address Caddy already
  recovered from Cloudflare -- and strips `Forwarded`, `X-Forwarded-Host`,
  `X-Forwarded-Prefix` and `X-Forwarded-Port` (pinning `X-Forwarded-Proto` to `https`). Without the overwrite the leftmost forwarded value is
  whatever the client sent, and every per-source limit becomes a bucket the caller
  chooses.
- **The application trusts that header only from Caddy.** Set
  `server.forward-headers-strategy: native`, so Tomcat's `RemoteIpValve` resolves
  the client address and honours the forwarded header only when the immediate peer
  matches `server.tomcat.remoteip.internal-proxies`, pinned to Caddy's own static
  address on the compose network (the whole subnet would also trust Postgres,
  MinIO and Gotenberg).
  (`framework` -- Spring's `ForwardedHeaderFilter` -- has no trusted-peer list and
  takes the leftmost value from any caller, so it cannot meet this requirement.)
- The two halves **deploy together**: the application change alone would trust a
  header Caddy still lets the client write.
- IPv6 sources are keyed on their `/64`, since one host typically holds a whole
  `/64` and per-address keys would give it unlimited buckets.

**Bound request bodies on every anonymous write route.**

- Generalize `PayloadSizeLimitConfig` (today a `signing/api` filter covering only
  the webhooks, `/api/signing/*` and recovery) into a root-package body guard that
  applies a 1 MiB ceiling to **every** unsafe `/api` request except the multipart
  upload routes, which keep their own `spring.servlet.multipart` ceilings. It
  enforces the ceiling on the **stream**, not only on the declared
  `Content-Length`, so a chunked body cannot slip past it, and it refuses as
  problem+json.
- Add the same ceiling at the edge in `deploy/Caddyfile`, **per path**: the draft
  upload route keeps its 11 MB allowance (`spring.servlet.multipart.max-request-size`)
  or uploads break.

**Rate-limit the anonymous surface.**

- Generalize `RecoveryRateLimiter` into a reusable limiter keeping its
  two-dimensional design (per-source *and* per-resource; a resource refusal still
  counts against the source, a source refusal never spends the resource's budget)
  and its documented rationale that an IP is not a person.
  Back it with a **size-capped** cache (Caffeine `maximumSize` + expiry), so the
  number of retained entries has a hard ceiling rather than one that grows with an
  attacker's request rate.
- Refuse API requests the browser marks **cross-site** (except the sign-in callback
  and the webhooks) before counting them, so a hostile page cannot spend a visitor's
  budget with embedded GETs.
- Apply it as a filter **inside the security chain, immediately after
  `CsrfFilter`**: a request refused for CSRF costs the caller nothing (so a
  cross-site page cannot burn a victim's -- and their whole carrier NAT's --
  budget), and a session-cookie flood is bounded before the session lookup reaches
  Postgres.
- Classify **every** API route into exactly one class -- render, anonymous write,
  auth, capability write, capability read, bootstrap, or excluded -- with a
  generous **default class** for any route not listed, so a future `permitAll` is
  never born unlimited. The auth routes `cookie-session-auth` left anonymous are in
  the inventory. The catalog reads and `GET /api/auth/csrf` (through which the SPA
  bootstraps every write) get a ceiling far above legitimate use and no lockout,
  rather than no limit, so they cannot become an unbounded path to the session
  lookup in Postgres.
- **Three routes are excluded, each because it has a better control:** recovery keeps
  its own limiter and its always-`202` answer (a `429` there would be an existence
  oracle), and the vendor webhooks are not rate limited at all -- their HMAC is the
  control, and a per-source limit would key on the vendor's handful of egress
  addresses and refuse genuine signed callbacks.
- **Lockout applies per source only.** The per-resource dimension refuses within
  its window but never locks an agreement out, so a leaked link or a counter-party
  cannot deny the agreement's holder their status page or checkout for minutes.
- Refuse with `429 Too Many Requests` as RFC 9457 problem+json carrying
  `Retry-After`, written by the filter itself (a servlet filter is outside the
  reach of `GlobalExceptionHandler`) in the shape `api-error-handling` mandates.

**Stop a render flood from exhausting the servlet threads -- and protect the paid render.**

- `GotenbergClient` currently parks a request thread waiting for a render permit.
  Under a flood the Tomcat pool -- and, because the render paths run inside a
  read-only transaction, the Hikari pool -- is consumed, not merely the renderer.
  Fail fast with `503` + `Retry-After` when no permit is available within a short
  bounded wait.
- **Reserve one render slot for the paid e-stamp render**, so an anonymous preview
  flood cannot fast-fail paid fulfilment.

**Make abuse visible.**

- Redacted security-event logging for webhook signature-verification failures and
  for rate-limit lockouts: event, matched route **pattern**, redacted source,
  count. **Never the agreement id** -- the id is the credential, and DEPLOYMENT.md
  already forbids logging it. Where a per-resource key must appear, log a keyed
  hash whose key is generated per process (no new secret).

**Close one permit instead of guarding it.**

- `POST /api/signing/*/request` moves from `permitAll` to STAFF (still CSRF-checked,
  like every unsafe request). It is a retry hatch, not a customer path: signing is
  already initiated server-side by `StampIntakeService` after the e-stamp is
  attached, and **no Vue view calls `requestSignature`** -- it is dead code in the
  SPA (`src/api/client.ts` and its test are the only references). Deleting the
  permit is strictly better than rate-limiting it.

**Raise the preview debounce, and let the SPA honour a refusal.**

- `CaptureForm.vue` debounces the live preview at **250 ms**, so a customer
  filling a form legitimately generates tens of renders a minute. Raise it to
  ~600 ms so a sane limit does not throttle real use.
- `apiFetch` turns a `429`/`503` into one typed error carrying the server's
  `Retry-After`, which views show as a retry message and status-page polling
  honours instead of its own backoff. It never treats a refusal as a session
  problem.

**Record the edge controls as gates, not prose.**

- Cloudflare Bot Fight Mode, a rate-limiting rule on `POST /api/*` excluding
  `/api/webhooks/*`, and the Caddy header and body-ceiling configuration are
  outside the JVM. They are recorded in `docs/DEPLOYMENT.md` with a verification
  step -- including an outside-in check that a forged `X-Forwarded-For` or
  `Forwarded` header does not become the resolved source -- and the origin already
  refuses non-Cloudflare traffic (the `DOCKER-USER` chain restricted to Cloudflare
  ranges, failing closed), which is what makes an edge control real rather than
  advisory.

**Not in scope:** any authentication or login requirement; ownership authZ on the
capability routes (the id remains the credential -- that trade is
`claim-bound-to-initiator`'s and `signing-auth`'s to revisit); a shared/distributed
limiter store (single instance today, tracked by `shared-limiter-store`);
Turnstile or any in-app CAPTCHA, since Cloudflare Bot Fight Mode covers the same
ground at the edge with no third-party script, no npm dependency and no DPDP
processor disclosure; request-rate limiting of the vendor webhooks (HMAC is the
control); and three items recorded in the follow-up register instead of folded in:
moving renders outside their database transaction (`render-outside-transaction`),
a global byte budget for anonymous uploads (`anonymous-upload-byte-budget`), and
existing DEBUG log lines that print raw agreement ids while production runs at
DEBUG (`agreement-id-debug-logging`).

**No signing-status FSM change**, and no change to the async signing/webhook flow
beyond logging verification failures. No sequence diagram is owed.

## Capabilities

### New Capabilities
- `anonymous-abuse-controls`: rate limiting and admission control across the
  anonymous API surface -- source identification, the route classes and their
  per-source/per-resource limits and refusal shape, request-body ceilings, render
  admission control, and redacted security-event logging.

### Modified Capabilities
- `backend-security-baseline`: the "Signing stub endpoint permitted pending an
  auth mechanism" requirement -- the temporary permit it describes is removed and
  the route becomes STAFF-only, which is the tightening that requirement itself
  demands. The CSRF obligation `cookie-session-auth` added to it is kept, and the
  requirement is renamed "Signing-request endpoint restricted to staff" so its
  title no longer contradicts its body.
- `signing-request`: the "Create an eSign request for an agreement" requirement --
  its note that the endpoint is unauthenticated, with authorization deferred to
  `signing-auth`, is replaced by the STAFF restriction this change makes.
- `agreement-recovery`: the "recovery request endpoint is rate limited and
  audited" requirement -- the source a limit is keyed on SHALL be the client
  address, not the proxy's, so the requirement holds behind a reverse proxy.

## Impact

**Backend**: `application.yml` (`forward-headers-strategy: native`,
`server.tomcat.remoteip.*`, limiter and admission settings), a root-package body
guard replacing `PayloadSizeLimitConfig`, a root-package rate limiter and the
filter applying it (registered in `SecurityConfig` after `CsrfFilter`),
`RecoveryRateLimiter` (replaced by the shared limiter), `GotenbergClient` and
`GotenbergProperties` (bounded admission + a reserved slot), the documents module's
public projection API (a render priority the stamp path passes),
`GlobalExceptionHandler` (a `503` render-capacity mapping), `SecurityConfig` (the
signing permit removed, stale comments fixed), the two webhook controllers
(verify-failure events), `build.gradle.kts` + `gradle.lockfile` (Caffeine), and the
roughly twenty existing integration-test call sites that post anonymously to
`/api/signing/*/request`.

**Frontend**: `CaptureForm.vue` (debounce), `src/api/http.ts` (`apiFetch` refusal
handling), `usePolling.ts` (`Retry-After`), the views that surface the retry
message, and removal of the dead `requestSignature` client.

**Infrastructure**: `deploy/Caddyfile` (forwarded-header overwrite and strip,
per-path body ceiling), `deploy/docker-compose.prod.yml` (a static address for Caddy
for `internal-proxies`), Cloudflare dashboard configuration, `docs/DEPLOYMENT.md`.

**Sequencing**: independent of `byo-document-upload`, but it **raises that
change's floor** -- BYO adds a LibreOffice conversion behind the same anonymous
upload endpoint, so landing these controls first is what keeps that acceptable.

### PII / security review

- **No new PII flow, and one reduced.** This change adds no new data collection.
  It removes exposure: bodies are capped, floods are refused before they reach the
  renderer, and one anonymous write endpoint is closed entirely.
- **No Aadhaar, OTP, VID or eKYC data is introduced, moved, or logged.**
- **The new logging is the one place to be careful.** Security events carry an
  event name, the matched route pattern (never the request URI, which contains the
  agreement id), a redacted source (IPv4 `/24`, IPv6 `/48`) and a count. The
  **agreement id is never logged** -- it is a bearer credential (DEPLOYMENT.md, and
  `agreement-recovery`'s referrer requirement), so a per-resource key is recorded
  as an HMAC under a key generated per process. No webhook payload is echoed,
  verbatim or otherwise.
- **Client-supplied headers stay untrusted.** The client address is taken from one
  header that Caddy overwrites, and only when the request's peer is Caddy. A
  client-supplied `X-Forwarded-For` or `Forwarded` header MUST NOT become the
  source: that would let an attacker mint unlimited buckets, or impersonate another
  customer's address to lock them out.
- **No new secret and no new outbound integration.** The hashing key is generated
  in memory at startup and never persisted. Sandbox and dummy data only is
  preserved; the limiter holds coarse counters, not identities.
