> **Prerequisite:** this change consumes the `EffectiveTemplate` + `ShowWhenEvaluator` from
> `template-resolution-engine` and, transitively, the `Field` / `FieldType` / `FieldValidation` /
> `Clause` / `Section` records from `template-definition-model`; and it rewires the existing Gotenberg
> render path (`document-render-service`) and the generate-as-draft / `agreement` aggregate. Apply
> **`template-definition-model`** then **`template-resolution-engine`** first; reuse their
> package-private records (do not fork them).

## 1. TemplateCompiler (package `in.agreementmitra.documents.template`, package-private)

- [ ] 1.1 Add a package-private `TemplateCompiler` co-located with the definition/resolution records so
  **no record visibility is widened**: `compile(EffectiveTemplate effective, Map<String,Object> data)
  -> String html`. Emit the document skeleton (sections, headings, ordered clause list, signature
  block) as **system-owned markup**; render each clause's plain text with each `{{slot}}` replaced by
  the **HTML-escaped** value from `data` (escape at compile time -- the markup/data boundary). A
  missing/blank slot value renders as an escaped placeholder, never a bare `null` and never unescaped.
- [ ] 1.2 Wire `showWhen`: for every clause carrying a `showWhen`, evaluate it against `data` via the
  resolution engine's `ShowWhenEvaluator` (the delivered-but-unwired evaluator) and **include the
  clause iff true**, dropping it and closing up its numbering otherwise. Route conditions **only**
  through the sandboxed DSL -- never Thymeleaf/SpringEL. Coerce values to the operand types the resolver
  validated; a value that cannot be coerced is a validation failure (task 2), not an evaluation error.
- [ ] 1.3 Ensure the compiled HTML is **self-contained** (fonts by family name, no external URLs) so the
  offline / Chromium-network-denied guarantee holds; never log the composed HTML.

## 2. Server-side data validation (package `in.agreementmitra.documents.template`, package-private)

- [ ] 2.1 Add a package-private `SubmittedDataValidator`: check a data map against the effective
  template's field schema -- each present value matches its `type` and its `FieldValidation` (numeric
  `min`/`max`, text `minLength`/`maxLength`/`pattern`, `enum` membership). Errors cite field keys / rules
  only, **never** a data value.
- [ ] 2.2 Two modes: **preview** validates present values and tolerates missing ones (placeholders; no
  `required` enforcement); **generate** validates fully (every `required` field present and valid). Map
  a validation failure to the app's RFC 9457 error (`400`/`422` `application/problem+json`) via the
  existing `GlobalExceptionHandler` contract; render nothing on failure.

## 3. HTML -> PDF seam + document-projection service (packages `documents` root and `documents.template`)

- [ ] 3.1 Factor a small **public** `HtmlPdfRenderer { byte[] toPdf(String html); }` in the root
  `in.agreementmitra.documents` package over the existing package-private `GotenbergClient` (preserve
  the offline / network-denied / never-log behavior). Retire `TemplateAssembler` and the hardcoded
  `documents/rental-agreement.html`; supersede the templateId-gated `DocumentRenderer.renderPdf` for the
  agreement path.
- [ ] 3.2 Add a package-private `DocumentProjectionService` (`@Service`) in `documents.template`
  implementing the public port (task 4): resolve `dimensions` via the resolution engine's
  `TemplateResolver`, validate (task 2), compile (task 1), and either return the HTML or call
  `HtmlPdfRenderer.toPdf(html)` for a PDF. Constructor injection; no internal/entity type crosses to the
  caller. Parity by construction (one compile output feeds both HTML and PDF).

## 4. Public document-projection API + HTTP surface (package `in.agreementmitra.documents.api`)

- [ ] 4.1 Extend the existing `documents.api` `@NamedInterface("api")` with a **public**
  `DocumentProjectionApi` port and immutable request/result DTOs (a preview request `{ dimensions?,
  data }`; a generate result carrying the PDF bytes + the effective-template identity `{ templateId,
  contentHash, layerVersions }`). No new named interface -- extend the one form projection added.
- [ ] 4.2 Add `DocumentProjectionController`: `POST /api/templates/document/preview`, content-negotiated
  -- `Accept: text/html` -> compiled HTML (live pane); `Accept: application/pdf` -> Gotenberg PDF
  (Download PDF). Set `Cache-Control: no-store`, persist nothing, never log the body. Missing fields ->
  placeholders (partial-mode validation).
