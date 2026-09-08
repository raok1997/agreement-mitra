# signing-request Specification

## Purpose

Start an Aadhaar eSign for an existing agreement and drive it to completion. A
persisted SigningRequest aggregate owns the signing-status FSM (SIGN_REQUESTED →
SIGNED | FAILED | EXPIRED), keeping the Agreement aggregate status-less. Covers the
create endpoint, the verified inbound webhook, Details-API-as-source-of-truth status
resolution, the EsignProvider (Leegality) adapter seam, secrets/PII handling, and the
Flyway-managed schema. (Created by archiving change `create-signing-request`.)
## Requirements
### Requirement: Create an eSign request for an agreement

The system SHALL provide `POST /api/signing/{agreementId}/request` that starts an
Aadhaar eSign for an existing agreement through the `EsignProvider` seam. It SHALL load
the agreement, build a vendor-neutral request carrying every signer (name, email, and
the data needed for Aadhaar eSign) plus the unsigned PDF, and respond `201 Created` with
the provider document identifier and a per-signer signing URL (with its expiry).

To avoid losing a completion, the system SHALL **persist the signing request before
calling the provider** (in a pre-request state with no provider document id yet), then
**stamp the document** (see "Auto-stamp before the provider call"), then call the
provider, then update the same row to `SIGN_REQUESTED` with the returned document id and
per-signer URLs. The provider call SHALL NOT be made while holding an open database
transaction (no transaction spans the outbound HTTP round-trip).

The unsigned PDF bytes SHALL be **server-sourced**, not a client-settable field
(anti-mass-assignment). The bytes SHALL be loaded from the agreement's **stored draft**
in object storage (the `draftPdfKey` set by the draft-ingestion flow), then **stamped**;
the document submitted to the provider SHALL be the **stamped PDF**, not the bare draft.
The system SHALL require that a draft has been uploaded: when the agreement has **no
stored draft**, the system SHALL respond `409 Conflict` as RFC 9457
`application/problem+json` and SHALL NOT persist a signing request or call the provider.
The system SHALL NOT fall back to a placeholder document. The endpoint SHALL bound the
request body size and reject an oversized body before doing provider work.

The endpoint SHALL be **asynchronous by contract**: it SHALL return once the request is
created and SHALL NOT block the request thread waiting for any signature. Completion is
driven later by the webhook. An agreement MAY have more than one signing request over
time; each create SHALL produce its own signing-request row.

For an unknown `agreementId` the system SHALL respond `404 Not Found` as RFC 9457
`application/problem+json` (per `api-error-handling`); a syntactically invalid id SHALL
respond `400 Bad Request` as problem+json, not 404. The response SHALL NOT echo signer
PII beyond the signing URLs needed by the caller.

> Note (documented risk, deferred): this endpoint is currently **unauthenticated** —
> consistent with the rest of the API (no auth mechanism exists yet). Because it now
> triggers a real provider call (outbound signer PII + signing invites + quota use),
> ownership-based authorization, rate-limiting, and security-event logging are deferred
> to a dedicated follow-up `signing-auth` change. Sandbox + dummy data only mitigates
> this in this repo.

#### Scenario: Signing request is created for a valid agreement

- **WHEN** a client POSTs `/api/signing/{agreementId}/request` for an existing
  multi-party agreement **that has an uploaded draft**
- **THEN** the system loads the stored draft bytes, persists a signing-request row before
  the provider call, **stamps the document**, calls the provider once **with the stamped
  PDF**, and updates that row to `SIGN_REQUESTED` with the provider document id and a
  signing URL plus expiry per signer
- **AND** the system responds `201 Created` with the document id and the per-signer
  signing URLs

#### Scenario: Stamped PDF, not the raw draft, is submitted to the provider

- **WHEN** a signing request is created for an agreement with an uploaded draft
- **THEN** the document handed to the `EsignProvider` is the composited stamped PDF
  (stamp page prepended, serial overlay present), not the bare draft bytes

#### Scenario: Agreement without an uploaded draft is rejected

- **WHEN** a client POSTs `/api/signing/{agreementId}/request` for an existing agreement
  that has **no stored draft**
- **THEN** the system responds `409 Conflict` as `application/problem+json` and persists
  no signing request and calls no provider

#### Scenario: Provider succeeds but persistence-update fails leaves a recoverable record

