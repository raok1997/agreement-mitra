## Why

CR-3b lets a user **preview** the filled agreement, but the document they see is ephemeral -- it is
not the file that gets signed. Today the draft that feeds stamping + Aadhaar eSign is a **manual PDF
upload** (`draft-ingestion`). This increment closes the guided-flow loop: **generate** the rendered
document and **store it as the agreement's signable draft**, so capture -> preview -> generate ->
stamp -> eSign flows with no manual upload.

Third and final increment of the CR-3 arc (CR-3a render / CR-3b preview / **CR-3c generate-draft**).

## What Changes

- **Generate endpoint** -- `POST /api/agreements/{id}/document` renders the agreement into the
  rental-agreement template (the same render as preview) and **stores the PDF as the agreement's
  draft** through the existing draft path (`DraftService.attachDraft` -> object storage,
  `draft_pdf_key`). On success it responds `200 OK` with the agreement id. `404` for an unknown
  agreement.
- **Overwrite-until-signing, then locked** -- generation is permitted while the agreement has **no
  signing request yet**, and **overwrites** any prior draft (uploaded or generated). Once a signing
  request exists, the draft is **locked** and generation is rejected (`409`) -- reusing the exact
  freeze rule the upload path already enforces, so a document cannot change under an in-flight
  signature.
- **Reuse, not reinvent** -- the render comes from the CR-3b `AgreementDocumentService`; the store +
  freeze-check + PDF-validation come from the existing `DraftService.attachDraft`. Downstream
  (stamping, eSign, webhook) cannot distinguish a generated draft from an uploaded one -- same key,
  same pipeline.
- **Frontend** -- a **"Use this document"** action on the capture screen (after preview) that calls
  the generate endpoint and confirms the document is saved as the signable draft, ready for signing.
  API call in `src/api/client.ts`. Still anonymous.
- **Security** -- permit `POST /api/agreements/*/document` (method-and-path-scoped), consistent with
  the existing anonymous draft-upload path.

No signing-status FSM change; generation happens before signing. No DB schema change (the generated
draft reuses `agreement.draft_pdf_key` and object storage).

## Capabilities

### Modified Capabilities
- `draft-ingestion`: an agreement's draft PDF MAY now be **system-generated** from the
  rental-agreement template (`POST /api/agreements/{id}/document`), not only uploaded. However
  produced, the draft is stored under the same object-storage key and feeds the existing stamp +
  eSign flow unchanged; generation is overwrite-until-signing, then locked (same freeze rule as
  upload).

## Impact

- **`signing` module**: a generate handler on `AgreementController` that orchestrates
  render (`AgreementDocumentService`) + store (`DraftService.attachDraft`). No new persistence, no
  new mapping -- both collaborators already exist.
- **Security**: one added matcher (`POST /api/agreements/*/document`).
- **Frontend**: a "Use this document" action + `generateAgreementDocument` call in `src/api/client.ts`.
- **No** change to: the signing FSM, stamping, the eSign/webhook flow, the `documents` module, or the
  DB schema.

## PII / security review checklist

- **Introduces or moves Aadhaar/OTP/VID/PII or secrets?** The generated document **contains full
  party PII** (names, fathers' names, addresses) and is now **persisted** as the draft PDF -- but in
  **object storage** (never Postgres), reusing the existing `draft_pdf_key` under which uploaded
  drafts already live. No Aadhaar number, OTP, virtual id, or government identifier is added; no
  secrets introduced.
- **How redacted/secured?** The PDF bytes are **never logged** (render + `DraftService` both avoid
  logging content); the store path is the existing, reviewed object-storage adapter; the render is
  offline with Gotenberg's outbound network denied and party data HTML-escaped (inherited guards).
  The generate response returns only the agreement id, never the bytes.
- **Sandbox + dummy data only?** Preserved -- local rendering + local MinIO storage of dummy
  agreements; no live provider, no real PII, no production credentials.
- **Signing-status FSM transitions touched?** **None** -- generation precedes signing; the freeze
  rule keys off "a signing request exists", it introduces no new state.
- **Async signing / webhook flow touched?** **None** -- no sequence diagram required.
