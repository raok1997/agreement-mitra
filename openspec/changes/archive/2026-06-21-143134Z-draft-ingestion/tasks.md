## 1. Schema & aggregate

- [x] 1.1 Add forward-only `V5__agreement_draft.sql` adding nullable `draft_pdf_key TEXT` to `agreement` (String→text, matching the V2 convention and the existing `signed_pdf_key`/`audit_trail_key` columns)
- [x] 1.2 Add server-managed `draftPdfKey` field to the `Agreement` entity + an aggregate method `attachDraft(String key)` (no ad-hoc setter); keep aggregate status-less and the entity package-private
- [x] 1.3 Add an internal `AgreementService` accessor for `draftPdfKey` (same-module read for the signing flow); do NOT add it to the public `AgreementResponse` GET body; never client-settable on create

- [x] 2.1 Add `DraftService` in `signing.agreement` enforcing this side-effect order: **validate → freeze-check → store → attach** (a rejected upload must never overwrite the blob). Validation: require exactly one file part; magic-byte (`%PDF-`) check (reject empty and sub-signature 1–4-byte parts as 400 non-PDF); ignore the client `Content-Type`; never log/reflect the multipart filename
- [x] 2.2 Add `existsByAgreementId(UUID)` to `SigningRequestRepository`; in `DraftService`, reject upload with `409` (`draft-frozen` type URN) when **any** signing request exists — draft is finalized for v1 regardless of state (incl. terminal `FAILED`/`EXPIRED`)
- [x] 2.3 Store bytes via `BlobStore.put("drafts/" + uuid + ".pdf", bytes, "application/pdf")` — key derived from the **parsed UUID**, not the raw path; server-set content type; managed-entity `attachDraft` inside a tx (no detached `save`)
- [x] 2.4 Add `POST /api/agreements/{id}/draft` (multipart, one `file` part, `@PathVariable UUID id`) to `AgreementController`: 404 unknown id, 400 non-UUID, success (200 + agreement id, no bytes)
- [x] 2.5 Configure `spring.servlet.multipart.max-file-size=10MB` + `max-request-size=11MB` (envelope can't multiply the ceiling); map `MaxUploadSizeExceededException` → 400 problem+json in `GlobalExceptionHandler`

## 3. Wire draft into signing & remove placeholder

- [x] 3.1 Add root-package `ConflictException` (carrying a `type` URN) + `GlobalExceptionHandler` mapping → `409` problem+json with constant detail + pinned `instance` (no leaked input — per api-error-handling precedent); distinct URNs `draft-frozen` (upload) vs `draft-required` (signing)
- [x] 3.2 `SigningRequestService.buildSignRequest`: source `blobStore.get(agreement.draftPdfKey())`; if `draftPdfKey` is null throw `ConflictException` (→ 409); delete `DUMMY_PDF` and its fallback

## 4. Security

- [x] 4.1 Add scoped permit `POST /api/agreements/*/draft` to `SecurityConfig` (not a `/**` wildcard); document deferred ownership check on the endpoint

## 5. Tests (unit)

- [x] 5.1 Magic-byte/size validator unit tests: valid `%PDF-` accepted; non-PDF (incl. spoofed `Content-Type`) rejected; empty rejected; sub-signature 1–4-byte part rejected without error; >1 part rejected
- [x] 5.2 `buildSignRequest` unit test: null `draftPdfKey` → `ConflictException`; present key → bytes loaded from `BlobStore`
- [x] 5.3 `GlobalExceptionHandler` unit tests: `ConflictException` → 409 problem+json with constant detail; `MaxUploadSizeExceededException` → 400 problem+json

## 6. Tests (integration — Testcontainers Postgres + MinIO)

- [x] 6.1 `V5` migration applies and JPA boots under `ddl-auto: validate` (mapping matches schema)
- [x] 6.2 Upload happy path: multipart PDF → 2xx, bytes round-trip in MinIO at `drafts/{id}.pdf` with `application/pdf` content type, `draft_pdf_key` persisted; 404 unknown id; 400 non-UUID; 400 oversized (>10 MiB) and non-PDF
- [x] 6.3 Finalize: upload after a signing request exists → 409 (`draft-frozen`), stored draft unchanged; lock holds even when the request is terminal (`FAILED`/`EXPIRED`)
- [x] 6.4 Create-signing-request now consumes the stored draft; create with no draft → 409 problem+json (`draft-required`; no signing-request row, no provider call — WireMock asserts no interaction)

## 7. Self-review & gates

- [x] 7.1 `./run-tests.sh check` green (tests + JaCoCo + OSV + SpotBugs + ModularityTests); confirm no lockfile change (no new deps)
