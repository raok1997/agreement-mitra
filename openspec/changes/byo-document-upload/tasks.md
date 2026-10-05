Coordination with `delete-draft-agreement` (noted 2026-10-05, re-check at the next review of this
change):
- **Migration number.** `V22` is already taken (`V22__agreement_last_edited_and_signer_position.sql`),
  and `delete-draft-agreement` plans `V23__draft_deletion.sql`. Renumber task 1.2 to the next free
  version when this change is applied.
- **Draft-stage object keys.** `delete-draft-agreement` lets an owner delete an unpaid draft and removes
  its objects by the single key list `DraftService.draftStageKeys(UUID)` (today only `drafts/{id}.pdf`).
  This change's `uploads/{agreementId}.{pdf,docx}` and `converted/{agreementId}.pdf` hold the customer's
  original document, so they must be added to that list. Otherwise a deleted BYO draft leaves the
  uploaded original in the bucket. Add a task for it and a test that deleting a BYO draft removes them.
  If this change lands first, `delete-draft-agreement` picks the keys up instead.

## 1. Prerequisites and schema

- [ ] 1.1 Confirm `signing-auth` has landed. If it has not, note the partial this change
      absorbs (ownership authZ + rate limit on `/api/agreements/*/draft` and the preview
      fetch only) and keep the `signing-auth` register row open recording it. A `permitAll`
      endpoint that hands attacker-supplied archives to LibreOffice is materially worse than
      one that only stores bytes, so this is the prerequisite not to skip quietly.
- [ ] 1.2 Add `V22__byo_document.sql`: `document_source TEXT NOT NULL DEFAULT 'TEMPLATE'`,
      `duty_state TEXT NULL`, `upload_content_hash TEXT NULL` on `agreement`. Forward-only,
      no backfill, no edit to `V1`-`V21` (design D2).
- [ ] 1.3 Map the three columns on `Agreement` with server-managed mutators only, and confirm
      the app boots under `ddl-auto: validate`.
- [ ] 1.4 Reject all three from every client body (create, edit, capture map), extending the
      existing anti-mass-assignment guard that already covers the pin columns.

## 2. Word conversion infrastructure

- [ ] 2.1 Add the metric-compatible font families to `docker/gotenberg/Dockerfile`
      (`fonts-crosextra-carlito` for Calibri, `fonts-crosextra-caladea` for Cambria,
      `fonts-liberation` for Arial / Times New Roman / Courier New) and refresh the
      fontconfig cache, so a Word file naming them substitutes without reflowing (design
      D11).
- [ ] 2.2 Determine what the LibreOffice route actually does with a linked external
      reference in a `.docx`, then deny the conversion path outbound network at the container
      or network level. `CHROMIUM_DENY_PUBLIC_IPS` / `CHROMIUM_DENY_PRIVATE_IPS` are
      Chromium-scoped and do not cover it. This is a security gate: do not ship the Word
      path without it.
- [ ] 2.3 Add the Gotenberg LibreOffice conversion call (`POST /forms/libreoffice/convert`)
      to the `documents` module, bounded by the same render-permit semaphore and request
      timeout as the HTML route, mapping a conversion failure to a distinct exception that
      carries no converter diagnostic.

## 3. Execution-page rendering (`documents`)

- [ ] 3.1 Add the minimal execution-page layer set (one `render: signatures` section,
      `entries: [ownerName, tenantName]`), not seeded into the catalog (design D1, D4).
- [ ] 3.2 Parameterize page size in `GotenbergClient.renderHtml`, defaulting to today's A4
      constants so existing callers are unchanged (design D5).
- [ ] 3.3 Add the `documents` module API method that renders an execution page for a party
      list at a given page size, returning PDF bytes. No `documents` internal type crosses
      into `signing`.
- [ ] 3.4 Verify the rendered page carries one `esign:<role>` anchor per party, 8px
      `#ffffff`, inside the signature area, extractable from the text layer -- and that it
      carries no covenant, term or recital.

## 4. Upload, conversion and composition (`signing`)

- [ ] 4.1 Detect the uploaded format from content, never from the declared type or filename:
      `%PDF-` for PDF, and for Word the ZIP signature plus confirmation that the archive
      carries an OOXML word-processing document. Refuse any other ZIP without attempting
      conversion.
