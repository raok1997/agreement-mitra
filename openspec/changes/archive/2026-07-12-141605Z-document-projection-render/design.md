## Context

This is the "wire it up" increment of the retired `template-document-projection` umbrella; its
decisions **D1..D6, D8, D9** are reused (see
`openspec/changes/archive/2026-07-12-superseded-template-document-projection/design.md`). CR-1 shipped
the pure `TemplateCompiler` + `SubmittedDataValidator`; this CR connects them to the existing Gotenberg
leg and the HTTP surface, and retires the hardcoded Thymeleaf path. Constraints unchanged: Java 21 +
Spring Boot 3.5.x + Spring Modulith; keep `ModularityTests` green; sandbox + dummy data; never log PII;
the markup/data boundary.

## Decisions (delta from the umbrella)

### D-A: One compiled HTML feeds both tiers -- font embedding preserves parity

`DocumentProjectionService` calls `TemplateCompiler.compile(effective, data)` **once** and uses that
exact string for both the `text/html` response and the `HtmlPdfRenderer.toPdf(...)` input, so the
live-pane HTML is **byte-for-byte** the PDF's HTML source (parity test). Because the browser cannot
reach Gotenberg's bundled fonts, the compiled HTML must be **self-contained** for complex Indic scripts
**and** identical for both tiers. Therefore the Noto `@font-face` **data-URI** embedding (previously in
the root-package `HtmlFontEmbedder`, only on the HTML pane) moves **into the compiled output** so both
tiers share it. Font faces are loaded once at bean init and injected into the compiler; unit tests
construct the compiler with **no** faces (pure, no I/O). Trade-off: Gotenberg requests now carry the
embedded faces (larger payload) rather than resolving by family name -- accepted because **parity is
the named non-negotiable** and this is sandbox; noted as a possible later optimization if payload size
matters.

### D-B: Packaging -- public `HtmlPdfRenderer` seam, service stays package-private (umbrella D8)

`HtmlPdfRenderer { byte[] toPdf(String html); }` is **public** in the root `in.agreementmitra.documents`
package; its impl is package-private over `GotenbergClient`. `DocumentProjectionService` (the
`DocumentProjectionApi` impl) stays **package-private** in `documents.template` so it reads the
package-private records + compiler directly. The public `documents.api` `@NamedInterface` (from form
projection) gains the `DocumentProjectionApi` port + DTOs + `DocumentProjectionController`. No record
visibility widens; `ModularityTests` sees the same one named interface, extended.

### D-C: Retire the hardcoded path without breaking `document-rendering` (umbrella D9)

Retire `TemplateAssembler`, `documents/rental-agreement.html`, `GotenbergDocumentRenderer`, and the
`DocumentRenderer` interface. Keep the Gotenberg HTML -> PDF leg untouched behind `HtmlPdfRenderer` over
the same `GotenbergClient`, so offline / network-denied / never-log are byte-for-byte preserved.
`AgreementDocumentService` maps the `Agreement` to the effective template's **declared field keys**
(first owner -> `ownerName`, first tenant -> `tenantName`, `monthlyRent`, `termMonths -> durationMonths`);
fields the definition declares but the aggregate lacks (e.g. `registrationResponsibility`) are filled
by the effective template's **declared defaults** in the projection service. Multi-party rendering
beyond the reference definition's single-party-per-role fields stays out of scope (a catalog/definition
concern).

### D-D: Default dimensions

With no catalog dimension carried on the agreement yet, the stateless preview and the id-bound preview
resolve a **default** `(state, type) = (IN, residential)` -- the reference base layer's own dimensions
-- when `dimensions` is omitted. The exact default token and how the agreement later carries chosen
dimensions are the catalog CR's concern.

### D-E: Remove the superseded stateless preview (umbrella D6)

`preview-centric-capture` sketched `POST /api/agreements/preview` against the hardcoded template. This
CR removes it (endpoint + `PreviewAgreementRequest` + the mapper's preview methods + its security
permit + its tests) and realizes the intent under `POST /api/templates/document/preview` in
`documents.api`, keeping `documents` domain-agnostic (a field-key data map, not a signing DTO).

## Non-Goals

- The reproducibility **pin** and its `V9` migration (CR-3, `agreement-template-pin`).
- The **frontend** client + shell wiring (CR-4, `document-capture-shell-wiring`).
- Template **selection / catalog**, the admin builder, the layer registry, and the eSign/webhook flow.

## Risks / Trade-offs

- **Font payload on the PDF path** (D-A) -- embedded faces enlarge Gotenberg requests; accepted for
  parity, noted for later optimization.
- **Retiring `TemplateAssembler`** -- guarded by the parity test + a Gotenberg PDF happy-path over the
  definition-compiled HTML; `HtmlPdfRenderer` preserves the exact Gotenberg leg.
- **Field reconciliation (flat vs nested parties)** -- the mapping targets the definition's declared
  keys; multi-party beyond the reference definition is out of scope (bounded mapper change, tested via
  the real reference definition + a dummy data map).
- **Unauthenticated render abuse** -- the stateless route renders from an anonymous body; the hot path
  is HTML-only (no Gotenberg), server-side validation caps payloads by the schema, and **rate-limiting
  is an owed companion control before leaving sandbox** (tracked with the other deferred pre-prod
  controls).
