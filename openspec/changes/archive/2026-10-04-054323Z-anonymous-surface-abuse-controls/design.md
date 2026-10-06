## Context

See `proposal.md` for the three findings that motivate this. The requirements are
in this change's `specs/`. This design was re-grounded on 2026-10-04 against the
code after `cookie-session-auth` archived (cookie session, double-submit CSRF on
every unsafe request, anonymous `GET /api/auth/csrf` and `POST /api/auth/logout`).

The state of the code and the deployment, since both halves matter here:

- **The edge recovers the client address, but does not forward it.**
  `deploy/Caddyfile` pins `trusted_proxies` to Cloudflare's published ranges and
  sets `client_ip_headers CF-Connecting-IP` (:34-58). That fills Caddy's own
  `{client_ip}` placeholder only. The `/api/*` `reverse_proxy` (:97-107) has no
  `header_up`, so Caddy forwards the incoming `X-Forwarded-For` -- which the client
  can seed, and which Cloudflare appends to rather than replaces -- with the peer
  appended. The leftmost value is client-controlled.
- **The application reads none of it.** `server.forward-headers-strategy` is unset,
  so `getRemoteAddr()` returns Caddy's address. `RecoveryController.java:52` uses
  exactly that.
- **The origin cannot be reached directly.** Provisioning restricts Docker-published
  80/443 to Cloudflare's ranges through the `DOCKER-USER` chain, fetched live and
  failing closed, and only Caddy publishes ports. So an edge control is a real
  control, not advice. (The allowlist admits any Cloudflare tenant, whose traffic
  our zone's rules do not see; the source key stays honest because Cloudflare
  always sets `CF-Connecting-IP`, and the application layer is designed to stand
  on its own. Authenticated Origin Pulls would close that gap and is noted in
  DEPLOYMENT.md rather than done here.)
- `RecoveryRateLimiter` (`signing/recovery`, package-private) is a sliding-window
  limiter with a lockout, two dimensions, hard-coded limits, and a documented
  rationale worth preserving (an IP is not a person; both dimensions are recorded
  even when one fails). Its `hits` and `lockedUntil` maps are never evicted; its
  test-only `clear()` is how the recovery integration tests stop one test's
  loopback traffic from throttling the next.
- `PayloadSizeLimitConfig` (`signing/api`) applies a 1 MiB guard to the webhooks,
  `/api/signing/*` and recovery only, checks only the declared `Content-Length`,
  answers a bare `sendError(413)`, and is registered with no order (so it runs
  after the security chain).
- `GotenbergClient` (`documents`) bounds concurrency with a `Semaphore` of
  `maxConcurrentRenders` (default 4), takes it with a blocking `acquire()`, and has
  a 30 s per-render timeout. The render paths in `AgreementDocumentService` run
  inside `@Transactional(readOnly = true)`, so a parked render also holds a Hikari
  connection (default pool 10).
- `SecurityConfig` (root package) runs `CsrfFilter`, then `SessionAuthenticationFilter`
  (a Postgres lookup for any request carrying a session cookie), then authorization.
  A CSRF refusal is a `403` problem+json written by `CsrfAwareAccessDeniedHandler`.
  The SPA's `apiFetch` (`frontend/src/api/http.ts`) bootstraps the token through
  `GET /api/auth/csrf` and retries once on a CSRF `403`.

## Goals / Non-Goals

**Goals:**

- Make the compensating control DEPLOYMENT.md already claims actually exist.
- Keep every control keyed on something that identifies a caller, not a proxy, and
  never on something the caller chooses.
- Keep legitimate anonymous use untouched -- no login, no CAPTCHA in the app, no
  new friction on the free drafting path, and no way for one party to lock another
  out of an agreement.
- Prefer deleting an anonymous route over guarding it, where the route has no
  customer.

**Non-Goals:**

- Authentication, ownership authZ, or any change to the capability-link model.
- A shared/distributed limiter store (single instance today; `shared-limiter-store`).
- A CSP, or the broader header posture (`spa-content-security-policy`).
- Turnstile or any in-app bot check.
- Request-rate limiting of the vendor webhooks (D4).
- Moving renders out of their transaction (`render-outside-transaction`), a global
  upload byte budget (`anonymous-upload-byte-budget`), and the existing raw-id DEBUG
  logging (`agreement-id-debug-logging`) -- all follow-up register rows.

## Decisions

### D1 -- Two layers, edge first, and the edge is not optional

