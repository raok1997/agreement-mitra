## Context

CR-3 (`create-signing-request`) shipped the create endpoint, the HMAC-verified
`POST /api/webhooks/esign` endpoint, the Details-API-as-source-of-truth read, the
`SIGN_REQUESTED → SIGNED | FAILED | EXPIRED` aggregate FSM, idempotent terminals,
and optimistic locking. It deliberately deferred three things, all of which this
CR delivers:

1. **Status is document-level.** `EsignProvider.getStatus(docId)` returns one
   `SignatureStatus`. A multi-party agreement needs *per-signer* resolution —
   "signed" means every invitee signed.
2. **`download()` is a stub** (`UnsupportedOperationException`). We never persist
   the signed PDF + audit trail.
3. **No reconciliation.** The webhook is the only completion trigger; a missed or
   Details-API-failed webhook (which CR-3 acks-and-defers) is never recovered.

Constraints carried in: Spring Modulith boundaries (`ModularityTests` green; infra
behind seams), no PDF bytes in Postgres, never log PII / signing URLs / payloads,
secrets via env, sandbox + dummy data only. The Leegality sandbox creds are **not
yet obtained**, so everything is built and tested against a stub `EsignProvider`
plus Testcontainers; the live happy-path is a deferred manual-test item.

## Goals / Non-Goals

**Goals:**
- Per-invitee status resolution from the Details API, aggregated in our domain to
  the existing aggregate FSM (`SIGNED` only when all signed).
- Implement `download()` and persist signed PDF + audit trail to object storage
  via a vendor-neutral `BlobStore` seam — keys in Postgres, bytes in storage,
  idempotent under webhook re-delivery.
- A bounded, scheduled reconciliation job reusing the same completion path.
- Forward-only `V4__signing_completion.sql`; `ModularityTests` stays green.

**Non-Goals:**
- Live Leegality sandbox e2e (creds pending) — stub-only here.
- **Recovering `PDF_GENERATED` create-path orphans.** A provider-failure-at-create
  row has `provider_document_id = NULL`, so it can never be Details-reconciled
  (there is no document id to query). Recovering those needs a different mechanism
  (e.g. re-issuing the provider create) — out of scope; recorded as a follow-up.
- Ownership-auth / rate-limiting (already deferred to `signing-auth`).
- PDF *rendering* (the `documents` module / dummy PDF stays as-is).
- Multi-instance scheduler coordination (distributed lock) — single-instance
  assumption; recorded as a risk.
- Download retry/backoff beyond "the next reconciliation tick retries it."
- Prod-grade object-storage hardening (SSE-at-rest, block-public-access bucket
  policy) — sandbox MinIO bucket is created private; prod hardening is a deferred
  note (first real-PII-bearing artifact in the system).

## Decisions

### D1 — Per-invitee status on the `EsignProvider` seam
`getStatus` changes from returning a single `SignatureStatus` to returning a
vendor-neutral **document view carrying per-invitee statuses** —
`DocumentStatusView(List<InviteeStatusView>)`, each `InviteeStatusView` carrying a
vendor-neutral `InviteeStatus ∈ {PENDING, SIGNED, REJECTED, EXPIRED}` **plus a
correlation token** (see D3). The adapter maps Leegality's per-invitee Details
fields to this; **aggregation to the aggregate FSM moves into the domain** (the
`SigningRequest` aggregate owns its invitees, so it owns the rule).

`InviteeStatus` (per-invitee sub-state) and `SignatureStatus` (aggregate FSM
state) are **deliberately distinct types** even though both spell `SIGNED` /
`EXPIRED` — they live at different levels and must never be cross-assigned. The
aggregate exposes a **mutation method** (e.g. `applyInviteeStatuses(...)`) that
updates its child rows' statuses and then runs the aggregation; callers do not
mutate the (still unmodifiable) invitee list directly.

- *Alternative — keep document-level + trust `document.status=COMPLETED`:*
  rejected; it can't express partial progress and isn't truly per-signer.
- *Alternative — add a parallel `getInviteeStatuses` method:* rejected; two status
  methods drift. One richer result is the single source.

### D2 — Aggregation rule and precedence
Over all invitees of a request: **any `REJECTED` → `FAILED`; else any `EXPIRED` →
`EXPIRED`; else all `SIGNED` → `SIGNED`; else no-op** (stay `SIGN_REQUESTED`).
`FAILED` outranks `EXPIRED` — a rejection is the more actionable terminal and a
rejected signer means the document can never complete. Mixed non-terminal stays
non-terminal (safe no-op), consistent with CR-3's "non-terminal authoritative
status is a no-op." The transition still goes through the aggregate's
state-machine method (no ad-hoc setters); terminals stay idempotent.

