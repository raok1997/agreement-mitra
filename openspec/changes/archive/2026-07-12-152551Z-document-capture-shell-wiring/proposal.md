## Why

CR-2 (`document-projection-render`) exposed `POST /api/templates/document/preview` (content-negotiated
HTML/PDF, `no-store`, persists nothing) and CR-3 (`agreement-template-pin`) made generate-as-draft pin
the effective template. The `preview-centric-capture` shell still calls the **removed** stateless
`POST /api/agreements/preview` through `src/api/preview.ts`. This change -- **fourth and last** of the
increments superseding `template-document-projection` (design point: the frontend wiring, umbrella task
6) -- points the shell at the new endpoints.

## What Changes

- Add a document-preview API client in `src/api/` (`postDocumentPreview(data, accept)`) that calls
  `POST /api/templates/document/preview` with the working-set **field-key data map** and the chosen
  `Accept` (`text/html` for the live pane, `application/pdf` for Download PDF). All API calls stay in
  `src/api/`. The working set is full party PII: nothing here logs the data map or the rendered
  document.
- Point the `preview-centric-capture` shell's **live-preview pane** at the HTML variant on (debounced)
  section save, **"Download PDF"** at the PDF variant, and **"Save & continue"** at the existing
  create + generate-as-draft (which now pins). Leave the shell layout, section modals, and completeness
  bar unchanged; only the preview engine and the endpoint it calls change.
- Retire the old `src/api/preview.ts` (`fetchWorkingPreviewHtml` / `fetchWorkingPreviewPdf` against
  `/api/agreements/preview`) and its `WorkingPreviewInput` shape (nested `signers[]`), replacing it
  with the field-key data map the form projection already drives.

**Explicitly not in this change:** any backend change (all shipped in CR-1..CR-3); template selection /
catalog browse; the admin builder.

## Capabilities

### Modified Capabilities

- `preview-centric-capture`: the shell's live pane, Download PDF, and Save & continue point at the new
  `documents` endpoints. The live pane renders the compiled HTML from
  `POST /api/templates/document/preview` (`Accept: text/html`) in a sandboxed iframe; Download PDF uses
  the `application/pdf` variant; Save & continue creates the agreement and calls generate-as-draft
  (which pins). The old `/api/agreements/preview` client is removed.

## Impact

- **Frontend (`frontend/src`)**: a new `src/api/` document-preview client; `CaptureForm.vue` (the
  `preview-centric-capture` shell) rewired to it; the old `src/api/preview.ts` removed. No change to the
  shell layout, section modals, widgets, or the completeness bar.
- **Backend**: **none** (all backend behavior shipped in CR-1..CR-3).
- **Dependencies**: **none added.** No `package-lock.json` change beyond what CR-1..CR-3 already
  covered; nothing new on the frontend OSV surface.

## PII / security review checklist

- **Introduces or moves Aadhaar/OTP/VID/PII or secrets?** **Party PII (not Aadhaar/OTP/VID).** The
  client sends the in-progress working-set data map (names, addresses, rent, dates) to the stateless
  preview and receives rendered HTML/PDF. **No** Aadhaar/OTP/VID/biometric/government id or secret is
  handled.
- **How redacted/secured?**
  - The client logs **neither** the working-set data map nor the rendered HTML/PDF.
  - The HTML variant is dropped into a **sandboxed iframe** (via `srcdoc`) and the server sends
    `Cache-Control: no-store` + an iframe-safe CSP; the client persists nothing server-side (the
    stateless preview stores nothing).
  - Any object URL created from a PDF blob for Download PDF is **revoked** by the caller after use.
  - Client-side working set at rest (localStorage) is owned by `preview-centric-capture` (clear on
    Save/reset + TTL); unchanged here. "Persists nothing" remains a server-side statement.
- **Sandbox + dummy data only?** Preserved -- dummy in-progress data rendered locally against the
  sandbox backend.
- **Signing-status FSM transitions touched?** **None** -- Save & continue reuses the existing
  create + generate-as-draft path unchanged.
- **Async signing / webhook flow touched?** **None.**
