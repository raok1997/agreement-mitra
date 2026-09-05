## Why

Today the `documents` module renders **exactly one** rental-agreement template: the markup is
hardcoded Thymeleaf, the field set is implied by the `Agreement` columns, and nothing declares what
a template *is*. The `document-templating-platform` exploration commits to evolving this into a
generic, multi-template platform, and its locked backbone is a single idea:

> **A template is a declarative definition** -- a typed field schema, plain-text clauses with typed
> slots, and ordered sections -- from which both the capture form and the rendered document are
> projected.

Every later capability (custom conditions, clause library, multi-template catalog, state/language
variants, admin authoring) sits on top of that definition shape. Before any of them can be built,
the **definition shape itself** must exist as a precise, validated, versioned data contract. Building
the resolver, the form projection, or the catalog on an undefined format would bake in ad-hoc
assumptions we would have to unpick later.

This change lands **only that foundation**: the declarative template-definition format and its
canonical, JSON-Schema-validated, content-hashed representation. It is a pure backend domain model --
no HTTP API, no database registry, no resolver, no rendering. It is the data contract the follow-on
CRs (layered resolver, dynamic form projection, template catalog, admin builder) all target.

**Why the definition is where the safety story starts.** The exploration's non-negotiable invariant
is *the template markup is system-owned and trusted; all user-supplied input is escaped data*. That
boundary is expressed **in the format**: clauses are **plain text with typed `{{slots}}`**, never
HTML and never code, and slots may bind only to declared fields. Getting the format right is what
lets the later renderer stay safe by construction.

## What Changes

- Introduce a new **`template-definition`** capability inside the `documents` module (a new internal
  sub-package `in.agreementmitra.documents.template`), defining the declarative template-definition
  format and its in-memory model. Nothing is exposed on the module's public API yet -- there is no
  consumer until the resolver/catalog CRs -- so `ModularityTests` stays green with the new code fully
  internal.
