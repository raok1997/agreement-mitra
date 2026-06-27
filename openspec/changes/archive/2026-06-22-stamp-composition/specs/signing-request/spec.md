## MODIFIED Requirements

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

## ADDED Requirements

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
