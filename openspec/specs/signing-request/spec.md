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

### Requirement: Auto-stamp before the provider call

`createSignRequest` SHALL stamp the agreement's instrument automatically as part of the
signing-request flow, with **no separate endpoint** and no client involvement. After the
pre-request persist and before the provider call, the system SHALL ensure a stamp is
attached: if the agreement has **no stamp yet** (its stamp info is empty), the system
SHALL procure one through the `StampProvider`, store the stamped PDF, populate the
agreement's stamp info, and transition the request to `STAMPED`; if the agreement
**already has a stamp** (its stamp info is populated), the system SHALL **reuse** the
existing stamped PDF and serial without re-procuring or re-compositing (idempotent), and
still transition to `STAMPED`. The document submitted to the provider SHALL always be the
stamped PDF.

Populating the agreement's stamp info and persisting the `STAMPED` transition SHALL run
as short database transactions **before** the outbound provider HTTP round-trip; no
transaction SHALL span the provider call (D9 discipline).

> Note (v1, documented): under the current lock-forever model (exactly one signing
> request per agreement) the reuse branch is **dormant** — stamp info is always empty on
> the first and only request, so a fresh stamp is always procured. The reuse-vs-rebuy
> legal decision for a future revision/supersede flow is deferred to that change plus
> legal input; v1 defaults the dormant branch to safe reuse.

#### Scenario: First signing request procures and attaches a fresh stamp

- **WHEN** `createSignRequest` runs for an agreement whose stamp info is empty
- **THEN** the system procures a stamp via `StampProvider`, stores the stamped PDF,
  populates the agreement's stamp info, transitions the request to `STAMPED`, and submits
  the stamped PDF to the provider

#### Scenario: Already-stamped agreement is not re-stamped (forward-looking; unit-level)

- **WHEN** `createSignRequest` runs for an agreement whose stamp info is already populated
- **THEN** the system reuses the existing stamped PDF and serial (no new procurement or
  composition) and still transitions to `STAMPED`
- **NOTE** under v1's CR-5 lock-forever model (one signing request per agreement) this
  state is unreachable via the live flow — stamp info is always empty on the only request.
  This scenario is therefore verified at the **unit level** by constructing the
  populated-stamp-info state directly; it documents the dormant reuse default that the
  future supersede flow will exercise.

#### Scenario: No transaction spans the provider call

- **WHEN** the stamp info is populated and the `STAMPED` transition persisted
- **THEN** those writes commit in short transactions before the provider HTTP call, and
  no open transaction is held across the provider round-trip

