## Context

`template-definition-model` established the definition shape (typed `Field`s with a closed
`FieldType` and declarative `FieldValidation`, plain-text-slot clauses, ordered `Section`s), a
deterministic `CanonicalJson` + SHA-256 identity, and an immutable record model in
`in.agreementmitra.documents.template`. `template-resolution-engine` composes `(state, type)` into one
materialized, re-validated, hash-pinned `EffectiveTemplate` (a `TemplateDefinition` + dimensions +
provenance + contentHash), and gives `showWhen` a sandboxed parser/validator/evaluator that it does
**not** wire to any render. Both are pure domain models with no HTTP surface and no public API.

The `document-templating-platform` exploration splits preview into three layers by what depends on
user data: **(1) resolve** and **(2) form projection** are data-independent and cacheable per version;
**(3) document projection** is data-dependent and rendered on the fly. This CR is layer (2). It also
retires a stopgap: `preview-centric-capture` shipped a document-first capture shell whose section rail
is hardcoded -- the exploration explicitly calls that "a stand-in for this projection". This change
feeds that shell from a projected schema.

Constraints unchanged: Java 21 + Spring Boot 3.5.x + Spring Modulith; Vue 3 + `<script setup>` +
Tailwind; keep `ModularityTests` green; sandbox + dummy data; never log PII; the markup/data boundary
(users edit data, never template markup).

## Goals / Non-Goals

**Goals**

- A pure, deterministic, **data-independent** `FormProjector`: `EffectiveTemplate -> FormSchema`,
  derived only from the effective template's fields + sections.
- A **FormSchema** JSON contract: `dimensions / templateId / version / contentHash` + ordered
  `sections -> fields`, each field carrying `key / label / widget / type / required / default? /
  options? / group? / validation{...}` and an unevaluated `showWhen?`.
- A closed **`FieldType -> widget`** mapping carried in the schema.
- **Per-field validation metadata** sufficient for client-side validation (required, type, numeric
  min/max, text length/pattern, enum options, default).
- The templating stack's **first public module API** (`TemplateFormApi` port + `FormSchema` DTO under
  a `documents.api` named interface) and **first HTTP surface**
  (`GET /api/templates/form?state=..&type=..`), 404 on unknown dimensions, cacheable per version.
- A **schema-driven Vue form renderer** that feeds the existing `preview-centric-capture` shell in
  place of its hardcoded registry.
- Reuse `template-definition` records + the `template-resolution` engine; `ModularityTests` green.

**Non-Goals (each a named follow-on CR)**

- **Document projection / rendering** -- filling slots, evaluating `showWhen` with real user data,
  HTML/PDF, and **server-side validation of submitted data**. This CR ships client-validation metadata
  only; the authoritative server-side re-validation of a submission belongs to document projection.
- **Persisting captured data / the draft** -- the existing generate-as-draft flow is unchanged; this
  CR persists nothing.
- **Template catalog / selection** -- browsing and choosing among many templates. This CR takes
  `(state, type)` as given and resolves the one effective template for it.
- **The admin template builder** -- authoring definitions/layers.
- **Evaluating `showWhen`** -- carried as opaque metadata; conditional field visibility with real data
  is data-dependent (document projection). This CR neither fires nor even needs the resolution engine's
  `showWhen` evaluator.

## Decisions

### D1: Form projection is a pure, data-independent function of the effective template

`FormProjector.project(effectiveTemplate) -> FormSchema` depends only on the effective template's
fields and sections. It reads **no user data**, takes no clock/IO/random input, and evaluates **no
`showWhen`**. Given the same effective template it always produces the same FormSchema. This is the
exploration's layer (2): the form structure exists the moment dimensions are resolved, before the user
types anything. Determinism is what makes the schema safely cacheable per `(state, type,
layer-version-set)` -- the same key that pins the effective template.

### D2: The FormSchema shape -- sections -> fields, widget + validation carried

The schema is an immutable record tree (defensive-copied lists), serialized to JSON by the
Spring-Boot-managed Jackson. Sections come from the effective template's `Section` list in order; each
section's `entries` are field keys **and** clause ids -- projection keeps the **field-key** entries in
order as form inputs and **skips clause-id entries** (clauses are document body content, not data the
form collects). A field that is declared but placed in no section is an authoring error surfaced by
the definition/resolution validators, not something the projector invents a home for; the projector
assumes every form field is section-placed (see Open Questions).

Each field is projected from its `Field` record: `key`, `label`, the resolved `widget` (D4), the raw
`type` token (so the client can format/parse), `required`, the `default` (type-typed literal, when
present), `options` (only for `enum`), `group` (when present), and a `validation` sub-object holding
whatever `FieldValidation` bounds exist. `showWhen` (a clause/field-level condition, when present) is
copied through **verbatim and unevaluated** as reserved metadata.