- **WHEN** the provider create call succeeds but the subsequent persistence update fails
- **THEN** a signing-request row for that agreement still exists (from the pre-request
  persist) so the live provider document is not orphaned and can be reconciled later

#### Scenario: Request thread does not block on signing

- **WHEN** a signing request is created
- **THEN** the response returns immediately after the provider create call, without
  waiting for any signer to sign

#### Scenario: Oversized request body is rejected

- **WHEN** a client POSTs a body exceeding the configured size limit
- **THEN** the system rejects it before performing provider work

#### Scenario: Unknown agreement id returns 404

- **WHEN** a client POSTs `/api/signing/{agreementId}/request` for an id that does not
  exist
- **THEN** the system responds `404 Not Found` as `application/problem+json` and
  persists no signing request and calls no provider

#### Scenario: Non-UUID agreement id returns 400

- **WHEN** a client POSTs `/api/signing/not-a-uuid/request`
- **THEN** the system responds `400 Bad Request` as `application/problem+json`

### Requirement: Signing request owns the status FSM

A signing request SHALL be a persisted aggregate that owns the signing-status state
machine. For this capability the lifecycle is `PDF_GENERATED → STAMPED → SIGN_REQUESTED →
SIGNED | FAILED | EXPIRED`, with a `STAMP_FAILED` branch off the stamp step.
`PDF_GENERATED` is the pre-request state the row is persisted in before the provider
call; `STAMPED` means a stamp is confirmed attached to this request's instrument (freshly
procured or reused). State changes SHALL go through the aggregate's state-machine method,
not ad-hoc setters. `SIGNED`, `FAILED`, `EXPIRED`, and `STAMP_FAILED` are terminal: a
transition out of a terminal state SHALL be rejected. The `Agreement` aggregate SHALL
remain **status-less** — the signing status (lifecycle) lives only on the signing request;
the stamp **data** the agreement carries is descriptive, not a status.

The post-`SIGN_REQUESTED` aggregate FSM transition SHALL be driven by **aggregating
per-invitee statuses**, not a single document-level status. Each invitee of the request
SHALL have a recorded per-invitee status (`PENDING`, `SIGNED`, `REJECTED`, or `EXPIRED`),
which is a sub-state of the aggregate's `SIGN_REQUESTED` state (not a new aggregate FSM
state). The aggregation rule SHALL be: if **any** invitee is `REJECTED` the request
transitions to `FAILED`; else if **any** invitee is `EXPIRED` the request transitions to
`EXPIRED`; else if **all** invitees are `SIGNED` the request transitions to `SIGNED`;
otherwise the request stays `SIGN_REQUESTED` (a safe no-op). `FAILED` SHALL outrank
`EXPIRED`. The terminal decision SHALL depend only on the set of per-invitee statuses
relative to the invitee count, so it cannot be corrupted by a per-invitee-to-row
correlation mismatch.

Concurrent transition attempts (e.g. webhook delivered more than once or to more than
one instance at the same time) SHALL be made safe by optimistic locking (a version
column): at most one of two racing transitions SHALL win and the other SHALL be retried
or rejected without corrupting state.

#### Scenario: Stamp transition precedes the request transition

- **WHEN** a signing request in `PDF_GENERATED` is stamped successfully
- **THEN** it transitions to `STAMPED`, and only from `STAMPED` does it transition to
  `SIGN_REQUESTED` after the provider call

#### Scenario: Stamp failure drives STAMP_FAILED

- **WHEN** stamping fails (procurement or composition error) before the provider call
- **THEN** the request transitions to `STAMP_FAILED`, the provider is not called, and the
  state is terminal

#### Scenario: Unparseable uploaded draft drives STAMP_FAILED, not a 500

- **WHEN** the stored draft is unparseable by the compositor (encrypted, corrupt, or
  zero-page)
- **THEN** stamping fails closed: the request transitions to `STAMP_FAILED`, the provider
  is not called, and the response is a mapped error (not an unmapped 500, hang, or OOM)

#### Scenario: Legal completion transition is accepted

- **WHEN** a signing request in `SIGN_REQUESTED` is driven to `SIGNED`, `FAILED`, or
  `EXPIRED`
- **THEN** the transition is applied and persisted

#### Scenario: Illegal transition is rejected

