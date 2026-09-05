## MODIFIED Requirements

### Requirement: Project an effective template into a FormSchema

The system SHALL provide a `FormProjector` that maps an **effective template** into a **FormSchema**:
a data contract of ordered `sections`, each with a `title`, an ordered list of `fields`, an
**`optional`** flag, and a **`renderKind`** token. The FormSchema SHALL carry the effective template's
`dimensions { state, type }`, `templateId`, `version`, and `contentHash`. Section order and
within-section field order SHALL be preserved exactly as the effective template declares them. A
section entry that is a declared field `key` SHALL become a form field; a section entry that is a
clause `id` SHALL be treated as document content and SHALL NOT appear as a form field. Each form field
SHALL be projected from its declared field, carrying `key`, `label`, a `widget`, the field `type`,
`required`, and -- where present -- `default`, `options`, `group`, and validation metadata.

Each projected section SHALL carry an **`optional`** boolean and a **`renderKind`** string **projected
verbatim from the effective template's `Section`** (`Section.optional`, `Section.renderKind`):
`optional == false` denotes a mandatory capture section and `optional == true` an optional one, so the
client can mark Mandatory vs Optional and build an add-optional catalog; `renderKind` is the section's
declared document render token (from the closed set `parties | keyvalue | clauses | annexure`), carried
as an opaque string. The projector SHALL NOT derive, override, or re-default either value.

A section that projects to **zero form fields** -- every entry is a clause `id`, i.e. a clause-only /
document-only section such as the "Now This Agreement Witnesseth" list -- SHALL be **omitted** from
`FormSchema.sections`. It is document structure, not a capture step; it MAY still be rendered in the
document by the compiler but SHALL NOT appear as a (field-less) section in the FormSchema. Omission
SHALL be driven solely by the section having no projected form fields, independent of its `renderKind`.

#### Scenario: An effective template projects into an ordered FormSchema

- **WHEN** the projector is given an effective template with sections `[ Parties, Financial terms ]`,
  each listing declared field keys in a fixed order
- **THEN** the FormSchema exposes those sections in that order, each field in its authored order, and
  the schema carries the template's dimensions, id, version, and content hash

#### Scenario: Clause entries are excluded from the form fields

- **WHEN** a section lists a mix of field keys and clause ids as entries
- **THEN** the projected section's `fields` contain only the field-key entries, in order, and the
  clause-id entries do not appear as form fields

#### Scenario: Every declared field type projects to a widget

- **WHEN** a template declares fields of types `text`, `longtext`, `int`, `money`, `date`, `bool`, and
  `enum`
- **THEN** each projected field carries a `widget` of, respectively, `text`, `textarea`, `number`,
  `money`, `date`, `checkbox`, and `select`, and an `enum` field also carries its `options`

#### Scenario: A mandatory section is present and marked mandatory

- **WHEN** the effective template declares a field-bearing section with `optional = false` and a
  `renderKind` (e.g. `keyvalue`)
- **THEN** the projected FormSchema contains that section with `optional == false` and that exact
  `renderKind`, and its fields in authored order

#### Scenario: An optional section carries its optional flag and render kind

- **WHEN** the effective template declares a field-bearing section with `optional = true` and a
  declared `renderKind`
- **THEN** the projected section carries `optional == true` and that exact `renderKind`, projected
  verbatim from the effective template's section

#### Scenario: A field-less document-only section is omitted from the FormSchema

- **WHEN** the effective template has a section whose entries are all clause ids (zero declared field
  keys), such as a "Now This Agreement Witnesseth" clause list, alongside a field-bearing section
- **THEN** the projected `FormSchema.sections` does not contain the field-less section, and the
  adjacent field-bearing section is still present in its authored order

### Requirement: Projection is deterministic and data-independent

Form projection SHALL be a **pure function of the effective template**: it SHALL NOT read user data,
SHALL NOT take clock, IO, or random input, and SHALL NOT evaluate `showWhen`. Given the same effective
template, projection SHALL produce the same FormSchema on every run -- including each section's
`optional` flag and `renderKind` token and the set of sections omitted for having no form fields. A
`showWhen` condition present on the effective template MAY be carried into the schema as **opaque
metadata**, but the projector SHALL NOT evaluate it and SHALL NOT mark any field hidden or shown based
on it. Reading `Section.optional` and `Section.renderKind` SHALL add no clock, IO, randomness, or
user-data input, and the field-less-section omission SHALL be a deterministic function of the projected
fields.

#### Scenario: Repeat projection is identical

- **WHEN** the projector is called twice for the same effective template -- one mixing an optional
  field-bearing section, a mandatory field-bearing section, and a field-less clause-only section
- **THEN** both calls return an equal FormSchema, including the `optional` flags, the `renderKind`
  tokens, and the omission of the field-less section, with no dependence on time, IO, or randomness

#### Scenario: showWhen is carried but not evaluated

- **WHEN** the effective template carries a `showWhen` condition
- **THEN** the projected schema carries that condition verbatim as opaque metadata, and the projector
  neither evaluates it nor uses it to include, exclude, hide, or show any field

#### Scenario: Projection reads no user data

- **WHEN** the projector runs
- **THEN** it derives the schema only from the effective template's fields and sections -- including
  each section's declared `optional` and `renderKind` -- and takes no user-data input

### Requirement: The FormSchema exposes no user data and the module boundary stays clean

The FormSchema and the form endpoint SHALL expose **only system-owned template metadata** -- field
keys, labels, widgets, types, defaults, validation bounds, enum options, group labels, dimensions, and
each section's `optional` flag and `renderKind` token -- and SHALL contain **no signer PII, no secrets,
and no instance of user data**. The section `optional` flag and `renderKind` token SHALL be
system-owned metadata (a boolean and a closed-vocabulary layout token), never user data or markup. The
`documents` module SHALL expose this capability through a single public named-interface API consisting
of the `FormSchema` DTO (with `FormSection` carrying `optional` + `renderKind`) and a `TemplateFormApi`
port (plus the HTTP controller); the `FormProjector` and the port implementation SHALL remain
module-internal, and no cross-module reach-in SHALL be introduced.

#### Scenario: The schema contains no user data or secret

- **WHEN** any FormSchema is produced for any dimensions
- **THEN** it contains only template metadata (keys, labels, widgets, types, defaults, validation,
  options, groups, dimensions, section `optional` flags, and `renderKind` tokens) and no signer name,
  address, identity number, one-time code, or secret

#### Scenario: The public surface is only the form DTO and port

- **WHEN** another module or the SPA consumes this capability
- **THEN** it depends only on the public `FormSchema` DTO (including `FormSection.optional` and
  `FormSection.renderKind`) and `TemplateFormApi` port (over the named interface / HTTP), never on the
  `FormProjector` or any `documents.template` internal type

#### Scenario: The module boundary stays clean

- **WHEN** `ModularityTests` runs after this change
- **THEN** it passes: the `optional` + `renderKind` fields ride the existing `documents` named
  interface (the `FormSection` DTO), no new named interface is added, no disallowed cross-module
  dependency is introduced, and the projector and port implementation stay package-private
