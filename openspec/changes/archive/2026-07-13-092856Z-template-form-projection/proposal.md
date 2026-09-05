## Why

`template-definition-model` gave us the shape of a single template (typed fields, plain-text-slot
clauses, ordered sections); `template-resolution-engine` composes `(state, type)` into one immutable,
hash-pinned **effective template**. Both are pure backend domain models -- nothing consumes them yet,
and nothing is exposed on any module API. The `document-templating-platform` exploration is explicit
about what turns that model into product value: an effective template is projected two ways.

> One source of truth (the effective template) drives two faces: the **capture form** the end-user
> fills, and the **document** that is rendered and signed.

This change lands the **first** of those two projections -- **form projection** -- and it is the
**data-independent** one:

> (1) RESOLVE            (2) FORM PROJECTION          (3) DOCUMENT PROJECTION
>     state x type            effective template           effective template + USER DATA
>     (data-independent)      (data-independent)           (data-dependent)
>     --> cached per version  --> cached per version       --> generated ON THE FLY, per edit

Form projection turns the resolved effective template into a **FormSchema**: a JSON contract of
sections -> fields, each field carrying `key / label / widget / type / required / default / options /
validation / group`, enough for the frontend to render a widget per field and validate input from the
metadata alone. It touches **no user data** and is a pure function of the effective template, so it is
cacheable per `(state, type, layer-version-set)` exactly like resolution.

**Why this CR matters now.** `preview-centric-capture` shipped a document-first capture shell whose
section rail is **hardcoded** -- the exploration calls that mockup "a stand-in for this projection".
This change makes the shell **schema-driven**: it feeds the existing two-pane / section-modal shell
from a projected FormSchema instead of a hand-written section registry. It does not rebuild the shell;
it replaces the shell's data source.

**Why this is also the first HTTP + first public-API step of the templating stack.** The definition
and resolution CRs added zero endpoints and zero named interfaces (no consumer existed). Form
projection has a consumer -- the Vue SPA -- so this CR introduces the templating stack's **first HTTP
surface** (`GET /api/templates/form?state=..&type=..`) and the `documents` module's **first
public/named-interface API** (a small `TemplateFormApi` port plus the `FormSchema` DTO), mirroring how
`signing.api` is exposed. Everything else stays module-internal and `ModularityTests` stays green.

## What Changes

- Introduce a new **`template-form-projection`** capability inside the `documents` module: a
  package-private **`FormProjector`** in `in.agreementmitra.documents.template` that maps an
  `EffectiveTemplate` to an immutable **`FormSchema`**. Projection is **pure, deterministic, and
  data-independent** -- derived only from the effective template's fields + sections, never from user
  data, no clock/IO/random input.
- Define the **FormSchema** contract (an immutable record tree serialized to JSON via the
  Spring-Boot-managed Jackson already present):
  - `dimensions { state, type }`, `templateId`, `version`, and the effective template's `contentHash`
    (the pin / cache key);
  - ordered **`sections`**, each `{ title, fields[] }`, preserving the effective template's section
    order;
  - ordered **`fields`** per section, one entry per section field-key (clause-id entries are document
    content, not form inputs, and are skipped here), preserving within-section order. Each field
    carries `key, label, widget, type, required, default?, options?, group?, validation{...}` and an
    optional carried-through `showWhen`.
- Map each closed `FieldType` to a **widget** the frontend renders: `text -> text`, `longtext ->
  textarea`, `int -> number (integer)`, `money -> number (decimal/currency)`, `date -> date`,
  `bool -> checkbox`, `enum -> select` (over `options`). The widget hint travels in the schema so the
  frontend never re-derives it.
- Carry **per-field validation metadata sufficient for client-side validation**: `required`, the
  field `type`, numeric `min`/`max` (int/money), text `minLength`/`maxLength`/`pattern`, the `enum`
  `options` set, and any `default`. The frontend validates from this metadata alone -- **server-side
  validation of submitted data is deliberately deferred** (document-projection CR).
