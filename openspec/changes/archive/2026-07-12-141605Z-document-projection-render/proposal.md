## Why

CR-1 (`template-html-compiler`) delivered the `TemplateCompiler` + `SubmittedDataValidator`
*unwired* -- nothing renders or serves them. Today the `documents` module still draws the agreement
from **one hardcoded Thymeleaf template** (`documents/rental-agreement.html` via `TemplateAssembler`),
and the id-bound preview / generate path renders through the templateId-gated
`DocumentRenderer.renderPdf`.

This change -- **second of four** superseding `template-document-projection` -- **wires the compiler
into the render path** and exposes it over HTTP:

- factors a small **public `HtmlPdfRenderer` seam** over the existing package-private
  `GotenbergClient` so the compiler's HTML can become a PDF without reaching a package-private client
  across packages (offline / Chromium-network-denied / never-log preserved);
- adds a package-private **`DocumentProjectionService`** that resolves -> validates -> compiles ->
  (optionally) renders, feeding the **same** compiled HTML to both the live pane and the PDF (parity
  by construction);
- extends the public `documents.api` named interface with a **`DocumentProjectionApi`** port + DTOs
  and a **`DocumentProjectionController`** exposing `POST /api/templates/document/preview`
  (content-negotiated HTML/PDF, `no-store`, persists nothing);
- **retires** `TemplateAssembler` + `documents/rental-agreement.html` + the templateId-gated
  `DocumentRenderer` render path, and **rewires** the id-bound signing preview
  (`GET /api/agreements/{id}/preview`) to source its HTML from the compiler;
