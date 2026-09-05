## Context

The `documents` module renders one hardcoded rental-agreement template (`TemplateAssembler` +
`GotenbergDocumentRenderer`, public API `DocumentRenderer` taking a template id + a generic data
map). The `document-templating-platform` exploration lays out the target: a template becomes a
**declarative definition** -- a typed field schema, plain-text clauses with typed slots, and ordered
sections -- and both the capture form and the rendered document are projections of that one
definition.

That exploration bundles a lot across several CRs (layered resolver, dynamic form projection,
persistence registry, admin builder). This CR is deliberately the **narrowest foundational slice**:
just the **definition format and its in-memory model** -- how a template is *described*, validated,
canonicalized, and identified. Nothing consumes it yet. Getting this contract precise and immutable
before the resolver/catalog build on it is the whole point; everything downstream targets this shape.

## Goals / Non-Goals

**Goals**

- A declarative **definition format** (`meta / fields / clauses / sections`), authored in YAML,
  compiled to a **canonical JSON**.
- A **closed, typed field schema** -- the data contract a form and a renderer will later project.
- Clauses as **plain text with typed `{{slots}}`** bound to declared fields, encoding the
  markup/data boundary at the format level.
- **Two-stage, reject-or-nothing validation**: JSON-Schema (structural) then semantic
  (cross-reference).
- A **deterministic canonical form + SHA-256 content hash**, giving each definition an
  `(id, version, contentHash)` identity.
- **Immutable value-type** model (records), fully internal to `documents`; `ModularityTests` green.

**Non-Goals (each a named follow-on CR)**

- The **layered composition resolver** (`base <- type <- state <- state+type`) and the layer-patch
  format. A definition here is a single, self-contained template for one `(state, type)` point.
- **`showWhen` grammar or evaluation** -- carried as an opaque string, never parsed or run here.
- **Dynamic capture-form projection** from the field schema.
- **Rendering** -- compiling a definition + data into HTML/PDF, and any parity with the signed PDF.
- **Persistence / registry** -- Postgres tables, object storage, versioning workflow, immutability
  *enforcement* across stored versions.
- **Any HTTP endpoint** and the **clause-library** storage that a `ref` resolves against.

## Decisions

### D1: A new `template-definition` capability, internal to `documents`, with no public API yet

The model lives in a new sub-package `in.agreementmitra.documents.template`, entirely
**package-private**. Nothing is added to the module's public API because there is no cross-module
consumer in this CR -- the resolver, form projection, and catalog (future CRs) are what will consume
it, and each will decide then what to expose. Keeping it internal now keeps `ModularityTests` green
with zero new named interfaces and avoids freezing a public surface before its first real caller
exists. The existing `DocumentRenderer` path is untouched.

### D2: Authoring in YAML, canonicalized to JSON; identity = (id, version, content-hash)

Authors read and diff YAML far more comfortably than JSON or XML, so definitions are **authored in
YAML**. The loader immediately compiles to a **canonical JSON** (deterministic key order, normalized
scalars) so that the definition has one byte-stable representation regardless of YAML formatting.
Over those canonical bytes we compute a **SHA-256 content hash**. A definition's identity is the
triple `(id, version, contentHash)`: `id`+`version` are the human-facing coordinates; `contentHash`
is the integrity anchor a future agreement pins to and the value a future registry can use to detect
"same content, different file". Two equivalent-but-differently-formatted YAML files therefore hash
identically.

### D3: A closed field-type set with per-field validation metadata

`FieldType` is a **closed enum**: `text, longtext, int, money, date, bool, enum`. A closed set keeps
the contract small and lets every downstream projector (form widget, validation, renderer formatting)
exhaustively switch on it. Each field carries `key, label, type, required, default?, options?,
validation?, group?`. `options` is required for and only valid on `enum`. `validation` holds a small,
typed bag (min/max for `int`/`money`, min/max length and an optional `pattern` for text, min/max for
`date`) -- declarative only; **no expression language**. `default`, when present, must be valid for
the field's type. The taxonomy is intentionally minimal now (see Open Questions) and additive later.

### D4: Clauses are plain text with typed `{{slots}}` -- the markup/data boundary, in the format

A clause is either a **`ref`** to a clause-library id (an opaque string here -- the library that
resolves it is a later CR) or an **inline** `{ id, text, showWhen? }`. Inline `text` is **plain
text** containing zero or more `{{slotKey}}` placeholders; `slotKey` must name a declared field.
`text` is **never HTML and never an expression** -- this is the exact point where the exploration's
"markup is system-owned, data is escaped" invariant is encoded. Because the model only *records* the
slots (it does not fill them), no escaping or evaluation happens here; the guarantee this CR makes is
structural: a slot cannot reference anything but a declared field, and a clause cannot carry markup or
code.

### D5: Two-stage validation, reject-or-nothing

Loading runs **structural** validation first -- the canonical JSON is checked against a checked-in
**JSON Schema** (`template-definition.schema.json`) covering required keys, the field-type enum, and
the shape of clauses/sections -- then **semantic** validation for the cross-references a schema
cannot express:

- field `key`s unique; clause `id`s unique;
- every `{{slot}}` in every clause references a declared field `key`;
- every section entry references a declared field `key` or clause `id`;
- an `enum` field declares non-empty `options`; a non-`enum` field declares none;
- a present `default` is type-valid (and, for `enum`, one of `options`).