| Layer | Controls | Why there |
|---|---|---|
| Cloudflare | Bot Fight Mode; a rate-limiting rule on `POST /api/*` excluding `/api/webhooks/*` | Absorbs a flood before it reaches a single box that also runs Postgres, MinIO and Chromium. Sees the true client address natively. |
| Caddy | Forwarded-header overwrite + strip; per-path request-body ceiling | Owns the one header the application trusts; rejects an oversized body before the JVM allocates for it. |
| Application | Body guard, per-route-class limits, render admission control, security events | Works in local and test, survives an edge misconfiguration, and is the only layer that can key on a resource. |

The edge layer is listed first because it is the only one that protects the
machine's CPU and memory rather than just the application's threads. The
application layer is not redundant with it: it is what makes the behaviour
testable and what keeps the control present in every environment.

*Alternative rejected: a Caddy rate-limit module.* Rate limiting is not in
Caddy's standard build; it needs a third-party module compiled with `xcaddy`,
which means a custom image rebuilt on every Caddy update. Cloudflare covers the
same ground with no build. (Header manipulation and the body ceiling *are* built
in, so those stay in Caddy.)

*Alternative rejected: in-app CAPTCHA (Turnstile/reCAPTCHA).* A challenge proves
a human was present once; it does not bound what the session then does, and it
cannot apply to the 20 s status polling or to vendor webhooks. Cloudflare Bot
Fight Mode gives the same signal at the edge with no third-party script in the
page, no npm dependency in the OSV-scanned graph, and no processor to disclose
under the DPDP Act -- which matters for identity/legal infrastructure. Revisit
only if edge bot scoring proves insufficient.

### D2 -- Fix the client-IP chain first, on both sides, because every limit depends on it

**Caddy** (`/api/*` `reverse_proxy`):

```
header_up X-Forwarded-For {client_ip}
header_up -Forwarded
header_up -X-Forwarded-Host
header_up -X-Forwarded-Prefix
header_up -X-Forwarded-Port
header_up X-Forwarded-Proto https
```

`header_up` runs after Caddy's own `X-Forwarded-*` handling, so it replaces the
chain rather than appending to it. `{client_ip}` is the address Caddy already
recovered from `CF-Connecting-IP`, trusted only because the peer is in
Cloudflare's pinned ranges (Cloudflare overwrites that header, so a client cannot
seed it). The application then receives exactly one address and nothing the client
wrote -- including the port and scheme, which `native` would otherwise also honour.

**Application**: `server.forward-headers-strategy: native`, with
`server.tomcat.remoteip.remote-ip-header: x-forwarded-for` and
`server.tomcat.remoteip.internal-proxies` pinned to **Caddy's own address**, which
`deploy/docker-compose.prod.yml` fixes with a static `ipv4_address` on a declared
subnet. Pinning the whole subnet would also trust Postgres, MinIO, Gotenberg and
the bridge gateway. Tomcat's `RemoteIpValve` then sets `getRemoteAddr()` from the
header **only** when the immediate peer is Caddy; from any other peer the header is
ignored and the peer is the source. It does not honour RFC 7239 `Forwarded` or
`X-Forwarded-Prefix`, so neither can rewrite the source or the path the limiter
classifies. Other client-sent headers (`X-Real-IP`, `True-Client-IP`) reach the
application but nothing reads them, and the source resolver reads only
`getRemoteAddr()`.

Cloudflare's "Pseudo IPv4" must stay Off (or "Add header"), never "Overwrite
headers", or IPv6 clients arrive as synthetic IPv4 addresses and the `/64`
aggregation below is silently defeated. DEPLOYMENT.md records this.

*Alternative rejected: `framework`.* Spring's `ForwardedHeaderFilter` has no
trusted-peer list -- it honours `Forwarded` and `X-Forwarded-*` from any caller and
takes the leftmost `X-Forwarded-For` value -- and it rewrites the request path from
`X-Forwarded-Prefix`. Its container independence is not worth a trust bypass.

**The two halves deploy together, Caddy first or simultaneously.** The application
change alone would start trusting a header Caddy still lets the client seed --
converting today's shared-bucket bug into a bypass, which is worse.

**IPv6 aggregation.** A source that is an IPv6 address is keyed on its `/64`
prefix (configurable). One host typically controls a `/64`; per-address keys
would give it 2^64 buckets.

**Fallback.** If no address can be resolved, the key is one shared bucket
(`unresolved`), never "no key" -- a misconfiguration degrades to a shared bucket, not
to an unlimited one. Behind the trusted proxy the valve has already replaced the peer
with the forwarded value, so the original peer is not recoverable; an unparseable
value can only come from the proxy itself, making the shared bucket the peer's in
effect.
The resolver parses only IP literals and never calls a resolver that could trigger
a DNS lookup.