- [ ] 4.3 Permit `POST /api/templates/document/preview` in `SecurityConfig` alongside the other
  anonymous render endpoints (it renders from an anonymous body, like today's preview). Do not touch any
  authenticated matcher, the signing permit, or the webhook permit.

## 5. Signing rewire + reproducibility pin (module `signing`) + Flyway migration

- [ ] 5.1 Rewire `AgreementDocumentService` to call `documents` `DocumentProjectionApi` instead of the
  templateId-based `renderPdf`: map the `Agreement` to the effective template's **declared field keys**
  (reconcile the current `AgreementDocumentMapper` output to the definition's field schema; `documents`
  stays domain-agnostic -- it receives a field-key data map, never a signing type). The id-bound
  `GET /api/agreements/{id}/preview` now sources its HTML from the compiler.
- [ ] 5.2 Add a server-managed `Agreement.pinEffectiveTemplate(templateId, contentHash, layerVersions)`
  (never client-settable) and call it from **generate-as-draft** (`POST /api/agreements/{id}/document`)
  after a successful full render + `attachDraft`. Preview pins nothing. Reuse the existing
  generate-as-draft transition -- **no new FSM state**; the draft-freeze `409` is unchanged.
- [ ] 5.3 Add forward-only Flyway migration `V8__agreement_template_pin.sql`: three **nullable** columns
  on `agreement` (`template_id TEXT`, `template_content_hash TEXT`, `template_layer_versions JSONB`),
  never editing V1--V7. Map them on the `Agreement` entity so JPA `ddl-auto: validate` passes.

## 6. Frontend -- wire the preview-centric shell to the new endpoints (`frontend/src`)

- [ ] 6.1 Add a document-preview API client in `src/api/` (`postDocumentPreview(data, accept)`) that
  calls `POST /api/templates/document/preview` with the working-set data map and the chosen `Accept`
  (text/html for the live pane, application/pdf for Download PDF); keep all API calls in `src/api/`.
- [ ] 6.2 Point the `preview-centric-capture` live-preview pane at the HTML variant on (debounced)
  section save, "Download PDF" at the PDF variant, and "Save & continue" at the existing create +
  generate-as-draft (which now pins). Leave the shell, section modals, and completeness bar unchanged.

## 7. Tests -- unit (many, fast; no Spring context, no I/O)

- [ ] 7.1 `TemplateCompiler` escaping: a slot value containing angle-bracket markup renders as literal
  text; the compiled HTML has no injected structure/active content; a missing slot renders an escaped
  placeholder, not `null`.
- [ ] 7.2 `TemplateCompiler` showWhen: a clause with a true `showWhen` is included; a false one is
  dropped and numbering closes up; conditions run only through `ShowWhenEvaluator` (a `showWhen` that
  would be an expression-engine construct is not evaluated as code).
- [ ] 7.3 `TemplateCompiler` partial data: an empty and a partially-filled data map each compile to a
  coherent document with placeholders; no bare `null` and no unescaped value.
- [ ] 7.4 `SubmittedDataValidator`: present-value type/bounds/pattern/enum checks pass/fail correctly;
  preview mode tolerates missing + skips `required`; generate mode enforces `required`; error messages
  contain field keys/rules and **no** data value.
- [ ] 7.5 Never-log: exercising the compiler and validators produces no log line containing the composed
  HTML or a submitted data value (a log-capturing unit assertion).

## 8. Tests -- integration (fewer; real wiring + module boundary)

- [ ] 8.1 Resolve -> validate -> compile -> HTML for the **real reference definition** (via the
  resolution engine's `ClasspathLayerSource`) + a dummy data map: the composed HTML reflects the
  definition's fields/clauses/sections, slots are escaped, and a `showWhen` clause is included/dropped
  per the data.
- [ ] 8.2 **Parity test**: for the same effective template + data, the HTML returned for the live pane
  is byte-for-byte the HTML handed to the PDF renderer (the single-renderer guarantee).
- [ ] 8.3 **Gotenberg PDF happy-path** over the definition-compiled HTML, reusing the existing
  Testcontainers pattern (`@Testcontainers(disabledWithoutDocker = true)`, the `./docker/gotenberg`
  image with `CHROMIUM_DENY_PUBLIC_IPS`/`CHROMIUM_DENY_PRIVATE_IPS`): returns non-empty PDF bytes
  starting with the PDF signature; a remote reference triggers no outbound request and still renders.
- [ ] 8.4 **MockMvc** test of `POST /api/templates/document/preview`: `Accept: text/html` returns
  `200` + `Cache-Control: no-store` and the compiled HTML; `Accept: application/pdf` returns an inline
  PDF (no-store); **nothing is persisted** (no agreement/draft/blob created); missing fields render as
  placeholders; an out-of-bounds value returns an RFC 9457 error that echoes no data value.
- [ ] 8.5 **PII-never-logged** integration assertion across a preview and a generate-as-draft: no log
  line contains the rendered bytes or composed party details (extends the existing preview no-log test).
- [ ] 8.6 **Generate-as-draft + pin**: generate-as-draft stores the PDF as the draft (existing path) and
  records `template_id` / `template_content_hash` / `template_layer_versions` on the agreement; a
  stateless preview pins nothing.
- [ ] 8.7 **Flyway migrate + `ddl-auto: validate`** with `V8` present (Testcontainers Postgres): the
  migration applies forward-only and the `Agreement` mapping validates against the new columns.
- [ ] 8.8 Keep `ModularityTests` green: document projection is exposed only through the existing
  `documents.api` named interface (extended); the `TemplateCompiler` and `DocumentProjectionService`
  stay package-private in `documents.template`; no new disallowed cross-module dependency; `signing`
  depends only on the `documents` public interface.

## 9. Wrap-up

- [ ] 9.1 `./gradlew spotlessApply` then `./gradlew check` (tests, `securityScan`, `ModularityTests`,
  JaCoCo gate) all green; `npm run lint` + `npm run test` (frontend) green. Confirm **no new dependency
  and no `gradle.lockfile` change** (HTML escaping uses a classpath utility; the compiler is plain
  Java).
- [ ] 9.2 Note that this CR stops at a **signable PDF stored as the draft**; the **eSign / webhook
  signing flow**, the **template catalog / selection**, the **capture form structure**
  (`template-form-projection`), the **admin builder**, and the **layer registry** persistence remain
  named follow-on CRs per the `document-templating-platform` exploration. The reproducibility pin's
  immutability enforcement (beyond the draft-freeze `409`) and stateless-preview rate-limiting are owed
  before leaving sandbox.