- **WHEN** a transition is attempted from a terminal state (e.g. `SIGNED → FAILED`, or
  `STAMP_FAILED → SIGN_REQUESTED`), or to a state not reachable from the current one
- **THEN** the state machine rejects it and the persisted status is unchanged

#### Scenario: Redundant terminal transition is idempotent

- **WHEN** a webhook drives a signing request to a terminal state it is already in
- **THEN** the operation is a no-op (the status and the persisted row are unchanged) and
  no error is raised

#### Scenario: Concurrent transitions do not corrupt state

- **WHEN** two verified webhooks for the same document id are processed concurrently
- **THEN** optimistic locking ensures only one transition is committed; the persisted
  status is a single legal terminal value, never an interleaved/corrupted one

#### Scenario: Request stays non-terminal until every invitee has signed

- **WHEN** the resolved per-invitee statuses show some invitees `SIGNED` and at least one
  still `PENDING`
- **THEN** no aggregate transition is applied and the request stays `SIGN_REQUESTED`

#### Scenario: All invitees signed drives SIGNED

- **WHEN** every invitee of a `SIGN_REQUESTED` request is resolved to `SIGNED`
- **THEN** the request transitions to `SIGNED`

#### Scenario: A single rejection drives FAILED regardless of others

- **WHEN** at least one invitee is `REJECTED` (even if others have `SIGNED`)
- **THEN** the request transitions to `FAILED`, taking precedence over any `EXPIRED`
  invitee

### Requirement: Inbound webhook is verified before any side effect

The system SHALL provide a single `POST /api/webhooks/esign` endpoint that accepts the
provider's completion callback for both the success and error channels and handles them
through one unified path. Because authoritative state is re-read from the Details API
(see the next requirement), the endpoint SHALL NOT branch on any untrusted body field
(e.g. a `webhookType` or status field) to decide the outcome — both channels run the
same verify → Details → FSM path. The provider message authentication code (`mac`) SHALL
be read from the
**request body** (not an HTTP header) and verified before any state change. The `mac`
is `HMAC-SHA1` computed over the document id using the configured webhook secret, and
the comparison SHALL be **constant-time** (no early-exit timing oracle). The endpoint
SHALL accept only a JSON request body (`application/json`) and SHALL bound the request
body size, rejecting an oversized body.

If verification fails (tampered or forged `mac`, or a `mac` over a different document
id), the system SHALL reject the request and SHALL NOT change any signing request. The
webhook body SHALL NOT be logged verbatim and SHALL NOT be echoed in any error response.

After a successful verification, the endpoint's response SHALL NOT reveal whether a
matching signing request exists (no existence oracle): a verified webhook SHALL return
the same acknowledgement regardless of whether its document id is known.

#### Scenario: Valid webhook is accepted

- **WHEN** a webhook arrives whose body `mac` is the correct `HMAC-SHA1` of its document
  id under the configured secret
- **THEN** verification passes and the system proceeds to resolve authoritative status

#### Scenario: Tampered or forged mac is rejected

- **WHEN** a webhook arrives whose `mac` does not match `HMAC-SHA1` of its document id
  (altered mac, or a mac computed for a different document id)
- **THEN** the system rejects the request and makes no change to any signing request

#### Scenario: Replay of a valid captured webhook is harmless

- **WHEN** a previously-valid webhook is replayed (same body and `mac`)
- **THEN** the system re-reads authoritative status and the transition is idempotent, so
  a replay produces no additional state change beyond the legitimate one

#### Scenario: Verified webhook for an unknown document id is acknowledged indistinguishably

- **WHEN** a verified webhook references a document id with no matching signing request
- **THEN** the system makes no state change and returns the same acknowledgement as for a
  known document id (no internal detail leaked, no existence oracle)

### Requirement: Authoritative status comes from the Details API, not the webhook body

Because the `mac` covers only the document id, the rest of the webhook payload SHALL be
treated as untrusted. After verifying the `mac`, the system SHALL call the provider
Details API to read the authoritative **per-invitee** statuses and SHALL drive the
signing request's FSM off the aggregation of those statuses (per the aggregation rule in
"Signing request owns the status FSM") — never off status/action fields in the webhook
body, and never off a single document-level status. The per-invitee event-to-status
mapping SHALL be: signer signed → `SIGNED`; signer rejected or certificate verification
failed → `REJECTED`; signer's invitation expired → `EXPIRED`; otherwise → `PENDING`. A
non-terminal aggregate result (not all signed, none rejected/expired) SHALL cause **no
FSM transition** (a safe no-op), not an error.

