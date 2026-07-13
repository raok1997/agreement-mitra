## Why

`template-definition-model` gave us the shape of a single template (typed fields, plain-text-slot
clauses, ordered sections); `template-resolution-engine` composes `(state, type)` into one immutable,
hash-pinned **effective template** and delivers a sandboxed `showWhen` DSL -- parser, validator, and a
pure **evaluator that it deliberately does not wire to any render**. `template-form-projection`
projects the effective template into the **capture form** (the data-independent face). All three are
either pure domain models or a metadata projection; **none of them draws the agreement**.

This change lands the **second, data-dependent projection** -- **document projection** -- the step
that actually produces the visible, signable document from the effective template **plus the user's
data**:

> (1) RESOLVE            (2) FORM PROJECTION          (3) DOCUMENT PROJECTION  (this CR)
>     state x type            effective template           effective template + USER DATA
>     (data-independent)      (data-independent)           (data-dependent)
>     --> cached per version  --> cached per version       --> generated ON THE FLY, per edit

Today the `documents` module still draws the agreement from **one hardcoded Thymeleaf template**
(`documents/rental-agreement.html`, its clauses baked in as static boilerplate). The definition model
and the resolver exist but are **fully unwired** -- their reference fixture header even says so. This
CR connects them to the render path: it introduces a **`TemplateCompiler`** that fills the effective
template's `{{slots}}` with **HTML-escaped** user data and finally **runs the resolution engine's
`ShowWhenEvaluator` with real data** to include or drop conditional clauses, then rewires the existing
Gotenberg path to source its HTML from that compiler instead of the static template.

**Why this is the safety-critical projection.** This is the **first templating CR that handles party
PII** -- names, father's names, addresses, rent, dates flow into the rendered HTML and PDF. The
exploration's non-negotiables land here as running code: the markup/data boundary becomes
**escape-at-compile-time**; `showWhen` fires **only** through the sandboxed hand-written DSL (never
Thymeleaf SpringEL); the live preview and the signed PDF come from the **same compiler** (parity, so
what a user previews is what they sign); the stateless preview is `no-store` and persists nothing; and
a generated draft **pins** the effective template it used so a signed agreement is never re-rendered
with newer layers.

It also gives `preview-centric-capture` its engine. That CR shipped the two-pane capture shell and a
stateless preview endpoint against the **single hardcoded template**; this CR replaces that engine
with the definition-driven compiler and -- consistent with form projection's `GET /api/templates/form`
-- exposes the document-projection HTTP surface under `/api/templates/document/*`, then wires the
shell's live pane, "Download PDF", and "Save & continue" to it.

## What Changes

- Introduce a new **`template-document-projection`** capability inside the `documents` module: a
  package-private **`TemplateCompiler`** in `in.agreementmitra.documents.template` (the same package as
  the definition/resolution records, per the locked packaging decision, so it can reuse the
  package-private `EffectiveTemplate`, `Clause`, `Field`, and `ShowWhenEvaluator` without widening any
  visibility). `TemplateCompiler.compile(effectiveTemplate, dataMap) -> String html` produces
  **system-owned, self-contained HTML**: it renders the effective template's sections as trusted
  markup, fills each clause's `{{slot}}` with the **HTML-escaped** value from the data map, and -- for
  every clause carrying a `showWhen` -- calls `ShowWhenEvaluator` with the real field values to
  **include or drop** the clause. This is where the delivered-but-unwired evaluator finally runs with
  data.
- **Single renderer, parity (non-negotiable):** the live-preview HTML and the signed-PDF source are
  the **same** `TemplateCompiler` output. Rewire the existing render pipeline
  (`GotenbergDocumentRenderer -> TemplateAssembler -> GotenbergClient`) so its HTML comes from the
  compiler, not the hardcoded Thymeleaf template. The Gotenberg **HTML -> PDF** leg
  (`GotenbergClient.renderHtml`, offline / Chromium-network-denied) is preserved unchanged; the
  hardcoded `TemplateAssembler` + `rental-agreement.html` are **retired** from the agreement path. A
  **parity test** asserts the preview HTML equals the PDF's HTML source.
- **Two render tiers (no Gotenberg in the keystroke loop):** the **live HTML preview** is compiled on
  every section save (cheap, tens of ms, no Gotenberg); the **Gotenberg PDF** runs **only** on explicit
  "Download PDF" and the final "Save & continue" (generate-as-draft). Never per keystroke.
- **Server-side validation of submitted data (the server leg):** before any compile/render, validate
  the posted data map against the **effective template's field schema** -- `required`, `type`, numeric
  `min`/`max`, text `minLength`/`maxLength`/`pattern`, and `enum` `options`. Invalid data is rejected
  via the app's RFC 9457 error contract; **no invalid document is ever drawn**. The **stateless
  preview** validates **present values** but tolerates missing ones (placeholders); **generate-as-draft**
  validates **fully** (required enforced). This is the authoritative server-side counterpart to form
  projection's client-side validation metadata.
