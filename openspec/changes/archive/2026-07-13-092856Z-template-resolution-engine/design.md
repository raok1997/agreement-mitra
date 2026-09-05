## Context

`template-definition-model` established the definition shape (typed fields, plain-text-slot clauses,
ordered sections), a two-stage reject-or-nothing validator, a deterministic `CanonicalJson` +
SHA-256 identity, and an immutable record model -- all internal to `documents.template`. It
deliberately modeled a **single, self-contained** template and left `showWhen` an opaque string.

The `document-templating-platform` exploration's backbone is that a real template library is
**composed**, not copied: an effective template is layered patches applied in a fixed precedence
(`base <- type <- state <- state+type`, language reserved), resolved deterministically from the few
dimensions a user picks. This CR builds exactly that engine on top of the definition model, and gives
`showWhen` its meaning through a sandboxed DSL. It stays a pure model: data-independent, no render, no
registry, no HTTP.

## Goals / Non-Goals

**Goals**

- A **layer-patch format** (add/replace/remove/reorder clauses & sections; override field metadata;
  add fields), authored YAML -> canonical JSON, structurally + semantically validated.
- **Fixed-precedence layered composition** (`base <- type <- state <- state+type`, last-wins),
  deterministic and **data-independent**.
- A **materialized, re-validated effective template** (reusing the definition records + validator),
  **reject-or-nothing** on any composition or validation fault.
- **Effective-template identity + pinning**: canonical JSON + SHA-256 over
  `(dimensions, layer versions)`; reproducible.
- A **sandboxed `showWhen` DSL**: closed grammar, hand-written parser, declared-field validator,
  pure evaluator -- delivered but not wired to rendering.
- All internal to `documents`; reuse `template-definition` code; `ModularityTests` green.

**Non-Goals (each a named follow-on CR)**

- **Document projection / rendering** -- filling slots, evaluating `showWhen` with real user data,
  HTML/PDF, parity with the signed PDF. This CR ships the evaluator but calls it only from tests.
- **Dynamic form projection** from the effective field schema.
- **Persistence / registry** -- Postgres/object-storage for layers, version lifecycle
  (draft -> approved -> published), immutability *enforcement* across stored versions. Layers are
  classpath resources here.
- **Clause-library** resolution behind a `ref` (still recorded, still not fetched).
- **Language layer** -- the dimension is reserved; only English is active.
- **HTTP API** and the **admin builder**.

## Decisions

### D1: Layering by patch precedence, not a single-parent `extends`

An effective template is composed by applying patches in a **fixed precedence**
`base <- type <- state <- state+type`, last-layer-wins on conflict. A single-parent `extends` chain
was rejected in the exploration because state and type both contribute and produce diamond conflicts.
Fixed precedence makes the outcome unambiguous: when a state layer and a type layer both touch the
same clause, the later layer in the precedence order wins, deterministically. `extends:`-style sugar
for authors may come later but would resolve to these same patches underneath.

### D2: `base` is a full definition; the other layers are patches

The `base` (national) layer is a complete `template-definition` -- it must stand alone. The `type`,
`state`, and `state+type` layers are **patches** over the composed-so-far template. This keeps the
common case (one shared base) authored once and every jurisdiction/type a small delta, which is the
whole maintainability argument. The patch shape is its own format with its own JSON Schema; a full
definition and a patch are not interchangeable.

### D3: Patch operations -- structural edits over the definition, never over markup

A patch expresses a list of ordered operations against the structured definition:

- `addField` / `overrideField(key, metadata...)` -- add a new typed field, or override
  `required | default | options | validation | group | label` of an existing one (type is **not**
  overridable -- changing a field's type would silently invalidate slots/defaults; a type change is a
  new field).
- `addClause` / `replaceClause(id)` / `removeClause(id)` -- clauses are `template-definition` clauses
  (ref or inline plain-text-with-slots); a patch never introduces markup or code.
- `addSection` / `replaceSection(title)` / `removeSection(title)` / `reorderSections([...])` and
  per-section `reorderEntries` -- ordering is explicit and deterministic.

