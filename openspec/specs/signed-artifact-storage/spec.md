# signed-artifact-storage Specification

## Purpose
TBD - created by archiving change signing-completion. Update Purpose after archive.
## Requirements
### Requirement: Signed artifacts are persisted to object storage on completion

When a signing request reaches `SIGNED`, the system SHALL download the signed PDF and the
audit trail from the provider and persist them to object storage, recording only the
object **keys** on the `signing_request` row. The artifact **bytes** SHALL be written to
object storage only — never to Postgres and never to logs. The keys (and only the keys)
MAY be persisted and logged, as they are non-sensitive.

The download SHALL be performed as a network call **outside any database transaction**,
reusing the persist-intent / call-network / persist-result split (the signed-completion
status is persisted first; the artifact fetch and key persistence follow). Artifact
presence SHALL be **decoupled** from the terminal status: if the download or store fails,
the request remains `SIGNED` with null artifact keys and recovery is left to the
reconciliation fallback — the terminal transition SHALL NOT be blocked or reverted by a
storage failure.

The audit trail SHALL be stored as an **opaque blob** with the provider-declared content
type; it SHALL NOT be parsed into application state nor logged (it may carry masked
identifiers / eSign evidence). The signed PDF and the audit trail SHALL be persisted as an
**atomic pair**: both object keys SHALL be recorded in the same transaction, so a partial
store (one artifact persisted, the other not) SHALL NOT leave a half-recorded row — on any
failure both keys remain null and recovery is left to reconciliation.

#### Scenario: Signed PDF and audit trail are stored on SIGNED

- **WHEN** a signing request transitions to `SIGNED`
- **THEN** the system downloads the signed PDF and audit trail, writes both to object
  storage, and records their object keys on the `signing_request` row

#### Scenario: Artifact bytes never touch Postgres or logs

- **WHEN** signed artifacts are persisted
- **THEN** only the object keys are stored in Postgres and the bytes appear only in object
  storage, with no artifact bytes written to the database or to any log

#### Scenario: Download outside the FSM transaction

- **WHEN** the SIGNED transition triggers the artifact download
- **THEN** the provider download call is made outside any open database transaction (no
  transaction spans the outbound round-trip)

### Requirement: Artifact storage is idempotent under re-delivery

A re-delivered or replayed `SIGNED` webhook (or a reconciliation re-scan) SHALL NOT create
duplicate artifacts and SHALL NOT corrupt state. Object keys SHALL be **deterministic**,
derived from the **internal signing-request id** (an application-generated UUID) — never
from the vendor-controlled provider document id and never from the agreement id — so a
repeated store overwrites the same key with the same bytes rather than creating a second
object. The fetch+store SHALL be **skipped when the artifact keys are already recorded**,
and concurrent key persistence SHALL be made safe by the existing optimistic-locking guard.
Under concurrency the skip-check and the store are not atomic, so a redundant re-download
MAY occur; this SHALL remain harmless (same deterministic key, same bytes, single winning
key-persist) — the guarantee is no duplicate object and no corrupt state, not the absence
of a redundant fetch. A genuine download/storage failure SHALL remain distinguishable from
a benign optimistic-lock loss (the former leaves null keys for reconciliation).

#### Scenario: Re-delivered SIGNED webhook does not duplicate artifacts

- **WHEN** a `SIGNED` webhook for a request whose artifacts are already stored is
  delivered again
- **THEN** the system does not re-download and does not create a second object; the
  recorded keys are unchanged

#### Scenario: Failed download is recoverable, not lost

- **WHEN** the SIGNED transition occurs but the artifact download fails
- **THEN** the request stays `SIGNED` with null artifact keys and the artifacts can be
  fetched later by the reconciliation fallback

#### Scenario: Object keys are derived only from the internal request id

- **WHEN** object keys are computed for an artifact
- **THEN** they are derived from the internal signing-request UUID, not the provider
  document id (key-injection surface) nor the agreement id (would overwrite a prior
  re-request's artifact)

### Requirement: Object storage is reached through a vendor-neutral seam

All object-storage specifics (endpoint, bucket, credentials, S3/MinIO client) SHALL live
behind a vendor-neutral `BlobStore` seam in the `signing` module — mirroring the
`EsignProvider` pattern. No code outside the `signing` module SHALL reference the storage
adapter, and `ModularityTests` SHALL remain green. The storage endpoint, bucket, and
credentials SHALL come from environment configuration only (sandbox MinIO locally); no
credentials SHALL be committed. Tests SHALL exercise the seam against a container-backed
store (Testcontainers MinIO) without requiring live cloud credentials.

#### Scenario: Storage specifics stay behind the seam

- **WHEN** the module-boundary verification runs
- **THEN** no module outside `signing` references the storage adapter and the verification
  passes

#### Scenario: Storage round-trip works against a container-backed store

- **WHEN** the integration test stores and reads back an artifact via the `BlobStore`
  against a Testcontainers MinIO
- **THEN** the bytes round-trip intact, requiring no live cloud credentials

### Requirement: Artifact retrieval and the bucket are not externally exposed

The object-storage bucket holding signed artifacts SHALL be **private** (no public-read);
the adapter SHALL ensure the bucket exists (create-if-absent) so neither local boot nor
the test depends on out-of-band bootstrap. When the provider returns artifact bytes via a
provider-controlled **URL**, the adapter SHALL **host-pin / allowlist** that URL to the
configured provider domain before issuing the outbound fetch — it SHALL NOT fetch an
arbitrary URL taken from a payload (SSRF guard). The `BlobStore` seam SHALL take a
document/request identifier, not a caller-supplied URL. Production object storage hardening
(encryption-at-rest, block-public-access policy) is a documented deferred requirement;
within this repo sandbox MinIO with a private bucket SHALL be used.

#### Scenario: Bucket is private and self-bootstrapping

- **WHEN** the application or the integration test starts against object storage
- **THEN** the artifact bucket exists (created if absent) and is not publicly readable

#### Scenario: Provider artifact URL is host-pinned

- **WHEN** the provider supplies an artifact location as a URL
- **THEN** the adapter fetches it only if its host matches the configured provider domain,
  rejecting any other host (no arbitrary outbound fetch)

