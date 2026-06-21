## Why

CR-3 shipped the create endpoint, the verified webhook, the Details-API status
read, and the `SIGN_REQUESTED → SIGNED | FAILED | EXPIRED` FSM — but it resolves
status at the **document** level, never downloads the signed artifact (the
`EsignProvider.download()` is an explicit not-yet-supported stub), and has no
fallback when a webhook is missed. A rental agreement is **multi-party** (e.g.
landlord + tenant + witness): "signed" means *every* signer signed, and the whole
point of eSign is to end up holding the signed PDF + audit trail. This CR closes
the completion path: per-signer aggregation, signed-artifact storage, and a
reconciliation fallback — the three pieces CR-3 deliberately deferred.

## What Changes

- **Per-signer aggregation.** The authoritative-status read becomes
  **per-invitee** (the provider Details API reports each signer's status). Our
  domain aggregates: the signing request reaches `SIGNED` only when **all**
  invitees signed, `FAILED` if any invitee rejects / cert-fails, `EXPIRED` on
  expiry. A partial (some-signed) document stays non-terminal. The webhook body's
  who-signed claim is still never trusted — per-invitee status is always re-read
  from Details (the `mac` covers only the document id).
- **Signed-artifact storage.** Implement `EsignProvider.download()` and add a
  `BlobStore` seam (MinIO local / S3-compatible prod) mirroring the
  `EsignProvider` pattern. On the `SIGNED` transition the signed PDF + audit
  trail are downloaded and written to object storage; only the **object keys**
  land in Postgres. The download is a network call **outside** the FSM
  transaction (the same persist-intent / call-network / persist-result split as
  create) and is **idempotent** — a re-delivered `SIGNED` webhook neither
  re-downloads nor duplicates artifacts.
- **Reconciliation job.** Add scheduling and a bounded job that scans stale
  non-terminal `SIGN_REQUESTED` rows and re-runs the **same** status-read →
  aggregate → download-on-`SIGNED` path the webhook uses (the existing spec
  already promises this code reuse). It is the fallback for missed webhooks and
  for the Details-API-failure case CR-3 already acks-and-defers.
- New forward-only migration **`V4__signing_completion.sql`**: adds
  `signing_request_invitee.status`, and `signed_pdf_key` / `audit_trail_key` on
  `signing_request`. V1–V3 are never edited.

No public HTTP contract changes: the create endpoint and the `POST
/api/webhooks/esign` endpoint keep their existing request/response shapes.

### Signing FSM transitions touched

Aggregate FSM is unchanged in shape — still `SIGN_REQUESTED → SIGNED | FAILED |
EXPIRED`. What changes is **what drives the transition**: a *per-invitee*
aggregation (all-signed → `SIGNED`) instead of a single document-level status.
The new per-invitee `status` is a sub-state of the existing `SIGN_REQUESTED`
aggregate state; it is not a new aggregate FSM state.

```
Provider          Webhook / Reconcile        SigningRequest (aggregate)
  │   per-invitee        │                          │
  │   statuses           │  verify mac (doc id)      │
  │◄─────────────────────│  getStatus(docId) ───────► per-invitee statuses
  │  [s1=SIGNED,         │                          │  aggregate:
  │   s2=PENDING]        │                          │   any FAILED  → FAILED
  │                      │                          │   any EXPIRED → EXPIRED
  │                      │                          │   all SIGNED  → SIGNED ─┐
  │                      │                          │   else        → no-op   │
  │  download(docId) ◄───┼── on SIGNED, OUTSIDE tx ─┼─────────────────────────┘
  │  signedPdf+audit ───►│  BlobStore.put ──────────► store keys (idempotent)
```

## Capabilities

### New Capabilities
- `signed-artifact-storage`: a vendor-neutral `BlobStore` seam and the
  persistence of the signed PDF + audit trail to object storage on completion —
  keys in Postgres, bytes in object storage, idempotent under re-delivery.
- `signing-reconciliation`: a scheduled, bounded fallback that drives missed /
  deferred completions through the same status-read → aggregate → store path as
  the webhook.

### Modified Capabilities
- `signing-request`: the authoritative-status requirement becomes per-invitee
  with domain aggregation (`SIGNED` only when all sign); `download()` moves from
  not-yet-supported to implemented; the Flyway-schema requirement gains the
  per-invitee `status` column and the artifact-key columns (V4).

## Impact

- **Code (`signing` module only):** `EsignProvider` (per-invitee status result;
  `download()` contract), `LeegalityEsignProvider` (Details per-invitee parsing,
  `download()` impl), `SigningRequest` + `SigningRequestInvitee` aggregate
  (per-invitee status, aggregation method), `SigningRequestService` /
  `SigningRequestPersistence` (download-on-SIGNED, idempotency), new `BlobStore`
  seam + MinIO adapter, new reconciliation scheduled component, new
  `SigningRequestRepository` stale-scan query.
- **Schema:** `V4__signing_completion.sql` (3 new columns).
- **Config:** object-storage endpoint/bucket/creds via env (sandbox MinIO);
  reconciliation interval / age-threshold / batch-size as config.
- **Infra/build:** MinIO already in `docker-compose`; MinIO Testcontainer + client
  already in `build.gradle.kts` test scope. `@EnableScheduling` added.
- **Modulith:** all new infra/vendor specifics stay behind `EsignProvider` and
  the new `BlobStore` seam; `ModularityTests` must stay green.
- **Out of scope / deferred:** live Leegality sandbox e2e (creds not yet
  obtained) — built and tested stub-only, live happy-path recorded as a deferred
  manual-test item; ownership-auth / rate-limiting (already deferred to
  `signing-auth`); PDF rendering via the `documents` module.

## PII / security review

- **Introduces a new inbound PII flow.** The downloaded **signed PDF** contains
  signer PII (names, agreement contents) and the **audit trail** may carry
  eSign evidence (timestamps, masked identifiers). These now enter our object
  storage.
- **How secured/redacted:** artifact **bytes** go only to object storage (MinIO
  sandbox), never to Postgres and never to logs; only opaque object **keys** are
  persisted and logged (and even keys are non-sensitive). The audit-trail blob is
  treated as opaque — never parsed-and-logged. Existing redaction holds: document
  ids redacted, signing URLs / webhook payloads / Aadhaar / OTP / VID / signer
  PII never logged. Webhook stays HMAC-verified before any side effect.
- **Secrets:** object-storage credentials and the existing provider/webhook
  secrets come from env vars only; nothing committed.
- **Sandbox + dummy data only** is preserved — no live credentials required to
  build or test; the live path is deferred.