### D3 — The FSM decision uses counts; correlation is separate and anchored
The terminal decision depends only on the **multiset of per-invitee statuses vs
the invitee count** — it does *not* depend on knowing which provider invitee maps
to which of our rows. So a correlation mismatch can never corrupt the FSM outcome
— at worst a *displayed* per-row status is off, never the terminal transition.

For persisting per-invitee status onto our `signing_request_invitee` rows
(audit/visibility), correlation is anchored two ways, best-available first:
1. **Provider invitee identifier** — the create response's per-invitee identity is
   **captured at create** (CR-3 currently discards it) and persisted on the
   invitee row; `InviteeStatusView` carries the same token, so Details statuses
   join to rows by a stable provider key.
2. **Ordinal fallback** — a `signing_order` (written at create, in provider
   response order) is the fallback when the provider exposes no stable per-invitee
   id. A `UNIQUE (signing_request_id, signing_order)` constraint keeps ordinals
   unambiguous.

Existing CR-3 rows predate these columns (NULL); this is a greenfield sandbox repo
with no live data, so no backfill is needed — new requests populate both.

### D4 — Download-on-`SIGNED`, decoupled from the transition, idempotent
On reaching `SIGNED` we fetch the signed PDF + audit trail and store them. The
explicit control flow (no hand-wave "reuse D9"):

```
1. getStatus(docId)                         — network, NO tx  → per-invitee view
2. tx_a: load request + invitees,           — DB tx
        applyInviteeStatuses + aggregate,
        transition (idempotent terminal),
        commit
3. if status==SIGNED AND signed_pdf_key IS NULL:
     download(docId)                         — network, NO tx  → SignedDocument
     tx_b: persist BOTH keys together,       — DB tx, optimistic-lock guarded
           commit
```

