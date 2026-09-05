## ADDED Requirements

### Requirement: Layer-patch format

The system SHALL define a **layer patch**: a declarative document, authored in YAML and compiled to
canonical JSON, that a non-base layer applies over the composed-so-far template. A patch SHALL carry
`meta { kind, dimensions, version }` where `kind` is one of `type`, `state`, `state_type` (and the
reserved, inactive `language`), and an ordered list of **operations**. The supported operations SHALL
be: `addField`, `overrideField` (of `required`, `default`, `options`, `validation`, `group`, `label`
-- but **not** `type`), `addClause`, `replaceClause`, `removeClause`, `addSection`, `replaceSection`,
`removeSection`, `reorderSections`, and per-section `reorderEntries`. A patch SHALL be validated
structurally against a checked-in JSON Schema (`layer-patch.schema.json`); a `base` layer SHALL be a
full `template-definition`, not a patch.

#### Scenario: A well-formed patch loads

- **WHEN** a patch declares `meta { kind: state_type, dimensions {state,type}, version }` and a list
  of supported ops
- **THEN** the patch loads into an immutable patch model with its ops in author order

#### Scenario: An unsupported operation or a type override is rejected

- **WHEN** a patch declares an operation not in the supported set, or an `overrideField` that changes
  a field's `type`
- **THEN** the loader raises an error naming the offending operation and returns no patch

### Requirement: Fixed-precedence layered composition

The system SHALL compose an effective template by applying layers in the fixed precedence
**base -> type -> state -> state+type**, with **last-layer-wins** on conflict and operations within a
layer applied in author order. The `language` layer SHALL be reserved but NOT applied (English only).
The base layer SHALL be a full `template-definition`; each subsequent layer SHALL be a patch applied
over the composed-so-far result.

#### Scenario: A state+type layer wins over a type layer

- **WHEN** a type layer and a state+type layer both modify the same clause
- **THEN** the state+type layer's change is present in the effective template (later precedence wins)

#### Scenario: A jurisdiction is added as one small state layer

- **WHEN** only a base and a single state patch exist for `(state, type)`
- **THEN** resolution composes the base plus that state patch into a complete effective template
  without any per-jurisdiction copy of the base

#### Scenario: The language layer is not applied

- **WHEN** a layer of kind `language` is present in the resource set
- **THEN** resolution ignores it and composes English-only, and the reserved dimension does not alter
  the result

### Requirement: Deterministic, data-independent resolution

The system SHALL provide `resolve(dimensions)` returning an **effective template** that is a **pure
function of `(dimensions, resolved layer versions)`**. Resolution SHALL NOT read user data and SHALL
NOT evaluate `showWhen`. Given the same dimensions and the same layer versions, resolution SHALL
produce byte-identical canonical JSON and the same content hash on every run.

#### Scenario: Repeat resolution is byte-identical

- **WHEN** `resolve` is called twice for the same dimensions and unchanged layer versions
- **THEN** both results have identical canonical JSON and the same content hash

#### Scenario: Resolution does not evaluate showWhen or touch user data

- **WHEN** the effective template contains clauses with `showWhen` conditions
- **THEN** resolution carries those conditions through unchanged and evaluates none of them, and takes
  no user-data input

### Requirement: The effective template is materialized and re-validated reject-or-nothing

The system SHALL materialize the composed result as a full `TemplateDefinition` (all patches applied,
no residual deltas) and SHALL re-validate it: the `template-definition` semantic rules (unique field
keys and clause ids, every clause slot resolves to a surviving field, every section entry resolves,
enum/options consistency, default type-consistency) PLUS resolution checks (no operation targets a
non-existent element; no removal orphans a surviving slot or section entry). If any check fails, the
system SHALL raise `ResolutionException` and return **no** template -- never a partially composed one.
Error messages SHALL cite the layer id and the target id / JSON pointer only, with no data value.

#### Scenario: An operation targeting a missing element fails resolution

- **WHEN** a patch tries to `replaceClause` / `removeClause` / `overrideField` an id or key that does
  not exist in the composed-so-far template
- **THEN** resolution raises `ResolutionException` naming the layer and target, and returns no
  template

#### Scenario: A removal that orphans a slot fails resolution

- **WHEN** a patch removes a field that a surviving clause slot still references (or removes a clause
  a section still lists)
- **THEN** resolution fails reject-or-nothing and returns no template

#### Scenario: A valid layer set composes to a valid effective template

- **WHEN** a base plus its applicable patches compose to a template satisfying every definition and
  resolution rule
- **THEN** resolution returns a materialized effective template with all patches applied

### Requirement: Effective-template identity and pinning

The system SHALL compute the effective template's identity as
`(dimensions, { layerId -> version }, contentHash)`, where `contentHash` is the SHA-256 of its
canonical JSON produced by the **same canonicalizer** used for `template-definition`. The effective
template SHALL expose its provenance (the ordered layer ids and versions it was composed from) so an
agreement can pin it. A content-changing edit to any contributing layer SHALL change the effective
template's content hash.

#### Scenario: The effective template exposes a pinnable identity

- **WHEN** a template is resolved for given dimensions
- **THEN** it exposes its dimensions, the map of contributing layer ids to versions, and a SHA-256
  content hash

#### Scenario: A layer change changes the effective hash

- **WHEN** a contributing layer is edited in any content-bearing way and its version advanced
- **THEN** re-resolution yields a different content hash and an updated layer-version map

#### Scenario: A trivially-resolved base matches its definition hash

- **WHEN** a `(state, type)` has only a base layer and no patches
- **THEN** the effective template's canonical JSON and content hash equal those the
  `template-definition` model computes for that base definition

### Requirement: Sandboxed showWhen DSL

The system SHALL define `showWhen` as a **closed boolean grammar** over declared field keys and
number/string/bool literals, supporting comparison (`== != < <= > >=`), boolean (`&& || !`), and
parentheses -- and **nothing else**: no method calls, property navigation, indexing, function calls,
or assignment. The system SHALL parse `showWhen` with a hand-written parser (NOT an expression
library and NOT Thymeleaf SpringEL). As part of resolution the system SHALL **validate** that every
`showWhen` in the effective template parses and references only declared field keys. The system SHALL
provide a **pure evaluator** `evaluate(expr, fieldValues) -> boolean` that reads only the supplied
field-value map; this evaluator SHALL NOT be wired to any render in this capability.

#### Scenario: A valid condition parses and references declared fields

- **WHEN** a clause declares `showWhen: "escalationPct > 0 && furnished == true"` and both identifiers
  are declared fields
- **THEN** resolution parses it and accepts it

#### Scenario: A condition referencing an undeclared field fails resolution

- **WHEN** a `showWhen` references an identifier that is not a declared field key
- **THEN** resolution raises `ResolutionException` naming the clause and the unknown identifier

#### Scenario: The grammar admits no code or property access

- **WHEN** a `showWhen` contains a method call, property dereference, index, function call, or
  assignment
- **THEN** the parser rejects it as a syntax error and resolution fails

#### Scenario: The evaluator is a pure function of the value map

- **WHEN** the evaluator is given a parsed condition and a field-value map
- **THEN** it returns the boolean result reading only that map, with no side effect, IO, or template
  access
