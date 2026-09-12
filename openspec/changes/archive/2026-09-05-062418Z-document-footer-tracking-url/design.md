## Context

The `documents` module compiles an agreement to HTML with a domain-agnostic `TemplateCompiler`
(`documents.template`) and renders that HTML to PDF via a Gotenberg (headless Chromium) leg behind the
public `HtmlPdfRenderer` seam. Two facts drive this change:

- The **on-screen preview** is an HTML iframe (`<iframe :srcdoc="previewHtml" sandbox="">`) fed by the
  stateless preview's `text/html` variant -- a **continuous, non-paginated** document.
- Any document identity today lives only in Chromium **footer furniture** (PDF-only) and only on the
  saved-agreement render (the raw UUID). The preview shows nothing; the client never surfaces a readable
  tracking number.

The requirement: the reader must see the **tracking number + platform URL** in **both** the preview and
the PDF, and the client must **hold** the tracking number after save. Page numbers are a paginated-PDF
concept and cannot render in the continuous HTML preview.

Constraints: Java 21 + Spring Boot 3.5.x + Spring Modulith; records for DTOs/value objects; constructor
injection; package-private by default, `public` only on module API; the `documents` module stays
**domain-agnostic** (no signing type, no brand literal in code); `TemplateCompiler` stays a **pure
function** of its inputs; keep `ModularityTests` green and preview<->PDF body parity intact; sandbox +
dummy data only; never log HTML/PDF; every rendered value HTML-escaped; render stays offline.

## Goals / Non-Goals

**Goals:**
- Render a system-owned **provenance line** (tracking number + platform URL) at the compiled document
  foot, in **both** the preview HTML and the PDF (parity).
- Keep **page numbers** as PDF furniture (they cannot exist in continuous HTML).
- Derive a human-friendly **tracking number** `AM-<LAST6>-<DDMMYY>` at render time (no persistence).
- **Expose the tracking number to the client** (`AgreementResponse.trackingNumber`) and have the
  frontend display it after save and feed it into the preview.

**Non-Goals (this increment):**
- No **persisted** tracking number, no per-tenant sequence, no human-friendly sequential id
  (`AM-2026-0001A7`) -- follow-up (D1), needs a migration.
- No per-page repetition of the reference/URL (a body element renders once at the foot; only page
  numbers repeat per page, in furniture).
- No change to the effective-template identity/pin, the resolver, stamping, the signing FSM, or the
  eSign flow.

## Decisions