- **removes** the now-superseded stateless `POST /api/agreements/preview`
  (`preview-centric-capture`'s placeholder against the hardcoded template) -- the new
  `/api/templates/document/*` route realizes that intent (design D6).

The reproducibility **pin** at generate-as-draft and its migration are **CR-3**
(`agreement-template-pin`); the frontend wiring is **CR-4**.

## What Changes

- **Single renderer, parity (non-negotiable):** the live-preview HTML and the signed-PDF source are
  the **same** `TemplateCompiler` output. `DocumentProjectionService` compiles once and either returns
  that HTML or hands the **identical** HTML to `HtmlPdfRenderer.toPdf(html)`. A **parity test** asserts
  the preview HTML equals the PDF's HTML source.
- **Public HTML -> PDF seam:** add `HtmlPdfRenderer { byte[] toPdf(String html); }` in the root
  `in.agreementmitra.documents` package, implemented package-private over `GotenbergClient`
  (network-denied, never-logs). Retire `TemplateAssembler`, `documents/rental-agreement.html`,
  `GotenbergDocumentRenderer`, and the `DocumentRenderer` interface for the agreement path. Relocate
  the Noto `@font-face` embedding so the single compiled HTML is self-contained for the browser and
  identical for both tiers (parity preserved).
- **Two render tiers (no Gotenberg in the keystroke loop):** the live HTML preview is compiled on
  each section save (cheap, no Gotenberg); the Gotenberg PDF runs **only** on explicit Download PDF
  and the final generate-as-draft. Content negotiation selects the tier on the one route.
- **Server-side validation before render:** `DocumentProjectionService` runs `SubmittedDataValidator`
  (preview = present-only/tolerant; generate = full) before any compile/render; invalid data is
  rejected via the RFC 9457 contract (CR-1's `DocumentDataInvalidException`), rendering nothing.
- **Stateless document-projection endpoint** (new public `documents.api` surface):
  `POST /api/templates/document/preview` -- body `{ dimensions?, data }`; `Accept: text/html` ->
  escaped HTML (live pane), `Accept: application/pdf` -> Gotenberg PDF (Download PDF). `Cache-Control:
  no-store`, iframe-safe CSP on the HTML variant, **persists nothing**, placeholders for missing
  fields, rendered bytes/HTML and submitted values **never logged**.
- **Signing rewire (render only, no pin yet):** `AgreementDocumentService` calls the `documents`
  `DocumentProjectionApi` instead of `renderPdf(templateId, data)`, mapping the `Agreement` to the
  effective template's **declared field keys** (`documents` stays domain-agnostic -- it receives a
  field-key data map, never a signing type). The id-bound `GET /api/agreements/{id}/preview` now
  sources its HTML from the compiler.
- **Security:** permit `POST /api/templates/document/preview` in `SecurityConfig` alongside the other
  anonymous render endpoints; remove the `POST /api/agreements/preview` permit with that endpoint. No
  change to any authenticated matcher, the signing permit, or the webhook permit.

**Explicitly not in this change:** the reproducibility pin + `V9` migration + generate-as-draft full
render/pin (**CR-3**); the frontend client + shell wiring (**CR-4**).

## Capabilities

### New Capabilities

- `template-document-projection` (wiring): single-renderer parity (live HTML == PDF source,
  parity-tested); the public `HtmlPdfRenderer` seam and package-private `DocumentProjectionService`;
  the stateless `no-store` `POST /api/templates/document/preview` (HTML + Gotenberg PDF) that persists
  nothing and renders placeholders; the two render tiers; server-side validation before render mapped
  to RFC 9457; offline render + never-log preserved; `ModularityTests` green (one existing named
  interface, extended).

### Modified Capabilities

- `document-rendering`: the renderer's HTML source changes from the single hardcoded Thymeleaf
  template to **HTML compiled from the effective template definition** (`TemplateCompiler`).
  `TemplateAssembler` + `rental-agreement.html` are retired and the templateId-based
  `DocumentRenderer.renderPdf` seam is superseded by the definition-driven `HtmlPdfRenderer` +
  document-projection API. Invariants **preserved and strengthened**: user data stays HTML-escaped
  (now at compile time), rendering stays **offline** with Gotenberg's outbound network denied, and the
  composed HTML + PDF bytes are **never logged**.
- `agreement-preview` / `preview-centric-capture`: the preview is now **definition-driven** and its
  source is the same compiler that produces the signed PDF (parity). A new **stateless**
  `POST /api/templates/document/preview` renders an in-progress working-set data map (`no-store`,
  nothing persisted, placeholders for missing fields); the existing `GET /api/agreements/{id}/preview`
  keeps its `no-store` / no-PII-in-logs contract while sourcing HTML from the compiler. The old
  stateless `POST /api/agreements/preview` placeholder is **removed** (superseded by the new route).

## Impact

- **`documents` module**: adds the public `HtmlPdfRenderer` seam + its package-private Gotenberg impl
  (over the existing `GotenbergClient`), a package-private `DocumentProjectionService` in
  `documents.template`, and -- in the public `documents.api` named interface -- a
  `DocumentProjectionApi` port, request/result DTOs, and a `DocumentProjectionController`. Retires
  `TemplateAssembler`, `GotenbergDocumentRenderer`, `DocumentRenderer`, and
  `documents/rental-agreement.html`; relocates the `@font-face` embedding into `documents.template` so
  parity holds. `ModularityTests` stays green (one existing named interface, extended; the service +
  compiler stay package-private).
- **`signing` module**: `AgreementDocumentService` is rewired to the `documents`
  `DocumentProjectionApi` (mapping the `Agreement` to the effective template's declared field keys);
  the old stateless working-preview methods + `PreviewAgreementRequest` + `POST /api/agreements/preview`
  are removed. `signing` still depends only on the `documents` public interface.
- **Security**: `POST /api/templates/document/preview` added to the anonymous permit set;
  `POST /api/agreements/preview` permit removed. No authenticated matcher / signing / webhook change.
- **Dependencies**: **none added.** Escaping uses `HtmlUtils` (already on the classpath). **No
  `gradle.lockfile` change.**
- **Data / schema**: **none** in this CR (the pin + `V9` migration are CR-3). PDF blobs stay in
  object storage.
- **No** change to: the signing-status FSM, `EsignProvider` / webhook flow, stamping, object storage,
  the reconciliation job, or the draft-storage path.

## PII / security review checklist

- **Introduces or moves Aadhaar/OTP/VID/PII or secrets?** **Party PII (not Aadhaar/OTP/VID).** This CR
  renders party PII (names, addresses, rent, dates) into HTML and PDF and serves it over a stateless
  endpoint. **No** Aadhaar number, one-time code, virtual id, biometric, or government identifier is
  collected, stored, or rendered here (those enter only at eSign, out of scope); **no secret** is
  introduced.
- **How redacted/secured?**
  - **Escape at compile time** (from CR-1) -- every `{{slot}}` is HTML-escaped; user data can never
    become document structure or active content.
  - **`showWhen` runs only in the sandbox** (from CR-1) -- never Thymeleaf SpringEL or any expression
    engine.
  - **Never logged:** the composed HTML, the PDF bytes, and submitted values are never written to any
    log; the `HtmlPdfRenderer` impl preserves `GotenbergClient`'s existing no-log guarantee.
  - **Stateless preview persists nothing and is `no-store`:** `POST /api/templates/document/preview`
    stores nothing server-side and is served `Cache-Control: no-store` with an iframe-safe CSP on the
    HTML variant.
  - **Offline render preserved:** the compiled HTML is self-contained (fonts by family / embedded
    faces as data-URIs, no external URLs); Gotenberg's outbound network stays denied
    (`CHROMIUM_DENY_PUBLIC_IPS` / `CHROMIUM_DENY_PRIVATE_IPS`), so no data value triggers an outbound
    request (SSRF/exfil guard).
  - **Server-side validation before render:** invalid/out-of-bounds data is rejected (RFC 9457) before
    any document is drawn.
- **Sandbox + dummy data only?** Preserved -- the reference definition/layers are dummy,
  system-authored content; the stateless preview renders dummy in-progress data; no live provider, no
  real PII, no production credentials.
- **Signing-status FSM transitions touched?** **None invented or changed.** The id-bound preview is a
  read; the generate-as-draft transition is unchanged in this CR (CR-3 adds the pin). The `agreement`
  aggregate stays status-less.
- **Async signing / webhook flow touched?** **None** -- this CR stops at rendering + the stateless
  preview; it drives no signing and starts no `EsignProvider` call.