This single change is also a production bug fix: the recovery limiter is keyed on
Caddy today.

### D3 -- Generalize the existing limiter, backed by a size-capped cache

Extract `RecoveryRateLimiter`'s algorithm into a reusable root-package component:
a sliding window per key, two dimensions, and an optional lockout. A resource
refusal still counts against the source (no refund for a sweep); a source refusal is
not counted against the resource (changed at apply, from code review: a locked-out
caller hammering an agreement would otherwise keep its bucket full and starve the
other link holders). Recovery becomes one configured caller of it, with its
current values (and its lockout) unchanged.

Reasons to extract rather than adopt Bucket4j or Resilience4j: the behaviour we
want already exists, is documented with its rationale, and is tested; a dependency
adds OSV-scanned surface for no behaviour we lack. Reasons to extract rather than
copy: a second copy would drift, and the eviction fix has to land in one place.

**Storage is Caffeine** with `expireAfterAccess(window + lockout)` and a
configured `maximumSize`. The original preference for a scheduled sweep assumed
TTL eviction was enough; it is not. Entries within one window grow with request
rate times distinct keys, so "bounded by recent traffic" is not a bound under a
flood. `maximumSize` gives the hard ceiling the spec now requires, and Caffeine is
version-managed by the Spring Boot BOM (it still enters the locked, OSV-scanned
graph). When the cap is reached Caffeine evicts by its own policy; with sources
aggregated and resolved from Caddy, forcing an eviction costs real addresses, and
the edge rule bounds the rate at which an attacker can bring them.

Per-key updates go through the cache's atomic `compute`, so an eviction racing an
update cannot lose a hit or resurrect an entry.

**Keys are scoped per class.** A key is (class, dimension, value), and a lockout is
held per class: a source locked out of the render class can still poll its status
page and return from checkout. One NAT neighbour tripping one limit must not lock
everyone behind that address out of everything.

**One cache per limiter configuration.** Recovery (15 min window, 30 min lockout)
and the route classes (1 min window, 5 min lockout) have different expiries, so
each gets its own cache instance with its own `maximumSize`.

**Time.** The limiter takes a `Clock` through its constructor, defaulting to
`Clock.systemUTC()` -- **not** through a new `Clock` bean: one already exists
(`TemplateResolutionConfig.documentClock()`) and `StampQuoting` resolves `Clock`
with `getIfAvailable`, which fails on a second bean. The cache's `Ticker` is
bridged from the same clock, so a test clock drives expiry too, and tests build the
cache with a same-thread executor (or call `cleanUp()`) so eviction is deterministic.

The component is the first stateful bean in the root package, and the `signing`
module's recovery depends on it. That follows the existing direction (signing
already imports root-package types such as `StampRenderUnavailableException`); the
component's public surface is the limiter API only. `ModularityTests` confirms it.
The test seam that replaces `RecoveryRateLimiter.clear()` is a public `reset()`.

### D4 -- Applied in the security chain after CSRF, keyed by route class

The limiter is a filter registered in `SecurityConfig` after `CsrfFilter` -- behind the
`CrossSiteRequestGuard` added at apply (see the notes below), so the chain runs CSRF,
then the cross-site refusal, then the limiter -- and **not** also registered as a
servlet filter (its `FilterRegistrationBean` is disabled, as `AuthWebConfig` does
for `SessionAuthenticationFilter`). Position matters, and each neighbour is
deliberate:

- **After `CsrfFilter`.** An unsafe request without a valid token is refused `403`
  before it reaches the limiter and consumes no budget. Otherwise any page a victim
  visits could fire no-preflight cross-site POSTs that burn the victim's -- and
  everyone behind their carrier NAT's -- source budget. CSRF is not itself an abuse
  control (the token is freely obtainable), so nothing is lost by counting only
  requests that pass it.
- **Before `SessionAuthenticationFilter`.** On every route the limiter covers, a
  flood of requests carrying junk session cookies is bounded before the session
  lookup reaches Postgres. (That is why the catalog and CSRF routes get a high
  ceiling rather than none -- see the bootstrap class below.)
- **Inside the chain**, so the path is the firewall-normalized one and the refusal
  carries the chain's security headers.
- Only `DispatcherType.REQUEST` is counted, so the `/error` re-dispatch does not
  double-count.

So for a request that is both CSRF-invalid and over its limit, the answer is the
CSRF `403`; for a request that is both oversized and over its limit, the body
guard (a servlet filter ahead of the chain) answers `413` first.

