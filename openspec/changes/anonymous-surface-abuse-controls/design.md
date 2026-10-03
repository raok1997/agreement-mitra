## Context

See `proposal.md` for the three findings that motivate this. The requirements are
in this change's `specs/`.

The state of the code and the deployment, since both halves matter here:

- **The edge is already correct.** `deploy/Caddyfile` pins `trusted_proxies` to
  Cloudflare's published ranges and sets `client_ip_headers CF-Connecting-IP`, and
  its own comment says why: *"Rate limiting and the redacted security-event
  logging that `signing-auth` will add are both meaningless on a proxy IP, so
  real-client-IP recovery has to be correct from day one."*
- **The application never reads it.** `server.forward-headers-strategy` is unset,
  so `getRemoteAddr()` returns Caddy's address.
  `RecoveryController.java:52` uses exactly that.
- **The origin cannot be bypassed.** Provisioning restricts Docker-published
  80/443 to Cloudflare's ranges through the `DOCKER-USER` chain, fetched live and
  failing closed. So an edge control is a real control, not advice.
- `RecoveryRateLimiter` is a sliding-window limiter with a lockout, two
  dimensions, and a documented rationale worth preserving (an IP is not a person;
  both dimensions are recorded even when one fails). Its `hits` and `lockedUntil`
  maps are never evicted.
- `PayloadSizeLimitConfig` (in `signing/api`) applies a 1 MiB body guard to
  `/api/webhooks/esign`, `/api/webhooks/razorpay`, `/api/signing/*` and
  `/api/agreements/recovery` only.
- `GotenbergClient` bounds concurrency with a `Semaphore` of
  `maxConcurrentRenders` (default 4) and a 30 s per-render timeout.
- `SecurityConfig` lives in the root package alongside
  `GlobalExceptionHandler`; `Customizer.withDefaults()` on `headers` means **no
  CSP is set** (out of scope here, but see Open Questions).

## Goals / Non-Goals

**Goals:**

- Make the compensating control DEPLOYMENT.md already claims actually exist.
- Keep every control keyed on something that identifies a caller, not a proxy.
- Keep legitimate anonymous use untouched -- no login, no CAPTCHA in the app, no
  new friction on the free drafting path.
- Prefer deleting an anonymous route over guarding it, where the route has no
  customer.

**Non-Goals:**

- Authentication, ownership authZ, or any change to the capability-link model.
- A shared/distributed limiter store (single instance today).
- A CSP, or the broader header posture.
- Turnstile or any in-app bot check.

## Decisions

### D1 -- Two layers, edge first, and the edge is not optional

| Layer | Controls | Why there |
|---|---|---|
| Cloudflare | Bot Fight Mode; a rate-limiting rule on `POST /api/*` excluding `/api/webhooks/*` | Absorbs a flood before it reaches a single box that also runs Postgres, MinIO and Chromium. Sees the true client address natively. |
| Caddy | Per-path request-body ceiling | Rejects an oversized body before the JVM allocates for it. |
| Application | Per-source/per-resource limits, render admission control, security events | Works in local and test, survives an edge misconfiguration, and is the only layer that can key on a resource. |

The edge layer is listed first because it is the only one that protects the
machine's CPU and memory rather than just the application's threads. The
application layer is not redundant with it: it is what makes the behaviour
testable and what keeps the control present in every environment.

*Alternative rejected: a Caddy rate-limit module.* Rate limiting is not in
Caddy's standard build; it needs a third-party module compiled with `xcaddy`,
which means a custom image rebuilt on every Caddy update. Cloudflare covers the
same ground with no build. (The body ceiling *is* built in, so that part stays in
Caddy.)

*Alternative rejected: in-app CAPTCHA (Turnstile/reCAPTCHA).* A challenge proves
a human was present once; it does not bound what the session then does, and it
cannot apply to the 20 s status polling or to vendor webhooks. Cloudflare Bot
Fight Mode gives the same signal at the edge with no third-party script in the
page, no npm dependency in the OSV-scanned graph, and no processor to disclose
under the DPDP Act -- which matters for identity/legal infrastructure. Revisit
only if edge bot scoring proves insufficient.

