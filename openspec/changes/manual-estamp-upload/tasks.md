# Tasks -- manual-estamp-upload

Schema and role model first (S1-S2), because everything else depends on the STAFF role and the
new columns. Then the seam + composer change (S3-S4), the intake endpoint (S5), and the signing
precondition (S6). Behavioral change -> unit **and** integration tests (S7), per `dev-policy`.

> **Grounding note.** `PdfStampComposer` already prepends a page 1, overlays a per-page header,
> respects each page's media box and rotation, and fails closed on bad PDFs. Only the **source of
> page 1** changes (generated template -> uploaded raster) and the **overlay text** (synthetic
> serial -> certificate number). Do not rewrite the composer; extend it. Likewise `StampProvider`
> survives as a seam (design D1) -- only its input contract changes.

## 1. Schema (Flyway, forward-only)

- [x] 1.1 New migration `V14__manual_estamp.sql`. Do NOT edit any applied migration.
- [x] 1.2 Add stamp columns to `agreement` as **nullable**: certificate number, scan key,
  duty amount, certificate issue date, description of document, purchased by. Reuse the existing
  `stamp_serial` column as the certificate number or add a clearly-named new column and drop the
  old one -- decide and record in the migration comment.
- [x] 1.3 Unique index on the **normalised** (trimmed, uppercased) certificate number. This is the
  single-use ledger (design D4) -- it must be a DB constraint, not an app check.
- [x] 1.4 Add the staff reference column to `agreement`; backfill existing rows, then apply NOT
  NULL + unique.
- [x] 1.5 Add the role column to the identity record, defaulting to `CUSTOMER`.
- [x] 1.6 Confirm the app boots with `ddl-auto: validate` against the migrated schema.

## 2. Role model + authorization

- [x] 2.1 Add the role enum (`CUSTOMER`, `STAFF`) and carry it on the authenticated principal.
- [x] 2.2 Default new accounts to `CUSTOMER`. Ensure role is **never** read from an OAuth claim
  and **never** bindable from a request body/header (design D7).
- [x] 2.3 Wire STAFF-only authorization in `SecurityConfig` under the existing default-deny
  posture; evaluate authorization **before** any resource lookup so refusals are not an existence
  oracle.

## 3. Staff reference

- [x] 3.1 Generate a short, human-safe reference at agreement creation: unambiguous alphabet (no
  `0/O`, `1/I/L`) plus a check character (design D3).
- [x] 3.2 Persist it immutably; expose a lookup by reference for staff use only.
- [x] 3.3 Reject a value in the display-only `AM-<LAST6>-<DDMMYY>` format as a lookup key.

## 4. Stamp seam + composition

- [x] 4.1 Change `StampProvider` from `procure(agreementId, draftPdf)` to an attach-style
  operation taking the scan bytes + certificate metadata; extend `StampResult` with certificate
  number, duty amount, jurisdiction, and `dutyPaid = true`.
- [x] 4.2 **Delete** `SyntheticKarnatakaStampProvider` and its deterministic serial logic; add the
  uploaded-certificate adapter in its place.
- [x] 4.3 Extend `PdfStampComposer` to prepend the uploaded raster as page 1 via
  `PDImageXObject`, fitted to the page with aspect ratio preserved and no upscaling beyond native
  resolution.
- [x] 4.4 Change the per-page overlay to carry the certificate number; keep the existing media-box
  and rotation handling untouched.
- [x] 4.5 Extend fail-closed handling to cover an undecodable/corrupt scan -> `STAMP_FAILED`, never
  an unmapped 500 or OOM.
- [x] 4.6 Store the scan under an agreement-scoped key and retain it after composition (design D6).

## 5. Intake endpoint

- [x] 5.1 Add the STAFF-only multipart intake endpoint (scan + certificate metadata + agreement
  reference).
- [x] 5.2 Validate image type by **magic bytes** (JPEG/PNG only), and bound byte size **and**
  decoded pixel dimensions (design D5). Reject mismatch/oversize with `400`.
- [x] 5.3 Validate mandatory metadata (certificate number, issue date, duty amount, jurisdiction)
  with a field-level error list; reject before any blob write or state change.
- [x] 5.4 Map a duplicate certificate number to `409` by catching the DB constraint violation --
  not a pre-check. Do not disclose the other agreement.
- [x] 5.5 Map an already-stamped agreement to `409`; leave the existing stamp untouched.
- [x] 5.6 Drive `PDF_GENERATED -> STAMPED` on success and `-> STAMP_FAILED` on composition failure;
  leave the state unchanged for pre-composition rejections.
- [x] 5.7 Return enough non-PII context (reference, property city, agreement date) for staff to
  confirm the right agreement was stamped.
- [x] 5.8 Redact the certificate number in logs (last 4 chars); never log scan bytes or any
  metadata value verbatim; ensure error bodies echo no submitted values.
