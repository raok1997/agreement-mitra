## Context

`template-definition-model` established the definition shape (typed `Field`s with a closed `FieldType`
and declarative `FieldValidation`, plain-text-slot `Clause`s, ordered `Section`s), a deterministic
`CanonicalJson` + SHA-256 identity, and an immutable record model in
`in.agreementmitra.documents.template`. `template-resolution-engine` composes `(state, type)` into one
materialized, re-validated, hash-pinned `EffectiveTemplate` and ships a sandboxed `showWhen`
parser/validator plus a pure **`ShowWhenEvaluator` that it deliberately does not wire to any render**.
`template-form-projection` projects the effective template into the capture **form** (the
data-independent face) and introduced the module's first public surface, the `documents.api`
`@NamedInterface`.

The `document-templating-platform` exploration splits preview into three layers by data dependence:
**(1) resolve** and **(2) form projection** are data-independent and cacheable per version;
**(3) document projection** is data-dependent and rendered on the fly. This CR is layer (3) -- it draws
the agreement.

Today the render path is still the hardcoded one: `GotenbergDocumentRenderer.renderPdf(templateId,
data)` calls `TemplateAssembler.assemble(...)` (Thymeleaf over the single classpath template
`documents/rental-agreement.html`, whose numbered clauses are baked in as static boilerplate) then
`GotenbergClient.renderHtml(html)` (HTML -> PDF via Gotenberg, Chromium outbound network denied). The
`documents.template` definition model, resolver, and `ShowWhenEvaluator` all exist but are **fully
unwired** -- the reference fixture's own header says replacing the live template is "a separate
render-parity CR." This is that CR.

Constraints unchanged: Java 21 + Spring Boot 3.5.x + Spring Modulith; Vue 3 + `<script setup>` +
Tailwind; keep `ModularityTests` green; sandbox + dummy data; never log PII; the markup/data boundary
(users edit data, never template markup).

## Goals / Non-Goals

**Goals**

- A package-private `TemplateCompiler`: `EffectiveTemplate + Map<String,Object> data -> escaped,
  self-contained HTML`, filling `{{slots}}` with HTML-escaped data and firing `ShowWhenEvaluator` with
  the real data to include/drop conditional clauses.
- **Single-renderer parity**: the live-preview HTML and the signed-PDF source are one compiler output;
  a parity test locks it.
- **Two render tiers**: HTML on every section save (no Gotenberg); Gotenberg PDF only on explicit
  Download PDF and the final generate-as-draft.
- **Server-side validation** of submitted data against the effective field schema before any render.
- **Stateless document-preview endpoints** (HTML + PDF) that are `no-store`, persist nothing, and
  render placeholders for missing fields.
- **Reproducibility pin** of the effective-template identity onto the agreement at generate-as-draft,
  plus its Flyway migration.
- Rewire the existing Gotenberg path definition-driven **without breaking** `document-rendering`'s
  offline / escape / never-log guarantees; reuse the resolution + definition records; keep the compiler
  package-private per the locked packaging; `ModularityTests` green.

**Non-Goals (each a named follow-on CR)**

- **The capture form structure** -- widgets, sections, client validation (`template-form-projection`).
  This CR consumes a field-key data map; it does not build the form.
- **Template selection / browse** across many templates (`template-catalog`). This CR resolves the one
  effective template for the given (or default) dimensions.