- Define the **definition format** as four parts, authored in **YAML** and compiled to a
  **canonical, JSON-Schema-validated JSON** form:
  - **`meta`** -- `id`, `dimensions { state, type }`, `version`, `status`
    (`draft | legal_approved | published | deprecated`). Dimensions and version are data-independent
    selection/identity metadata; a definition is a single self-contained template for one
    `(state, type)` point.
  - **`fields`** -- the typed variable schema (the data contract). Each field carries
    `key, label, type, required, default, options, validation, group`, with a **closed field-type
    set** (`text, longtext, int, money, date, bool, enum`). This is the contract a capture form and
    a renderer will later project from.
  - **`clauses`** -- reusable legal text, either a `ref` to a clause-library id or an inline
    `{ id, text, showWhen? }`. `text` is **plain text with typed `{{slots}}`** bound to declared
    fields -- **never HTML, never code**. `showWhen` is carried verbatim as an **opaque string** and
    is **not parsed or evaluated** in this CR (its sandboxed DSL is the resolver CR's job).
  - **`sections`** -- ordered grouping of fields and clauses; defines both the eventual form groups
    and the document body order. Every referenced key/id must resolve.
- Add a **loader**: parse YAML -> canonical JSON -> validate -> bind to immutable model records.
  Validation is **two-stage and reject-or-nothing** -- a definition either loads whole or fails with
  an actionable error, never partially:
  - **Structural** -- the definition is validated against a checked-in **JSON Schema**
    (`template-definition.schema.json`): required keys, field-type enum, shape of clauses/sections.
  - **Semantic** -- cross-references the structure cannot express: field `key`s and clause `id`s are
    unique; every clause `{{slot}}` references a declared field; every section entry references a
    declared field or clause; an `enum` field declares `options`; a `default` matches its field type.
- Add a **canonical form + content hash + version identity**: the loaded definition serializes to a
  deterministic canonical JSON (sorted keys, normalized), over which a stable **SHA-256 content
  hash** is computed. A definition's identity is `(id, version, contentHash)` -- the anchor a
  future agreement will pin to, and the guarantee that two byte-different-but-equivalent YAML files
  producing the same definition hash to the same value. The model objects are **immutable value
  types** (records + defensive copies).
- Add one realistic **reference definition** -- the current residential rental agreement expressed as
  a definition YAML -- used as the loader's end-to-end test fixture and as the worked example for the
  format. **It is not wired to the renderer**; replacing the live template is the render-parity CR.
- Add the **`com.networknt:json-schema-validator`** dependency (JSON-Schema Draft validation), and
  **regenerate `backend/gradle.lockfile`**; the new graph passes the `securityScan` (OSV) gate.

**Explicitly not in this change** (each is a named follow-on CR in the exploration): the layered
composition resolver (`base <- type <- state <- state+type`) and its patch format; `showWhen` DSL
grammar/evaluation; projecting the definition into a dynamic capture form; compiling a definition +
data into HTML/PDF (render parity); persisting definitions in a registry (Postgres/object storage);
any HTTP endpoint; the clause-library storage that `ref` resolves against.

## Capabilities

### New Capabilities
- `template-definition`: the declarative template-definition format (`meta / fields / clauses /
  sections`, YAML -> canonical JSON), its closed typed-field schema, plain-text-with-typed-slots
  clauses (upholding the markup/data boundary in the format), two-stage reject-or-nothing structural
  + semantic validation, and a deterministic canonical form with a SHA-256 content hash giving each
  definition an `(id, version, contentHash)` identity. Pure in-memory model; no persistence, no API,
  no resolution, no rendering.

## Impact

- **`documents` module**: new internal sub-package `in.agreementmitra.documents.template` --
  immutable model records (`TemplateDefinition`, `Meta`, `Dimensions`, `Field`, `FieldType`,
  `FieldValidation`, `Clause`, `Section`), a `TemplateDefinitionLoader`, a `CanonicalJson`
  serializer/hasher, a semantic `TemplateDefinitionValidator`, and a `TemplateDefinitionException`.
  All package-private; **no addition to the module's public API** (no consumer yet). The existing
  `DocumentRenderer` / `TemplateAssembler` / Gotenberg path is **untouched**.
- **Resources**: `template-definition.schema.json` (the JSON Schema) and one reference definition
  YAML (`examples/residential-rental.yaml`) under the module's resources; no template markup is
  changed.
- **Dependencies**: add `com.networknt:json-schema-validator`; **regenerate `gradle.lockfile`**
  (`./gradlew dependencies --write-locks`); the new dependency is scanned by the existing OSV
  `securityScan` gate. `jackson-dataformat-yaml` is pulled in for YAML parsing (Spring-Boot-managed
  version).
- **Data / schema**: **none** -- no Flyway migration, no new table, no object-storage change.
  Definitions live only as classpath resources and in memory in this CR; persistence is the registry
  CR.
- **Modulith**: the new code is entirely internal to `documents`; `ModularityTests` stays green.
- **No** change to: the signing FSM, `EsignProvider` / webhook flow, stamping, object storage, the
  reconciliation job, security config, or any HTTP surface.

## PII / security review checklist

- **Introduces or moves identity numbers, one-time codes, VID, PII, or secrets?** **None.** A
  template definition is **system-owned schema metadata** -- field keys, labels, types, and trusted
  clause markup. It carries **no signer data** at all: no government identity number, one-time code,
  virtual id, name, address, or any party PII, and no secret material. The model describes the
  *shape* of data a document will later collect, never an instance of it.
- **Markup/data boundary.** The format **encodes the boundary**: clause `text` is **plain text with
  typed `{{slots}}`**, never HTML and never an expression language, and slots may bind only to
  declared fields. No user-authored markup exists in this CR (authoring is the admin-builder CR); the
  reference definition is system-authored. `showWhen` is stored as an **opaque, unevaluated string**,
  so **no expression/DSL execution surface is introduced** here -- evaluating it (as a sandboxed DSL,
  never Thymeleaf SpringEL) is deferred to the resolver CR.
- **Untrusted input.** The only inputs parsed are **system-owned definition files** (the JSON Schema
  and the reference YAML), not user uploads. Parsing is nonetheless hardened: YAML is loaded with a
  safe, type-restricted mapper (no arbitrary-type instantiation), and validation is reject-or-nothing
  so a malformed definition never yields a partial model.
- **Logging.** Loader errors reference field keys / clause ids / JSON-pointer locations only -- never
  any data value -- so there is nothing sensitive to redact (none is present).
- **Secrets.** None introduced; no env var, credential, or key added.
- **Sandbox + dummy data only?** Preserved -- the reference definition is dummy, system-authored
  content; nothing connects to a live provider or real data.
- **Signing-status FSM transitions touched?** **None.**
- **Async signing / webhook flow touched?** **None** -- no signing sequence diagram required.
