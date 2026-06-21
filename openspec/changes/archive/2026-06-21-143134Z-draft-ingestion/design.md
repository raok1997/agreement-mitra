## Context

The signing flow (CR-3/CR-4) is complete end-to-end but signs a hardcoded `DUMMY_PDF`
fixture ([`SigningRequestService`](../../../backend/src/main/java/in/agreementmitra/signing/signingrequest/SigningRequestService.java)).
This CR makes the document real: a user uploads their own rental-agreement PDF, which
becomes the unsigned base the provider signs. It is the "make the document real" step
between the vendor tracer bullet (done) and e-stamping (CR-6).

Current state this builds on:
- `BlobStore` (MinIO) seam already exists with `put`/`get`; used today for signed
  artifacts under `signed/…`. We reuse it for the input draft.
- `Agreement` is a status-less aggregate in `signing.agreement` (package-private entity);
  the `signing` module owns both `agreement` and `signingrequest` sub-packages, so they may
  collaborate without crossing a Modulith boundary.
- `SecurityConfig` is default-deny with scoped per-route permits.
- `PayloadSizeLimitConfig` guards `/api/signing/*` + `/api/webhooks/esign` at 1 MiB — it
  does **not** cover `/api/agreements/*`, so it won't clip the upload path.

## Goals / Non-Goals

**Goals:**
- Accept a user-supplied draft PDF for an existing agreement, validated and size-bounded.
- Store raw bytes in object storage; persist only the storage key on the aggregate.
- Replace `DUMMY_PDF` — signing now sources the stored draft and requires one to exist.
- Freeze the draft once a signing request exists; allow overwrite before that.

**Non-Goals:**
- `.docx` ingestion, our-template (Chromium) generation, a draft **download** endpoint.
- Stamp composition (CR-6 consumes this draft).
- Ownership / caller-identity authorization (deferred to `signing-auth`).
- Antivirus / deep PDF structural validation (sandbox; magic-byte + size only).

## Decisions

**D1 — Separate multipart endpoint on the agreement resource.**
`POST /api/agreements/{id}/draft`, `multipart/form-data`, one `file` part. Lives on
`AgreementController`. Chosen over folding the PDF into `POST /api/agreements` (keeps the
validated JSON create contract clean and lets the draft be re-uploaded independently) and
over a top-level `/api/drafts` route (the draft is owned by an agreement; the nested path
expresses that and reuses the agreement permit pattern).

**D2 — Validate by magic bytes, not Content-Type.** Read the leading bytes and require the
`%PDF-` signature; declared `Content-Type` is untrusted and ignored for the decision.
Empty part → 400. No parsing/rendering of the bytes (treat as hostile). Alternatives
(trust Content-Type; full PDF parse) rejected: the former is spoofable, the latter widens
attack surface against an untrusted upload for no v1 benefit.

**D3 — 10 MiB ceiling via Spring multipart config, mapped to 400.** Set
`spring.servlet.multipart.max-file-size=10MB` and pin `max-request-size` only modestly
above it (e.g. `11MB`) so the multipart envelope can't multiply the effective ceiling.
Multipart is already enabled by default in Spring Boot — only the size properties are new.
Tomcat's multipart parser streams an oversized part to a temp file and aborts at the limit
(it does not buffer the whole oversized body into heap), so the spec's "reject before
storage" holds and heap pressure is bounded; `fileSizeThreshold` is left at its small
default so normal files don't sit in memory. `MaxUploadSizeExceededException` is mapped in
the root-package `GlobalExceptionHandler` to `400` problem+json (Spring defaults it to
500). This is independent of the existing 1 MiB JSON guard, which does not apply to this
route. The magic-byte/empty checks also live in the service and return 400 problem+json.

Residual DoS note: unlike the 1 MiB JSON routes (which short-circuit on `Content-Length`
in `PayloadSizeLimitConfig`), this is the most expensive unauthenticated route (disk +
MinIO write) and is enforced only mid-parse, with no per-IP rate limit. That is acceptable
in sandbox; **rate-limiting the upload/overwrite path is folded into the deferred
`signing-auth` scope** (alongside the create-signing rate limit), and the residual is
documented on the endpoint.