- **Stateless document-projection endpoints** (new public `documents.api` surface, backing
  `preview-centric-capture`):
  - `POST /api/templates/document/preview` -- body is a partial working-set data map (optionally with
    `dimensions`); content-negotiated: `Accept: text/html` returns the **escaped HTML** for the live
    pane, `Accept: application/pdf` returns the Gotenberg PDF for "Download PDF". `Cache-Control:
    no-store`; **nothing is persisted**; missing fields render as **placeholders**; rendered
    HTML/PDF and submitted values are **never logged**.
- **Reproducibility pin (final commit only):** rewire the existing generate-as-draft path
  (`POST /api/agreements/{id}/document` in `signing.api`) to render definition-driven and to **pin**
  the effective template's identity -- `(templateId, contentHash, layer versions)` -- onto the
  `agreement` aggregate, so a generated/signed draft is **never re-rendered** with newer layers. Adds a
  forward-only Flyway migration `V8__agreement_template_pin.sql` (three nullable columns; JPA stays
  `ddl-auto: validate`). Previews are never pinned.
- **Frontend:** wire `preview-centric-capture`'s live-preview pane to
  `POST /api/templates/document/preview` (`Accept: text/html`) on section save, "Download PDF" to the
  same route (`Accept: application/pdf`), and "Save & continue" to the existing create +
  generate-as-draft (which now pins). API calls stay in `src/api/`.

**Explicitly not in this change** (each a named follow-on CR): the edit/capture **form structure**
(`template-form-projection`); template **selection / browse** (`template-catalog`); the **admin
template builder**; the **layer registry** persistence (layers stay classpath resources); and the
**eSign / webhook signing flow** itself -- this CR stops at a signable PDF stored as the draft. See
Non-Goals in `design.md`.

## Capabilities

### New Capabilities

- `template-document-projection`: the data-dependent projection of an effective template + a user data
  map into the rendered agreement -- a package-private `TemplateCompiler` that fills HTML-escaped slots
  and fires the sandboxed `showWhen` evaluator with real data to include/drop clauses; single-renderer
  parity (the live-preview HTML and the signed-PDF source are one compiler output, parity-tested);
  server-side validation of submitted data against the effective field schema before rendering;
  stateless `no-store` document-preview HTTP endpoints (HTML + Gotenberg PDF) that persist nothing and
  render placeholders for missing fields; the two render tiers (HTML per section save, Gotenberg PDF
  only on download/final); and the reproducibility pin recorded at generate-as-draft.

### Modified Capabilities

- `document-rendering`: the renderer's HTML source changes from the **single hardcoded Thymeleaf
  template** to **HTML compiled from the effective template definition** (`TemplateCompiler`). The
  `TemplateAssembler` + `rental-agreement.html` are retired and the templateId-based
  `DocumentRenderer.renderPdf` seam is superseded by the definition-driven document-projection API. The
  invariants are **preserved and strengthened**: all user data stays HTML-escaped (now at compile time,
  not by Thymeleaf auto-escape); rendering stays **offline** with Gotenberg's outbound network denied
  (SSRF/exfil guard); and the composed HTML and PDF bytes are **never logged**.
- `agreement-preview` / `preview-centric-capture`: the preview is now **definition-driven** and its
  source is the same compiler that produces the signed PDF (parity). A new **stateless** preview that
  renders an in-progress working-set data map (not a persisted agreement) is added, `no-store`, nothing
  persisted, placeholders for missing fields; the existing `GET /api/agreements/{id}/preview` keeps its
  `no-store` / no-PII-in-logs contract while sourcing its HTML from the compiler. The two-pane shell,
  section modals, and completeness bar are unchanged; only the preview engine and the endpoint it calls
  change.
- `agreement-management` (the `agreement` aggregate): gains a server-managed **effective-template pin**
  -- `template_id`, `template_content_hash`, `template_layer_versions` -- set only at generate-as-draft
  (never client-settable), so the generated draft is reproducible. No existing field or transition
  changes.

## Impact

- **`documents` module**: a package-private `TemplateCompiler`, a package-private `SubmittedDataValidator`,
  and a package-private `DocumentProjectionService` (`@Service`, implementing the public port) added to
  the existing `in.agreementmitra.documents.template` package (co-located with the records so **no record
  visibility widens**). A small public **HTML -> PDF seam** (`HtmlPdfRenderer`) is factored out of the
  root `documents` package over the existing `GotenbergClient` so the compiler's HTML can be turned into
  a PDF without reaching a package-private client across packages. The public `documents.api`
  named-interface package (introduced by `template-form-projection`) gains a `DocumentProjectionApi`
  port + request/result DTOs and a `DocumentProjectionController`. The retired `TemplateAssembler` and
  `rental-agreement.html` are removed. `ModularityTests` stays green (one existing named interface,
  extended).