If the Details API call fails (unreachable, timeout, or error), the system SHALL
acknowledge the webhook without applying a transition, leaving completion to the
scheduled reconciliation job — it SHALL NOT return an error that induces unbounded
vendor redelivery. This Details-API-as-source-of-truth path SHALL be the same code path
reused by that reconciliation job for missed webhooks.

#### Scenario: Completion drives SIGNED off the Details API

- **WHEN** a verified webhook triggers a status read and the Details API reports every
  invitee has signed
- **THEN** the signing request transitions to `SIGNED`

#### Scenario: Rejection or certificate failure drives FAILED

- **WHEN** a verified webhook triggers a status read and the Details API reports any
  invitee rejected or certificate verification failed
- **THEN** the signing request transitions to `FAILED`

#### Scenario: Expiry drives EXPIRED

- **WHEN** a verified webhook triggers a status read and the Details API reports the
  document/invitation expired with no rejection
- **THEN** the signing request transitions to `EXPIRED`

#### Scenario: Partial signing is a safe no-op

- **WHEN** a verified webhook triggers a status read and the Details API reports some but
  not all invitees signed (none rejected/expired)
- **THEN** no FSM transition is applied and no error is raised

#### Scenario: Details API failure defers to reconciliation

- **WHEN** a verified webhook triggers a status read and the Details API call fails
- **THEN** the system acknowledges the webhook, applies no transition, and leaves
  completion to the reconciliation fallback (no error that forces vendor redelivery)

#### Scenario: Webhook body status is not trusted

- **WHEN** a verified webhook body claims a status that disagrees with the Details API
- **THEN** the system uses the Details API per-invitee statuses, not the body's claim

### Requirement: Provider specifics stay behind the EsignProvider seam

All Leegality-specific details SHALL live behind the `EsignProvider` interface in the
provider adapter package — the base URL, per-endpoint API version, `X-Auth-Token` auth,
request/response shapes, and `mac` algorithm. No code outside the `signing` module SHALL
reference the adapter package, and `ModularityTests` SHALL remain green.

`EsignProvider` SHALL expose webhook verification **without a transport-header
parameter** (`verifyWebhook(payload)`; the `mac` and document id are parsed from the body
by the adapter). Verification SHALL surface the **verified document id** to the caller
(present when the `mac` matches, absent on failure) so the controller can look up the
signing request without itself parsing the vendor payload. The provider request/response
value objects SHALL be vendor-neutral and multi-invitee: the create request carries a
list of invitees, and the create result carries the document id plus a per-invitee
signing URL, expiry, and the provider's per-invitee identifier (captured for later
correlation). The authoritative-status read SHALL return a vendor-neutral **per-invitee**
status view (each invitee's status plus a correlation token — the provider per-invitee id
when available, an ordinal otherwise — with non-terminal/`PENDING` representable), not a
single document-level status. Downloading the signed document and audit trail SHALL be
implemented: given a provider document id it SHALL return the signed PDF bytes and the
audit-trail bytes **each with its provider-declared content type** (default
`application/octet-stream` when absent), staying vendor-neutral.

#### Scenario: Module boundaries hold

- **WHEN** the module-boundary verification runs
- **THEN** no module outside `signing` references the provider adapter package and the
  verification passes

#### Scenario: Webhook verification needs no transport header

- **WHEN** the webhook is verified
- **THEN** verification uses only the payload (the `mac` is parsed from the body), with
  no HTTP-header signature parameter

#### Scenario: Status read returns per-invitee statuses

- **WHEN** the authoritative-status read is invoked for a document id
- **THEN** the provider returns a vendor-neutral per-invitee status view (one status per
  invitee), not a single collapsed document status

#### Scenario: Signed-document download returns the signed artifact

- **WHEN** the signed-document download is invoked for a completed document id
- **THEN** the provider returns the signed PDF bytes and the audit-trail bytes (no
  not-yet-supported error)

### Requirement: Secrets, config, and PII handling