- `showWhen` is **carried through as opaque metadata and NOT evaluated** -- consistent with how the
  definition model stores it and the resolution engine parses/validates but does not fire it. Real
  conditional field visibility against user data is document projection, not this CR.
- Add the **first public module API** for the templating stack: a `@NamedInterface("api")` package
  `in.agreementmitra.documents.api` exposing the `FormSchema` DTO tree and a `TemplateFormApi` port
  (`formFor(state, type) -> FormSchema`), plus a `TemplateFormController`. The port implementation and
  the `FormProjector` stay package-private in `documents.template` (same package as the definition
  records -- **no record visibility is widened**). `ModularityTests` stays green with one new named
  interface, mirroring `signing.api`.
- Add the **first HTTP surface**: `GET /api/templates/form?state=..&type=..` resolves the dimensions
  (via the resolution engine) -> projects -> returns the `FormSchema` as JSON. Unknown dimensions
  return **404** (reusing the app's `ResourceNotFoundException` -> RFC 9457 contract). The response
  carries **no user data**; it is **cacheable per version** -- a strong `ETag` set to the effective
  template's `contentHash` plus a short `Cache-Control: public` max-age, so a layer-version change
  invalidates the cache automatically (see design D3). No `no-store` is needed because there is no PII.
- Frontend: a **schema-driven form renderer** in the Vue SPA (`frontend/src/`) -- one widget component
  per `widget` type, sections -> the existing `preview-centric-capture` section-modal shell, and
  validation wired from each field's metadata (required/type/min-max/length/pattern/options). This
  **replaces the hardcoded section registry** with a schema-fed one; API calls live in `src/api/` per
  conventions. The live-preview pane, stateless preview endpoint, and persistence path are unchanged.

**Explicitly not in this change** (each a named follow-on CR): rendering the document / generating the
PDF and **server-side validation of submitted data** (`template-document-projection`); persisting
captured data / the draft (existing generate-as-draft flow); browsing/selecting among many templates
(`template-catalog`); the admin template builder; and **evaluating `showWhen`** against real data
(data-dependent; document projection). See Non-Goals in `design.md`.

## Capabilities

### New Capabilities

- `template-form-projection`: a pure, data-independent projection of an effective template into a
  **FormSchema** (sections -> fields, each field carrying key/label/widget/type/required/default/
  options/validation/group and an unevaluated carried-through `showWhen`); the closed
  `FieldType -> widget` mapping; per-field validation metadata sufficient for client-side validation;
  the templating stack's first public module API (`TemplateFormApi` port + `FormSchema` DTO under a
  `documents.api` named interface) and its first HTTP surface
  (`GET /api/templates/form?state=..&type=..`, 404 on unknown dimensions, cacheable per version via a
  contentHash `ETag`); and a schema-driven Vue form renderer that feeds the existing
  `preview-centric-capture` shell.

### Modified Capabilities

- `preview-centric-capture`: the section rail / section registry, hardcoded in Phase 1 to prove the
  UX, becomes **schema-fed** -- the shell now renders its sections and fields from a projected
  `FormSchema` and validates from field metadata, instead of a hand-written registry. The shell,
  modal pattern, live-HTML preview pane, completeness bar, stateless preview endpoint, and the final
  Save & continue persistence path are **unchanged**; only the source of the section/field definitions
  changes from hardcoded to projected.

## Impact

- **`documents` module**: a new package-private `FormProjector` and a package-private `TemplateFormApi`
  implementation in `in.agreementmitra.documents.template` (co-located with the definition records so
  **no record visibility widens**); a **new public named-interface package**
  `in.agreementmitra.documents.api` (`@NamedInterface("api")`) holding the `FormSchema` DTO tree, the
  `TemplateFormApi` port, and `TemplateFormController`. This is the templating stack's first public API
  and first HTTP surface. The existing `DocumentRenderer` / `TemplateAssembler` / Gotenberg path is
  **untouched**.