- **`signing` module**: `AgreementDocumentService` is rewired to call the `documents`
  `DocumentProjectionApi` (mapping the `Agreement` to the effective template's declared field keys)
  instead of the templateId-based `renderPdf`; the `Agreement` aggregate gains a server-managed
  `pinEffectiveTemplate(...)` invoked by `generate-as-draft`. `signing` still depends only on the
  `documents` public interface.
- **Reuse, not fork**: this CR consumes `EffectiveTemplate` + `ShowWhenEvaluator` from
  `template-resolution-engine` and, transitively, the `Field` / `FieldType` / `FieldValidation` /
  `Clause` / `Section` records from `template-definition-model`. **Both must be applied first.**
- **Security wiring**: `POST /api/templates/document/preview` is added to the anonymous permit set
  alongside the other render endpoints (it renders from an anonymous body, exactly like today's
  preview). No change to any authenticated matcher, the signing permit, or the webhook permit.
- **Dependencies**: **none added.** Escaping uses a standard HTML-escape utility already on the
  classpath; the compiler is plain Java over existing records. **No `gradle.lockfile` change**, nothing
  new enters the OSV `securityScan` surface.
- **Data / schema**: one forward-only Flyway migration `V8__agreement_template_pin.sql` (three nullable
  columns on `agreement`; never edits V1--V7). JPA stays `ddl-auto: validate`. PDF blobs stay in object
  storage, never Postgres.
- **Frontend**: the live pane, Download PDF, and Save & continue in the `preview-centric-capture` shell
  point at the new endpoints; API calls in `src/api/`.
- **No** change to: the signing-status FSM, `EsignProvider` / webhook flow, stamping, object storage,
  the reconciliation job, or the draft-storage path.

## PII / security review checklist

- **Introduces or moves Aadhaar/OTP/VID/PII or secrets?** **Party PII (not Aadhaar/OTP/VID).** This is
  the first templating CR that renders **party PII** -- names, father's names, current addresses, rent,
  deposit, dates -- into HTML and PDF. **No** Aadhaar number, one-time code, virtual id, biometric, or
  government identifier is collected, stored, or rendered here (those enter only at eSign, out of
  scope); **no secret material** is introduced.
- **How redacted/secured?**
  - **Escape at compile time (markup/data boundary):** the `TemplateCompiler` fills every `{{slot}}`
    with an **HTML-escaped** value, so user data renders as literal text and can never become document
    structure or active content. Clause text is **plain text** (system-owned, trusted); only the
    document skeleton is markup, and it is authored, not user-supplied. Users edit **data**, never
    markup.
  - **`showWhen` runs only in the sandbox:** conditional clauses are included/dropped **solely** by the
    resolution engine's hand-written boolean DSL evaluator over declared fields and literals -- **never
    Thymeleaf SpringEL or any expression engine** (the exploration's named RCE surface). The evaluator
    reads only the supplied field-value map and returns a boolean; it has no method call, property
    navigation, indexing, or code path.
  - **Rendered content is never logged:** the composed HTML, the PDF bytes, and the submitted data
    values are **never** written to any log at any level (the renderer's existing no-log guarantee is
    preserved and extended to the compiler and the validators). Validation errors cite field keys /
    structural locations only, never a data value.
  - **Stateless preview persists nothing and is `no-store`:** `POST /api/templates/document/preview`
    stores nothing server-side and is served `Cache-Control: no-store`; only the deliberate
    generate-as-draft persists -- and it persists the PDF to **object storage**, never Postgres.
  - **Offline render preserved:** the compiler emits **self-contained** HTML (fonts by family name, no
    external URLs); Gotenberg's outbound network stays denied (`CHROMIUM_DENY_PUBLIC_IPS` /
    `CHROMIUM_DENY_PRIVATE_IPS`), so no data value can trigger an outbound request (SSRF/exfil guard).
  - **Server-side validation before render:** invalid submitted data is rejected before any document is
    drawn, so a malformed or out-of-bounds value never reaches the compiler or Gotenberg.
- **Sandbox + dummy data only?** Preserved -- the reference definition/layers are dummy, system-authored
  content; the stateless preview renders dummy in-progress data locally; no live provider, no real PII,
  no production credentials.
- **Signing-status FSM transitions touched?** **None invented or changed.** This CR makes the existing
  **generate-as-draft** step (which produces the signable draft PDF, the pre-signing
  `DRAFT -> PDF_GENERATED` milestone) **definition-driven** and adds the reproducibility pin; it reuses
  that existing transition and touches no signing-request FSM state. The `agreement` aggregate stays
  status-less.
- **Async signing / webhook flow touched?** **None** -- this CR stops at a signable PDF stored as the
  draft; eSign/webhook is a separate CR. A capture -> validate -> resolve/compile -> preview/PDF ->
  generate-as-draft data-flow diagram is in `design.md` (the render/preview data flow this CR changes).
