## MODIFIED Requirements

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
(anti-mass-assignment). The bytes SHALL be loaded from the agreement's **stored draft**
in object storage (the `draftPdfKey` set by the draft-ingestion flow). The system SHALL
require that a draft has been uploaded: when the agreement has **no stored draft**, the
system SHALL respond `409 Conflict` as RFC 9457 `application/problem+json` and SHALL NOT
persist a signing request or call the provider. The system SHALL NOT fall back to a
placeholder document. The endpoint SHALL bound the request body size and reject an
oversized body before doing provider work.

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
  the provider call, calls the provider once, and updates that row to `SIGN_REQUESTED`
  with the provider document id and a signing URL plus expiry per signer
- **AND** the system responds `201 Created` with the document id and the per-signer
  signing URLs

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