- [x] 5.9 Record the intake audit entry (staff identity, agreement, outcome, timestamp) for both
  accepted and rejected attempts.

## 6. Signing precondition

- [x] 6.1 Remove `ensureStamped` from `SigningRequestService.create()` and delete the dormant
  reuse branch.
- [x] 6.2 Reject signing with a distinguishable `409` when stamp info is empty -- before any
  signing-request row is persisted and before any provider call.
- [x] 6.3 Submit the stored stamped PDF to the provider; never the bare draft.
- [x] 6.4 Confirm no reconciliation/cleanup path treats `PDF_GENERATED` as transient (it is now
  durable).

## 7. Tests

**Unit:**

- [x] 7.1 Composer: scan becomes page 1; `N + 1` page count; aspect ratio preserved with no
  upscaling; overlay carries the certificate number; draft and scan bytes unmutated; overlay
  in-frame for landscape/non-A4/rotated pages.
- [x] 7.2 Composer fail-closed: corrupt/truncated scan, encrypted draft, zero-page draft.
- [x] 7.3 Image validation: magic-byte mismatch, disallowed type, oversize bytes, decompression
  bomb (small file, huge decoded raster).
- [x] 7.4 Staff reference: uniqueness, immutability, check-character rejects a single-character
  typo, `AM-...` format refused as a lookup key.
- [x] 7.5 Certificate-number normalisation (trim/uppercase collide as duplicates).
- [x] 7.6 FSM: upload drives `STAMPED`; composition failure drives `STAMP_FAILED`; pre-composition
  rejection leaves `PDF_GENERATED`; terminal states still reject transitions.
- [x] 7.7 Redaction helper: certificate number redacted; no metadata value logged verbatim.

**Integration:**

- [x] 7.8 Testcontainers happy path: staff uploads scan -> blobs written to MinIO -> stamp info
  persisted with `dutyPaid = true` -> request `STAMPED`.
- [x] 7.9 Duplicate certificate number across two agreements -> `409`, first agreement unchanged.
- [x] 7.10 **Concurrent** upload of the same certificate number -> exactly one succeeds (verifies
  the DB constraint, not the app check).
- [x] 7.11 AuthZ slice: anonymous -> `401`; CUSTOMER -> `403`; STAFF -> `200`; refusal identical
  for existing vs non-existent agreement (no existence oracle).
- [x] 7.12 Signing precondition: `createSignRequest` without a stamp -> `409`, no row persisted,
  provider not called.
- [x] 7.13 Schema: app boots under `ddl-auto: validate` post-migration; `ModularityTests` green.

## 8. Docs + housekeeping

- [x] 8.1 Update `docs/ROADMAP.md`: `leegality-real-stamp` (Track B item 4) is superseded by
  manual SHCIL procurement; re-scope the queued "PDF_GENERATED orphan recovery" non-goal now that
  the state is durable.
- [x] 8.2 Add a test fixture certificate image and document the local stamping flow, so removing
  the synthetic adapter does not regress developer experience.
- [x] 8.3 Record SHCIL online certificate verification as the follow-up hardening (largest
  residual legal risk per design).
- [x] 8.4 Run `./gradlew spotlessApply` then the full gate (`./gradlew check`, or
  `./gradlew test spotbugsMain` where `osv-scanner` is unavailable locally).

## 9. Gap closure (added after review -- product owner clarification)

Three gaps found reviewing the first implementation. The flow is: customer finalises and pays ->
customer receives a tracking number and is DONE until sign-off -> staff buy, scan and upload the
stamp against **that same** tracking number -> eSign begins.

### 9a. One tracking number, not two

- [x] 9.1 Make the **persisted** reference the single tracking number. Surface it as
  `AgreementResponse.trackingNumber` / `AgreementSummaryResponse.trackingNumber`, replacing the
  derived `AM-<LAST6>-<DDMMYY>` value.
- [x] 9.2 Render the **persisted** reference in the document provenance line
  (`AgreementDocumentService` currently passes `agreement.trackingNumber()`), so the document, the
  customer, and staff all show one value.
- [x] 9.3 Remove the derived `trackingNumber()` veneer and its now-dead helper, keeping the
  `AM-...`-format rejection in reference lookup so an old-format value is never resolved.
- [x] 9.4 Update the `document-footer-tracking-url` behaviour and its tests to the new value.
  Preview/PDF parity must hold - the footer comes from one compiler input.

### 9b. Freeze at finalisation, not at stamp upload

- [x] 9.5 Create the signing request (`PDF_GENERATED`) when the customer **finalises**, not
  inside stamp intake. This reverses the placement chosen in the first pass.
- [x] 9.6 Ensure the agreement is non-editable from finalisation onward (the existing
  `AgreementDisplayStatus.isEditable` rule already freezes once a signing request exists - verify
  it now trips at finalisation).