Every op targets an existing element by its stable key/id/title (except the `add*` ops). Operations
apply in author order within a layer, and layers apply in precedence order. This keeps the
markup/data boundary intact: authors manipulate structure, never HTML.

### D4: Resolution is a deterministic, data-independent pure function

`resolve(dimensions) -> EffectiveTemplate` depends only on `(dimensions, the resolved layers and
their versions)`. It reads **no user data** and evaluates **no `showWhen`** -- those are document
projection. Given the same dimensions and the same layer versions, it always yields byte-identical
canonical JSON and the same hash. This determinism is what makes the effective template a safe pin
for reproducibility and cacheable per `(state, type, layer-version-set)` later.

### D5: The effective template is a materialized definition, re-validated reject-or-nothing

Composition produces a fully materialized `TemplateDefinition` (all patches applied, nothing left as
a delta). We then **re-run the `template-definition` semantic validator** on the result, plus
resolution-specific checks:

- an op targeting a non-existent field/clause/section id fails;
- a `removeClause`/`removeField` that orphans a surviving slot or section entry fails;
- after composition, all the definition invariants still hold (unique keys/ids, slots resolve,
  section entries resolve, enum/options, default type-consistency).

Any failure raises `ResolutionException` and returns **no** template -- never a half-composed one.
Errors cite layer id + target id/pointer only (no data value).

### D6: Effective-template identity = (dimensions, layer versions, content hash)

The materialized template is canonicalized with the **same `CanonicalJson`** used for definitions and
SHA-256 hashed. Identity is `(dimensions, { layerId -> version }, contentHash)`. Reusing the one
canonicalizer guarantees a definition and its (trivially-resolved) effective template hash
consistently, and gives a future agreement a single value to pin. A signed agreement records this pin
and is **never re-resolved** against newer layers.

### D7: `showWhen` is a tiny closed grammar, hand-parsed -- the RCE guard

`showWhen` gains meaning here, so it is deliberately minimal and self-contained:

```
expr    := or
or      := and ( '||' and )*
and     := not ( '&&' not )*
not     := '!' not | cmp
cmp     := atom ( ('=='|'!='|'<'|'<='|'>'|'>=') atom )?
atom    := field | number | string | bool | '(' expr ')'
field   := a declared field key (identifier)
```