### D3: Cacheable per version (ETag = contentHash), not no-store

The response body is deterministic, non-PII system metadata, so unlike the stateless *document*
preview (which carries party PII and is `no-store`), the form schema is **cacheable per version**. The
endpoint sets a strong **`ETag` equal to the effective template's `contentHash`** and a short
`Cache-Control: public, max-age=<small>`. Because the contentHash changes whenever any contributing
layer version changes (guaranteed by the resolution engine), a stale schema self-invalidates -- the
ETag no longer matches and a conditional `GET` (`If-None-Match`) revalidates. This mirrors the
exploration's "form schema cached per template version" and costs nothing in PII exposure because the
body has none. `no-store` was rejected: it would force a re-projection on every keystroke-triggered
form load for zero privacy benefit.

### D4: Closed FieldType -> widget mapping, resolved server-side

The closed `FieldType` set maps one-to-one to a small widget vocabulary, resolved by the projector and
carried in the schema so the frontend never re-derives it:

| FieldType  | widget            | client parse/format + validation drivers                     |
| ---------- | ----------------- | ------------------------------------------------------------ |
| `text`     | `text`            | single-line; `minLength` / `maxLength` / `pattern`           |
| `longtext` | `textarea`        | multi-line; `minLength` / `maxLength`                        |
| `int`      | `number`          | integer step; `min` / `max`                                  |
| `money`    | `money`           | decimal / currency; `min` / `max`                            |
| `date`     | `date`            | date picker; (date bounds reserved)                          |
| `bool`     | `checkbox`        | boolean; no bounds                                           |
| `enum`     | `select`          | choose one of `options` (client validates membership)        |

Keeping the vocabulary closed and switch-exhaustive means a new `FieldType` (a deliberate,
additive definition-model change) forces both a projector case and a widget component -- the compiler
and a mapping test catch a gap. `money` stays distinct from `number` so the frontend can apply
currency formatting without re-inspecting `type`.

### D5: Validation metadata lives in the schema (client validation); server-side is deferred

The schema carries enough per field for the **client** to validate before preview/submit: `required`,
the `type`, numeric `min`/`max`, text `minLength`/`maxLength`/`pattern`, `enum` `options`, and a
type-typed `default`. This is `FieldValidation` projected verbatim plus `required`/`type`/`options`.
Client validation is a UX affordance, **not** a trust boundary: the **authoritative** validation of a
*submitted* payload against the schema is **server-side and belongs to document projection** (which
owns the submit path). Stating this split here prevents a later assumption that client metadata is the
enforcement point. The metadata is declarative only -- bounds and a regex `pattern`, never an
expression or code.

### D6: First public module API -- a `documents.api` named interface, projector stays internal

This is the templating stack's first cross-module consumer (the SPA over HTTP), so it earns the
`documents` module's first public surface -- built exactly like `signing.api`:

- A new `@NamedInterface("api")` package `in.agreementmitra.documents.api` holds the **public**
  `FormSchema` DTO tree, the **public** `TemplateFormApi` port (`formFor(String state, String type)
  -> FormSchema`), and `TemplateFormController` (`GET /api/templates/form`).
- The port **implementation** and the `FormProjector` stay **package-private in
  `in.agreementmitra.documents.template`** -- the same package as the `Field` / `Section` /
  `EffectiveTemplate` records. This is the key packaging decision: **co-locating the projector with the
  records means no record visibility is widened** (the definition and resolution CRs kept those records
  package-private, and this CR keeps them so). A package-private class can still construct and return
  the `public` `FormSchema` (a public type is visible module-wide) and can implement the public
  `TemplateFormApi` interface, which Spring wires as a bean into the controller.

`ModularityTests` therefore sees exactly one new named interface (`documents.api`) and no new
cross-module reach-in; the controller depends only on the `TemplateFormApi` port, never on
`documents.template` internals.

### D7: Unknown dimensions -> 404 via the existing error contract

`formFor(state, type)` resolves via the resolution engine's `LayerSource`; a `(state, type)` with no
base layer has no template. The port throws the app-wide `ResourceNotFoundException`, which the root
`GlobalExceptionHandler` already maps to a **404** RFC 9457 `ProblemDetail` whose `detail` is a fixed
constant (it never echoes the requested `state`/`type`). No new exception type or handler is needed;
the never-echo invariant is inherited.

### D8: Relationship to preview-centric-capture -- feed the shell, do not rebuild it

`preview-centric-capture` built the responsive two-pane shell, the section-modal pattern, the
completeness bar, and the stateless HTML/PDF preview -- with a **hardcoded** section registry as a
stand-in. This CR **replaces only that registry's data source**: the shell now fetches a `FormSchema`
(via a new `src/api/` client) and renders its sections/fields/widgets/validation from the schema. The
shell, modals, live-preview pane, preview endpoint, and Save & continue persistence are untouched. One
widget component per `widget` value; a section maps to the existing section-modal; each field's
metadata drives the modal's per-field validation. The completeness rule per section is derived from
which of its `required` fields are filled.

