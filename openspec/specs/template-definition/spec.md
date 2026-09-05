# template-definition Specification

## Purpose

The declarative template-definition format: the in-memory model, its loader, and its semantic
invariants. A definition describes a single self-contained template for one `(state, type)` point
and expresses no layering or composition (that is `template-resolution`).

**Backfilled 2026-09-05** from the archived change's `ADDED` delta, which was archived on 2026-07-13 without its spec fold running (see `openspec/BASELINE-FOLD-GAP.md`). The text below is the archived delta verbatim, not a re-derivation.

## Requirements

### Requirement: Declarative template-definition format

The system SHALL define a **template definition** as a declarative document with four parts --
`meta`, `fields`, `clauses`, and `sections` -- authored in **YAML** and compiled to a **canonical
JSON** form. A definition SHALL describe a single, self-contained template for one `(state, type)`
point; it SHALL NOT express inheritance, layering, or composition (those are out of scope for this
capability).

`meta` SHALL carry `id`, `dimensions { state, type }`, `version`, and `status`, where `status` is
one of `draft`, `legal_approved`, `published`, `deprecated`. `meta` MAY additionally carry an
**optional `document` block** `{ title, subtitle, executionLine }` that declares the rendered
document's header. When the `document` block is present, `title` SHALL be required; `subtitle` and
`executionLine` are optional. `executionLine` SHALL be **system-authored plain text** that MAY
contain `{{slot}}` placeholders referencing declared fields (e.g. `{{agreementDate}}`); it SHALL NOT
be HTML and SHALL NOT be an expression or code, and this capability SHALL record its slots without
filling, escaping, or evaluating them (escaping and value substitution happen at compile/render time,
not here). The definition SHALL be a pure in-memory model: it SHALL NOT be persisted, exposed over
any HTTP endpoint, resolved, or rendered by this capability.

#### Scenario: A well-formed definition loads into the model

- **WHEN** a well-formed definition YAML with `meta`, `fields`, `clauses`, and `sections` is loaded
- **THEN** the loader returns an immutable `TemplateDefinition` whose `meta`, fields, clauses, and
  sections match the source, and performs no persistence, rendering, or network call

#### Scenario: A definition declares its dimensions, version, and status

- **WHEN** a definition's `meta` declares `dimensions { state, type }`, a `version`, and a
  `status` of `draft`
- **THEN** the loaded model exposes those values, and a `status` outside the allowed set is rejected

#### Scenario: A meta.document block loads with its title, subtitle, and execution line

- **WHEN** a definition's `meta` declares `document { title, subtitle, executionLine }` where
  `executionLine` contains `{{agreementDate}}` and `agreementDate` is a declared field
- **THEN** the loaded model exposes the document block's title, subtitle, and execution-line text with
  a recorded slot reference to `agreementDate`, unfilled and unescaped

#### Scenario: A meta.document block without a title is rejected

- **WHEN** a definition declares a `meta.document` block that omits `title`
- **THEN** structural validation fails, the loader raises `TemplateDefinitionException`, and returns
  no model

#### Scenario: A definition without a meta.document block still loads

- **WHEN** a definition's `meta` omits the `document` block entirely
- **THEN** the definition loads and its `meta.document` is absent (null), with no other behavior
  changed

### Requirement: Typed field schema (the data contract)

The system SHALL model `fields` as a typed variable schema. Each field SHALL carry a `key`, a
`label`, and a `type` drawn from the **closed set** `text, longtext, int, money, date, bool, enum`,
and MAY carry `required`, `default`, `options`, `validation`, and `group`. Field `key`s SHALL be
unique within a definition.

An `enum` field SHALL declare a non-empty `options` list, and a non-`enum` field SHALL NOT declare
`options`. When a field declares a `default`, the default SHALL be valid for the field's `type` (and,
for `enum`, SHALL be one of its `options`). Validation metadata SHALL be declarative only (e.g.
numeric min/max, text length/pattern) -- it SHALL NOT be an expression or executable code.

#### Scenario: A typed field with validation metadata loads

- **WHEN** a definition declares a field `{ key: lockInMonths, type: int, required: false,
  default: 6, validation: { min: 0, max: 60 } }`
- **THEN** the field loads with its type, requiredness, default, and validation bounds intact

