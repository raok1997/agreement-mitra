## Why

`template-definition-model` gave us the shape of a single, self-contained template: a typed field
schema, plain-text clauses with typed slots, ordered sections -- validated, canonicalized, and
content-hashed. But a real template library is not a flat pile of self-contained definitions. The
`document-templating-platform` exploration is explicit: a template is **not** a single-parent
`extends` chain (that hits diamond conflicts the moment state and type both contribute); the
effective template is **composed by layering patches in a fixed precedence**:

> national base  <-  type layer  <-  state layer  <-  state+type layer   [ <- language -- deferred ]

Without this, adding a jurisdiction means copying a whole template and maintaining it forever; a bug
fix in the shared base has to be re-applied by hand to every copy. With it, a new jurisdiction is
**one small state layer over the shared base**.

This change adds the **resolution engine**: the layer-patch format, the deterministic
fixed-precedence resolver that composes `(state, type)` into one immutable, hash-pinned **effective
template**, and the sandboxed **`showWhen` DSL** that `template-definition-model` deliberately left as
an opaque string. It is still a pure backend domain model -- no rendering, no dynamic form, no
database registry, no HTTP. It is the deterministic function the projection CRs (form + document) and
the reproducibility guarantee (an executed agreement pins the effective template it used) both build
on.

**Why this is the safety-critical seam.** The exploration warns that a general expression language is
the RCE surface (never Thymeleaf SpringEL). This CR is where `showWhen` stops being an opaque string
and gains meaning -- so it delivers a **tiny, closed, hand-written boolean grammar** over declared
fields and literals only, with no method calls, property navigation, or code, evaluated by our own
engine and not a template engine. Getting this sandbox right here is what keeps every later render
safe.

## What Changes

- Introduce a new **`template-resolution`** capability inside the `documents` module, added to the
  **existing internal package `in.agreementmitra.documents.template`** (same package as the definition
  model — not a sub-package, because Java grants a sub-package no access to the parent's
  package-private records/helpers, and the resolver reuses those directly), building on the
  `template-definition` model records, `CanonicalJson`, and semantic validator. Nothing is added to the
  module's public API (still no cross-module consumer); `ModularityTests` stays green.
- Define the **layer-patch format**: a declarative patch, authored in YAML and compiled to canonical
  JSON, that a layer applies over the composed-so-far template. A patch can:
  - **add / replace / remove / reorder** clauses and sections (targeting by clause `id` / section
    `title` / field `key`);
  - **override field metadata** (`required`, `default`, `options`, `validation`, `group`, `label`)
    on an existing field, and **add** new fields.
  Patches are validated structurally (a checked-in JSON Schema) exactly like definitions.
- Define the **layer set and fixed precedence**: layers are identified by
  `{ kind: base | type | state | state_type, dimensions, version }` and applied **base -> type ->
  state -> state+type**, last-layer-wins on conflict. The **language layer is reserved but not
  activated** (English only). The `base` layer is a full `template-definition`; the `type`/`state`/
  `state+type` layers are patches. A `LayerSource` seam supplies the ordered layers for a
  `(state, type)`; in this CR it reads them from **classpath resources** (a DB registry is the
  catalog CR).
- Add the **resolver**: `resolve(dimensions) -> EffectiveTemplate`. Resolution is a **deterministic,
  data-independent pure function of `(dimensions, layer versions)`** -- it composes the base with each
  patch in precedence order and materializes one fully-resolved template (fields + clauses +
  sections). It does **not** touch user data and does **not** evaluate `showWhen` (that is document
  projection).
- **Validate the effective template**: after composition, re-run the `template-definition` semantic
  validation on the *result* (slots resolve to surviving fields, section entries resolve, enum/options
  consistency, default type-consistency) plus new resolution checks (a patch cannot target a
  non-existent id; a remove that orphans a slot fails). **Reject-or-nothing**: a layer set that
  composes to an invalid template fails resolution with an actionable, location-only error -- never a
  half-composed template.
- Add **effective-template identity + pinning**: the resolved template is canonicalized (reusing
  `CanonicalJson`) and **SHA-256 hashed**; its identity is
  `(dimensions, { layerId -> version }, contentHash)`. Same `(dimensions, layer versions)` always
  resolves to the same effective template and the same hash -- the anchor a future agreement pins so a
  signed agreement is **never re-resolved** with newer layers.
- Add the **sandboxed `showWhen` DSL**: a small closed grammar (comparison `== != < <= > >=`, boolean
  `&& || !`, parentheses, over declared field `key`s and number/string/bool literals), a **hand-written
  recursive-descent parser** (no expression library), a **validator** (every `showWhen` in the
  effective template parses and references only declared fields -- run as part of resolution, so it is
  data-independent), and a **pure evaluator** (`parse tree + field-value map -> boolean`). The
  evaluator is delivered and unit-tested but **not yet wired to any render** -- document projection
  calls it later with real data.
- Add a small **reference layer set** (a base + a type patch + a state patch + a state+type patch,
  dummy system-authored content) used as the resolver's end-to-end integration fixture and as the
  worked example of composition.