- [ ] 4.2 Convert a Word upload to PDF on receipt, store the conversion at
      `converted/{agreementId}.pdf`, and carry it forward as the document to validate and
      compose (design D11).
- [ ] 4.3 Extend `DraftService` upload validation with a PDFBox parse step enforcing design
      D10's bounds (unparseable, encrypted, zero pages, over 50 pages, page dimensions
      outside 200-1684pt), as `400 invalid-upload` with no parser detail, filename or
      document content echoed, and nothing stored.
- [ ] 4.4 Make the bounds config-backed (`application.yml`) rather than literals.
- [ ] 4.5 Store the original upload at `uploads/{agreementId}.{pdf,docx}` and compute the
      `upload_content_hash` over the bytes as received, before any conversion (design D6).
- [ ] 4.6 Add the composer that appends the execution page using the `importPage` pattern,
      saving while the source document is still open, and rendering the page at the
      document's first-page size.
- [ ] 4.7 Store the composed document at the existing `drafts/{agreementId}.pdf` key so every
      downstream step is unchanged; keep the existing validate -> freeze-check -> store ->
      attach order and the `draft-frozen` 409.
- [ ] 4.8 Recompose from the retained original when the party list changes while the draft is
      still replaceable; refuse after a signing request exists (existing 409).
- [ ] 4.9 Fail composition closed with a distinct problem type; never leave a stored draft
      that has no anchors.

## 5. Declaration, eligibility and duty

- [ ] 5.1 Accept and persist the declaration: `duty_state` to its column, the remaining duty
      facts through the existing `captureData` map (design D3).
- [ ] 5.2 Require every duty fact explicitly for a BYO agreement rather than defaulting an
      untouched one (design D3, and the `stamp-quote-capture-defaults` edge).
- [ ] 5.3 Resolve a BYO agreement's duty jurisdiction from `duty_state`, keeping `IN`
      inadmissible, and confirm the existing stamp duty calculator quotes it unchanged.
- [ ] 5.4 Refuse order placement for a BYO agreement with an incomplete declaration, as
      problem+json naming what is missing.

## 6. Placement and signing

- [ ] 6.1 Emit the anchored block placement only when `document_source = UPLOAD`, in
      `SigningRequestService.buildSignRequest`, leaving the templated two-placement path
      untouched (design D8).
- [ ] 6.2 Add an `@ExceptionHandler` mapping the missing-anchor `IllegalStateException` to a
      distinct `422` problem type instead of today's unmapped 500 (design D9).

## 7. Review surface

- [ ] 7.1 Branch `GET /api/agreements/{id}/preview` to serve the stored composed draft for an
      agreement with no selected template, `Cache-Control: no-store`, server-set content
      type, no client filename; `404` problem+json when no draft exists yet.
- [ ] 7.2 Keep the stateless template preview template-only and confirm it refuses a BYO
      agreement rather than rendering a default template.

## 8. Frontend

- [ ] 8.1 Add the "Upload your own document" card to `TemplatePicker.vue` as a peer of the
      catalog cards, emitting a BYO start rather than a `(state, type)` selection.
- [ ] 8.2 Build the declaration step (duty facts + parties) reusing the existing form
      widgets, with every duty fact required.
- [ ] 8.3 Build the upload step accepting PDF and `.docx`, with client-side type and size
      feedback, the backend's rejection reasons surfaced as written copy rather than a raw
      status, and a line telling a customer whose layout matters that exporting to PDF
      themselves avoids conversion.
- [ ] 8.4 Build the review step showing the composed document (the **converted** one for a
      Word upload) from the preview route, with the statement that signatures appear on the
      appended execution page and not on every page.
- [ ] 8.5 Add the `LegalDisclaimer` BYO variant ("generated from a template" is false here)
      and wire it into the BYO screens.
- [ ] 8.6 Add the `src/api/` functions for upload, declaration and composed-draft fetch.

## 9. Legal text

- [ ] 9.1 Edit the terms generator source (never `docs/TERMS-OF-SERVICE.md` directly) so the
      terms cover a customer-supplied instrument, including that a Word upload is converted
      and that the converted document is what is signed, and regenerate via
      `npm run legal:doc`.
- [ ] 9.2 Record in `docs/LEGAL-POSTURE.md` what the declaration attests to and what the
      integrity record is for a BYO agreement (uploaded-bytes hash, not a template pin).

## 10. Tests -- unit