#### Scenario: An unknown field type is rejected

- **WHEN** a definition declares a field whose `type` is not in the closed set
- **THEN** the loader raises a definition error naming the offending field `key` and returns no model

#### Scenario: An enum field without options is rejected

- **WHEN** a definition declares an `enum` field with no `options` (or a non-`enum` field *with*
  `options`)
- **THEN** the loader raises a definition error naming the offending field `key`

#### Scenario: Duplicate field keys are rejected

- **WHEN** two fields in a definition share the same `key`
- **THEN** the loader raises a definition error identifying the duplicated `key`

#### Scenario: A default inconsistent with the field type is rejected

- **WHEN** a field of type `int` declares a `default` that is not an integer (or an `enum` default
  not among its `options`)
- **THEN** the loader raises a definition error naming the field `key`

### Requirement: Clauses are plain text with typed slots

The system SHALL model each clause as either a **reference** (`ref` to a clause-library id, recorded
as an opaque string and NOT resolved by this capability) or an **inline** clause `{ id, text,
showWhen? }`. Inline clause `id`s SHALL be unique within a definition.

Inline `text` SHALL be **plain text** that MAY contain `{{slot}}` placeholders; it SHALL NOT be HTML
and SHALL NOT be an expression or code. Every `{{slot}}` SHALL reference a declared field `key`. The
system SHALL uphold the markup/data boundary at the format level: a clause carries text and slot
references only -- it SHALL NOT carry markup or executable content. The model SHALL record slots
without filling, escaping, or evaluating them.

An inline clause MAY carry a `showWhen` string. The system SHALL store `showWhen` **verbatim as an
opaque value** and SHALL NOT parse, validate, or evaluate it in this capability.

#### Scenario: An inline clause with a valid slot loads

- **WHEN** a clause declares `text: "Monthly rent is {{monthlyRent}}."` and `monthlyRent` is a
  declared field
- **THEN** the clause loads with its text and a recorded slot reference to `monthlyRent`

#### Scenario: A slot referencing an undeclared field is rejected

- **WHEN** a clause's `text` contains `{{notAField}}` and no field with that `key` is declared
- **THEN** the loader raises a definition error naming the clause `id` and the unknown slot

#### Scenario: A clause reference is recorded but not resolved

- **WHEN** a clause is `{ ref: clause-lib/late-payment }`
- **THEN** the clause loads as a reference carrying that id verbatim, and the loader does not attempt
  to look up, fetch, or validate the referenced library entry

#### Scenario: showWhen is carried without evaluation

- **WHEN** an inline clause declares `showWhen: "escalationPct > 0"`
- **THEN** the loaded clause exposes that exact string and the loader neither evaluates it nor
  requires the identifiers in it to be declared fields

### Requirement: Sections order and reference declared fields and clauses

The system SHALL model `sections` as an ordered list, each with a `title`, an ordered list of
entries, an **`optional` flag** (a boolean, **default `false`** meaning mandatory), and a **`render`
kind** drawn from the closed set `parties | keyvalue | clauses | annexure` (**default `keyvalue`**).
Every entry SHALL reference a declared field `key` or a declared clause `id`. Section order and the
entry order within a section SHALL be preserved as authored. Every section entry SHALL resolve to a
declared field or clause. The `optional` flag and the `render` kind SHALL be declarative facts carried
on the model -- this capability SHALL NOT itself lay out, hide, or render a section by them.

#### Scenario: A section preserves order and resolves its entries

- **WHEN** a section lists entries `[ monthlyRent, rent, late-payment ]`, all declared as a field or
  clause
- **THEN** the section loads with its entries in that exact order, each resolved to the matching
  field or clause

#### Scenario: A section entry that resolves to nothing is rejected

- **WHEN** a section entry names a `key`/`id` that is neither a declared field nor a declared clause
- **THEN** the loader raises a definition error naming the section and the unresolved entry

#### Scenario: optional defaults to false and render defaults to keyvalue

- **WHEN** a section omits `optional` and omits `render`
- **THEN** the loaded section exposes `optional == false` (mandatory) and `render == keyvalue`

#### Scenario: A section declares its optional flag and render kind

- **WHEN** a section declares `optional: true` and `render: clauses`
- **THEN** the loaded section exposes `optional == true` and a `render` kind of `clauses`, preserving
  its entry order

