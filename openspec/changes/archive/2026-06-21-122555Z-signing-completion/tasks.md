# Tasks — signing-completion (CR-4)

> Deferred (blocker): the **live Leegality sandbox e2e happy-path** is NOT run in this
> CR — sandbox credentials are not yet obtained. All work is built and tested stub-only
> (stub `EsignProvider` + Testcontainers Postgres/MinIO). The live happy-path is recorded
> as a deferred manual-test item, as CR-3 did.

## 1. Schema (V4 migration)

- [x] 1.1 Add `V4__signing_completion.sql` (forward-only; do not edit V1–V3): on
  `signing_request_invitee` add `status` (bounded varchar, nullable), `signing_order`
  (smallint, nullable), and `provider_invitee_id` (varchar, nullable), plus
  `UNIQUE (signing_request_id, signing_order)`; on `signing_request` add `signed_pdf_key`
  and `audit_trail_key` (text, nullable, object-storage **keys** not bytes).
- [x] 1.2 Add index `signing_request (status, created_at)` for the reconciliation scan.
- [x] 1.3 Confirm the migration applies and `ddl-auto: validate` still boots (covered by
  the persistence integration test in §8).

## 2. Per-invitee status on the EsignProvider seam

- [x] 2.1 Add a vendor-neutral `InviteeStatus` enum (`PENDING`, `SIGNED`, `REJECTED`,
  `EXPIRED`) — a distinct type from the aggregate `SignatureStatus` (no cross-assignment).
- [x] 2.2 Change `EsignProvider.getStatus` to return a per-invitee status view
  (`DocumentStatusView(List<InviteeStatusView>)`, each carrying a correlation token —
  provider per-invitee id when available, ordinal fallback — plus `InviteeStatus`) instead
  of a single `SignatureStatus`.
- [x] 2.3 Capture the provider's per-invitee identifier in `createSignRequest`'s result
  (CR-3 currently discards it) so it can be persisted at create for later correlation.
- [x] 2.4 Update `LeegalityEsignProvider.getStatus` to parse Details per-invitee statuses +
  ids and map each to `InviteeStatus`; keep vendor field shapes private; redact doc ids.

## 3. Domain: per-invitee status + aggregation

- [x] 3.1 Add `status` (default `PENDING`), `signingOrder`, and `providerInviteeId` to
  `SigningRequestInvitee`; populate `signingOrder` + `providerInviteeId` at create in
  `SigningRequestService.create` (provider-response order / captured id).
- [x] 3.2 Add a mutation method on the `SigningRequest` aggregate
  (`applyInviteeStatuses(view)`) that correlates by provider id (ordinal fallback), updates
  each child row's `status`, then runs the aggregation and transitions via the
  state-machine method (no ad-hoc setters; invitee list stays unmodifiable to callers).
- [x] 3.3 Implement the aggregation rule: any `REJECTED` → `FAILED`; else any `EXPIRED` →
  `EXPIRED`; else all `SIGNED` → `SIGNED`; else no-op. The terminal decision is computed
  from per-invitee status **counts** (correlation-independent); idempotent terminals.

## 4. BlobStore seam + MinIO adapter

- [x] 4.1 Add a vendor-neutral `BlobStore` interface in the `signing` module
  (`put(key, bytes, contentType)`, `get(key)`); Modulith-internal.
- [x] 4.2 Add an S3/MinIO adapter: **path-style** addressing, **create-if-absent** private
  bucket on startup, endpoint/bucket/credentials from env config only; never log bytes.
- [x] 4.3 Add object-storage config properties (endpoint, bucket, access/secret keys) and
  wire the client bean.

## 5. Signed-artifact download

- [x] 5.1 Widen `SignedDocument` to carry each artifact's content type (default
  `application/octet-stream`); rename `providerRequestId` → `providerDocumentId`.
- [x] 5.2 Implement `LeegalityEsignProvider.download(documentId)` to fetch the signed PDF +
  audit-trail bytes with content types, replacing the `UnsupportedOperationException` stub.
  If the artifact location is a provider-returned URL, **host-pin/allowlist** it to the
  configured provider domain (SSRF guard) before fetching; redact ids in logs.

## 6. Completion path (download-on-SIGNED, idempotent, shared)

- [x] 6.1 Extract one completion method in `SigningRequestService` that both the webhook
  handler and the reconciliation job call; rewrite `handleWebhook` to use it.