- [ ] 10.1 Format detection: PDF by signature; `.docx` by ZIP signature plus OOXML content,
      including under a misleading filename and content type; a plain ZIP refused without
      conversion; a sub-signature and an empty part refused.
- [ ] 10.2 Upload-bounds validation: unparseable, encrypted, zero-page, over-page-count and
      out-of-dimension inputs refused and a valid one accepted, asserting nothing is stored
      and that no parser detail, filename or content reaches the message.
- [ ] 10.3 Composition: composed page count is `sourcePages + 1`; the appended page's
      dimensions equal the source's first page; the source pages are unmodified.
- [ ] 10.4 Execution-page render: one anchor per party, located inside the signature area,
      present in the text layer, and no operative clause in the output.
- [ ] 10.5 Placement: `document_source = UPLOAD` yields exactly one placement per signer (the
      block, on the appended page) and no strip on any page; `TEMPLATE` still yields both.
- [ ] 10.6 Content hash: computed over the uploaded bytes before conversion, ignored from any
      client body, replaced on re-upload, unchanged after freeze.
- [ ] 10.7 Duty-fact mapping: a BYO declaration maps to the same normalized fact set a
      templated agreement produces; an incomplete declaration is refused, not defaulted.
- [ ] 10.8 Frontend unit tests: the picker renders the BYO card and emits a BYO start; the
      upload step accepts both formats and rejects others; the declaration step blocks
      submission on a missing fact; the review step renders the signatures-placement
      statement.

## 11. Tests -- integration

- [ ] 11.1 Slice test of the full BYO backend path against Testcontainers Postgres + MinIO
      and Gotenberg, for a **PDF** upload: create with declaration -> upload -> composed
      draft at the draft key and original at the upload key -> preview returns the composed
      bytes.
- [ ] 11.2 The same path for a **`.docx`** upload: the conversion is stored, the composed
      draft is built from the conversion, the preview returns it, and the hash is over the
      uploaded `.docx`.
- [ ] 11.3 An unconvertible or non-OOXML upload is refused `400` with nothing stored in
      either bucket key.
- [ ] 11.4 Composed draft through stamp intake and signing: the certificate page is
      prepended, anchors are located on the appended page, the signing request is created
      with block-only placements, and the FSM path `PDF_GENERATED -> STAMPED ->
      SIGN_REQUESTED` is unchanged.
- [ ] 11.5 A BYO agreement in a chargeable jurisdiction is quoted and admitted to paid
      fulfilment; one with no `duty_state`, or with `IN`, is refused.
- [ ] 11.6 The freeze rules hold: re-upload and recompose before a signing request, `409
      draft-frozen` after one, with the stored draft and hash unchanged.
- [ ] 11.7 Migration applies forward-only on a live schema and the app boots under
      `ddl-auto: validate`; existing agreements read back as `TEMPLATE`.
- [ ] 11.8 `ModularityTests` stays green (the conversion call and the new execution-page
      method are reached through the `documents` module API only).

## 12. Close-out

- [ ] 12.1 Run `./run-tests.sh` and report the wall-clock duration against the 3-minute
      `check` budget; raise a register row if it has regressed (the Word conversion tests add
      Gotenberg round-trips).
- [ ] 12.2 Run the frontend gates (`npm run build`, `npm run lint`).
- [ ] 12.3 Manual test: upload a real multi-page non-A4 PDF **and** a real `.docx` that names
      Calibri, walk both to the review step, and confirm by inspection that the appended page
      matches the page size, that the anchors sit inside the signature areas, and that the
      converted document has not reflowed.
- [ ] 12.4 Record in `docs/ROADMAP.md`'s follow-up register, before archiving: the
      image-XObject tightening of the footer-band check, the remaining mobile frames (only
      the upload step is mocked at 390px), legacy `.doc`/`.odt` admission, and any
      `signing-auth` partial from task 1.1. Verify with
      `flow-journal.mjs followups --change byo-document-upload`.
- [ ] 12.5 Update `docs/BYO-DOCUMENT-UPLOAD.md` -- its status line, and section 1's
      "`.docx` stays out" paragraph, which this change reverses on evidence (design D11) --
      and `docs/ROADMAP.md` Track A, including removing `.docx` ingestion from the queued
      non-goals.
- [ ] 12.6 `openspec validate --strict byo-document-upload`, then archive with
      `openspec archive -y byo-document-upload`.
