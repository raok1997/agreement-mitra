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
call the provider, then update the same row to `SIGN_REQUESTED` with the returned
document id and per-signer URLs. The provider call SHALL NOT be made while holding an
open database transaction (no transaction spans the outbound HTTP round-trip).

The unsigned PDF bytes SHALL be **server-sourced**, not a client-settable field
(anti-mass-assignment); for this capability they may be a fixed dummy fixture. The
endpoint SHALL bound the request body size and reject an oversized body before doing
provider work.

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
  multi-party agreement
- **THEN** the system persists a signing-request row before the provider call, calls the
  provider once, and updates that row to `SIGN_REQUESTED` with the provider document id
  and a signing URL plus expiry per signer
- **AND** the system responds `201 Created` with the document id and the per-signer
  signing URLs

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
machine. For this capability the lifecycle is `SIGN_REQUESTED → SIGNED | FAILED |
EXPIRED`. State changes SHALL go through the aggregate's state-machine method, not
ad-hoc setters. `SIGNED`, `FAILED`, and `EXPIRED` are terminal: a transition out of a
terminal state SHALL be rejected. The `Agreement` aggregate SHALL remain status-less —
the signing status lives only on the signing request.

Concurrent transition attempts (e.g. webhook delivered more than once or to more than
one instance at the same time) SHALL be made safe by optimistic locking (a version
column): at most one of two racing transitions SHALL win and the other SHALL be retried
or rejected without corrupting state.

#### Scenario: Legal completion transition is accepted

- **WHEN** a signing request in `SIGN_REQUESTED` is driven to `SIGNED`, `FAILED`, or
  `EXPIRED`
- **THEN** the transition is applied and persisted

#### Scenario: Illegal transition is rejected

- **WHEN** a transition is attempted from a terminal state (e.g. `SIGNED → FAILED`), or
  to a state not reachable from the current one
- **THEN** the state machine rejects it and the persisted status is unchanged

#### Scenario: Redundant terminal transition is idempotent

- **WHEN** a webhook drives a signing request to a terminal state it is already in
- **THEN** the operation is a no-op (the status and the persisted row are unchanged) and
  no error is raised

#### Scenario: Concurrent transitions do not corrupt state

- **WHEN** two verified webhooks for the same document id are processed concurrently
- **THEN** optimistic locking ensures only one transition is committed; the persisted
  status is a single legal terminal value, never an interleaved/corrupted one

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
Details API to read the authoritative document status and SHALL drive the signing
request's FSM off that status — never off status/action fields in the webhook body. The
event-to-status mapping SHALL be: document completed / signer signed → `SIGNED`; signer
rejected or certificate verification failed → `FAILED`; document expired → `EXPIRED`. A
non-terminal authoritative status (e.g. still sent/in-flight) SHALL cause **no FSM
transition** (a safe no-op), not an error.

If the Details API call fails (unreachable, timeout, or error), the system SHALL
acknowledge the webhook without applying a transition, leaving completion to the future
scheduled reconciliation job — it SHALL NOT return an error that induces unbounded
vendor redelivery. This Details-API-as-source-of-truth path SHALL be the same code path
reused by that reconciliation job for missed webhooks.

#### Scenario: Completion drives SIGNED off the Details API

- **WHEN** a verified webhook triggers a status read and the Details API reports the
  document is completed
- **THEN** the signing request transitions to `SIGNED`

#### Scenario: Rejection or certificate failure drives FAILED

- **WHEN** a verified webhook triggers a status read and the authoritative status
  indicates the signer rejected or certificate verification failed
- **THEN** the signing request transitions to `FAILED`

#### Scenario: Expiry drives EXPIRED

- **WHEN** a verified webhook triggers a status read and the authoritative status
  indicates the document expired
- **THEN** the signing request transitions to `EXPIRED`

#### Scenario: Non-terminal authoritative status is a safe no-op

- **WHEN** a verified webhook triggers a status read and the Details API reports a
  non-terminal status (still in flight)
- **THEN** no FSM transition is applied and no error is raised

#### Scenario: Details API failure defers to reconciliation

- **WHEN** a verified webhook triggers a status read and the Details API call fails
- **THEN** the system acknowledges the webhook, applies no transition, and leaves
  completion to the reconciliation fallback (no error that forces vendor redelivery)

#### Scenario: Webhook body status is not trusted

- **WHEN** a verified webhook body claims a status that disagrees with the Details API
- **THEN** the system uses the Details API status, not the body's claim

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
value objects SHALL be vendor-neutral and
multi-invitee: the create request carries a list of invitees, and the create result
carries the document id plus a per-invitee signing URL and expiry. `getStatus` SHALL
return the vendor-neutral authoritative status (with non-terminal statuses
representable). Downloading the signed document and audit trail SHALL remain
unimplemented in this capability (an explicit not-yet-supported stub).

#### Scenario: Module boundaries hold

- **WHEN** the module-boundary verification runs
- **THEN** no module outside `signing` references the provider adapter package and the
  verification passes

#### Scenario: Webhook verification needs no transport header

- **WHEN** the webhook is verified
- **THEN** verification uses only the payload (the `mac` is parsed from the body), with
  no HTTP-header signature parameter

#### Scenario: Signed-document download is not yet supported

- **WHEN** the signed-document download is invoked
- **THEN** the provider reports the operation is not yet supported (no signed bytes are
  fetched in this capability)

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
by a forward-only Flyway migration (`V3__signing_request.sql`); the JPA mapping SHALL
match the migrated schema so the application boots under `ddl-auto: validate`. The
`signing_request` table SHALL carry a foreign key to `agreement`, a unique constraint on
the provider document id, and a version column for optimistic locking; its `status`
column SHALL have an explicit, bounded type sized for the status vocabulary. The
per-signer child table SHALL carry a foreign key to `signer` (not just an email string).
The migration SHALL NOT edit any existing migration.

#### Scenario: Application boots against the migrated schema

- **WHEN** the application starts against a database where the Flyway migrations have
  been applied
- **THEN** Hibernate schema validation passes and the context starts