The provider base URL, auth token, and webhook secret SHALL come from environment
variables only; the dashboard `profileId` SHALL be non-secret configuration. The system
SHALL NOT log Aadhaar numbers, OTPs, virtual IDs, signer PII, signing URLs, or webhook
payloads verbatim — identifiers SHALL be redacted before logging. A signing URL SHALL be
treated as a bearer capability (it grants access to the signing page) and SHALL NOT
appear in logs. Tests and local development SHALL NOT require real Aadhaar/OTP or live
provider credentials (sandbox + dummy data only).

#### Scenario: Sensitive values are redacted in logs

- **WHEN** the system logs around create, status read, or webhook handling
- **THEN** signing URLs, signer PII, and the webhook payload do not appear verbatim;
  only redacted identifiers are logged

#### Scenario: Tests run without live credentials

- **WHEN** the test suite runs with no real provider credentials configured
- **THEN** every signing-request test passes against a stubbed provider, requiring no
  real Aadhaar/OTP

### Requirement: Signing-request schema is Flyway-managed

The `signing_request` table and its per-signer signing-URL child table SHALL be created
by forward-only Flyway migrations; the JPA mapping SHALL match the migrated schema so the
application boots under `ddl-auto: validate`. The `signing_request` table SHALL carry a
foreign key to `agreement`, a unique constraint on the provider document id, and a
version column for optimistic locking; its `status` column SHALL have an explicit,
bounded type sized for the status vocabulary. The per-signer child table SHALL carry a
foreign key to `signer` (not just an email string).

Signing-completion state SHALL be added by a new forward-only migration
(`V4__signing_completion.sql`) that SHALL NOT edit any existing migration: the per-signer
child table SHALL gain a bounded per-invitee `status` column and a `signing_order`
ordinal, and the `signing_request` table SHALL gain `signed_pdf_key` and
`audit_trail_key` columns that hold **object-storage keys** (never artifact bytes). The
new columns SHALL be nullable so existing rows validate.

#### Scenario: Application boots against the migrated schema

- **WHEN** the application starts against a database where the Flyway migrations
  (including `V4`) have been applied
- **THEN** Hibernate schema validation passes and the context starts

#### Scenario: V4 does not edit an applied migration

- **WHEN** the signing-completion schema change is introduced
- **THEN** it ships as a new `V4__signing_completion.sql` and V1–V3 are left unchanged

### Requirement: A signing request requires a reachable contact for every party

The system SHALL verify that **every** party on the agreement has **at least one** reachable
contact (email or mobile) before a signing request is created, because a party's email and
mobile are optional when an agreement is drafted (per `agreement-management`).
When any party has neither, `POST /api/signing/{id}/request` SHALL respond `409 Conflict`
and SHALL NOT create a signing request, procure a stamp, or call the eSign provider. This
check SHALL occur before any state transition, alongside the existing "draft PDF present"
precondition. When a party has both an email and a mobile, the eSign invite SHALL be
addressed to both channels. (Added by archiving change `rich-agreement-capture`.)

#### Scenario: Signing is blocked when a party has no contact

- **WHEN** a client requests a signing for an agreement on which at least one party has no
  email or mobile
- **THEN** the system responds `409 Conflict`, creates no signing request, procures no
  stamp, and makes no provider call

#### Scenario: Signing proceeds when every party has at least one contact

- **WHEN** every party on the agreement has an email or a mobile (or both) and a draft PDF
  is present
- **THEN** the signing-request precondition passes and the existing flow proceeds
  (`PDF_GENERATED` and onward) unchanged, addressing each invite to every channel the party
  provided

### Requirement: Map rendered eSign anchors to provider signature fields

When creating a signing request, the signing module SHALL derive each signer's `esign:<role>` anchor
from its role -- the same deterministic token the renderer emits at each signature zone -- and map it
to the eSign provider's signature field for that signer. The mapping SHALL keep the `documents` module
eSign-agnostic (the anchor is a plain role key, not a provider type; `signing` holds no
`documents.template` type) and SHALL NOT introduce a new signing-status FSM state -- anchors are
produced at `PDF_GENERATED` (generate-as-draft) and consumed at `SIGN_REQUESTED`
(create-signing-request).

#### Scenario: Anchors mapped to signature fields

- **GIVEN** a drafted two-party agreement with an Owner and a Tenant (whose rendered PDF carries the
  `esign:owner` and `esign:tenant` zones)
- **WHEN** a signing request is created
- **THEN** the provider request contains one signature field for the Owner and one for the Tenant,
  each bound to its `esign:<role>` anchor