- [x] 9.7 Stamp intake advances an existing `PDF_GENERATED` request to `STAMPED`; it MUST NOT
  create the request. A rejected upload still leaves the request in `PDF_GENERATED`.
- [x] 9.8 Return the tracking number to the customer on finalisation.

### 9c. Staff console

- [x] 9.9 STAFF-only backend endpoint listing agreements awaiting stamp intake: tracking
  reference, property city, agreement date, waiting time, payment state where available.
  Longest-waiting first. Exclude stamped, closed, and terminally-failed agreements.
- [x] 9.10 Vue staff console view listing the queue, with scan upload **per entry** so the
  reference is never re-typed. Follow existing `src/views/` + `src/api/` conventions.
- [x] 9.11 Route-guard the console to STAFF; a non-staff caller sees no queue size or agreement
  detail.

### 9d. Tests

- [x] 9.12 **Unit:** one reference everywhere (response, document, staff lookup); old `AM-...`
  format still rejected as a lookup key; finalisation creates the request and freezes editing;
  intake advances rather than creates.
- [x] 9.13 **Integration:** finalise -> queue shows the order -> upload from the queue entry ->
  `STAMPED` and the order leaves the queue; stamped/closed agreements never appear; non-staff
  gets no queue.
- [x] 9.14 **Frontend:** console renders the queue and uploads without re-typing the reference;
  non-staff cannot reach the route.
- [x] 9.15 Re-run `./gradlew spotlessApply`, the backend suite (0 failures, 0 skipped), and the
  frontend tests.

## Implementation notes

- **Where the signing-request row is born (REVISED in section 9).** It is created when the CUSTOMER
  finalises (`POST /api/agreements/{id}/finalise`), which places the order and freezes the terms from
  that instant. Stamp intake only ever ADVANCES `PDF_GENERATED -> STAMPED`; an upload against an
  agreement that was never finalised is refused `409 order-not-placed` rather than silently creating
  an order on the customer's behalf. `createSignRequest` then advances the `STAMPED` row.
- **Finalise is a new endpoint.** No finalisation concept existed: generate-as-draft
  (`POST /{id}/document`) is repeatable by design and is "save", not "commit" - so a separate
  `POST /{id}/finalise` was added rather than overloading it. It is `permitAll` like the rest of the
  anonymous drafting surface, and it is the natural seam for the payment gate to sit behind.
- **One tracking reference.** The persisted checksummed value IS the tracking number: returned as
  `AgreementResponse.trackingNumber` / `AgreementSummaryResponse.trackingNumber`, rendered in the
  document provenance line, and quoted by staff at intake. The derived `AM-<LAST6>-<DDMMYY>` helper
  is deleted; its format is still recognised so an old value is refused deliberately at lookup. V15
  renames the column `staff_reference -> tracking_reference` and re-prefixes `AMS` -> `AM` (body and
  check character unchanged, so every existing value stays valid and unique).
- **Frontend scope.** The staff console (`/staff`) is built and route-guarded. A customer-facing
  "finalise and pay" button is deliberately NOT wired: it belongs with the `razorpay-payment` change,
  which is a separate change not to be anticipated here. The backend endpoint exists and is tested.
- **401 vs 403.** The app previously returned 403 for every denial (no `AuthenticationEntryPoint`).
  A `DelegatingAuthenticationEntryPoint` now returns 401 for `/api/staff/**` only; every other
  route keeps its historical 403, so the default-deny baseline test is unchanged.
- **Not built (out of scope per design non-goals):** a staff queue/console surface. The staff
  reference is persisted and unique but is not yet exposed on any customer-facing or staff-facing
  response - there is no order or queue endpoint to surface it against. Operators read it from the
  `agreement.staff_reference` column (documented in `README.md`).
- **Blob-before-database ordering.** The scan and stamped PDF are written to object storage before
  the certificate row is committed (existing D9 precedent: no transaction spans a network write).
  A duplicate-certificate 409 therefore leaves two orphan blobs under agreement-scoped keys, which
  a later successful upload overwrites. No stamp data is persisted.

## 11. Optional signing kick-off from the stamp upload

- [x] 11.1 Carry an optional "start signing" instruction on the upload request and command.
- [x] 11.2 Start signing AFTER the stamp is durably attached, never inside the attach path,
      and never in a way that can undo it.
- [x] 11.3 Report the stamp outcome and the signing outcome separately in the response.
- [x] 11.4 Staff console: a checkbox on the upload form, and a result line that names both
      outcomes.
- [x] 11.5 Unit: flag off starts nothing; flag on starts signing; a signing failure leaves
      the stamp attached and is reported as not-started.
- [x] 11.6 Integration: the endpoint honours the flag end to end against a stubbed provider.
