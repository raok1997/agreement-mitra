# draft-ingestion Specification

## Purpose
TBD - created by archiving change draft-ingestion. Update Purpose after archive.
## Requirements
### Requirement: Upload a draft agreement PDF

The system SHALL provide `POST /api/agreements/{id}/draft` that accepts a
`multipart/form-data` body carrying exactly one PDF part and stores it as the signable
draft for an existing agreement. On success it SHALL respond `200 OK` (or `204 No
Content`) and SHALL NOT return the stored bytes.

The endpoint SHALL operate on an **existing** agreement: for an unknown `{id}` it SHALL
respond `404 Not Found` as RFC 9457 `application/problem+json` (per `api-error-handling`);
a syntactically invalid id (not a UUID) SHALL respond `400 Bad Request` as problem+json,
not 404.

The uploaded bytes SHALL be treated as **untrusted**: the system SHALL NOT parse, render,
or execute them. The bytes SHALL NOT be written to Postgres and SHALL NOT be logged
(verbatim or otherwise).

#### Scenario: Valid PDF is accepted and stored

- **WHEN** a client uploads a valid PDF part for an existing agreement
- **THEN** the system stores the raw bytes in object storage and responds with success
- **AND** the response body does not include the stored bytes

#### Scenario: Unknown agreement id returns 404

- **WHEN** a client uploads a draft for an agreement id that does not exist
- **THEN** the system responds `404 Not Found` as `application/problem+json` and stores
  nothing

#### Scenario: Non-UUID agreement id returns 400

- **WHEN** a client uploads a draft to `/api/agreements/not-a-uuid/draft`
- **THEN** the system responds `400 Bad Request` as `application/problem+json`, not 404

### Requirement: Draft upload is validated by content and size

The system SHALL reject an upload that is not a PDF or exceeds the size ceiling, with
`400 Bad Request` as RFC 9457 `application/problem+json`, and SHALL store nothing.

PDF-ness SHALL be determined by the file's **magic bytes** (the leading `%PDF-`
signature), not solely by the client-declared `Content-Type` (which is untrusted). A part
shorter than the signature (1–4 non-empty bytes) SHALL be rejected as non-PDF, not error.
An empty part SHALL be rejected. The request SHALL carry exactly one file part; additional
or unexpected parts SHALL be rejected. The per-upload size ceiling SHALL be **10 MiB**,
enforced independently of the existing JSON request-body size guard, and an oversized
upload SHALL be rejected before the bytes are stored.

The client-declared part `Content-Type` and the client-supplied **filename** SHALL NOT be
trusted, reflected in any response, or logged.

#### Scenario: Non-PDF content is rejected by magic bytes

- **WHEN** a client uploads a part whose bytes do not begin with the `%PDF-` signature
  (even if it declares `Content-Type: application/pdf`)
- **THEN** the system responds `400 Bad Request` as `application/problem+json` and stores
  nothing

#### Scenario: Empty or sub-signature upload is rejected

- **WHEN** a client uploads an empty part, or a non-empty part shorter than the `%PDF-`
  signature
- **THEN** the system responds `400 Bad Request` as `application/problem+json` and stores
  nothing, without an unhandled error

#### Scenario: Oversized upload is rejected before storage

- **WHEN** a client uploads a PDF exceeding the 10 MiB ceiling
- **THEN** the system responds `400 Bad Request` (problem+json) and stores nothing,
  rejecting before writing to object storage

### Requirement: Draft bytes are stored in object storage, not Postgres

The raw draft bytes SHALL be stored in object storage (MinIO local / S3-compatible prod)
through the existing `BlobStore` seam, under a **deterministic key derived from the
canonical parsed-UUID agreement id** (`drafts/{agreementId}.pdf`). The key SHALL be built
from the bound `UUID`, never from the raw request path segment, so a crafted id cannot
produce a traversal or arbitrary-key write (a non-UUID id is already rejected `400`). The
stored object's content type SHALL be a **server-set constant** (`application/pdf`), never
the client-declared value. Postgres SHALL hold only the storage **key** reference on the
agreement, never the bytes. Re-uploading SHALL overwrite the object at the same key.

#### Scenario: Bytes go to object storage and the key is referenced on the agreement

- **WHEN** a valid draft is uploaded for an agreement
- **THEN** the bytes are written to object storage under the agreement's deterministic
  draft key
- **AND** the agreement persists the storage key (not the bytes) so the draft can be
  retrieved by the signing flow

### Requirement: Draft is finalized once a signing request is created

A draft SHALL be **replaceable by overwrite** while the agreement has **no signing
request**. Once **any** signing request has been created for the agreement, a further
upload SHALL be **rejected** with `409 Conflict` as RFC 9457 `application/problem+json`
(distinct `type` URN from the no-draft-on-signing 409) and SHALL NOT overwrite the stored
draft. The draft is then **finalized** — this holds regardless of the signing request's
state, including terminal `FAILED`/`EXPIRED`. eSign incurs provider cost, so a
failed-integration retry is the provider's responsibility on the existing request, not a
free re-upload; revising a finalized draft after signing is requested is a separate,
paid flow deferred to a future feature (see `docs/future-features/`).

#### Scenario: Re-upload before any signing request overwrites the draft

- **WHEN** a client uploads a new draft for an agreement that has no signing request
- **THEN** the system overwrites the stored draft and responds with success

#### Scenario: Re-upload after a signing request exists is rejected

- **WHEN** a client uploads a draft for an agreement that already has a signing request,
  in any state (including terminal `FAILED`/`EXPIRED`)
- **THEN** the system responds `409 Conflict` as `application/problem+json` and leaves the
  stored draft unchanged