**The classifier is a table of (method, `PathPattern`) entries written with
`{id}`** -- e.g. `GET /api/agreements/{id}/payment`. A matching entry supplies the
class, the resource key (`matchAndExtract` yields `{id}`, wherever it sits in the
path -- `/api/signing/{id}/progress` and `/api/agreements/{id}/…` alike) and the
`route` label for security events (D8). MVC's best-matching pattern does not exist
yet at this point in the chain, so the classifier's own pattern is the only label
available, and a request in the default class is labelled `default`, never with
its URI.

**Keys.** The source is D2's resolved address. The resource is the `{id}` value
**parsed as a UUID and canonicalized**; a value that does not parse gets no
per-resource bucket (source only), so case, encoding, path parameters or a trailing
slash cannot mint fresh resource buckets.

**Route classes.** Every API route is in exactly one class:

| Class | Routes | Dimensions |
|---|---|---|
| Render | `POST /api/templates/document/preview` (PDF), `GET /api/agreements/{id}/preview`, `POST /api/agreements/{id}/document` | source |
| Live preview | `POST /api/templates/document/preview` with `Accept: text/html` (the capture form's pane; no Gotenberg) | source, high ceiling, no lockout |
| Anonymous write | `POST /api/agreements` | source |
| Auth | `GET /api/auth/google/start`, `GET /api/auth/google/callback`, `POST /api/auth/session/exchange` | source |
| Capability write | `PATCH /api/agreements/{id}/contacts`, `POST /api/agreements/{id}/finalise`, `POST /api/agreements/{id}/payment/order`, `POST /api/agreements/{id}/payment/callback`, `POST /api/agreements/{id}/draft` | source + resource |
| Capability read | `GET /api/agreements/{id}`, `GET /api/agreements/{id}/payment`, `GET /api/agreements/{id}/stamp-quote`, `GET /api/agreements/{id}/signed-document`, `GET /api/signing/{id}/progress` | source + resource |
| Bootstrap | `GET /api/templates`, `GET /api/templates/{id}`, `GET /api/templates/form`, `GET /api/jurisdictions`, `GET /api/auth/csrf`, `POST /api/auth/logout` | source, high ceiling, no lockout |
| Not limited | `/actuator/health`, `/error` (Caddy proxies only `/api/*`, so neither is reachable from outside) | none |
| Excluded -- own control | `POST /api/agreements/recovery` (own limiter, always `202`); `POST /api/webhooks/esign`, `POST /api/webhooks/razorpay` (HMAC) | none here |
| Default | any other route, including the authenticated and staff routes | source |

The render routes that name an agreement are deliberately source-only: their cost
is the render, which D6 bounds, not anything specific to the agreement.

Notes on the less obvious placements:

- **Cross-site requests are refused before the limiter** (added at apply, from code
  review). Placing the limiter after CSRF protects only unsafe methods: a hostile
  page can still embed GETs (`<img src=…>`) that the visitor's browser sends, and
  every GET class that locks out keys on the visitor's address. A
  `CrossSiteRequestGuard` between `CsrfFilter` and the limiter refuses any `/api`
  request carrying `Sec-Fetch-Site: cross-site` with `403
  urn:agreementmitra:problem:cross-site`, uncounted -- except the Google sign-in
  callback and the two webhooks. A script can set the header too, but setting it
  only gets that script refused; a request without it is limited normally. It is
  always on, independent of `abuse.limits.enabled`.
- **The live preview is its own class** (added at apply, from code review). The
  capture form's HTML preview compiles HTML and never calls Gotenberg, and the
  ~600 ms debounce still fires per keystroke for a slow typist, so sharing the
  render class's 60/min and 5-minute lockout could lock a NAT out of real renders
  through normal typing. The classifier tells the variants apart by the same
  `Accept: text/html` test the controller uses; a client that claims HTML gets HTML,
  which costs no render.

- **The bootstrap class has a ceiling, not none.** `apiFetch` bootstraps every
  write through `GET /api/auth/csrf`, so a tight limit there would make the next
  write go out tokenless, fail CSRF and re-bootstrap into another `429` -- breaking
  every write for everyone behind that NAT. But no limit at all would leave an
  unbounded path to Postgres: `SessionAuthenticationFilter` looks up (and, for a
  valid session, writes `last_seen_at` on) every request carrying a session cookie,
  and the edge rule covers only `POST`. The ceiling (600/min per source, refusing
  within the window, never locking out) is far above anything the client generates
  -- writes themselves cap at 30/min -- so the bootstrap loop cannot trigger, while
  a scripted flood is still bounded.
- **Logout is in the bootstrap class, not auth** (moved at apply, from code review): a
  lockout must never refuse it, because a refused logout leaves the HttpOnly session
  cookie live on a shared machine. It keeps a ceiling because it is still a session
  lookup and delete.
- **`GET /api/auth/google/start` persists an `oauth_login_state` row per call.**
  The auth class bounds that row creation per source; purging expired rows is
  already the `auth-expired-row-purge` register row.
- **`payment/callback` is a capability write**, not a webhook: it is the browser's
  return from checkout, may trigger an authoritative order read from Razorpay, and
  is CSRF-protected.
- **Recovery is excluded** because its requirement is a shape-identical `202` for a
  throttled request; a generic `429` there would be the existence oracle the
  recovery spec forbids. It keeps its own limiter, now on the shared component.
- **Webhooks are excluded.** Razorpay, Leegality and Zoop call from a handful of
  egress addresses, so a per-source limit keys on the vendor, not on an attacker,
  and a lockout would refuse genuine signed completions -- delaying payment
  settlement and signing until a reconciliation job catches up. HMAC verification
  is the control (and is cheap; the 1 MiB body guard still applies); verification
  failures are logged (D8). The Cloudflare rule already excludes webhooks for the
  same reason.
- **The default class** means a future `permitAll` route is never born unlimited.
  It is deliberately generous because it also covers authenticated staff work.

The table and the routes cannot silently drift: a test enumerates every `/api`
endpoint registered in `RequestMappingHandlerMapping` and requires each to be either
in the class table or on an explicit list of routes that consciously take the
default class, so a new endpoint fails the build until someone classifies it. The
same test asserts no two entries for one method overlap (e.g. `GET
/api/templates/{id}` and `GET /api/templates/form` resolve deterministically).

A filter rather than per-controller annotations: the classification is a property
of the route, the list is the thing to review in one place, and it keeps the
module boundaries untouched -- the limiter is cross-cutting and belongs beside
`SecurityConfig` in the root package, not inside `signing`.

**The refusal is written by the filter.** A filter runs outside
`DispatcherServlet`, so `GlobalExceptionHandler` never sees it. The filter writes
the `429` problem+json itself -- as `CsrfAwareAccessDeniedHandler` does -- with
type `urn:agreementmitra:problem:rate-limited`, `instance` pinned to the type URN
(the `GlobalExceptionHandler.problem()` convention, so the path is never echoed),
and `Retry-After` in seconds. The body is identical whether or not the named
resource exists.

### D5 -- Starting limits, lockout per source only, and why the frontend changes first

| Route class | Per source (per min) | Per resource (per min) |
|---|---|---|
| Render | 60 | -- |
| Live preview | 300, no lockout | -- |
| Anonymous write | 10 | -- |
| Auth | 30 | -- |
| Capability write | 30 | 60 |
| Capability read | 120 | 300, shared across the class's routes |
| Bootstrap | 600, no lockout | -- |
| Default | 120 | -- |

**Lockout: 5 minutes, per-source dimension only, held per class** (D3), and none
at all for the bootstrap class. The per-resource dimension
refuses requests beyond its window but never locks the resource out. A lockout on
an agreement id would let anyone holding the link -- a leaked URL, or the
counter-party -- deny the agreement's holder their status page, finalise or
checkout for minutes, which is a worse outcome than the abuse it bounds.
Recovery keeps its existing lockout on both dimensions: its answer is a uniform
fire-and-forget `202`, so a locked reference harms no one.

**The capability-read figure accounts for multiple viewers.** The status page polls
three endpoints every 20 s -- 9 requests a minute per open tab. Landlord, tenant,
staff and a spare tab together reach ~36, far inside both figures.

**Per-resource is set above per-source** (changed at apply, from code review; it was
90/10 under 120/30). With the per-resource figure at or below the per-source one, a
single link holder polling just under their own limit -- never refused, never locked
out -- keeps the agreement's bucket full and the other parties get `429` on every
poll. Above it, one source hits its own limit first, and a source refusal is not
counted against the resource (D3), so only several sources converging on one link
can exhaust it, which is what the dimension is for. Shared addresses are protected by
the per-source figure itself being generous.

All values configurable, none compiled in. A binding-time check rejects a
configuration in which a class's per-resource limit is not higher than its
per-source limit.

The render figure is deliberately high because of a real constraint:
`CaptureForm.vue` debounces the live preview at **250 ms**, so a customer typing
through a section can legitimately generate tens of renders a minute. **Raising
that debounce to ~600 ms is part of this change, not a follow-up** -- tune the
limit without it and the choice is between a limit that does nothing and one that
throttles paying customers. The debounce change also cuts render load directly,
which is the cheapest mitigation in this whole change.

**The SPA honours a refusal in one place.** `apiFetch` keeps returning the
`Response` for every other status, but **throws** a single `ServiceBusyError`
(carrying `Retry-After` in seconds, defaulting to 5 when the header is absent) for
a `429`, and for a `503` whose problem type is `render-busy`. Every other `503` --
notably `stamp-render-unavailable`, which the staff console reads, and bodyless
edge pages -- passes through unchanged. It does not retry the refusal and does not
run the session re-check (`reconcileHook`) for it. Because it throws, the views
that can meet it handle it explicitly: capture-form preview, create, finalise,
payment, the status page and the staff console. `usePolling` receives the error
(its catch currently swallows it) and waits `Retry-After` instead of doubling its
own interval; the status page, which polls three endpoints with
`Promise.allSettled`, surfaces the busy error in preference to any other.

### D6 -- Render admission control: refuse, do not queue -- and reserve the paid slot

`GotenbergClient` waits on its semaphore, so under a flood request threads fill
with requests parked for up to 30 s. Worse, the render paths hold a read-only
transaction, so each parked request also holds a Hikari connection: with the
default pool of 10, four admitted renders plus six waiters stall **every**
DB-backed endpoint before Tomcat's threads run out.

Replace the unbounded wait with a bounded one (`gotenberg.admission-wait`, default
2 s) **and a bounded waiting room** (`gotenberg.max-waiters`, default 2): a render
that finds every slot busy and the waiting room full is refused immediately, and
one that waits past the bound is refused when it elapses -- both with `503` +
`Retry-After`. A time bound alone does not cap the waiters (arrival rate × 2 s), and
each waiter already holds its connection because the transaction opens before the
semaphore. With both bounds, the connections anonymous renders can pin are at most
slots + waiters (4 + 2 at the defaults), leaving the rest of the pool of 10 for every
other endpoint. A fulfilment render that must wait is not counted against the waiting
room (changed at apply, from code review: anonymous waiters filling it would otherwise
fast-fail paid fulfilment); it is staff-driven and rare, so it adds at most a waiter or
two beyond that bound. A startup check refuses a configuration where `reserved-renders` is not
less than `max-concurrent-renders` or where slots + waiters would not leave
connections free. Moving the render out of the transaction entirely is the fuller
fix and is recorded as `render-outside-transaction`.

**One slot is reserved for the paid e-stamp render** (`gotenberg.reserved-renders`,
default 1, out of `max-concurrent-renders` 4). The documents module's public
projection API gains a render priority -- `STANDARD` by default, `FULFILMENT` for
the stamp render -- which `StampIntakeService`'s path passes. A `FULFILMENT` render
may use the reserved slot or a general one; a `STANDARD` render only the general
ones. Without this, an anonymous preview flood would fast-fail staff fulfilment of
an agreement a customer has already paid for.

The priority travels through the two-argument `generate(request, systemValues)`
overload that `renderForStamp` uses, then `DocumentProjectionService` and
`HtmlPdfRenderer.toPdf`; `renderPreview`, `renderForDraft` and the stateless preview
stay `STANDARD`.

The refusal is a new public `RenderCapacityException`, distinct from
`DocumentRenderException` so that a capacity refusal and a render failure are
never confused. It lives in the root package beside `DocumentDataInvalidException`
(the documents module throws it): `GlobalExceptionHandler` importing a `documents`
type makes `ModularityTests` report a root <-> documents cycle. `GlobalExceptionHandler` maps it to `503`,
`urn:agreementmitra:problem:render-busy`, with `Retry-After`. **On the stamp path
it is wrapped like any other render unavailability:** `renderForStamp` catches it
alongside `DocumentRenderException` and raises `StampRenderUnavailableException`,
so a fulfilment render refused because the reserved slot is also taken is audited
as `OUTCOME_RENDER_UNAVAILABLE` and shows staff the existing "nothing saved; retry
with the same certificate" problem, not a new one. Ordinary render failures keep
their current behaviour.

This is the one control that cannot be moved to the edge, because it is about our
thread and connection pools, not about arrival rate.

### D7 -- Close the signing permit instead of limiting it

`POST /api/signing/*/request` becomes STAFF-only, and like every unsafe request it
still needs a valid CSRF token. Evidence it costs no customer capability:
`StampIntakeService` calls `signingRequestService.create` itself once the stamp is
attached, and no `.vue` file calls `requestSignature` -- the only references are
`src/api/client.ts` and its own test. The dead client is deleted with it. An
anonymous caller is refused `403` (the `401` challenge is reserved for
`/api/staff/**`).

About twenty existing integration-test call sites post anonymously to this route
and move to a STAFF session (`support/StaffSessions`).

This is the cheapest possible outcome for that route: no limit to tune, no
anonymous path to log, one fewer route egressing signer data to a paid vendor.

*Alternative considered: keep it anonymous and rate-limit it.* Rejected --
guarding a route nobody calls is strictly worse than removing it.

### D8 -- Security events name the abuse, never the credential

Events go to a dedicated logger (`in.agreementmitra.security`) with fields:

- `event` -- `rate_limit_lockout` or `webhook_verification_failed`.
- `route` -- the **classifier's pattern** (D4), e.g. `/api/agreements/{id}/finalise`,
  or the literal `default` for a default-class request, or the controller's own
  mapping for a webhook. Never the request URI, which carries the agreement id.
- `class` -- the route class (lockouts only).
- `source` -- redacted: IPv4 truncated to `/24`, IPv6 to `/48`.
- `count` -- requests counted in the window that tripped the lockout, or
  verification failures from that source since the last event.
- `resource` -- only on a lockout of a per-resource dimension, which after D5 means
  only recovery's per-reference lockout: an HMAC-SHA-256 of the canonical value,
  truncated, under a key generated randomly at startup and held only in memory.
  Events correlate within a process lifetime; there is no new environment secret,
  nothing to rotate, and a stolen log cannot be brute-forced back to a 122-bit id.
  Recovery's lockouts are emitted through the same component.

Emission is bounded so logging cannot become the disk-fill vector this change
closes. A lockout event is emitted on the **transition** into lockout, not per
refused request. A verification failure from a source emits immediately; further
failures from it are counted, and the next failure after a minute has passed emits
one event carrying that count (a trailing count with no later failure is not
emitted -- accepted, since the first event already flagged the source). The
per-source state for this lives in a size-capped cache like the limiter's, so it
cannot itself grow without bound under rotating sources.

Verification-failure events are emitted by the two webhook controllers, which know
the request; the adapters' existing WARN lines (which carry the redacted vendor
reference and the reason, not the source) stay as diagnostics.

