## Purpose

Bounds what an anonymous caller can consume on a deliberately login-free API:
rate limits keyed on the real client address, request-body ceilings, admission
control on document rendering, and redacted logging so abuse is visible.

## ADDED Requirements

### Requirement: The source a limit is keyed on SHALL be the client address, not the proxy's

The system SHALL resolve the requester's address from the reverse-proxy chain
rather than from the immediate peer, so that every per-source control is keyed on
the caller and not on the proxy in front of the application.

A forwarded address SHALL be trusted only when it arrives from a trusted proxy.
A client-supplied forwarding header SHALL NOT be trusted directly: if it were, a
caller could mint an unlimited number of distinct sources and bypass every limit
in this capability, which is worse than applying no limit at all.

Where the chain cannot be resolved, the system SHALL fall back to the immediate
peer rather than to no key at all, so a misconfiguration degrades to a shared
bucket instead of to an unlimited one.

#### Scenario: A forwarded address from a trusted proxy is used

- **WHEN** a request arrives through the trusted reverse proxy carrying the
  client's address
- **THEN** every per-source limit is keyed on that client address
- **AND** two requests from different clients behind the same proxy occupy
  different buckets

#### Scenario: A client-supplied forwarding header is not trusted

- **WHEN** a caller sets a forwarding header itself and the request does not
  arrive from a trusted proxy
- **THEN** the system ignores the supplied value and keys on the immediate peer

#### Scenario: An unresolvable chain fails to a shared bucket, not to none

- **WHEN** no client address can be resolved from the chain
- **THEN** the request is keyed on the immediate peer and remains subject to the
  limit

### Requirement: The anonymous surface SHALL be rate limited per source and per resource

The system SHALL limit the rate of requests to the anonymous API surface on two
dimensions -- **per source** and **per resource** -- and SHALL apply a lockout
after a limit is exceeded.

Both dimensions are required because they stop different things: per-source stops
one machine sweeping many resources, and per-resource stops many machines
converging on one. Both SHALL be recorded even when the other has already
refused, so that tripping one limit does not refund the other's budget.

A per-source limit SHALL be set looser than a per-resource limit, because a source
address is not a person: carrier NAT and shared offices place many unrelated
customers behind one address, so a per-source limit tuned as tightly as a
per-resource one would refuse a second legitimate customer who did nothing.

The limits SHALL be configurable without a code change, and their defaults SHALL
be set above the volume the legitimate client generates. The system SHALL NOT
apply a limit to the published catalog and jurisdiction reads, which carry no
personal data and cost one indexed query.

#### Scenario: A flood from one source is refused

- **WHEN** one source exceeds the configured request rate for a limited route
- **THEN** further requests from that source are refused for a lockout period
- **AND** requests from an unrelated source are unaffected

#### Scenario: A flood from many sources onto one resource is refused

- **WHEN** many sources converge on a single agreement's routes beyond the
  per-resource rate
- **THEN** further requests for that resource are refused for a lockout period

#### Scenario: Tripping one dimension does not refund the other

- **WHEN** a request is refused by the per-source limit
- **THEN** its per-resource attempt is still recorded

#### Scenario: The legitimate client is not throttled

- **WHEN** a customer fills in the capture form, previews the document, and polls
  the status page at the client's own intervals
- **THEN** no limit refuses any of those requests

#### Scenario: Catalog reads are not limited

- **WHEN** a caller lists the published templates or the eligible jurisdictions
- **THEN** no rate limit applies

### Requirement: A refused request SHALL say so in the format the API already uses

The system SHALL refuse a rate-limited request with `429 Too Many Requests` as
RFC 9457 `application/problem+json`, carrying a distinct problem `type` and a
`Retry-After` value, and SHALL refuse a request it cannot admit for capacity with
`503 Service Unavailable` in the same shape.

The refusal SHALL NOT echo the submitted body, the client-supplied filename, or
any personal data, per `api-error-handling`. It SHALL NOT reveal whether the
resource named in the request exists, so that a limit cannot be used as an
existence oracle.