A definition either loads **whole or not at all**: any structural or semantic failure raises
`TemplateDefinitionException` carrying the JSON-pointer / key / id at fault, and **no partial model is
returned**. Errors name only structural locations (keys, ids, pointers), never data values.

### D6: `showWhen` is carried, not evaluated

An inline clause may carry a `showWhen` string. This CR **stores it verbatim and does nothing else**
-- it is not parsed, its identifiers are not checked, and it is never evaluated. Introducing any
evaluation now would mean introducing an expression engine, which is precisely the RCE surface the
exploration warns against (never Thymeleaf SpringEL). The sandboxed boolean DSL over declared fields
is the resolver CR's responsibility; keeping `showWhen` opaque here reserves the field in the format
without opening that surface.

### D7: JSON-Schema validation via `com.networknt:json-schema-validator`

Structural validation uses `com.networknt:json-schema-validator` (a mature, offline, Draft
2020-12-capable validator) against a checked-in schema resource. This is a **new backend dependency**:
`gradle.lockfile` is regenerated (`./gradlew dependencies --write-locks`) and the new graph must pass
the existing OSV `securityScan` gate (fail-closed, no CVSS threshold). YAML parsing uses
`jackson-dataformat-yaml` (Spring-Boot-managed). The alternative -- a hand-rolled structural checker
-- was rejected: it would re-implement schema validation badly and drift from the checked-in schema
that also documents the format for authors.

### D8: The reference definition is a fixture, not a wiring change

The residential rental agreement is expressed once as a definition YAML
(`examples/residential-rental.yaml`) to (a) prove the format is expressive enough for the real
document and (b) drive the loader's integration test end-to-end through real classpath I/O. It is
**not** connected to `TemplateAssembler`/`DocumentRenderer` -- replacing the live template with a
definition-driven render is the render-parity CR. Keeping them separate keeps this CR's blast radius
at zero for the shipping render path.

## Model sketch

```
TemplateDefinition
  meta:      Meta { id, dimensions: Dimensions{ state, type }, version, status }
  fields:    List<Field>    Field { key, label, type: FieldType, required,
                                     default?, options?, validation?, group? }
  clauses:   List<Clause>   Clause = Ref{ ref } | Inline{ id, text, showWhen? }
  sections:  List<Section>  Section { title, entries: List<String> }  // field keys / clause ids

FieldType = text | longtext | int | money | date | bool | enum
status    = draft | legal_approved | published | deprecated
```

```yaml
# examples/residential-rental.yaml  (illustrative shape)
meta:   { id: residential-rental, dimensions: { state: IN, type: residential },
          version: 1, status: draft }
fields:
  - { key: monthlyRent,  label: Monthly rent,    type: money, required: true }
  - { key: lockInMonths, label: Lock-in (months), type: int,  required: false, default: 6,
      validation: { min: 0, max: 60 } }
clauses:
  - { id: rent, text: "The lessee shall pay a monthly rent of {{monthlyRent}}." }
  - ref: clause-lib/late-payment
sections:
  - { title: Financial terms, entries: [ monthlyRent, rent, late-payment ] }
```

## Risks / Trade-offs

- **Over- or under-modeling the format now.** Fields we omit force a format change later; fields we
  add speculatively are dead weight. Mitigation: ship the **minimal closed** field-type set and
  metadata that the *current* residential agreement actually needs (proven by the reference fixture),
  and treat the taxonomy as additive.
- **New dependency = new scan surface.** `json-schema-validator` (plus its Jackson transitives)
  enters the OSV gate. Mitigation: it is a shipping runtime dep, correctly in scope for the scan; the
  lockfile is regenerated and the gate must pass before merge.
- **Schema/record drift.** The JSON Schema and the Java records describe the same shape twice.
  Mitigation: a round-trip test loads the reference definition, and negative tests assert the schema
  rejects each malformed shape -- if the two drift, a test fails.
- **`ref` points at a library that does not exist yet.** A `ref` is only recorded, never resolved, so
  a dangling `ref` cannot fail here. Mitigation: this is intended; the clause-library CR adds
  resolution and its own validation.

## Migration Plan

Additive only. New sub-package, two new resources, one new dependency + lockfile regen. No Flyway
migration, no schema change, no change to any existing class or endpoint. Nothing consumes the model,
so there is no rollout ordering to manage; the change is inert until a later CR calls the loader.

## Open Questions (resolved per follow-on CR)

- **Field-type taxonomy** -- is `text/longtext/int/money/date/bool/enum` sufficient, or do we need
  `phone`, `email`, `percent`, `list`? Deferred until custom-conditions / catalog surface real needs.
- **Version scheme** -- monotonic integer vs semver for `meta.version`. Integer is assumed here;
  the registry CR (which enforces immutability across stored versions) makes the final call.
- **Canonical-JSON rules** -- exact normalization (key sort, number/whitespace form) that the hash
  depends on. Pinned by the `CanonicalJson` implementation + its determinism test in this CR; noted
  here because the resolver CR must reuse the *same* canonicalizer when it hashes effective templates.
- **`showWhen` DSL grammar** -- operators and evaluation point. Entirely the resolver CR's.
- **Where definitions ultimately live** -- classpath resource now; Postgres registry + object
  storage later (the exploration's registry: bodies never inline in Postgres).
