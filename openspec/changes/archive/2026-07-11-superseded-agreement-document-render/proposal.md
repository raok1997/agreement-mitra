## Why

The guided flow can now capture an agreement's parties, property, money, and dates
(`rich-agreement-capture`), but the user cannot yet **see the agreement**: the `documents`
module's `DocumentRenderer` is an interface with **no implementation**, and the draft PDF
that feeds stamping and eSign is still a **manual upload**. The screens we are building need
the opposite -- take the captured data, **embed it into a rental-agreement template, and
show the filled document on screen** -- and then use that rendered document as the draft
that flows into the existing stamp and eSign path.

This CR implements rendering for **one bundled rental-agreement template**. The searchable
**template catalog is parked** (`CR-2`, good-to-have); until it lands, this CR ships a single
default template, so a user can preview and proceed without choosing. Rendering uses headless
**Chromium via a Gotenberg service**, per the architecture decision: only Chromium shapes
complex Indic scripts correctly, and Noto fonts are bundled for coverage (a pure-Java PDF
library cannot).

## What Changes

- **Implement `DocumentRenderer`** in the `documents` module by calling a **Gotenberg** render
  service (open source, MIT -- a prebuilt Chromium-in-a-container) over HTTP: a bundled HTML
  template + a data map are rendered to self-contained HTML (server-side templating), POSTed to
  Gotenberg, which returns the PDF. **No browser binary or Playwright lives in the app** --
  Gotenberg runs as a `docker-compose` service alongside Postgres/MinIO, pulled once as an
  image (no runtime browser download). The module stays generic -- `renderPdf(templateId,
  data)` -- and knows nothing about agreements.
- **Bundle one rental-agreement template** (an HTML resource) with **Noto fonts** (baked into
  the Gotenberg image / sent as render assets), and send **self-contained HTML** so the render
  loads **no external resource**; Gotenberg is configured to **deny outbound network**
  (SSRF / data-exfiltration guard).
- **Map an agreement to template data in the `signing` module**: build the data map from the
  agreement (owners and tenants with full name / father's name / current address, property
  address, monthly rent, security deposit, start and end dates, and the derived duration in
  months) and call `DocumentRenderer`. The `signing` module depends only on the `documents`
  module's **public `DocumentRenderer` interface** (Modulith-clean).
- **Preview endpoint** -- `GET /api/agreements/{id}/preview` renders the filled template and
  returns it as an **inline PDF** for on-screen display (rendered on demand, not stored).
- **Generate-as-draft** -- `POST /api/agreements/{id}/document` renders the template and
  **stores the result as the agreement's draft PDF** (via the existing draft mechanism), so
  the guided flow feeds stamping and eSign **without a manual upload**.
- **Escape all user-supplied data** in the template (the template is trusted, the party data
  is not) and **block network access** during the Chromium render, so a crafted party field
  cannot inject content or trigger an outbound fetch.
- **Frontend** -- the capture screen gains a **Preview** panel that embeds the rendered PDF,
  and a "use this document" action that generates the draft and proceeds. Still anonymous
  (no login to draft or preview).

No signing-status FSM state or transition changes; rendering happens **before** signing.
Stamping, the eSign provider, webhook intake, and object storage are otherwise unchanged.
There is **no database schema change** (the generated draft reuses the existing
`draft_pdf_key`).

## Capabilities

### New Capabilities
- `document-rendering`: turn a template id + a data map into a PDF via headless Chromium
  (a Gotenberg service) with bundled fonts and offline rendering; the concrete
  `DocumentRenderer`, the
  single bundled rental-agreement template, the agreement-to-data mapping, and the preview +
  generate-draft endpoints.

### Modified Capabilities
- `draft-ingestion`: an agreement's draft PDF MAY now be **system-generated** from the
  rental-agreement template (not only uploaded). The stored draft, however it is produced,
  feeds the existing stamp and eSign flow unchanged.

## Impact

- **`documents` module**: implement `DocumentRenderer` as a **Gotenberg HTTP client**, add a
  server-side HTML template engine, bundle the rental-agreement template + Noto fonts, and send
  self-contained HTML. Expose `DocumentRenderer` as the module's public API (named interface);
  `ModularityTests` stays green.
- **`signing` module**: an agreement-to-template-data mapper, plus `preview` and
  `generate-document` endpoints on the agreement API; the generated PDF is stored through the
  existing draft path.
- **Infra + dependencies**: add a **Gotenberg service to `docker-compose`** (a thin image
  carrying the Noto fonts); add an **HTTP client + the template engine** to the backend and
  **regenerate the Gradle lockfile** + run the OSV/SpotBugs gates. **No Playwright, no browser
  binary in the app, no runtime download** -- Gotenberg's image is pulled once like any other
  compose image. The Gotenberg base URL comes from an env var (like the S3 endpoint).
- **Security**: user data is HTML-escaped; the render is **self-contained with Gotenberg's
  outbound network denied**; rendered PDFs (full party PII) are streamed **inline with
  `no-store`** and never logged.
- **No** change to: the signing FSM, `EsignProvider`/webhook flow, stamping, or the DB schema.

## PII / security review checklist

- **Introduces or moves Aadhaar/OTP/VID/PII or secrets?** The rendered document **contains
  full party PII** (names, father's names, current addresses) composed into a PDF. No Aadhaar
  number, OTP, virtual id, biometric, or government identifier is added; no secrets are
  introduced.
- **How redacted/secured?** The PDF bytes are **never logged**; preview responses stream the
  PDF **inline with `Cache-Control: no-store`** (no PII cached by intermediaries); generated
  drafts are stored in **object storage** (never in Postgres), reusing the existing draft
  key. Party data is **HTML-escaped** before templating (no content injection), and the
  Chromium render runs **offline with all network blocked** (a crafted field cannot exfiltrate
  data or fetch a remote resource -- SSRF/exfil guard). No request body or rendered content is
  echoed to logs.
- **Sandbox + dummy data only?** Preserved -- local rendering of dummy agreements; no live
  provider, no real PII, no production credentials.
- **Signing-status FSM transitions touched?** **None** -- rendering precedes signing and adds
  no state or transition.
- **Async signing / webhook flow touched?** **None** -- rendering is independent of the
  eSign/webhook path; no sequence diagram required.