### D2 -- Fix the client-IP chain first, because every limit depends on it

`server.forward-headers-strategy: framework` makes Spring's
`ForwardedHeaderFilter` populate `getRemoteAddr()` from the proxy chain. Caddy's
`reverse_proxy` already sets the forwarded header from the client address it
recovered.

`framework` rather than `native`: the filter is container-independent, and the
posture then does not change if the embedded container is ever swapped.

**This must land before any new limit.** A per-source limit keyed on a proxy
address is not a weaker control, it is a different and harmful one -- a shared
fuse where one abuser refuses every customer. That is the state `RecoveryRateLimiter`
is in today, so this single line is also a production bug fix.

Trust boundary: the forwarded value is trusted **only** because `trusted_proxies`
is pinned to Cloudflare's ranges and the origin firewall drops everything else. A
direct client-supplied header must never be trusted -- that would let a caller
mint unlimited buckets, which is worse than having no limit.

### D3 -- Generalize the existing limiter rather than add a library

Extract `RecoveryRateLimiter`'s algorithm into a reusable component (sliding
window, lockout, two dimensions, both recorded on failure) and keep recovery as
one configured caller of it.

Reasons to extract rather than adopt Bucket4j or Resilience4j: the behaviour we
want is 40 lines that already exist, are already documented with their rationale,
and are already tested; a dependency adds OSV-scanned surface for no behaviour we
lack. Reasons to extract rather than copy: a second copy would drift, and the
eviction fix has to land in one place.

Add **TTL eviction** in the extraction (a bounded cache such as Caffeine, or a
scheduled sweep). Caffeine is a small, widely used dependency; a scheduled sweep
adds none. Prefer the sweep unless the cache is wanted for other reasons -- fewer
dependencies is the tie-breaker on a project whose build fails on any
unsuppressed OSV finding.

### D4 -- Applied as a filter, keyed by route class