**D4 — Store bytes in MinIO, key on the aggregate.** The key is derived from the
**parsed `UUID`'s canonical string**, never the raw path segment: `blobStore.put("drafts/"
+ id + ".pdf", bytes, "application/pdf")` where `id` is the bound `UUID` (a non-UUID path
segment is rejected 400 before any key is built — see D2/spec), closing path-traversal /
arbitrary-key-overwrite. The content type is a **server-set constant** `"application/pdf"`;
the client-declared part Content-Type is never propagated to blob metadata. Overwrites at
the same deterministic key (no orphan on re-upload). The `Agreement` aggregate gains a
server-managed nullable `draftPdfKey`; a `V5__agreement_draft.sql` adds a nullable
`draft_pdf_key` column. The key is set through an aggregate method `attachDraft(key)` (not
an ad-hoc setter) as a **managed-entity update inside a transaction** (load → `attachDraft`
→ flush), not a detached `save`, so the signer collection is never clobbered. A new
`DraftService` in `signing.agreement` (same package as the entity) owns validate →
freeze-check → store → attach so the package-private aggregate stays encapsulated;
`AgreementService` stays focused on create/read. The signing flow reads the key via an
**internal `AgreementService` accessor** (same module) — `draftPdfKey` is **not** added to
the public `AgreementResponse` GET body (it is a server-internal storage key, not part of
the external contract). Blob garbage-collection on agreement deletion is **out of scope**
(no delete endpoint exists today).

**D5 — Draft finalized once any signing request exists (lock-forever for v1).** Before
storing, the upload path checks whether a signing request exists for the agreement
(`existsByAgreementId(id)`, added here). If one exists → `409` problem+json
(`draft-frozen`), no overwrite. Otherwise store/overwrite. This is a deliberate **business
rule**, not just a technical guard: eSign incurs real provider cost, so once signing is
requested the document is finalized. A failed-integration retry is handled on the existing
request (the CR-4 reconciliation path / a provider retry), **not** a free re-upload; and a
genuine purchaser-initiated revision is a separate **paid** flow. Allowing draft edits
after a signing request therefore needs its own flow (re-pay + supersede) and is **deferred
to a future feature** (`docs/future-features/draft-revision-after-signing-request.md`) — out
of scope here. The lock holds regardless of the request's state, including terminal
`FAILED`/`EXPIRED`. This keeps `Agreement` status-less (no new state field) — "is it
frozen?" is derived from the existence of a signing request, which already carries the FSM.

**D6 — Signing requires a stored draft; `DUMMY_PDF` deleted.**
`SigningRequestService.buildSignRequest` loads `blobStore.get(draftKey)` where `draftKey`
comes from the internal `AgreementService` accessor (D4). If the agreement has no draft →
throw a new root-package `ConflictException` mapped to `409` problem+json (sibling to the
existing `ResourceNotFoundException` → 404). The `DUMMY_PDF` constant and its fallback are
removed.

The two 409 conditions (freeze-after-in-flight-request on upload, and no-draft-on-signing)
SHALL carry **distinct `type` URNs** so clients can disambiguate
(`urn:agreementmitra:problem:draft-frozen` vs `urn:agreementmitra:problem:draft-required`),
following the constant-detail / pinned-`instance` rules already established in
`api-error-handling` (never echo input).

**D7 — Scoped security permit.** Add `requestMatchers(HttpMethod.POST,
"/api/agreements/*/draft").permitAll()` — narrow, not a `/**` wildcard, preserving the
fail-closed posture. Ownership check deferred (documented, `signing-auth`).

## Risks / Trade-offs

- **Untrusted binary upload** → magic-byte + size validation, opaque storage (never
  parsed/rendered/executed/logged), bytes never in Postgres. The **attacker-controlled
  multipart filename is never logged and never reflected** in any response (error details
  stay constant per the `GlobalExceptionHandler` precedent) — it is added to the never-log
  set. Only one part is accepted; extra/unexpected parts are rejected. Residual: a
  malformed-but-`%PDF-`-prefixed file is stored as-is; acceptable in sandbox, and the
  provider/render step would reject a truly broken PDF downstream.
- **No ownership check** → any caller can upload a draft to any agreement id. Mitigated by
  sandbox + dummy data only; explicitly deferred to `signing-auth`. Documented on the
  endpoint like the existing signing-request note.
- **Freeze is best-effort, not transactional** → a race between upload and
  create-signing-request could in theory let an upload land just before a request. Low risk
  (single-user sandbox, no concurrency); the create path re-reads the draft at request time
  so the worst case is the just-uploaded draft is the one signed. Not worth a lock in v1.
- **`existsByAgreementId` couples upload to the signingrequest sub-package** → both are
  inside the `signing` module, so this is intra-module collaboration, not a boundary
  crossing; `ModularityTests` stays green. We query by id, not by sharing a JPA association
  (preserves the CR-3 aggregate-isolation decision).
- **Multipart config** → multipart is enabled by default in Spring Boot; only the size
  properties are added. The 1 MiB JSON guard is unaffected (different route + the guard
  checks Content-Length on its own URL patterns).
- **Pre-signing overwrite has no auth gate** → any caller can replace an agreement's draft
  in the pre-signing window (documented ownership deferral). The freeze (D5) bounds the
  blast radius to before a request is in flight. The deferred `signing-auth` CR MUST cover
  the **upload/overwrite** path, not only create-signing.

## Migration Plan

- Forward-only `V5__agreement_draft.sql`: `ALTER TABLE agreement ADD COLUMN draft_pdf_key
  TEXT` (nullable) — `TEXT` matches the V2 String→text convention and the existing
  `signed_pdf_key`/`audit_trail_key` columns, so Hibernate `ddl-auto: validate` passes. An
  integration test asserts the migration applies and the mapping validates. No backfill (existing rows legitimately have
  no draft). Rollback = the column is nullable and unused by older code paths, but
  forward-only policy means we roll forward, not back.

## Open Questions

- Response shape on successful upload: `200 OK` with a tiny JSON `{draftKey}` vs `204 No
  Content`. Leaning `200` with the agreement id echoed (no bytes, no PII) for client
  convenience; finalize at apply. (Spec allows either.)
