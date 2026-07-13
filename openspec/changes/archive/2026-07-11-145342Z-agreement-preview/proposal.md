## Why

CR-3a shipped the render **engine** (`DocumentRenderer` + a Gotenberg service + one bundled
template), but nothing maps a captured agreement onto it and there is no way to **see** an agreement
from the app. The guided flow can create an agreement (`rich-agreement-capture`) but the user then
has no on-screen document. This increment closes that: map an `Agreement` to the template data and
**preview the filled document inline** from the capture screen.

It also **upgrades the bundled template** from CR-3a's minimal scaffold to a **complete
India-standard residential rental agreement** -- the parties block (lessor/lessee with father's
name + address), property, term, rent + deposit, and the standard clauses (use, maintenance, notice,
termination, obligations, stamp/registration note, witnesses + signatures) -- filled from the
captured data. No new captured fields are required; the standard clauses are boilerplate.

This is the second of three increments (CR-3a render / **CR-3b preview** / CR-3c generate-draft).

## What Changes

- **Agreement-to-template-data mapper** (`signing` module): from an `Agreement`, build the data map
  the template needs -- owners and tenants grouped by role, each with full name (as per Aadhaar),
  father's name, and current address; property address; monthly rent; security deposit; start and
  end dates; derived duration in months; and the agreement date. `signing` depends **only** on the
  `documents` module's public `DocumentRenderer` interface (Modulith-clean).
- **Preview endpoint** -- `GET /api/agreements/{id}/preview` renders the filled template on demand
  and returns the PDF **inline** (`Content-Type: application/pdf`, `Content-Disposition: inline`,
  `Cache-Control: no-store`). **Not stored.** `404 Not Found` for an unknown id.
- **Upgrade the bundled `rental-agreement.html`** to a complete India-standard residential rental
  agreement (still one bundled template, still Thymeleaf auto-escaped, still fully offline). The
  render-engine requirements are unchanged; the template *content* becomes a real legal format.
- **Frontend** -- a **Preview** action on the capture screen (after an agreement is created) that
  fetches the preview PDF and **embeds it** (object/iframe). API call lives in `src/api/client.ts`.
  Still anonymous (no login to preview).
- **Security** -- permit `GET /api/agreements/*/preview` (method-and-path-scoped, not a wildcard),
  consistent with the existing anonymous agreement read/draft paths.

No signing-status FSM change; preview happens before signing and stores nothing. No DB schema change.

## Capabilities

### New Capabilities
- `agreement-preview`: map an agreement to the rental-agreement template and preview the filled
  document inline (on demand, not stored, non-cacheable), with 404 for an unknown agreement and no
  party PII in logs.

### Modified Capabilities
- `document-rendering`: the single bundled rental-agreement template is upgraded to a **complete
  India-standard residential rental agreement** (parties, property, term, rent/deposit, standard
  clauses, signatures). The render contract (template id + data map -> PDF, offline, escaped) is
  unchanged.

## Impact

- **`signing` module**: an `AgreementDocumentMapper` (agreement -> data map) + an
  `AgreementDocumentService` that loads the agreement, maps it, and calls `DocumentRenderer`; a
  `GET /{id}/preview` handler on `AgreementController`. `signing` gains a dependency on the
  `documents` public `DocumentRenderer` interface only.
- **`documents` module**: the bundled `rental-agreement.html` is rewritten to the India-standard
  format (a template-resource change; no Java API change).
- **Frontend**: a Preview button + embedded PDF viewer on `CaptureForm.vue`; a `fetchAgreementPreview`
  call in `src/api/client.ts`.
- **Security**: one added matcher (`GET /api/agreements/*/preview`).
- **No** change to: the signing FSM, stamping, the eSign/webhook flow, or the DB schema.

## PII / security review checklist

- **Introduces or moves Aadhaar/OTP/VID/PII or secrets?** The preview response **contains full
  party PII** (names, fathers' names, current addresses) composed into a PDF and streamed to the
  browser. No Aadhaar number, OTP, virtual id, biometric, or government identifier is added; no
  secrets are introduced.
- **How redacted/secured?** The PDF bytes and the composed party details are **never logged**;
  preview streams **inline with `Cache-Control: no-store`** (no PII cached by intermediaries); the
  document is **rendered on demand and not stored** (no PII persisted for a preview). Party data is
  **HTML-escaped** before templating (inherited CR-3a guard), and the render runs **offline with
  Gotenberg's outbound network denied**. No request/response body is echoed to logs.
- **Sandbox + dummy data only?** Preserved -- local rendering of dummy agreements; no live provider,
  no real PII beyond the user's own sandbox input, no production credentials.
- **Signing-status FSM transitions touched?** **None** -- preview precedes signing and adds no state.
- **Async signing / webhook flow touched?** **None** -- no sequence diagram required.
