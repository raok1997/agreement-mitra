> **Second of four increments** superseding `template-document-projection`. Depends on **CR-1
> `template-html-compiler`** (the `TemplateCompiler` + `SubmittedDataValidator` it wires up). Reuses
> the resolution engine's `TemplateResolver` + `ClasspathLayerSource` and the existing `GotenbergClient`
> / Gotenberg leg.

## 1. HTML -> PDF seam + document-projection service (packages `documents` root and `documents.template`)

- [x] 1.1 Factor a small **public** `HtmlPdfRenderer { byte[] toPdf(String html); }` in the root
  `in.agreementmitra.documents` package with a package-private `@Component` impl over the existing
  `GotenbergClient` (preserve the offline / network-denied / never-log behavior). Retire
  `TemplateAssembler`, `GotenbergDocumentRenderer`, `DocumentRenderer`, and
  `documents/rental-agreement.html`.
- [x] 1.2 Relocate the Noto `@font-face` data-URI embedding into `documents.template` and inject the
  font `<style>` block into the compiler so the **single** compiled HTML is self-contained for the
  browser **and** identical for both tiers (parity). Delete the root-package `HtmlFontEmbedder`.
- [x] 1.3 Add a package-private `DocumentProjectionService` (`@Service`) in `documents.template`
  implementing the public port (task 2): resolve `dimensions` via `TemplateResolver` (default
  `(IN, residential)` when omitted), validate (CR-1's `SubmittedDataValidator`, preview vs generate),
  compile (CR-1's `TemplateCompiler`), and either return the HTML or call `HtmlPdfRenderer.toPdf(html)`.
  Constructor injection; no internal/entity type crosses to the caller. Parity by construction (one
  compile output feeds both HTML and PDF).

## 2. Public document-projection API + HTTP surface (package `in.agreementmitra.documents.api`)

- [x] 2.1 Extend the existing `documents.api` `@NamedInterface("api")` with a **public**
  `DocumentProjectionApi` port and immutable request/result DTOs (a request `{ dimensions?, data }`;
  a generate result carrying the PDF bytes + the effective-template identity `{ templateId,
  contentHash, layerVersions }` -- the identity is consumed by CR-3's pin). No new named interface.
- [x] 2.2 Add `DocumentProjectionController`: `POST /api/templates/document/preview`,
  content-negotiated -- `Accept: text/html` -> compiled HTML (live pane, iframe-safe CSP);
  `Accept: application/pdf` -> Gotenberg PDF. Set `Cache-Control: no-store`, persist nothing, never
  log the body. Missing fields -> placeholders (partial-mode validation).
- [x] 2.3 Permit `POST /api/templates/document/preview` in `SecurityConfig` alongside the other
  anonymous render endpoints; **remove** the `POST /api/agreements/preview` permit. Do not touch any
  authenticated matcher, the signing permit, or the webhook permit.

## 3. Signing rewire (module `signing`) -- render only, no pin

- [x] 3.1 Rewire `AgreementDocumentService` to call the `documents` `DocumentProjectionApi` instead of
  the templateId-based `renderPdf`: map the `Agreement` to the effective template's **declared field
  keys** (`documents` stays domain-agnostic -- it receives a field-key data map, never a signing type).
  The id-bound `GET /api/agreements/{id}/preview` now sources its HTML from the compiler.
- [x] 3.2 Remove the superseded stateless preview: `POST /api/agreements/preview`,
  `PreviewAgreementRequest`, the working-preview service methods, the mapper's preview-only methods,
  and its `SecurityConfig` permit (task 2.3). Generate-as-draft still stores the render as the draft
  via the existing path (the **pin** is CR-3).

## 4. Tests -- integration (fewer; real wiring + module boundary)

- [x] 4.1 Resolve -> validate -> compile -> HTML for the **real reference definition** (via the
  resolution engine's `ClasspathLayerSource`) + a dummy data map: the composed HTML reflects the
  definition's fields/clauses/sections, slots are escaped, and a `showWhen` clause is included/dropped
  per the data.
- [x] 4.2 **Parity test**: for the same effective template + data, the HTML returned for the live pane
  is byte-for-byte the HTML handed to the PDF renderer (the single-renderer guarantee).
- [x] 4.3 **Gotenberg PDF happy-path** over the definition-compiled HTML, reusing the existing
  Testcontainers pattern (`@Testcontainers(disabledWithoutDocker = true)`, the `./docker/gotenberg`
  image with `CHROMIUM_DENY_PUBLIC_IPS`/`CHROMIUM_DENY_PRIVATE_IPS`): returns non-empty PDF bytes
  starting with the PDF signature; a remote reference triggers no outbound request and still renders.
- [x] 4.4 **MockMvc** test of `POST /api/templates/document/preview`: `Accept: text/html` returns
  `200` + `Cache-Control: no-store` and the compiled HTML; `Accept: application/pdf` returns an inline
  PDF (no-store); **nothing is persisted** (no agreement/draft/blob created); missing fields render as
  placeholders; an out-of-bounds value returns an RFC 9457 error that echoes no data value.
- [x] 4.5 **PII-never-logged** integration assertion across a preview (and the id-bound preview): no
  log line contains the rendered bytes or composed party details (extends the existing preview no-log
  test).
- [x] 4.6 Keep `ModularityTests` green: document projection is exposed only through the existing
  `documents.api` named interface (extended); `DocumentProjectionService` stays package-private in
  `documents.template`; no new disallowed cross-module dependency; `signing` depends only on the
  `documents` public interface. Update the retired-path tests (delete `TemplateAssembler*`,
  `GotenbergDocumentRenderer*`, `HtmlFontEmbedder*`, `WorkingPreviewIntegrationTest`,
  `PreviewAgreementRequestValidationTest`; adjust `AgreementDocumentMapperTest` /
  `AgreementControllerTest`).

## 5. Wrap-up

- [x] 5.1 `./gradlew spotlessApply` then `./gradlew check` (tests, `securityScan`, `ModularityTests`,
  JaCoCo gate) green. Confirm **no new dependency and no `gradle.lockfile` change**.
  _Done: `spotlessApply` + `check` green (327 tests incl. `ModularityTests`, JaCoCo, SpotBugs SAST);
  the OSV dependency-scan leg of `securityScan` was skipped locally (no `osv-scanner` binary on this
  host) — no dependency was added and this change made no `gradle.lockfile` edit (the lockfile lines
  present are from earlier branch CRs), so the OSV baseline is unaffected._
- [x] 5.2 Note that generate-as-draft renders definition-driven but is **not yet pinned** -- CR-3
  (`agreement-template-pin`) adds `Agreement.pinEffectiveTemplate` + the `V9` migration + the
  generate-as-draft full render/pin; the frontend is CR-4.
