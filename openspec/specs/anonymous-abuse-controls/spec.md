# anonymous-abuse-controls Specification

## Purpose
TBD - created by archiving change anonymous-surface-abuse-controls. Update Purpose after archive.
## Requirements
### Requirement: The source a limit is keyed on SHALL be the client address, not the proxy's

The system SHALL resolve the requester's address from a single forwarded header
that the reverse proxy overwrites with the client address it recovered, rather than
from the immediate peer, so that every per-source control is keyed on the caller
and not on the proxy in front of the application.

The forwarded header SHALL be honoured only when the immediate peer is the trusted
reverse proxy. The reverse proxy SHALL overwrite that header rather than append to
it, and SHALL strip RFC 7239 `Forwarded`, `X-Forwarded-Host` and
`X-Forwarded-Prefix`, so that no value the client wrote reaches the application as
its address or its path. A client able to choose its source could mint an
unlimited number of buckets, or adopt another customer's address to lock them out,
which is worse than applying no limit at all.

An IPv6 source SHALL be keyed on its `/64` prefix (configurable), because one host
typically controls a whole `/64`.

Where no address can be resolved, the system SHALL fall back to one shared bucket
rather than to no key at all, so a misconfiguration degrades to a shared bucket
instead of to an unlimited one. (Behind the trusted proxy an address is unparseable
only when the proxy itself sent a bad value, so that bucket is in effect the
immediate peer's.)

#### Scenario: A forwarded address from the trusted proxy is used

- **WHEN** a request arrives from the trusted reverse proxy carrying the client's
  address in the forwarded header
- **THEN** every per-source limit is keyed on that client address
- **AND** two requests from different clients behind the same proxy occupy
  different buckets

#### Scenario: A forwarding header arriving directly is not trusted

- **WHEN** a caller sets a forwarding header itself and the request's immediate
  peer is not the trusted proxy
- **THEN** the system ignores the supplied value and keys on the immediate peer

#### Scenario: A client-seeded header does not survive the proxy

- **WHEN** a client sends its own `X-Forwarded-For` or `Forwarded` header through
  the edge and the reverse proxy
- **THEN** the address the application resolves is the client address the proxy
  recovered, not the value the client wrote

#### Scenario: IPv6 addresses in one /64 share a bucket

- **WHEN** requests arrive from different IPv6 addresses within the same `/64`
- **THEN** they are counted against one per-source bucket

#### Scenario: An unresolvable chain fails to a shared bucket, not to none

- **WHEN** no client address can be resolved from the chain
- **THEN** the request is keyed on the shared fallback bucket and remains subject to
  the limit

### Requirement: The anonymous surface SHALL be rate limited per source and per resource

The system SHALL assign every API route to exactly one route class, SHALL limit the
rate of requests per class on a **per-source** dimension and, for the classes that
act on or read a named agreement's capability routes, a **per-resource** dimension,
and SHALL apply a configured default class to any route not explicitly classified,
so that no route is unlimited by omission. Budgets and lockouts SHALL be held per
class, so that tripping one class's limit does not refuse the same source on
another class.

Both dimensions are required because they stop different things: per-source stops
one machine sweeping many resources, and per-resource stops many machines
converging on one. A request refused by the per-resource limit SHALL still be
counted against its source, so that tripping a resource refunds no source budget.
A request refused by the per-source limit SHALL NOT be counted against the
resource, so that a refused or locked-out caller cannot keep an agreement's budget
spent and starve its other link holders. The resource SHALL
be the agreement id parsed and canonicalized from the path; a value that does not
parse SHALL get no per-resource bucket, so that encoding or case variants cannot
mint fresh resource buckets.

Exceeding a per-source limit SHALL place that source in a lockout. Exceeding a
per-resource limit SHALL refuse the request but SHALL NOT lock the resource out,
because a lockout on an agreement id would let anyone holding the link deny the
agreement's holder access to it.

A per-resource limit SHALL be set higher than the per-source limit of the same
class, so that one source exhausts its own budget before it can exhaust an
agreement's, and one holder of a link cannot starve the others. The per-source
limit SHALL itself be set well above the volume one household generates, because a
source address is not a person: carrier NAT and shared offices place many unrelated
customers behind one address. The limits SHALL be
configurable without a code change, and their defaults SHALL be set above the
volume the legitimate client generates, including several parties viewing one
agreement at once.

The system SHALL refuse an API request that the browser marks as cross-site
(`Sec-Fetch-Site: cross-site`) before counting it, except the sign-in callback and
the vendor webhooks, which are cross-site by nature; otherwise a hostile page could
spend a visitor's address budget through embedded requests, which CSRF tokens do
not guard for safe methods. The limiter SHALL count a request only after its CSRF
token has been accepted, so a request refused for CSRF consumes no budget, and SHALL run before the session
lookup, so that on every route it limits a flood of requests carrying junk session
cookies is bounded before it reaches the database.

The published catalog and jurisdiction reads and the CSRF token endpoint, through
which the client bootstraps every write, SHALL carry only a per-source ceiling far
above any rate the client generates, refusing within the window and never locking
out, so that legitimate use is never refused while a scripted flood is still
bounded. The recovery endpoint
SHALL be excluded from this limiter and keep its own, because its throttled answer
must be indistinguishable from an unthrottled one. The vendor webhooks SHALL be
excluded, because their signature verification is the control and a per-source
limit would key on the vendor and refuse genuine signed callbacks.

#### Scenario: A flood from one source is refused

- **WHEN** one source exceeds the configured request rate for a limited route
- **THEN** further requests from that source are refused for a lockout period
- **AND** requests from an unrelated source are unaffected

#### Scenario: A flood from many sources onto one resource is refused without locking it out

- **WHEN** many sources converge on a single agreement's routes beyond the
  per-resource rate
- **THEN** requests beyond that rate are refused
- **AND** once the window has moved on, requests for that agreement are served
  again with no lockout period

#### Scenario: Tripping one dimension does not refund the other

- **WHEN** a request is refused by the per-resource limit
- **THEN** it is still counted against its source

#### Scenario: One holder of a link cannot starve the others

- **WHEN** one source requests an agreement's routes as fast as its own per-source
  limit allows
- **THEN** that agreement's per-resource budget is not exhausted, and another
  source's requests for it are still served

#### Scenario: A refused caller does not starve other holders of the link

- **WHEN** a source that the per-source limit has refused keeps requesting one
  agreement's routes
- **THEN** none of those refused requests is counted against that agreement's
  per-resource budget

#### Scenario: A cross-site request is refused without spending budget

- **WHEN** a hostile page makes a visitor's browser send API requests marked
  `Sec-Fetch-Site: cross-site`
- **THEN** each is refused
- **AND** the visitor's budget for those routes is unchanged

#### Scenario: A slow typist's live preview is not throttled

- **WHEN** a customer's live HTML preview refreshes on nearly every keystroke for a
  minute
- **THEN** no limit refuses those previews and none places the source in lockout

#### Scenario: A non-canonical agreement id does not mint a fresh resource bucket

- **WHEN** requests name the same agreement with its id in different letter case
- **THEN** they are counted against one per-resource bucket

#### Scenario: The legitimate client is not throttled

- **WHEN** a customer fills in the capture form, previews the document, and polls
  the status page at the client's own intervals
- **THEN** no limit refuses any of those requests

#### Scenario: Several parties viewing one agreement are not throttled

- **WHEN** four viewers poll the same agreement's status page at the client's own
  interval
- **THEN** no limit refuses any of those requests

#### Scenario: Catalog reads and the CSRF bootstrap are never refused at legitimate rates

- **WHEN** a client lists the published templates, the eligible jurisdictions, or
  fetches a CSRF token at any rate the web client generates
- **THEN** no limit refuses those requests, and none of them places the source in
  lockout

#### Scenario: A lockout in one class does not refuse another

- **WHEN** a source is locked out of one route class
- **THEN** its requests to a route in a different class are still served

#### Scenario: A request refused for CSRF consumes no budget

- **WHEN** a source sends unsafe requests without a valid CSRF token to a limited
  route
- **THEN** each is refused for CSRF
- **AND** that source's budget for the route is unchanged

#### Scenario: An unclassified route falls into the default class

- **WHEN** a route that no class lists is called beyond the default class's rate
- **THEN** further requests are refused as for any other limited route

#### Scenario: Recovery is never answered by this limiter

- **WHEN** recovery requests exceed any route-class limit
- **THEN** the recovery endpoint still answers with its own uniform response and
  never with a rate-limit refusal

#### Scenario: A verified webhook is never refused by the limiter

- **WHEN** a vendor delivers signed webhooks at any rate
- **THEN** none is refused by a rate limit

### Requirement: A refused request SHALL say so in the format the API already uses

The system SHALL refuse a rate-limited request with `429 Too Many Requests` as
RFC 9457 `application/problem+json`, carrying a distinct problem `type` and a
`Retry-After` value, and SHALL refuse a request it cannot admit for capacity with
`503 Service Unavailable` in the same shape.

The refusal SHALL NOT echo the submitted body, the request path, the
client-supplied filename, or any personal data, per `api-error-handling`. It SHALL
NOT reveal whether the resource named in the request exists, so that a limit cannot
be used as an existence oracle.

#### Scenario: A throttled caller is told when to retry

- **WHEN** a request is refused by a rate limit
- **THEN** the response is `429` as `application/problem+json` with a distinct
  type and a `Retry-After` value

#### Scenario: A refusal is not an existence oracle

- **WHEN** rate-limited requests name a resource that exists and one that does
  not
- **THEN** both refusals are identical in shape and status

#### Scenario: The client shows a retry message rather than a raw status

- **WHEN** the web client receives a `429` or `503` refusal
- **THEN** it shows a message telling the customer when to try again, based on
  the server's `Retry-After`
- **AND** it does not treat the refusal as a signed-out session

### Requirement: The limiter SHALL NOT itself grow without bound

The system SHALL evict a limiter entry once its window and any lockout have
elapsed, and SHALL hold no more than a configured maximum number of entries
regardless of traffic, so that memory stays bounded even under a flood of distinct
keys.

Without both, the limiter is a memory-exhaustion vector in its own right: entries
within a single window grow with request rate times distinct keys, so expiry alone
does not bound a flood.

#### Scenario: Stale entries are evicted

- **WHEN** a key's window and lockout have both elapsed
- **THEN** its entry is no longer retained

#### Scenario: Rotating sources do not grow memory without bound

- **WHEN** requests arrive from more distinct sources than the configured maximum
  within one window
- **THEN** the number of retained entries does not exceed that maximum

### Requirement: Request bodies SHALL be bounded on every anonymous write route

The system SHALL enforce a request-body ceiling on every unsafe API request,
including agreement creation and the stateless document preview, and SHALL refuse
an oversized body as problem+json without persisting anything, whether the size is
declared up front or only discovered while reading a body sent without a declared
length.

The ceiling SHALL be enforced independently of field-level validation, because a
field constrained only as non-blank accepts a body of any size. Routes that
legitimately carry a larger payload -- a document upload -- SHALL keep their own
higher ceiling rather than forcing a single global value.

#### Scenario: An oversized create body is rejected

- **WHEN** a caller posts an agreement whose fields carry megabytes of text
- **THEN** the system rejects it as problem+json before persisting anything

#### Scenario: An oversized body without a declared length is rejected

- **WHEN** a caller streams an over-ceiling body to agreement creation with no
  declared content length
- **THEN** the system rejects it as problem+json before persisting anything

#### Scenario: A document upload keeps its larger ceiling

- **WHEN** a caller uploads a document larger than the general ceiling but within
  the upload route's own ceiling
- **THEN** it is accepted, and the smaller ceiling applied to other routes does
  not refuse it

### Requirement: Document rendering SHALL refuse rather than queue when it is at capacity

The system SHALL bound both how long and how many requests wait for a render slot,
and SHALL refuse with `503` and a `Retry-After` when the wait elapses or the
waiting room is full, rather than holding the request thread until a slot frees.

Renders are few and slow relative to request threads and database connections, so
an unbounded wait lets a flood exhaust the servlet thread pool and the connection
pool rather than only the renderer -- which takes down unrelated endpoints with it.
Refusing quickly keeps the failure confined to rendering.

The system SHALL reserve render capacity for the render that fulfils a paid
agreement's e-stamp, so that anonymous rendering cannot exhaust it.

#### Scenario: A render flood does not take down unrelated endpoints

- **WHEN** more render requests arrive than there are render slots
- **THEN** the excess is refused with `503` and a `Retry-After`
- **AND** endpoints that do not render, including ones that read the database,
  continue to serve normally

#### Scenario: A render within capacity is unaffected

- **WHEN** a render request arrives and a slot is available
- **THEN** it renders as before, with no added latency beyond slot acquisition

#### Scenario: The paid stamp render proceeds during an anonymous render flood

- **WHEN** anonymous render requests occupy every general render slot
- **AND** staff attach an e-stamp, which renders the paid agreement
- **THEN** that render is admitted to the reserved slot and completes

### Requirement: Abuse SHALL be recorded without recording the credential

The system SHALL emit a security event for a webhook signature-verification
failure and for a transition into rate-limit lockout, carrying the event name, the
matched route pattern, a redacted source and a count.

The event SHALL NOT carry the agreement id, and the route SHALL be the classifying
pattern -- or a fixed label for a request in the default class -- never the request
path, which contains the id. The id is a bearer
capability: an attacker reading a log would gain the access the log was written to
protect. Where a per-resource key must appear, it SHALL be recorded as a keyed hash
rather than the value. The source SHALL be redacted to a network prefix. No webhook
payload SHALL be echoed, verbatim or in part, and no signer personal data SHALL
appear.

Emission SHALL be bounded so that logging cannot itself fill the disk: a lockout is
recorded when it begins, not per refused request, and verification failures are
recorded at most once per source per interval, with a count.

#### Scenario: A verification failure is recorded

- **WHEN** an inbound webhook fails signature verification
- **THEN** a security event records the event, the route pattern and a redacted
  source
- **AND** no part of the payload is logged

#### Scenario: A lockout is recorded once

- **WHEN** a limit places a key in lockout and further requests from it are refused
- **THEN** exactly one security event records the lockout, with any resource key as
  a keyed hash

#### Scenario: No log line carries an agreement id

- **WHEN** any security event in this capability is emitted
- **THEN** no agreement id appears in it, in any form other than a keyed hash

#### Scenario: A default-class lockout on an id-bearing path logs no id

- **WHEN** a source is locked out of the default class on a route whose path
  contains an agreement id
- **THEN** the security event's route is the fixed default label and no agreement
  id appears in it