- **Reuse, not fork**: form projection consumes the `EffectiveTemplate` from
  `template-resolution-engine` and, transitively, the `Field` / `FieldType` / `FieldValidation` /
  `Section` records from `template-definition-model`. **Both CRs must be applied first.** The projector
  reuses those records rather than re-declaring any field shape.
- **Security wiring**: `GET /api/templates/form` is a public read of system-owned metadata (no PII, no
  auth needed); it is added to the permit set alongside the other anonymous read endpoints. No change
  to any authenticated matcher, the signing permit, or the webhook permit.
- **Dependencies**: **none added.** JSON serialization uses the Jackson already provided by Spring
  Boot; no new library and **no `gradle.lockfile` change**. Nothing enters the OSV `securityScan`
  surface.
- **Data / schema**: **none** -- no Flyway migration, no table, no object-storage change. The
  FormSchema is computed in memory from classpath-resource layers (via the resolution engine's
  `LayerSource`); persistence remains the registry CR.
- **Frontend**: a schema-driven form renderer + widget components + a `src/api/` client for the form
  endpoint, wired into the existing `preview-centric-capture` shell in place of the hardcoded registry.
- **Modulith**: one new named interface (`documents.api`); the projector and port implementation stay
  internal; `ModularityTests` stays green.
- **No** change to: the signing FSM, `EsignProvider` / webhook flow, stamping, object storage, the
  reconciliation job, or the existing render path.

## PII / security review checklist

- **Introduces or moves identity numbers, one-time codes, VID, PII, or secrets?** **None.** A
  `FormSchema` is **system-owned schema metadata** -- field keys, labels, widget hints, types, default
  values, validation bounds, enum options, and group labels. It carries **no signer data** at all: no
  government identity number, one-time code, virtual id, name, address, or party PII, and no secret
  material. Projection is **data-independent** -- it never reads, and the schema never contains, an
  instance of user data. It describes the *shape* of data a form will later collect, never a value.
- **The one new surface is an HTTP read.** `GET /api/templates/form?state=..&type=..` takes only the
  two dimension tokens as input; they are validated against the known dimensions of the resolution
  engine, and an unknown `(state, type)` returns **404** (via the existing `ResourceNotFoundException`
  -> RFC 9457 contract, whose `detail` is a fixed constant that never echoes the requested value). The
  response body carries **no PII** and is deterministic per template version.
- **Caching / freshness.** Because the body is non-PII, deterministic metadata, the response is
  **cacheable per version** rather than `no-store`: a strong `ETag` set to the effective template's
  `contentHash` (plus a short `Cache-Control: public` max-age) lets caches revalidate cheaply and
  invalidate automatically when any contributing layer version changes. No PII is ever cached because
  none is present.
- **Markup/data boundary held.** The projector emits a **structured data contract**, never HTML and
  never an expression. `showWhen` is carried through **verbatim as an opaque string and is NOT
  evaluated** here (consistent with the definition model storing it opaque and the resolution engine
  parsing/validating it but not firing it). No expression/DSL execution surface is introduced by this
  CR.
- **Untrusted input.** The only request input is the `state`/`type` query pair (short tokens,
  validated against known dimensions); no user body, no upload, no markup. The effective template is
  composed from **system-owned** classpath layers, not user content.
- **Logging.** The endpoint and projector log only structural locations (dimension tokens, field keys,
  section titles, JSON pointers) -- never a data value; there is no PII present to redact.
- **Secrets.** None introduced; no env var, credential, or key added.
- **Sandbox + dummy data only?** Preserved -- the FormSchema is projected from dummy, system-authored
  reference layers; nothing connects to a live provider or real data.
- **Signing-status FSM transitions touched?** **None.**
- **Async signing / webhook flow touched?** **None** -- no signing sequence diagram required.