Webhook payloads are never echoed, consistent with CLAUDE.md.

This is deliberately *logging*, not alerting: there is nowhere to send an alert
yet (`security-event-alerting` in the register).

### D9 -- Body guard: one ordered guard, enforced on the stream

`PayloadSizeLimitConfig` moves from `signing/api` to the root package as an
ordered servlet filter ahead of the security chain. It applies a 1 MiB ceiling to
every unsafe `/api/**` request except the multipart upload routes
(`POST /api/agreements/*/draft` and the staff stamp-intake upload,
`POST /api/staff/estamp`), which keep
their `spring.servlet.multipart` ceilings (10 MB file / 11 MB request). It refuses
a declared over-limit `Content-Length` immediately, and wraps the input stream so a
body without one (chunked) is cut off once it passes the ceiling. Either refusal is
`413` problem+json of the existing `payload-too-large` type. The filter writes it
for the declared case. The streamed case surfaces inside MVC, where Spring wraps
any read failure in `HttpMessageNotReadableException` (which today always becomes
`400 malformed-request`): the guard throws a dedicated exception, and
`GlobalExceptionHandler.handleHttpMessageNotReadable` walks the cause chain and
returns the `413` when it finds it, before its `400` fallback. The same holds for
the webhooks' `@RequestBody String`. Nothing is persisted on either path.