#### Scenario: An unknown render kind is rejected

- **WHEN** a section declares a `render` value outside `parties | keyvalue | clauses | annexure`
- **THEN** the loader raises a definition error naming the offending section, and returns no model

### Requirement: Two-stage, reject-or-nothing validation

The system SHALL validate a definition in two stages: **structural** validation against a checked-in
**JSON Schema** (required keys, the field-type enum, the optional `meta.document` block shape with a
required `title`, the section `render` enum, and the shape of `meta`/`fields`/`clauses`/`sections`),
followed by **semantic** validation (uniqueness of keys and ids, slot-to-field resolution for both
clause text and `meta.document.executionLine`, section-entry resolution, enum/options consistency,
and default type-consistency).

Validation SHALL be **reject-or-nothing**: if any structural or semantic check fails, the loader
SHALL raise `TemplateDefinitionException` and SHALL return **no** model -- never a partially
populated one. Error messages SHALL identify the fault by structural location (JSON pointer, field
`key`, clause `id`, section, the offending `render` token, or the unresolved slot name) and SHALL NOT
include any data value.

#### Scenario: A structurally invalid definition is rejected by the schema

- **WHEN** a definition omits a required key or declares a malformed shape
- **THEN** structural (JSON-Schema) validation fails, the loader raises `TemplateDefinitionException`
  citing the JSON-pointer location, and returns no model

#### Scenario: A semantically invalid definition is rejected whole

- **WHEN** a definition is structurally valid but has a semantic fault (e.g. a slot referencing an
  undeclared field)
- **THEN** the loader raises `TemplateDefinitionException` and returns no partial model

#### Scenario: An executionLine slot resolves to a declared field

- **WHEN** `meta.document.executionLine` is `"Executed on {{agreementDate}}."` and `agreementDate` is
  a declared field
- **THEN** the definition loads and the execution line's slot is recorded as resolving to
  `agreementDate`, unfilled and unevaluated

#### Scenario: An executionLine slot referencing an undeclared field is rejected

- **WHEN** `meta.document.executionLine` contains `{{notAField}}` and no field with that `key` is
  declared
- **THEN** the loader raises `TemplateDefinitionException` naming the unresolved slot, and returns no
  model

#### Scenario: Error messages carry no data values

- **WHEN** any validation error is raised
- **THEN** the message names only structural locations (pointer/key/id/section/render token/slot name)
  and contains no field value

### Requirement: Canonical form, content hash, and version identity

The system SHALL compile a loaded definition to a **deterministic canonical JSON** (stable key order
and normalized scalars) and SHALL compute a **SHA-256 content hash** over those canonical bytes. The
canonical form SHALL include the `meta.document` block (or its absence, normalized), each section's
`optional` flag, and each section's `render` kind, so the content hash reflects them. A definition's
identity SHALL be `(id, version, contentHash)`. The canonical form and hash SHALL be a pure function
of the definition's content: two inputs that differ only in YAML formatting (key order, whitespace,
comments) or in an explicitly-stated-vs-defaulted `optional`/`render` but denote the same definition
SHALL produce the **same** canonical JSON and the **same** content hash. The loaded model SHALL be an
**immutable** value type.

#### Scenario: Equivalent definitions hash identically

- **WHEN** two YAML files denote the same definition but differ in key order, whitespace, comments, or
  a section stating `optional: false` explicitly versus omitting it
- **THEN** both load to equal canonical JSON and the same SHA-256 content hash

#### Scenario: Adding a document block changes the hash

- **WHEN** a definition is altered only by adding a `meta.document` block
- **THEN** its canonical JSON changes and its content hash differs from the original

#### Scenario: Changing a section optional flag or render kind changes the hash

- **WHEN** a definition is altered only by flipping a section's `optional` flag or changing its
  `render` kind
- **THEN** its canonical JSON changes and its content hash differs from the original, deterministically

#### Scenario: A content change changes the hash

- **WHEN** a definition is altered in any content-bearing way (e.g. a field's `required` flips)
- **THEN** its canonical JSON changes and its content hash differs from the original

#### Scenario: The model is immutable

- **WHEN** a caller holds a loaded `TemplateDefinition`
- **THEN** it cannot mutate the definition's fields, clauses, or sections through the returned model