- **The admin template builder** -- authoring definitions/layers.
- **The layer registry** -- layers stay classpath resources (via the resolver's `LayerSource`); Postgres
  + object-storage persistence is the catalog CR.
- **The eSign / webhook signing flow** -- this CR stops at a signable PDF stored as the draft; it drives
  no signing and starts no `EsignProvider` call.

## Decisions

### D1: One compiler, one HTML -- parity between the live preview and the signed PDF

The live-preview HTML and the PDF's HTML source are the **same** `TemplateCompiler.compile(effective,
data)` output. The live pane returns that HTML directly; the PDF path feeds the **identical** HTML to
Gotenberg (`HtmlPdfRenderer.toPdf(html)`). Because there is exactly one HTML producer and no
client-side template mirror, the document a user previews is byte-for-byte the document they sign
(modulo A4 pagination, which is a Gotenberg layout concern, not a content one). A **parity test**
asserts the preview-HTML source equals the PDF's HTML source for the same effective template + data.
**Alternatives rejected:** a client-side Vue/React render of the legal document (guarantees drift from
the server compiler -- the exploration's explicit "never a separate renderer that can drift"); and a
PDF-only live preview (a Gotenberg round-trip per section save is too slow and puts Chromium in the
keystroke loop).

### D2: Escape at compile time -- the markup/data boundary is enforced in the compiler

The compiler is the boundary. The **document skeleton** (sections, headings, ordered clause list,
signature block) is **system-owned HTML** authored by the compiler. Each **clause** is **plain text**
from the effective template (system-owned, trusted); the compiler emits it as text and replaces each
`{{slot}}` with the **HTML-escaped** value from the data map. Therefore no user value can ever be
interpreted as markup or active content -- a value containing `<script>` renders as literal text. This
replaces Thymeleaf's `th:text` auto-escape (retired with `TemplateAssembler`) with an explicit
escape-at-fill step that is unit-testable in isolation. Missing/blank slot values render as a
placeholder (D6), never a bare `null` and never unescaped.

### D3: `showWhen` fires here -- through the sandboxed DSL evaluator, with real data

`template-resolution-engine` shipped `ShowWhenEvaluator.evaluate(ast, fieldValues) -> boolean` but
called it only from tests. This CR is where it finally runs with data: for each clause carrying a
`showWhen`, the compiler evaluates it against the submitted field-value map and **includes the clause
iff the result is true**; a clause whose condition is false is dropped from the document (and its
numbering closes up). Evaluation goes **only** through the resolution engine's hand-written
recursive-descent DSL -- comparisons, boolean ops, parentheses, declared field references, literals --
**never Thymeleaf SpringEL or any expression engine**. The evaluator reads only the value map and has
no method-call / property-navigation / code path, so wiring it to render adds **no** new
code-execution surface. Values are coerced to the operand types the resolver validated (the field
`type` governs numeric/date/bool/string comparison); a value that cannot be coerced is a validation
failure (D5), not an evaluation error.

### D4: Two render tiers -- HTML per section save, Gotenberg PDF only on download/final

The live pane compiles **HTML only** on every (debounced) section save -- tens of milliseconds, no
Chromium. **Gotenberg** (HTML -> PDF) runs **only** on explicit "Download PDF" and the final
generate-as-draft. This keeps Chromium out of the keystroke loop (the exploration's "no Gotenberg per
keystroke") and bounds render cost. Content negotiation on the one stateless route selects the tier:
`Accept: text/html` -> compiled HTML (live pane, the hot path), `Accept: application/pdf` -> compile +
Gotenberg (Download PDF). Both share the one compiler (D1), so the tiers cannot diverge.

### D5: Server-side validation of submitted data, before any render -- partial-tolerant for preview

Before the compiler runs, a package-private `SubmittedDataValidator` checks the posted data map against
the **effective template's field schema**: each present value matches its field `type` and satisfies
its `FieldValidation` (numeric `min`/`max`, text `minLength`/`maxLength`/`pattern`, `enum` membership).
This is the **authoritative server-side** counterpart to form projection's client-side metadata (which
is a UX affordance, explicitly not a trust boundary). Two modes:

- **Stateless preview** validates **present** values but **tolerates missing** ones (they become
  placeholders, D6) -- a half-filled agreement must preview. `required` is **not** enforced.
- **Generate-as-draft** validates **fully**: every `required` field must be present and valid; an
  invalid or incomplete submission is rejected and **no draft is generated**.

Rejection uses the app's RFC 9457 contract (a `400`/`422` `application/problem+json`) whose `detail`
names offending field keys/rules but **never echoes a data value**. An invalid document is never drawn.

### D6: Stateless preview endpoints -- `no-store`, persist nothing, placeholders for missing fields

`POST /api/templates/document/preview` takes a body `{ dimensions?: {state,type}, data: { fieldKey:
value } }` -- a partial working-set data map held by the browser. It resolves the effective template
(default dimensions when omitted -- the single reference template today), validates (D5, partial mode),
compiles, and returns the escaped HTML (`Accept: text/html`) or a Gotenberg PDF (`Accept:
application/pdf`). It is served **`Cache-Control: no-store`**, **stores nothing** server-side (no row,
no blob, no draft), renders **placeholders** for absent fields (e.g. `[ owner name ]`), and **logs
neither** the HTML/PDF nor the submitted values. The route lives in the public `documents.api` package,
the document-projection sibling of form projection's `GET /api/templates/form`.

**Why a new `documents.api` route rather than the signing-side `GET /api/agreements/{id}/preview`.**
The id-bound preview requires a **persisted, fully-valid agreement**; a document-first capture must
render **before** anything is persisted, from a raw field-key data map that only the templating stack
understands. Keeping it in `documents.api` also keeps `documents` **domain-agnostic** -- it takes a data
map, not a signing DTO. `preview-centric-capture` sketched a `POST /api/agreements/preview` placeholder
against the hardcoded template; this CR realizes that intent under `/api/templates/document/*` for
consistency with the rest of the templating stack (recorded as a route decision the shell's `src/api/`
client follows). The existing `GET /api/agreements/{id}/preview` is kept for previewing a persisted
agreement and simply sources its HTML from the compiler now.

### D7: Reproducibility pin -- here, not a separate CR; three nullable columns on `agreement`

A generated draft is the thing that gets stamped and signed, so it **must** be reproducible: a signed
agreement can never be silently re-rendered against newer layers (the exploration's non-negotiable). At
**generate-as-draft** the projection returns the effective template's identity, and the `agreement`
aggregate records it via a server-managed `pinEffectiveTemplate(templateId, contentHash,
layerVersions)`. Forward-only migration `V8__agreement_template_pin.sql` adds three **nullable** columns
(`template_id TEXT`, `template_content_hash TEXT`, `template_layer_versions JSONB`); nullable because
pre-existing agreements and un-generated drafts have no pin. JPA stays `ddl-auto: validate`.

**Here vs a separate CR (the call):** include the pin **in this CR**. The pin has no meaning without a
definition-driven render (there is nothing to pin while the template is a single hardcoded file), and a
generated draft that is *not* reproducible would ship a correctness hole this CR would immediately have
to backfill. It is one small migration + one aggregate method + one call site, tightly coupled to the
generate step this CR already rewires -- splitting it would create a window where drafts are
definition-driven but unpinned. Previews are **never** pinned (they persist nothing). The columns are
recorded but **not yet enforced** as immutable-after-signing; enforcement (reject re-generate once a
signing request exists) rides the existing draft-freeze `409` and the eSign CR.

### D8: Packaging -- compiler package-private in `documents.template`; a public HTML -> PDF seam

Per the locked packaging decision (`template-resolution-engine` D10), all templating types live in the
**one** package `in.agreementmitra.documents.template`, package-private, so the compiler can reuse the
package-private `EffectiveTemplate`, `Clause`, `Field`, `Section`, and `ShowWhenEvaluator` directly --
no record visibility widens. So:

- `TemplateCompiler`, `SubmittedDataValidator`, and the `DocumentProjectionService` (`@Service`
  implementing the public port) are **package-private in `documents.template`**.
- The Gotenberg **HTML -> PDF** leg lives in the **root** `documents` package where `GotenbergClient`
  is package-private. The compiler cannot call it across packages, so a small **public** seam
  `HtmlPdfRenderer { byte[] toPdf(String html); }` is factored out of the root package over the existing
  `GotenbergClient` (network-denied, never-logs). This exposes exactly one HTML -> PDF method as module
  infra -- it widens **no record** and adds no new cross-module reach-in.
- The public `documents.api` `@NamedInterface` (from form projection) gains a `DocumentProjectionApi`
  port + request/result DTOs and a `DocumentProjectionController`. The port implementation
  (`DocumentProjectionService`) stays package-private in `documents.template`, exactly as form
  projection kept its projector internal.

`ModularityTests` therefore sees the same one named interface, extended; no new module and no widened
record.

### D9: How the existing render path is rewired without breaking `document-rendering`

The join point is `GotenbergDocumentRenderer.renderPdf -> TemplateAssembler.assemble ->
GotenbergClient.renderHtml`. The rewire:

- **Retire** `TemplateAssembler` and `documents/rental-agreement.html` (the hardcoded Thymeleaf source)
  and the templateId-gated `DocumentRenderer.renderPdf` seam for the agreement path.
- **Keep** the Gotenberg HTML -> PDF leg untouched (behind the new `HtmlPdfRenderer` seam over the same
  `GotenbergClient`), so the offline / Chromium-network-denied / never-log guarantees are byte-for-byte
  preserved.
- **Signing side**: `AgreementDocumentService` calls `DocumentProjectionApi` instead of
  `renderPdf(templateId, data)`; it maps the `Agreement` to the effective template's **declared field
  keys** (the `documents` module stays domain-agnostic -- it receives a field-key data map, never a
  signing type). `signing` still depends only on the `documents` public interface.

`document-rendering`'s external promises (a template + data map -> PDF; offline; escaped; never-logged)
hold; only the **HTML source** changes from one hardcoded template to definition-compiled HTML, which
is the whole point of the capability modification.

### D10: FSM interaction -- reuse the existing generate-as-draft transition, invent no state

The `agreement` aggregate is status-less; the signing-status FSM
(`DRAFT -> PDF_GENERATED -> ... `) lives on the signing request. Generate-as-draft is the existing
pre-signing step that renders and stores the signable draft PDF (the `PDF_GENERATED` milestone). This
CR changes **only how the draft's bytes are produced** (definition-driven) and **adds the pin**; it
introduces **no** new FSM state and touches **no** signing-request transition. The draft-freeze `409`
(a signing request already exists) is preserved unchanged.

## Data-flow diagram (the render / preview data flow this CR changes)

```
                          browser working set (client-held, in-progress)
                                       |
             section save (debounced)  |                       Download PDF
                                       v                            |
   POST /api/templates/document/preview  (Accept: text/html)        | (Accept: application/pdf)
                                       |                            |
                                       v                            |
  [documents.api] DocumentProjectionController --------------------+
                                       |
                                       v
  [documents.template] DocumentProjectionService
        |                                                   (system-owned, package-private)
        |  1. resolve(dimensions)  --------->  TemplateResolver  -> EffectiveTemplate (+ contentHash,
        |                                                            layer versions)   [pure, cached]
        |  2. validate submitted data  ----->  SubmittedDataValidator
        |        preview = present-only (missing -> placeholder)
        |        generate = full (required enforced)   -- invalid -> RFC 9457 400/422, NO render
        |  3. compile  --------------------->  TemplateCompiler
        |        fill {{slots}} with HTML-ESCAPED data      (markup/data boundary)
        |        showWhen -> ShowWhenEvaluator(realData)    (sandboxed DSL, include/drop clause)
        |        emit self-contained HTML (fonts by family, no external URLs)
        v
   +----------------------------+                 +--------------------------------------+
   | Accept: text/html          |                 | Accept: application/pdf              |
   | -> HTML (live pane)        |  SAME HTML -->  | -> HtmlPdfRenderer.toPdf(html)       |
   | no-store, persist nothing  |  (parity test)  |    -> GotenbergClient (offline,      |
   | escaped, never logged      |                 |       network-denied) -> PDF bytes   |
   +----------------------------+                 |    no-store, never logged            |
                                                  +--------------------------------------+

  Save & continue (deliberate, persisted -- the only persist path):
    POST /api/agreements  (create)  then  POST /api/agreements/{id}/document  (generate-as-draft)
        |
        v
  [signing] AgreementDocumentService
        map Agreement -> field-key data map
        -> DocumentProjectionApi (resolve + FULL validate + compile + Gotenberg PDF)
        -> DraftService.attachDraft(pdf)      -> object storage (drafts/{id}.pdf), NOT Postgres
        -> agreement.pinEffectiveTemplate(templateId, contentHash, layerVersions)   [V8 columns]
        (reuses the existing generate-as-draft transition; no new FSM state)
```

## Risks / Trade-offs

- **Field-model reconciliation (flat vs nested parties).** The reference definition declares **flat,
  single-party** field keys (`ownerName`, `tenantName`, ...), while the `Agreement` supports **multiple**
  owners/tenants and the current `AgreementDocumentMapper` emits nested `owners[]/tenants[]`. This CR
  keeps parity on the fields the definition declares; **repeated-party groups** in the definition model
  are a `template-definition`/catalog concern (see Open Questions), so the generate-as-draft mapping
  targets the definition's declared keys and multi-party rendering beyond the reference definition is
  out of scope. Mitigation: an integration test over the real reference definition + a dummy data map;
  the reconciliation is a bounded mapper change, not a compiler change.
- **Placeholder correctness at every fill stage.** Placeholders must read well when half the fields are
  empty. Mitigation: partial-data compile tests over empty / partially-filled maps; placeholders are
  escaped and per-field-typed, never a bare `null`.
- **`showWhen` value coercion.** Firing the evaluator with real, possibly-missing data means coercion
  rules matter (a `showWhen` referencing an unfilled field). Mitigation: validate-before-evaluate (D5);
  the resolver already pinned operand-type rules and validated that every `showWhen` references declared
  fields; a missing referenced value is treated deterministically (documented, tested), never an
  exception that leaks a value.
- **Unauthenticated render abuse.** `POST /api/templates/document/preview` renders from an anonymous
  body with no persisted precondition -- the cheapest render to abuse, and PDF invokes Gotenberg.
  Mitigation: server-side validation caps payloads by the field schema's length/bounds; the live-pane
  hot path is HTML-only (no Gotenberg); **rate-limiting is an owed companion before this leaves
  sandbox**, recorded with the other deferred pre-prod controls (consistent with
  `preview-centric-capture`).
- **Retiring `TemplateAssembler`.** Removing the hardcoded template changes the render source. Mitigation:
  the parity test + a Gotenberg PDF happy-path test over the definition-compiled HTML guard the swap;
  `HtmlPdfRenderer` preserves the exact Gotenberg leg.
- **Client-side PII at rest.** The browser working set (names, addresses) may sit in localStorage (per
  `preview-centric-capture`). "Persists nothing" is a **server-side** statement. Mitigation is owned by
  `preview-centric-capture` (clear on Save/reset + TTL); unchanged here.

## Migration Plan

Additive and ordered **after** `template-definition-model` and `template-resolution-engine` (it consumes
their records + resolver + evaluator). Steps:

1. Add the compiler, validators, projection service, and the `HtmlPdfRenderer` seam; extend
   `documents.api` with the document-projection port + controller; retire `TemplateAssembler` +
   `rental-agreement.html`.
2. Rewire `AgreementDocumentService` to the projection API; add `Agreement.pinEffectiveTemplate` and the
   generate-as-draft pin call.
3. Ship `V8__agreement_template_pin.sql` (three nullable columns; forward-only, never edits V1--V7);
   `ddl-auto: validate` must pass against the new columns.
4. Permit `POST /api/templates/document/preview` in `SecurityConfig` alongside the other render
   endpoints.
5. Frontend: point the live pane / Download PDF / Save & continue at the new endpoints via `src/api/`.

The existing capture flow keeps working until the shell is wired; the id-bound preview keeps its route
and contract. **No new dependency, no lockfile change.**

## Open Questions

- **Repeated-party field modeling.** How the definition model expresses multiple owners/tenants
  (repeated groups) is deferred to the definition-model/catalog track; the reference definition is
  single-party-per-role and this CR renders exactly what the definition declares.
- **Default dimensions token.** With no catalog yet, the stateless preview and generate-as-draft resolve
  a **default** `(state, type)` (the single reference template). The exact default token and how the
  agreement will later carry chosen dimensions are the catalog CR's concern; this CR pins whatever was
  resolved.
- **Pin immutability enforcement.** The pin columns are recorded now; enforcing "never re-generate once
  signed" (beyond the existing draft-freeze `409`) lands with the eSign CR that introduces the signing
  request.
- **Rate-limiting the stateless preview.** Owed before leaving sandbox; scope/among the deferred
  pre-prod controls with `preview-centric-capture`.