- **AND** the signing-status transition is the existing `SIGN_REQUESTED` (no new state)

#### Scenario: Exercised against the stub provider, no live credentials

- **GIVEN** the sandbox stub / WireMock eSign provider
- **WHEN** a signing request is created for a two-party agreement
- **THEN** the anchor -> field mapping succeeds without any live vendor credential
- **AND** no Aadhaar / OTP / VID is logged

#### Scenario: No anchors -> clear failure, nothing submitted

- **GIVEN** an agreement whose signer set yields no eSign anchors (no signers -- nothing signable)
- **WHEN** a signing request is attempted
- **THEN** it fails clearly before any provider call and no partial request is submitted to the
  provider

### Requirement: An attached stamp is a precondition of the provider call

`createSignRequest` SHALL require that a stamp is **already attached** to the agreement before
the eSign provider is called. The system SHALL NOT procure, generate, or composite a stamp as
part of the signing-request flow.

If the agreement has **no stamp attached** (its stamp info is empty), the request SHALL be
rejected with `409` before any signing-request row is persisted and before any provider call.
The rejection SHALL be distinguishable from other `409` conditions (such as a missing draft or
an uncontactable party) so an operator can tell why signing could not start.

If the agreement **has a stamp attached**, the system SHALL submit the **stamped PDF** to the
provider - never the bare draft - and persist the `SIGN_REQUESTED` transition after the
provider call. The stamp itself is not re-composited at signing time.

#### Scenario: Signing without a stamp is refused

- **WHEN** `createSignRequest` runs for an agreement whose stamp info is empty
- **THEN** the request is rejected with `409`, no signing-request row is persisted, and the
  provider is not called

#### Scenario: The stamped PDF is what reaches the provider

- **WHEN** `createSignRequest` runs for an agreement with an attached stamp
- **THEN** the document submitted to the provider is the stored stamped PDF, not the draft

#### Scenario: Missing-stamp rejection is distinguishable

- **WHEN** signing is refused because no stamp is attached
- **THEN** the error identifies the missing stamp as the cause, distinctly from a missing
  draft or an uncontactable party

### Requirement: The order is placed when the customer finalises, and the draft freezes then

The customer's involvement SHALL end when they **finalise** the agreement. At that point the
system SHALL place the order: it SHALL create the signing request in `PDF_GENERATED`, freeze the
agreement's terms against further editing, and surface the agreement's tracking reference to the
customer.

Freezing SHALL happen at **finalisation**, not at stamp upload. The document that staff stamp and
that the parties sign SHALL be the document the customer finalised; it SHALL NOT be editable in
the window between finalisation and stamp intake.

After finalising, the customer SHALL have nothing further to do until they are invited to sign.
Stamp procurement and intake are staff work and SHALL NOT require customer action.

Where the payment gate is enforced (per `payment-gate`), order placement SHALL follow payment
confirmation. While the gate is permissive, finalisation alone SHALL place the order.

#### Scenario: Finalising places the order and freezes the draft

- **WHEN** a customer finalises their agreement
- **THEN** a signing request is created in `PDF_GENERATED`, the agreement is no longer editable,
  and the tracking reference is available to the customer

#### Scenario: The draft cannot change between finalisation and stamping

- **WHEN** an edit is attempted after finalisation but before a stamp is uploaded
- **THEN** the edit is refused and the finalised document is unchanged

#### Scenario: The customer has nothing to do while staff stamp

- **WHEN** an agreement is awaiting stamp intake
- **THEN** no customer action is required or requested until signing begins

### Requirement: PDF_GENERATED is the durable awaiting-stamp state

`PDF_GENERATED` SHALL be a **durable, long-lived** state, not a momentary pre-request step. A
signing request SHALL rest in `PDF_GENERATED` for as long as it takes staff to purchase the
e-stamp out-of-band and upload it - potentially hours or days.

The transition `PDF_GENERATED -> STAMPED` SHALL be driven by a **staff stamp upload** (per
`estamp-intake`), not by the signing-request flow. The transition `PDF_GENERATED ->
STAMP_FAILED` SHALL be driven by an upload that is accepted for processing but whose
composition fails. A rejected upload that never reaches composition (bad role, bad file,
duplicate certificate) SHALL leave the request in `PDF_GENERATED` so staff can retry.