- [x] 6.2 Reshape `SigningRequestPersistence.applyAuthoritativeStatus` to load the request
  **with its invitees**, call `applyInviteeStatuses` (§3.2), and commit the transition
  (tx_a) — replacing the single-`SignatureStatus` mapping.
- [x] 6.3 After tx_a, if `status == SIGNED` and `signed_pdf_key IS NULL`: call
  `download()` **outside any transaction** (the D4 control flow), then persist **both**
  artifact keys together in tx_b under the optimistic-lock guard.
- [x] 6.4 Object keys derive only from the internal `signing_request.id`
  (`signed/{id}.pdf`, `audit/{id}`) — never `providerDocumentId`/`agreementId`. Skip
  fetch+store when both keys already set.
- [x] 6.5 Keep a real download/storage failure recoverable (both keys null → reconcile
  retries) and **distinct** from a benign `ObjectOptimisticLockingFailureException` no-op.
- [x] 6.6 Keep CR-3 `handleWebhook` ack semantics (verify → 202/401; Details/download
  failure acks-and-defers; no payload logging).

## 7. Reconciliation job

- [x] 7.1 Add `@EnableScheduling` (dedicated, module-scoped config) and **disable it / make
  the interval overridable in the test profile**.
- [x] 7.2 Add a repository query: `SIGN_REQUESTED` older than an age threshold, plus
  `SIGNED AND signed_pdf_key IS NULL` past a grace window; `ORDER BY created_at ASC LIMIT
  batchSize`; never `PDF_GENERATED`.
- [x] 7.3 Add a `@Scheduled` reconciliation component that calls the shared completion
  method (§6.1) per selected row; interval/age/grace/batch from config.

## 8. Tests (pyramid — unit + integration, no live credentials)

- [x] 8.1 **Unit:** aggregation rule + precedence (all-signed→SIGNED, any-reject→FAILED
  over EXPIRED, partial→no-op, idempotent terminal) computed from counts; per-invitee
  status mapping; correlation by provider id with ordinal fallback.
- [x] 8.2 **Unit:** completion method drives the right transition + triggers download only
  on first SIGNED; deterministic key derivation (from internal id only); skip-when-both-
  keys-set guard.
- [x] 8.3 **Integration (Testcontainers Postgres):** V4 applies, context boots under
  `ddl-auto: validate`; aggregate persists per-invitee status + ordinal + provider id +
  keys; `UNIQUE(signing_request_id, signing_order)` holds.
- [x] 8.4 **Integration (Testcontainers MinIO):** `BlobStore` round-trips signed PDF +
  audit-trail bytes (private, self-bootstrapped bucket); download-on-SIGNED stores both and
  records keys atomically; re-delivery does not duplicate; failed download leaves both keys
  null (recoverable); partial put does not half-record.
- [x] 8.5 **Integration:** webhook completion happy-path (all invitees signed via stub →
  SIGNED + artifacts stored); reconciliation job (invoked directly) recovers a missed
  completion and a failed download, and does **not** sweep a `PDF_GENERATED` orphan.
- [x] 8.6 **Unit:** log-redaction — signed-PDF bytes, audit-trail bytes, and any provider
  download URL never appear in logs (mirror CR-3's redaction tests).
- [x] 8.7 **Unit:** a genuine download/storage failure leaves both keys null (recoverable)
  and is NOT masked as a benign optimistic-lock no-op (the two paths are distinct).
- [x] 8.8 Add/keep a stub `EsignProvider` returning per-invitee statuses + downloadable
  dummy artifacts so the suite needs no live credentials.

## 9. Gates & boundaries

- [x] 9.1 `ModularityTests` green (BlobStore + provider specifics behind seams; no
  cross-module internal reach).
- [x] 9.2 Regenerate the Gradle lockfile if deps changed
  (`./gradlew dependencies --write-locks`); `./gradlew check` (tests + coverage +
  `securityScan`) passes; `./gradlew spotlessApply`.

## 10. Deferred (record, do not run / build)

- [x] 10.1 Document the live Leegality sandbox e2e happy-path as a deferred manual-test
  item (blocked on sandbox creds) — to be exercised once credentials are obtained.
- [x] 10.2 Record follow-up notes: `PDF_GENERATED` create-path orphan recovery (separate
  mechanism); multi-instance scheduler distributed lock (e.g. ShedLock); prod object-store
  hardening (SSE-at-rest + block-public-access); these feed later CRs / `signing-auth`.
