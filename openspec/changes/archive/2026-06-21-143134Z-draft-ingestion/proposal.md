## Why

The signing flow currently sends a hardcoded placeholder PDF (`DUMMY_PDF`) to the
eSign provider — there is no way for a user to supply the actual rental agreement they
want signed. This change makes the document real: the user uploads their own draft
agreement as a PDF, which becomes the signable base for the existing create-signing
flow. It is the bottom-up "make the document real" step that precedes e-stamping
(CR-6) and unblocks an end-to-end run on a genuine document.

## What Changes

- **New** `POST /api/agreements/{id}/draft` (multipart) — accepts exactly one PDF part
  for an existing agreement, validates it, and stores the raw bytes in object storage.
- Upload is validated by **magic bytes** (`%PDF-`), not just the declared
  `Content-Type`, and is bounded by a **10 MiB** per-upload ceiling (distinct from the
  existing 1 MiB JSON body guard). Empty / oversized / non-PDF uploads are rejected as
  RFC 9457 `application/problem+json`.
- Raw draft bytes are stored in MinIO via the existing `BlobStore` seam at the
  deterministic key `drafts/{agreementId}.pdf`. Bytes never touch Postgres or logs.
- The `Agreement` aggregate gains a **server-managed** `draftPdfKey` reference
  (nullable until a draft is uploaded), persisted via a new `V5__agreement_draft.sql`
  migration. It remains client-unsettable (anti-mass-assignment) and status-less.
- **Re-upload is allowed (overwrite) only until a signing request is created.** Once any
  signing request exists, the draft is **finalized** — a further upload is rejected `409`,
  regardless of the request's state (including terminal `FAILED`/`EXPIRED`). eSign costs
  money, so post-signing draft revision is a separate paid flow, **deferred to a future
  feature** (`docs/future-features/`), not a free re-upload.
- **BREAKING (internal flow):** creating a signing request now **requires** a stored
  draft — `POST /api/signing/{agreementId}/request` responds `409 Conflict` (problem+json)
  when none has been uploaded. The `DUMMY_PDF` placeholder is **removed entirely**; the
  unsigned PDF is sourced from the stored draft blob.
- Ownership ("own-draft only") enforcement is **deferred to the `signing-auth` CR** — no
  caller-identity check is added here (consistent with the current default-deny-but-no-
  auth posture); the gap is documented.

Out of scope (deferred): `.docx` ingestion, our-template generation (Chromium render),
a draft **download** endpoint, and stamp composition (CR-6).

## Capabilities

### New Capabilities
- `draft-ingestion`: upload, validation, storage, and lifecycle of a user-supplied
  rental-agreement draft PDF — the signable base document for an agreement.

### Modified Capabilities
- `agreement-management`: the `Agreement` aggregate gains a server-managed, nullable
  `draftPdfKey` reference and a new Flyway migration column; the create contract is
  otherwise unchanged.
- `signing-request`: the unsigned PDF is now sourced from the agreement's stored draft
  blob (replacing the server-side placeholder); creating a signing request requires a
  draft and responds `409` (problem+json) when none exists.

## Impact

- **Code**: `signing` module — new upload endpoint on `AgreementController` (or a sibling
  controller in `signing.api`), draft validation + storage service, `Agreement`
  aggregate field + repository, `SigningRequestService.buildSignRequest` now reads the
  stored draft (removes `DUMMY_PDF`). Reuses the existing `BlobStore`/MinIO adapter — no
  new storage infra.
- **Schema**: new forward-only `V5__agreement_draft.sql` adds nullable `draft_pdf_key`
  to `agreement`; JPA stays `ddl-auto: validate`.
- **API**: one new multipart endpoint; `POST /api/signing/{id}/request` gains a `409`
  precondition. New Spring Security permit for the upload path (scoped, not wildcard).
- **PII/security**: an inbound binary upload is **untrusted** — validated by magic bytes,
  size-bounded, single-part, stored as opaque bytes (never parsed/rendered/executed,
  never logged). The attacker-controlled multipart **filename** and declared
  `Content-Type` are never trusted, logged, or reflected; the storage key is built from the
  parsed `UUID`, not the raw path (no traversal). No Aadhaar/OTP/VID/PII or secret flow is
  introduced or moved by this change; sandbox + dummy data only is preserved. The draft is
  a user document, not identity data; only its storage key (not its bytes) is persisted in
  Postgres.
- **FSM**: no signing-status transition is added or changed. The upload gates the
  existing `→ SIGN_REQUESTED` create path (a precondition), and the FSM continues to live
  on the `SigningRequest` aggregate; `Agreement` stays status-less.