#### Scenario: A throttled caller is told when to retry

- **WHEN** a request is refused by a rate limit
- **THEN** the response is `429` as `application/problem+json` with a distinct
  type and a `Retry-After` value

#### Scenario: A refusal is not an existence oracle

- **WHEN** rate-limited requests name a resource that exists and one that does
  not
- **THEN** both refusals are identical in shape and status

### Requirement: The limiter SHALL NOT itself grow without bound

The system SHALL evict a limiter entry once its window and any lockout have
elapsed, so that the number of tracked entries is bounded by recent traffic rather
than by the number of distinct keys ever seen.

Without eviction the limiter is a memory-exhaustion vector in its own right: a
caller rotating source addresses would add an entry per address indefinitely,
turning an abuse control into the abuse.

#### Scenario: Stale entries are evicted

- **WHEN** a key's window and lockout have both elapsed
- **THEN** its entry is no longer retained

#### Scenario: Rotating sources do not grow memory without bound

- **WHEN** requests arrive from a continuously changing set of sources
- **THEN** the number of retained entries stays bounded by recent traffic

### Requirement: Request bodies SHALL be bounded on every anonymous write route

The system SHALL enforce a request-body ceiling on every anonymous write route,
including agreement creation and the stateless document preview, and SHALL reject
an oversized body before reading it into memory.

The ceiling SHALL be enforced independently of field-level validation, because a
field constrained only as non-blank accepts a body of any size. Routes that
legitimately carry a larger payload -- a document upload -- SHALL keep their own
higher ceiling rather than forcing a single global value.

#### Scenario: An oversized create body is rejected

- **WHEN** a caller posts an agreement whose fields carry megabytes of text
- **THEN** the system rejects it as problem+json before persisting anything

#### Scenario: A document upload keeps its larger ceiling

- **WHEN** a caller uploads a document within the upload route's own ceiling
- **THEN** it is accepted, and the smaller ceiling applied to other routes does
  not refuse it

### Requirement: Document rendering SHALL refuse rather than queue when it is at capacity

The system SHALL bound how long a request waits for a render slot and SHALL refuse
with `503` and a `Retry-After` once that bound elapses, rather than holding the
request thread until a slot frees.

Renders are few and slow relative to request threads, so an unbounded wait lets a
flood exhaust the servlet thread pool rather than only the renderer -- which takes
down unrelated endpoints with it. Refusing quickly keeps the failure confined to
rendering.

#### Scenario: A render flood does not take down unrelated endpoints

- **WHEN** more render requests arrive than there are render slots
- **THEN** the excess is refused with `503` and a `Retry-After`
- **AND** endpoints that do not render continue to serve normally

#### Scenario: A render within capacity is unaffected

- **WHEN** a render request arrives and a slot is available
- **THEN** it renders as before, with no added latency beyond slot acquisition

### Requirement: Abuse SHALL be recorded without recording the credential

The system SHALL emit a security event for a webhook signature-verification
failure and for a rate-limit lockout, carrying the event name, the route, a
redacted source and a count.

The event SHALL NOT carry the agreement id. The id is a bearer capability: an
attacker reading a log would gain the access the log was written to protect.
Where a per-resource key must appear, it SHALL be recorded as a salted hash rather
than the value. No webhook payload SHALL be echoed, verbatim or in part, and no
signer personal data SHALL appear.

#### Scenario: A verification failure is recorded

- **WHEN** an inbound webhook fails signature verification
- **THEN** a security event records the event, the route and a redacted source
- **AND** no part of the payload is logged

#### Scenario: A lockout is recorded

- **WHEN** a limit places a key in lockout
- **THEN** a security event records it, with any resource key as a salted hash

#### Scenario: No log line carries an agreement id

- **WHEN** any security event in this capability is emitted
- **THEN** no agreement id appears in it, in any form other than a salted hash