Because `PDF_GENERATED` is now durable, an agreement resting in it SHALL NOT be treated as an
orphan or reaped by any reconciliation or cleanup process.

#### Scenario: A request rests in PDF_GENERATED awaiting the stamp

- **WHEN** an agreement's instrument has been generated but no stamp has been uploaded
- **THEN** the signing request remains in `PDF_GENERATED` indefinitely and is not failed,
  expired, or reaped

#### Scenario: Staff upload drives the STAMPED transition

- **WHEN** a staff user successfully uploads a stamp for a request in `PDF_GENERATED`
- **THEN** the request transitions to `STAMPED`

#### Scenario: A rejected upload leaves the state unchanged

- **WHEN** a stamp upload is rejected before composition (unauthorized caller, invalid image,
  or duplicate certificate number)
- **THEN** the request remains in `PDF_GENERATED` and staff can retry

#### Scenario: A failed composition drives STAMP_FAILED

- **WHEN** an accepted upload fails during composition
- **THEN** the request transitions to `STAMP_FAILED` and the provider is not called

### Requirement: An order is placed only for an eligible duty jurisdiction

Finalising SHALL refuse an agreement whose duty jurisdiction is not eligible for paid
fulfilment. The check SHALL run **before the agreement is frozen and before any signing
request is created**, so an ineligible agreement never reaches the durable awaiting-stamp
state and never appears in the staff stamp queue.

This is deliberately one gate among several rather than a substitute for the others.
Finalise and checkout are separately reachable endpoints, and finalise is what commits the
order to staff, so each enforces the rule independently.

This adds a **precondition** to the existing transition into the awaiting-stamp state; it
introduces no new signing status and changes no existing transition. Finalise SHALL remain
idempotent for an eligible jurisdiction.

The refusal SHALL use the distinct unsupported-jurisdiction error kind.

#### Scenario: Finalise is refused for an ineligible jurisdiction

- **WHEN** finalise is called for an agreement whose duty jurisdiction is not eligible
- **THEN** the response is `409` with the unsupported-jurisdiction problem type
- **AND** no signing request is created
- **AND** the agreement's terms are not frozen
- **AND** the agreement does not appear in the staff stamp queue

#### Scenario: Finalise is refused for an agreement with no pinned template

- **WHEN** finalise is called for an agreement that has no selected template
- **THEN** the response is `409` with the unsupported-jurisdiction problem type
- **AND** no signing request is created

#### Scenario: Finalise proceeds for an eligible jurisdiction

- **WHEN** finalise is called for an agreement whose duty jurisdiction is eligible and which
  satisfies every existing precondition
- **THEN** the order is placed and the draft freezes exactly as before this change
- **AND** finalising again remains idempotent

### Requirement: A billable eSign transaction requires an eligible duty jurisdiction

Initiating an eSign request SHALL refuse an agreement whose duty jurisdiction is not
eligible for paid fulfilment, and SHALL do so **before any call to the eSign provider**, so
no billable vendor transaction is incurred for an agreement we have no defined way to stamp.

This gate SHALL be independent of the payment gate rather than implied by it. Payment state
can be satisfied by a staff waiver, so a paid-or-waived agreement is not thereby a
fulfillable one; the two questions are separate and are asked separately.

The refusal SHALL use the distinct unsupported-jurisdiction error kind, so it is
distinguishable from a payment-required or stamp-required refusal at the same step.

#### Scenario: eSign initiation is refused for an ineligible jurisdiction

- **WHEN** an eSign request is initiated for an agreement whose duty jurisdiction is not
  eligible
- **THEN** the response is `409` with the unsupported-jurisdiction problem type
- **AND** no call is made to the eSign provider
- **AND** no signing status transition occurs

#### Scenario: A waived payment does not satisfy the jurisdiction requirement

- **WHEN** an eSign request is initiated for an agreement whose payment has been waived and
  whose duty jurisdiction is not eligible
- **THEN** the response is `409` with the unsupported-jurisdiction problem type
- **AND** no call is made to the eSign provider

#### Scenario: eSign initiation proceeds for an eligible jurisdiction

- **WHEN** an eSign request is initiated for an agreement whose duty jurisdiction is
  eligible and which satisfies every existing precondition
- **THEN** initiation proceeds exactly as before this change