### D9: No new dependency

JSON serialization of the `FormSchema` uses the Jackson that Spring Boot already provides; the
projector is plain Java over existing records. **No new library, no `gradle.lockfile` change, no new
OSV surface.**

## FormSchema sketch

```
FormSchema
  dimensions:  { state, type }
  templateId:  string           # effective template's meta.id
  version:     int              # effective template's meta.version (of the base/pin)
  contentHash: string           # effective-template SHA-256 -- the ETag / cache key
  sections:    [ FormSection ]

FormSection
  title:   string
  fields:  [ FormField ]        # section entries that are field keys, in authored order
                                # (clause-id entries are document content -- skipped here)

FormField
  key:        string
  label:      string
  widget:     text | textarea | number | money | date | checkbox | select   # from FieldType (D4)
  type:       text | longtext | int | money | date | bool | enum            # raw FieldType token
  required:   bool
  default:    <type-typed literal>?      # Boolean / Long / BigDecimal / String, when present
  options:    [ string ]?                # present iff type == enum
  group:      string?
  validation: { min?, max?, minLength?, maxLength?, pattern? }   # projected FieldValidation
  showWhen:   string?                    # opaque, carried-through, NOT evaluated in this CR
```

```
GET /api/templates/form?state=TG&type=residential
  -> 200 application/json
     ETag: "<effective-template contentHash>"
     Cache-Control: public, max-age=<small>
     { "dimensions": { "state": "TG", "type": "residential" },
       "templateId": "residential-rental", "version": 1,
       "contentHash": "...", "sections": [ ... ] }
  -> 304 (on a matching If-None-Match)
  -> 404 (unknown state/type -- RFC 9457 ProblemDetail, no echo of the input)
```

## Risks / Trade-offs

- **Schema/record drift.** The FormSchema restates parts of the `Field`/`FieldValidation`/`Section`
  shape. Mitigation: the projector reuses those records directly (no re-declared field model), and a
  round-trip test over the reference layer set asserts every field/section/validation projects
  faithfully; a `FieldType`/widget mapping test asserts the switch is exhaustive.
- **Cache staleness across layer edits.** A cached schema must not outlive a layer change. Mitigation:
  the ETag **is** the effective-template contentHash, which the resolution engine changes on any
  content-bearing layer edit; a stale entry fails `If-None-Match` and revalidates. `max-age` is kept
  small.
- **Client validation mistaken for enforcement.** Carrying validation metadata to the client could be
  read as the trust boundary. Mitigation: D5 states plainly that server-side validation of submissions
  is document projection's job; this CR's metadata is UX only.
- **`showWhen` carried but inert.** Shipping `showWhen` in the schema without evaluating it could
  invite a frontend to fake-evaluate it. Mitigation: it is documented as reserved/opaque; conditional
  visibility is explicitly a document-projection concern; the projector never sets a field hidden.
- **First public API freeze.** Exposing `TemplateFormApi` + `FormSchema` freezes a surface. Mitigation:
  it is deliberately minimal (one method, one DTO tree) and additive; internals (projector, port impl)
  stay package-private so the shape can evolve behind the port.

## Migration Plan

Additive, and ordered **after** `template-definition-model` and `template-resolution-engine` (it
consumes their records and the resolver). New package-private projector + port impl, one new public
named-interface package, one controller, one new frontend renderer + `src/api/` client. **No new
dependency, no Flyway migration, no schema change**, and no change to any existing backend class or the
existing render/preview endpoints. The frontend swaps the hardcoded section registry for the
schema-fed one behind the same capture route, so the existing shell keeps working until the schema
feed is validated.

## Open Questions (resolved per follow-on CR)

- **Fields not placed in any section.** Assumed an authoring error (every form field is section-placed
  in the reference layers); if real templates need un-sectioned fields, an implicit trailing group is
  the likely answer -- revisited when the catalog surfaces real templates.
- **`date` field bounds.** `FieldValidation` today carries numeric `min`/`max` and text length/pattern
  but no explicit date range; date bounds are reserved and added to the validation projection when the
  definition model grows them.
- **`showWhen` on fields vs clauses.** Today `showWhen` sits on clauses; field-level conditional
  visibility is a document-projection (data-dependent) concern. The schema reserves the optional slot
  so it can carry through if the model attaches field conditions later, still unevaluated here.
- **Schema versioning / content negotiation.** A single JSON shape now; if the FormSchema contract
  itself needs versions (distinct from template versions), that is a later API concern.
- **Caching tier.** HTTP `ETag`/`max-age` now; a server-side computed-schema cache keyed by
  `(state, type, layer-version-set)` is an optional optimization the catalog CR can add behind the
  port without changing the contract.