**Explicitly not in this change** (each a named follow-on CR): **document projection / rendering** --
filling slots, evaluating `showWhen` against real user data, HTML/PDF, and parity with the signed
PDF; **dynamic form projection** from the effective field schema; **persisting layers/versions in a
registry** (Postgres + object storage) and the versioning/lifecycle workflow -- layers are classpath
resources here; the **clause-library** storage a `ref` resolves against (still recorded, still not
fetched); the **language layer** (reserved dimension); any **HTTP endpoint**; the **admin builder**.

## Capabilities

### New Capabilities
- `template-resolution`: the layer-patch format (add/replace/remove/reorder + field-metadata
  override, structurally validated), fixed-precedence layered composition
  (`base <- type <- state <- state+type`, last-wins, language reserved), a deterministic
  data-independent resolver producing a materialized effective template that is re-validated
  reject-or-nothing, effective-template identity + pinning (canonical JSON + SHA-256 over
  `(dimensions, layer versions)`), and a sandboxed hand-written `showWhen` boolean DSL (grammar,
  parser, declared-field validator, and pure evaluator -- not yet wired to rendering).

### Modified Capabilities
- `template-definition`: unchanged in behavior, but its model records, `CanonicalJson`, and semantic
  validator are now **reused** by resolution (the effective template is a materialized
  `TemplateDefinition`). No format or validation rule changes; `showWhen` remains opaque *in a raw
  definition* and gains meaning only once resolution's DSL validator runs over the composed result.

## Impact

- **`documents` module**: new types in the existing internal package
  `in.agreementmitra.documents.template` (same package as the definition model, so the resolver can
  reuse its package-private records/helpers without widening any visibility) --
  `LayerPatch` (+ patch-op records), `LayerRef`/`LayerKind`, a `LayerSource` seam (+ classpath
  implementation), the `TemplateResolver`, `EffectiveTemplate` (materialized template + provenance +
  hash), a `ResolutionException`, and the `showWhen` DSL (`ShowWhenParser`, `ShowWhenExpr` AST,
  `ShowWhenValidator`, `ShowWhenEvaluator`). All package-private; **no public-API addition**. The
  existing `DocumentRenderer` / `TemplateAssembler` / Gotenberg path is **untouched**.
- **Reuse, not fork**: resolution depends on the `template-definition` records + `CanonicalJson` +
  semantic validator from `template-definition-model`. **This CR requires that change to be applied
  first.**
- **Resources**: a patch JSON Schema (`layer-patch.schema.json`) and a small reference layer set
  (`examples/layers/...`) under the module's resources; no template markup changed.
- **Dependencies**: **none added.** JSON-Schema validation reuses the `json-schema-validator` already
  introduced by `template-definition-model`; the `showWhen` parser is hand-written specifically to
  avoid pulling in a general expression library (which would reintroduce the RCE surface). No
  lockfile change.
- **Data / schema**: **none** -- no Flyway migration, no table, no object-storage change. Layers are
  classpath resources; persistence is the registry CR.
- **Modulith**: all new code internal to `documents`; `ModularityTests` stays green.
- **No** change to: the signing FSM, `EsignProvider` / webhook flow, stamping, object storage, the
  reconciliation job, security config, or any HTTP surface.

## PII / security review checklist

- **Introduces or moves identity numbers, one-time codes, VID, PII, or secrets?** **None.** Layers,
  patches, and the effective template are **system-owned schema metadata** (field keys, labels,
  types, trusted clause markup, and now `showWhen` conditions). They carry **no signer data**: no
  government identity number, one-time code, virtual id, name, address, or party PII, and no secret
  material. Resolution is data-independent -- it never sees user data.
- **The `showWhen` sandbox (the one real new surface).** `showWhen` stops being an opaque string and
  gains an evaluator, so it is deliberately confined: a **tiny closed grammar** (comparisons, boolean
  ops, parentheses, declared field references, and literals) parsed by a **hand-written
  recursive-descent parser -- not a general expression engine and never Thymeleaf SpringEL**. The
  grammar has **no** method invocation, property/dereference navigation, indexing, function calls, or
  assignment -- so there is no code-execution, reflection, or property-traversal surface. Identifiers
  are checked against declared field keys at resolution time; the evaluator only reads a supplied
  field-value map and returns a boolean.
- **Markup/data boundary held.** Patches manipulate the **structured definition** (add/replace/remove/
  reorder/override), never raw markup; clause `text` stays plain-text-with-typed-slots. No
  user-authored markup is introduced (authoring is the admin-builder CR); the reference layers are
  system-authored dummy content.
- **Untrusted input.** Only **system-owned** layer/patch resources are parsed (not user uploads),
  with the same safe, type-restricted YAML mapper and reject-or-nothing validation as
  `template-definition`. A malformed or malicious patch fails resolution and yields no template.
- **Reproducibility as a security property.** The effective template is hash-pinned over
  `(dimensions, layer versions)`; a future agreement records that pin, so a signed agreement is
  **never silently re-resolved** with newer/altered layers.
- **Logging.** Resolution and DSL errors reference layer ids, field keys, clause ids, section titles,
  and JSON pointers only -- never a data value; there is no PII present to redact.
- **Secrets.** None introduced; no env var, credential, or key added.
- **Sandbox + dummy data only?** Preserved -- reference layers are dummy, system-authored; nothing
  connects to a live provider or real data; the DSL evaluator is exercised only with test data.
- **Signing-status FSM transitions touched?** **None.**
- **Async signing / webhook flow touched?** **None** -- no signing sequence diagram required.