A servlet filter (ordered after `ForwardedHeaderFilter`, before the security
chain's expensive work) classifies the request and applies the matching limit:

| Class | Routes | Key | Rationale |
|---|---|---|---|
| Render | stateless preview, `*/document`, `*/preview` | source | The DoS vector; no id needed for the first of them |
| Anonymous write | `POST /api/agreements` | source | Unbounded row creation |
| Capability write | draft upload, contacts, finalise, payment order | source + agreement id | Bounds damage if one link leaks |
| Polled read | `GET /api/agreements/*`, `*/payment`, `*/stamp-quote`, `signing/*/progress` | source + agreement id | ~3 req/min per open tab today |
| Webhook | `/api/webhooks/*` | source | Bounds a forgery flood; HMAC remains the control |
| Unlimited | `/api/templates*`, `/api/jurisdictions`, health | none | Public metadata, one indexed query |

A filter rather than per-controller annotations: the classification is a property
of the route, the list is the thing to review in one place, and it keeps the
module boundaries untouched -- the limiter is cross-cutting and belongs beside
`SecurityConfig` in the root package, not inside `signing`.

### D5 -- Starting limits, and why the frontend changes first

| Route class | Per source | Per resource |
|---|---|---|
| Render | 60 / min | -- |
| Anonymous write | 10 / min | -- |
| Capability write | 30 / min | 10 / min |
| Polled read | 120 / min | 30 / min |
| Webhook | 120 / min | -- |

Lockout: 5 minutes. All values configurable, none compiled in.

The render figure is deliberately high because of a real constraint:
`CaptureForm.vue` debounces the live preview at **250 ms**, so a customer typing
through a section can legitimately generate tens of renders a minute. **Raising
that debounce to ~600 ms is part of this change, not a follow-up** -- tune the
limit without it and the choice is between a limit that does nothing and one that
throttles paying customers. The debounce change also cuts render load directly,
which is the cheapest mitigation in this whole change.

### D6 -- Render admission control: refuse, do not queue

`GotenbergClient` waits on its semaphore, so under a flood the Tomcat threads
fill with requests parked for up to 30 s and endpoints that never render start
failing too. Replace the unbounded wait with a short bounded one and a `503` +
`Retry-After` when it elapses.

The bound is a small fraction of the render timeout -- a caller who cannot get a
slot within a couple of seconds is better served a fast refusal than a 30 s hang,
and the browser can retry. This is the one control that cannot be moved to the
edge, because it is about our thread pool, not about arrival rate.

### D7 -- Close the signing permit instead of limiting it

`POST /api/signing/*/request` becomes STAFF-only. Evidence it costs no customer
capability: `StampIntakeService` calls `signingRequestService.create` itself once
the stamp is attached, and no `.vue` file calls `requestSignature` -- the only
references are `src/api/client.ts` and its own test. The dead client is deleted
with it.

This is the cheapest possible outcome for that route: no limit to tune, no
anonymous path to log, one fewer route egressing signer data to a paid vendor.

*Alternative considered: keep it anonymous and rate-limit it.* Rejected --
guarding a route nobody calls is strictly worse than removing it.

### D8 -- Security events name the abuse, never the credential

Events carry `event`, `route`, redacted source, count. The agreement id is a
bearer capability, so it is never logged; where a per-resource key must appear it
is a salted hash (salt from the environment, as with every other secret). Webhook
payloads are never echoed, consistent with CLAUDE.md.

This is deliberately *logging*, not alerting: there is nowhere to send an alert
yet. Making the events exist and be greppable is the step that turns "we would
never know" into "we could find out".

## Risks / Trade-offs

- **A limit set too tight refuses paying customers** -> every value is
  configurable, defaults are set well above observed legitimate volume, the
  preview debounce is raised in the same change, and the `429` carries a
  `Retry-After` the client surfaces as a retry message rather than a raw status.
- **Carrier NAT means one address is many people** -> per-source limits are
  deliberately looser than per-resource ones, which is the existing limiter's own
  documented reasoning; the tight limits are keyed on the resource.
- **The limiter is per-instance** -> on one deployment that is the whole
  population. On more than one it weakens proportionally rather than failing, and
  the moment to move it to shared storage is when the app is actually scaled out
  (tracked by `session-store-not-durable`).
- **Trusting a forwarded header is a bypass if the trust boundary is wrong** ->
  trusted only from Cloudflare ranges, with an origin firewall that drops
  everything else; a direct client-supplied header is ignored. This is the single
  most important thing to get right in review.
- **The edge controls are configuration, not code, so nothing in the build proves
  they are on** -> they are recorded in `docs/DEPLOYMENT.md` with a verification
  step, and belong in `prod-readiness-preflight`'s production-gate table when
  that lands. Until then this is honestly a documented manual gate.
- **Fast-failing renders turns a slow success into a visible failure** for a
  customer who arrives during a burst. Preferable to the current behaviour, where
  the same burst degrades every endpoint instead of one.

## Migration Plan

1. `forward-headers-strategy` + the Caddy body ceiling + the
   `PayloadSizeLimitConfig` paths. Small, independently valuable, and item one
   fixes a live production defect in the recovery limiter.
2. Extract the limiter with eviction; keep recovery on it with its current values
   so behaviour there is unchanged apart from the source key and eviction.
3. Add the filter with limits set generously, plus the frontend debounce and the
   `429`/`503` handling.
4. Render admission control.
5. Close the signing permit; delete the dead client.
6. Cloudflare rules + DEPLOYMENT.md.

**Rollback**: every limit is configuration, so an over-tight value is reverted
without a deploy; setting the limits off leaves the code inert. Step 5 is the only
behavioural change and its rollback is one line in `SecurityConfig`. Step 1 is not
rolled back -- reverting it restores a shared-bucket bug.

## Open Questions

1. The starting limit values (D5) -- chosen to be safe rather than measured, and
   tunable from real traffic without a spec change.
2. Whether eviction uses Caffeine or a scheduled sweep (D3) -- a dependency
   trade-off, decidable at implementation.
3. **No CSP is set** (`Customizer.withDefaults()`). Out of scope here, but it is
   the kind of gap this review surfaced and it should become a register row rather
   than be forgotten.