In Caddy, the 1 MB `request_body` block's matcher excludes the two upload paths;
`request_body` directives with overlapping matchers would both apply and the
smaller would win, capping uploads at 1 MB.

### D10 -- Tests: limits off by default, on where they are the subject

All integration-test traffic comes from loopback, so a suite-wide limiter at the
D5 defaults would let one test's traffic throttle the next (36 anonymous creates
alone exceed the anonymous-write limit). The test profile therefore disables the
route-class limits with one switch (`abuse.limits.enabled: false`); every
integration test that is *about* limits -- including the exclusion and
legitimate-flow tests, which would otherwise pass vacuously -- runs in a dedicated
context that enables them and calls `reset()` between tests. Recovery keeps its own
limiter enabled in every profile, as today, with `reset()` replacing `clear()`.

The render-flood test lowers the render limit in its own properties rather than
driving ~60 real renders to the default; the legitimate-flow test keeps the true
defaults. Integration tests stay inside one window rather than moving time.
Over-ceiling test bodies stay between 1 MiB and 2 MiB, since Tomcat resets the
connection rather than answering once a refused body passes `maxSwallowSize`.

The trusted-proxy negative ("a client-supplied header arriving directly is
ignored") needs its own `RANDOM_PORT` context with `internal-proxies` set to
exclude loopback; in the default context loopback is a trusted peer, which is also
how the positive tests simulate distinct client addresses with `X-Forwarded-For`.

The render-saturation tests use a stub upstream that blocks on a latch, with
`max-concurrent-renders` set small, rather than racing the real Chromium container.

Each extra context is real seconds against the 3-minute `check` budget, so they
reuse `HarnessTestConfig`'s shared containers.

## Risks / Trade-offs

- **A limit set too tight refuses paying customers** -> every value is
  configurable, defaults are set well above observed legitimate volume including
  multiple viewers, the preview debounce is raised in the same change, the
  per-resource dimension never locks out, and the `429` carries a `Retry-After` the
  client surfaces as a retry message rather than a raw status.
- **Carrier NAT means one address is many people** -> per-source limits are set
  well above what a household generates, the existing limiter's own documented
  reasoning. Per-resource limits sit above them (enforced at binding time), so one
  holder of a link cannot starve the others.
- **The limiter is per-instance** -> on one deployment that is the whole
  population. On more than one it weakens proportionally rather than failing, and
  the moment to move it to shared storage is when the app is actually scaled out
  (tracked by `shared-limiter-store`).
- **Trusting a forwarded header is a bypass if the trust boundary is wrong** ->
  Caddy overwrites the one header the application reads; the application trusts it
  only from Caddy's pinned address; `Forwarded` and prefix/host/port headers are
  stripped and not honoured; and DEPLOYMENT.md carries an outside-in check that a
  forged header does not become the source. This is the single most important thing
  to get right in review.
- **The cache cap evicts under extreme key churn** -> an attacker able to bring
  enough distinct (aggregated, Caddy-resolved) sources to exceed the cap can evict
  entries, including lockouts. That requires real address diversity at a rate the
  edge rule also bounds; the cap's purpose is that memory stays bounded either way.
- **The edge controls are configuration, not code, so nothing in the build proves
  they are on** -> they are recorded in `docs/DEPLOYMENT.md` with a verification
  step, and belong in `prod-readiness-preflight`'s production-gate table when
  that lands. Until then this is honestly a documented manual gate.
- **Fast-failing renders turns a slow success into a visible failure** for a
  customer who arrives during a burst. Preferable to the current behaviour, where
  the same burst degrades every endpoint instead of one.
- **Renders still hold a DB connection while they run** -> bounded to slots plus the
  waiting room; the full fix is `render-outside-transaction`.
- **The excluded routes still reach the session lookup** -> a request carrying a
  session cookie to recovery or a webhook is looked up before its handler runs.
  Recovery has its own limiter after that point; the webhook path is bounded only
  at the edge for non-Cloudflare traffic. Accepted for now: the lookup is one
  indexed read, and the origin admits only Cloudflare.
- **Disk fill through the draft upload is bounded per source, not globally** -> 30
  uploads/min per source at 10 MB is still up to ~300 MB/min from one source, more
  with many. Recorded as `anonymous-upload-byte-budget`; `byo-document-upload`
  should not land without revisiting it.
- **Webhooks have no application rate limit** -> deliberate (D4); a forged-webhook
  flood costs one HMAC check each, is visible through D8, and the origin only
  accepts traffic through Cloudflare.

## Migration Plan

1. Client-IP chain: Caddy `header_up` overwrite + strip and Caddy's static compose
   address, deployed **together with** `forward-headers-strategy: native` and
   `internal-proxies`. Small, independently valuable, and it fixes a live
   production defect in the recovery limiter.
2. Body guard moved to the root package, applied to every non-multipart unsafe
   route, enforced on the stream; the Caddy per-path ceiling.
3. Extract the limiter onto Caffeine; keep recovery on it with its current values
   so behaviour there is unchanged apart from the source key and eviction.
4. Add the filter after `CsrfFilter` with limits set generously, plus the frontend
   debounce and the `apiFetch` refusal handling.
5. Render admission control with the reserved fulfilment slot.
6. Close the signing permit; move the test call sites to STAFF; delete the dead
   client.
7. Cloudflare rules + DEPLOYMENT.md.

**Rollback**: every limit is configuration, so an over-tight value is reverted
without a deploy; setting the limits off leaves the code inert. Step 6 is the only
behavioural change and its rollback is one line in `SecurityConfig`. Step 1 is not
rolled back -- reverting it restores a shared-bucket bug -- and its two halves
never roll back separately.

## Open Questions

1. The starting limit values (D5) -- chosen to be safe rather than measured, and
   tunable from real traffic without a spec change.
2. The `maximumSize` default for the limiter cache -- sized for the 8 GB box at
   implementation; a value in the low hundreds of thousands of entries is a few
   tens of MB.