- **Object-key id source is pinned to the internal `signing_request.id` (an
  app-generated random `UUID`)** — never the vendor-controlled `providerDocumentId`
  (a `VARCHAR(255)` that could inject/traverse an object key) and never the
  `agreementId` (stable across re-requests → would overwrite a prior signed
  agreement's artifact). Keys: `signed/{signingRequestId}.pdf`,
  `audit/{signingRequestId}` — deterministic, so a repeated put overwrites the same
  key with the same bytes (never a duplicate object).
- **The two artifacts are an atomic pair**: both keys are persisted in the same
  `tx_b`, and the skip-guard requires **both** keys present. A PDF-put-success /
  audit-put-fail leaves both keys NULL → recoverable, never a half-recorded state.
- **Artifact presence is decoupled from the terminal status.** If the download or
  store fails, the row stays `SIGNED` with NULL keys and **reconciliation
  re-attempts** (its scan includes `SIGNED AND signed_pdf_key IS NULL`). The legal
  terminal status (the provider truly completed) is never blocked or reverted by a
  storage hiccup.
- **Honest idempotency:** under concurrency (a webhook and a reconcile tick racing
  the same fresh completion) the null-key check and the outside-tx put are *not*
  atomic, so both may download — but deterministic keys mean **no duplicate
  object** and the optimistic-lock guard means only one key-persist wins. The
  guarantee is "no duplicate artifacts and no corrupt state," *not* "never a
  redundant re-download."
- **Storage/download failure must stay distinguishable from a benign optimistic-
  lock loss.** CR-3 swallows `ObjectOptimisticLockingFailureException` at debug; the
  new key-persist must not let a real download/storage error be masked as a lock
  loss — a real failure leaves NULL keys for reconciliation, a lock loss is a true
  no-op.
- *Alternative — download before marking `SIGNED` (invariant "SIGNED ⇒ artifact
  held"):* rejected; it makes the legal terminal status hostage to a storage
  hiccup and forces re-running provider aggregation on storage retry.

### D5 — `BlobStore` seam + MinIO/S3 adapter
A vendor-neutral `BlobStore` (`put(key, bytes, contentType)` / `get(key)`)
mirroring the `EsignProvider` pattern; the only adapter is an S3-API MinIO client
(local MinIO already in `docker-compose`; MinIO Testcontainer + client already in
`build.gradle.kts` test scope). Endpoint/bucket/credentials come from env. Notes:
- The adapter uses **path-style addressing** (MinIO default) and **ensures the
  bucket exists** on startup (create-if-absent), so neither local boot nor the
  Testcontainers test depends on out-of-band bucket bootstrap.
- The bucket is **private** (no public-read). `content-type` is stored with each
  object. No bytes ever touch Postgres or logs.
- `SignedDocument` is widened to carry the **content type** of each artifact (it
  currently has none) so the audit trail is stored "with the provider-declared
  content type"; default `application/octet-stream` when absent. (Also rename its
  `providerRequestId` → `providerDocumentId` for consistency with the rest of the
  module.)

### D6 — Reconciliation as a bounded scheduled job
`@EnableScheduling` + a `@Scheduled(fixedDelayString=…)` component that selects a
**bounded, ordered** batch of recoverable rows and drives each through the **same**
completion path the webhook uses (the completion logic is extracted into one method
both callers invoke). Selection:
- `SIGN_REQUESTED` with `created_at < now - ageThreshold`, **plus** `SIGNED AND
  signed_pdf_key IS NULL` with a short **grace window** (`created_at`/updated
  older than a small delay) so the job does not race the in-flight webhook download
  on a just-completed row.
- `ORDER BY created_at ASC LIMIT batchSize` — oldest-first, deterministic, no
  starvation. A `(status, created_at)` index backs the scan.
- Interval, age threshold, grace window, and batch size are config. The job is
  disabled / interval-overridden in the **test profile** so it does not fire
  against other tests' fixtures; reconciliation behaviour is tested by invoking the
  job method directly.
- Single-instance assumption (see Risks). It does **not** select `PDF_GENERATED`
  rows (Non-Goal — no document id to reconcile).

## Risks / Trade-offs

- **Provider invitee ordering / id may not be stable between create and Details.**
  → Mitigated by D3: the FSM decision is count-based and correlation-independent;
  correlation only affects the per-row displayed status, anchored by provider id
  with ordinal fallback.
- **Download network failure / partial put leaves `SIGNED` with no artifact.** →
  Decoupled by D4 (atomic key pair, both-NULL on failure); reconciliation's scan
  includes `SIGNED AND signed_pdf_key IS NULL` and retries.
- **SSRF via the artifact-download URL.** If Leegality's Details/download returns a
  provider-controlled file URL, the adapter must **host-pin/allowlist** it to the
  configured provider domain before issuing the outbound GET — never fetch an
  arbitrary URL from the payload. Confirmed against the sandbox when creds arrive;
  the seam takes a document id, the URL resolution stays adapter-internal.
- **Audit trail may carry masked Aadhaar / evidence.** → Stored as an **opaque
  blob** with the provider-declared content type; never parsed, never logged; only
  the object key is persisted/logged. Avoid logging content-type alongside a
  correlatable doc id.
- **Reconciliation widens the unauthenticated-create blast radius.** The create
  endpoint is unauthenticated (deferred to `signing-auth`); reconciliation now
  *auto-fetches and stores* a signed artifact for any such row. Noted as an
  expanded risk feeding `signing-auth`; sandbox + dummy data mitigates it here.
- **Multi-instance deployment double-runs the scheduler.** → Work is idempotent
  (deterministic keys + optimistic lock), so duplication is wasteful but not
  corrupting. A distributed lock (e.g. ShedLock) is a future CR; noted, not built.
- **`getStatus` signature change ripples into the CR-3 webhook path
  (`handleWebhook` + `applyAuthoritativeStatus`).** → Contained within the
  `signing` module behind `EsignProvider`; reshaping `applyAuthoritativeStatus` to
  load invitees + aggregate is broken into atomic tasks (§3, §6). No other module
  references it; `ModularityTests` guards the boundary.
- **Unbounded artifact size from the provider.** → For sandbox/dummy this is
  trivial; the adapter caps/streams defensively. Hardening deferred.

## Migration Plan

- `V4__signing_completion.sql` (forward-only; never edits V1–V3):
  - `signing_request_invitee`: add `status` (bounded varchar, nullable),
    `signing_order` (smallint, nullable), and the provider per-invitee id (varchar,
    nullable); add `UNIQUE (signing_request_id, signing_order)`.
  - `signing_request`: add `signed_pdf_key` and `audit_trail_key` (text, nullable)
    — object-storage **keys**, never bytes.
  - Add index on `signing_request (status, created_at)` for the reconciliation scan.
  - All new columns nullable/additive → existing rows validate; JPA stays
    `ddl-auto: validate`.
- **Rollback:** changes are additive — no destructive DDL. Scheduling and
  reconciliation sit behind config and can be disabled (interval off) without a
  schema change.

## Open Questions

- **Exact Leegality Details per-invitee field names, the per-invitee id, and
  ordering stability** — resolvable only against the sandbox (creds pending).
  Adapter-local; the vendor-neutral seam and the count-based FSM are unaffected.
- **Whether the signed artifact + audit trail come back in one Details/download
  round-trip or via provider-returned URLs** (drives the SSRF mitigation in D4/
  Risks) — adapter-local; confirm on creds. `download(docId)` stays a single
  seam call returning both artifacts regardless.
- **Audit-trail content type/format** (PDF vs JSON/XML) — stored opaque with the
  provider-declared content type; default `application/octet-stream` if absent.