### D1: Derive the tracking number; do not persist a number
Computed at render time from the agreement's existing id and start date: `"AM-" + last4Upper(id) + "-" +
ddMMyy(startDate)`. No new column, no sequence, no migration.
Rejected -- a persisted gap-free sequential number (`AM-2026-0001A7`): reads best but needs a column +
allocation + migration; a clean follow-up if the business wants it.

### D2: The number is display-only; the full UUID stays the authoritative audit tie
`last4` = 4 hex = 24 bits = 16,777,216 values; scoped to a date the birthday bound bites early, so the
number is **not collision-free** and must never key the artifact->audit tie. The **full UUID stays
canonical** (DB PK, storage keys, provider ids). `AgreementResponse.trackingNumber` is likewise
display-only; the response keeps the raw `id` as the identifier.

### D3: Hybrid -- the provenance is a SCREEN-ONLY body line (preview) + Gotenberg FURNITURE (PDF)
The reference + URL must show on-screen (the preview iframe renders body HTML; furniture is PDF-only) AND
on the PDF. A pure body line fails in the PDF: as trailing flow content it **orphans onto its own last
page** and, pinned with `position:fixed`, **overlaps content** (a fixed element cannot live in the page
margin). So the two faces are served by two system-owned renderings of the same resolved reference + URL:
- **Preview (screen):** a body `<div class="doc-provenance">` at the document foot -- `display:none` by
  default, shown only under `@media screen`. The iframe (screen media) shows it; the reader sees the
  reference + URL in the preview.
- **PDF (print):** the render emulates **print** media (`emulatedMediaType=print`), which hides the
  screen-only body line (so it never orphans/overlaps), and Gotenberg stamps the reference + URL as
  **footer furniture in the reserved bottom margin**, per page, clear of the content.

Parity is preserved: `previewHtml` is byte-for-byte the HTML handed to the renderer (the same body,
including the screen-only line and its media rules); only the render medium differs (screen vs print),
which is inherent to any HTML-vs-PDF render.
Rejected -- (a) pure body flow line (orphans onto its own page -- the reported bug); (b) `position:fixed`
body line (overlaps content, cannot sit in the margin); (c) furniture-only (never shows in the preview --
the earlier gap).

### D4: Page numbers + reference + URL are Gotenberg footer furniture in the PDF; table layout for the total
`Page X of Y` is produced by Chromium's running `.pageNumber`/`.totalPages`, which only exist in
Gotenberg's header/footer template (a continuous HTML preview has no pages). The PDF footer furniture
therefore carries all three -- reference + URL (left) and `Page X of Y` (right) -- stamped on every page,
in the reserved margin (no content overlap, no orphan). The footer uses a **table** layout, not flex:
Chromium reliably fills `.totalPages` in a table-based footer, whereas a flex row leaves the **total
blank** (the reported "of Y is missing"). It is furniture, so the effective-template pin is unaffected.

### D5: Compiler stays pure -- reference + URL are resolved inputs, URL from config
The compiler renders the provenance line from two **resolved values passed into `compile`** (mirroring
how the execution date is already resolved at the projection layer and passed in): the **reference**
from the request (`documentReference`) and the **platform URL** resolved by `DocumentProjectionService`
from configuration. The compiler reads no clock and no config; the `documents` module holds **no brand
literal** (URL default blank -> that part omitted). A blank reference omits its part (so a pre-id
preview can carry only the URL + marker).
Rejected -- the compiler reading config directly (breaks its purity and the domain-agnostic boundary).

### D6: Date source = the agreement's start date ("Agreement date" on the document)
`DDMMYY` from the agreement's **start date** (the date printed on the document), zero-padded. `NOT
NULL` (V7), always safe; the draft freezes once a signing request exists, so the number is stable for
the signed artifact.
Rejected -- `createdAt`: immutable but less meaningful and can differ from the printed date.

### D7: Pre-save preview shows the URL + a marker, not a fabricated number
The stateless preview (pre-save) has no agreement, so no tracking number exists. The reference passed is
the `PREVIEW - NOT FOR EXECUTION` marker, so the provenance line reads `PREVIEW - NOT FOR EXECUTION .
agreementmitra.com`. Once saved, the frontend passes the real `trackingNumber` (from the create response)
into the preview request, so the post-save preview body shows the real number.
Rejected -- minting a provisional id (contradicts statelessness; prints a meaningless reference).

### D8: The tracking number is exposed to the client via the response, derived server-side
`AgreementResponse` gains `trackingNumber`, derived by the same server-side helper that stamps the
document, so the client shows the **identical** value (no drift from a duplicated client derivation).
Rejected -- deriving `AM-<LAST6>-<DDMMYY>` in the frontend from the raw id: duplicates the format in
two languages and drifts if it ever changes.

## Component shape

- **`documents.template`.** `TemplateCompiler.compile(...)` takes two new resolved inputs (reference,
  platformUrl) and emits a system-owned provenance line at the document foot (escaped; each part omitted
  when blank). `DocumentProjectionService` resolves the platform URL from configuration and passes the
  request's `documentReference` + the URL into `compile` on the single compile path (preview, previewPdf,
  generate all share it -> parity).
- **`documents` (furniture).** `GotenbergClient` footer furniture reduced to page numbers only, stamped
  on every PDF render.
- **`documents` config.** `DocumentFooterProperties(String platformUrl)` (`documents.footer.*`, blank
  default) -- made reachable to `DocumentProjectionService` (e.g. public within the module / injected)
  so the URL is resolved once and passed into `compile`.
- **`signing`.** `AgreementDocumentService` builds `AM-<LAST6>-<DDMMYY>` and passes it as the
  reference; `AgreementResponse` + `AgreementService.toResponse` (and `AgreementDocumentMapper`/helper)
  expose `trackingNumber` (derived, not stored).
- **Frontend.** `client.ts` `AgreementView`/`createAgreement` carry `trackingNumber`; `CaptureForm.vue`
  shows it on the save confirmation and includes it in the preview request (`documentReference`) so the
  post-save preview body shows it.
- **Config.** `documents.footer.platform-url: ${DOCUMENT_FOOTER_PLATFORM_URL:agreementmitra.com}`.

## Risks / Trade-offs

- **Reworks the interim furniture footer** -- the reference + URL move from furniture to body; furniture
  keeps only page numbers. Contained; covered by revised tests.
- **Provenance renders once, not per-page** (D3/D4) -- accepted: per-page repetition survives via the
  PDF page numbers; the reference is a foot-of-document line, like the signature block.
- **Body parity is load-bearing** -- the provenance line must be in the compiled body both faces share;
  a parity test guards it.
- **Label collisions** (D2) -- accepted: display-only; UUID stays authoritative.

## Migration Plan

**No database migration.** The tracking number is derived from existing columns; `trackingNumber` on the
response is derived, not stored; the platform URL is an additive non-secret config property with a blank
code default. No dependency change -> **no `gradle.lockfile` change**, nothing new on the OSV/SpotBugs
surface.

## Open Questions

- **Sequential reference number?** A true gap-free reference is the D1 follow-up (column + allocation +
  migration).
- **Coordination with `agreement-execution-block`** -- reconcile the footer requirement on archive.