- **Hand-written recursive-descent parser** -- no expression library, and explicitly never Thymeleaf
  SpringEL (the exploration's named RCE surface). The grammar has **no** method calls, property
  navigation, indexing, functions, or assignment.
- **Validator (data-independent, part of resolution):** every `showWhen` in the effective template
  parses, and every identifier is a declared field key. A parse or unknown-field error fails
  resolution.
- **Evaluator (pure, delivered but unwired):** `ShowWhenEvaluator.evaluate(ast, fieldValues) ->
  boolean` reads only the supplied value map. Document projection will call it with real data in a
  later CR; here it is exercised only by unit tests. Type rules (e.g. ordering comparisons require
  numeric/date operands) are defined and tested.

Delivering the evaluator now (rather than only the parser/validator) keeps the DSL a single cohesive,
fully-tested unit; the *wiring* to render is the only piece deferred.

### D8: `LayerSource` seam, classpath-backed now, registry-ready later

Resolution depends on a `LayerSource` that, given `(state, type)`, returns the ordered layers (base +
applicable patches) with their versions. In this CR it is a **classpath-resource** implementation
reading the reference layer set. The DB registry (Postgres rows + object-storage bodies, versioning
workflow) is the catalog CR and swaps in behind this same seam without touching the resolver.

### D10: Same package as the definition model, not a `.resolve` sub-package

The resolver reuses the definition model's **package-private** records and helpers directly
(`TemplateDefinition`, `Field`, `Clause`, `Section`, `Meta`, `CanonicalJson`,
`TemplateDefinitionValidator`). Java grants a sub-package **no** access to its parent package's
package-private members, so placing the new types in `documents.template.resolve` would force those
reused types to become `public` — widening the module's surface against the repo convention
(`public` only on the module API) and this CR's own "no public-API addition" promise, and risking a
`ModularityTests` flag. The new types therefore live in the **same package**
`in.agreementmitra.documents.template`, all package-private. Everything stays internal to the
`documents` module; nothing new is exported.

### D9: No new dependency

Structural validation of patches reuses `json-schema-validator` (already added by
`template-definition-model`); the `showWhen` parser is hand-written by design. So there is no new
dependency and no lockfile change -- lower blast radius and no new OSV surface. The hand-written
parser is a deliberate security choice, not merely a dependency-avoidance one.

## Resolution sketch

```
resolve( dimensions{ state, type } ):
    layers   = LayerSource.layersFor(state, type)      # ordered: base, type?, state?, state+type?
    acc      = layers.base                              # a full TemplateDefinition
    for patch in layers.patches (precedence order):
        acc = apply(patch, acc)                        # add/replace/remove/reorder/override
    validate(acc)                                       # definition semantics + resolution checks + showWhen
    canonical = CanonicalJson.of(acc)
    return EffectiveTemplate(
        template   = acc,
        dimensions = dimensions,
        provenance = { layerId -> version for layers },
        contentHash= sha256(canonical) )
```

```yaml
# examples/layers/telangana-residential.patch.yaml  (illustrative)
meta: { kind: state_type, dimensions: { state: TG, type: residential }, version: 1 }
ops:
  - { op: overrideField, key: registrationResponsibility, required: true }
  - { op: addClause, after: rent,
      clause: { id: tg-stamp, text: "Stamp duty per Telangana Act is {{stampDuty}}." } }
  - { op: reorderSections, order: [ Parties, Property, Financial terms, Statutory ] }
```

## Risks / Trade-offs

- **Composition ambiguity.** Two layers touching the same element must resolve predictably.
  Mitigation: fixed precedence + last-wins + author-order within a layer, all covered by
  determinism tests; no implicit merge heuristics.
- **DSL scope creep.** A "just add functions/lists" request would reopen the RCE surface. Mitigation:
  the grammar is closed and documented; extensions require an explicit proposal weighing the sandbox.
- **Effective-template validity depends on patch authors.** A bad patch could orphan a slot.
  Mitigation: reject-or-nothing re-validation of the composed result, with resolution-specific
  orphan/target checks and negative tests.
- **Canonicalizer coupling.** Resolution must reuse the exact `CanonicalJson` from
  `template-definition-model` or hashes diverge. Mitigation: shared class, plus a test asserting a
  trivially-resolved base equals its own definition hash.
- **Determinism vs future caching.** We do not cache here, but the pure-function contract is what
  makes the projection CR's caching sound. Mitigation: the resolver takes no clock/IO/random input;
  a property test asserts repeat resolution is byte-identical.

## Migration Plan

Additive, and ordered **after** `template-definition-model` (it reuses that model). New types added
to the existing `in.agreementmitra.documents.template` package (see D10),
one patch JSON Schema, a reference layer set; no new dependency, no Flyway migration, no change to any
existing class or endpoint. Nothing consumes the resolver yet, so it is inert until the projection CR
calls it; no rollout ordering beyond the definition-model prerequisite.

## Open Questions (resolved per follow-on CR)

- **`showWhen` type coercion** -- exact operand-type rules (string vs number vs date comparisons,
  bool coercion). Pinned by this CR's evaluator + tests; the projection CR must feed values matching
  the declared field types.
- **Patch targeting of reordered/removed elements across layers** -- e.g. a state+type layer removing
  a clause a type layer added. Assumed allowed (later precedence wins); covered by tests, revisited if
  authoring proves it confusing.
- **Where layers ultimately live** -- classpath now; Postgres registry + object-storage bodies +
  version lifecycle later (the catalog CR), behind the `LayerSource` seam.
- **Language layer activation** -- reserved dimension; activated when go-to-market needs Telugu /
  bilingual (per `docs/ROADMAP.md`).
- **Effective-template caching** -- the resolver is pure and cacheable per
  `(state, type, layer-version-set)`, but caching itself is the projection CR's concern.
