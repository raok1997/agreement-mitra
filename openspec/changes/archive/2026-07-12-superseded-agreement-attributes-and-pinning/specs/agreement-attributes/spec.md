## ADDED Requirements

### Requirement: Template-declared field values persist in a migration-free attribute store

An agreement SHALL persist **template-declared** field values -- those the fixed `Agreement` columns
do not carry (e.g. `lockInMonths`, `escalationPct`, `petAllowed`, `registrationResponsibility`) -- in a
flexible **attribute store** keyed by the resolved template's `FormSchema` field **`key`**. Adding a
new template that declares new fields SHALL require **no database migration**: new keys persist in the
same store. Core fields (parties, monthly rent, security deposit, property address, tenancy dates)
SHALL remain first-class `Agreement` columns and SHALL NOT be duplicated into the attribute store.

The attribute value's **type** SHALL be recovered from the **pinned** `FormSchema` (`field.type`), not
stored redundantly per value. The store SHALL be **server-managed** (populated from the validated
capture flow) and SHALL NOT introduce a signing-status field -- the aggregate stays **status-less**.
Attribute values MAY contain party data and SHALL be treated as PII: **never logged** (neither keys nor
values), and the aggregate's `toString` SHALL remain id-only.

#### Scenario: A new template's declared field persists without a migration

- **WHEN** a template declares a field `lockInMonths` that no fixed `Agreement` column carries, and an
  agreement is captured against it
- **THEN** the value persists in the agreement's attribute store keyed by `lockInMonths`, with no
  schema/migration change

#### Scenario: Core fields are not duplicated into the store

- **WHEN** an agreement is persisted with its monthly rent, deposit, dates, and parties
- **THEN** those values remain in their fixed columns and the attribute store holds only the
  template-declared extras

#### Scenario: Attribute values are never logged

- **WHEN** attributes are written or read for an agreement
- **THEN** no attribute key or value appears in any log line, and the aggregate's `toString` shows only
  the id

### Requirement: The render data map is built schema-driven from the pinned template

The render data map SHALL be built by **walking the pinned template's declared fields** (its
`FormSchema`), reading each field's value from **either** its fixed **core column** **or** the
**attribute store** (by `field.key`), coercing to the declared `field.type`. A blank or absent value
SHALL map to `null` so the template's placeholder fires -- never a bare `null` in the rendered output.
The mapper SHALL emit only plain nested maps/lists (no entity or PII type crossing into the `documents`
module), keeping that module domain-agnostic.

The mapper SHALL be **single-sourced**: the persisted-PDF path and the stateless live-preview path
SHALL funnel through **one** data-map builder, so the live preview and the finally-signed PDF **cannot
diverge**. This single source SHALL be enforced by a **parity test** locking the two entry points to
the same data map for the same input.

#### Scenario: A declared field is read from the attribute store

- **WHEN** the render data map is built for an agreement whose pinned template declares an attribute
  field (e.g. `escalationPct`)
- **THEN** the mapper reads that field's value from the attribute store, coerces it to the declared
  type, and places it in the data map under the field key

#### Scenario: A declared field is read from a core column

- **WHEN** the pinned template declares a field backed by a fixed column (e.g. `monthlyRent`)
- **THEN** the mapper reads it from the core column, not the attribute store

#### Scenario: Preview and signed-PDF data maps are identical

- **WHEN** the same in-progress input is mapped by the preview path and (once persisted) by the
  persisted path
- **THEN** both produce the same data map, so the previewed document matches the signed document

#### Scenario: A blank attribute renders a placeholder, not null

- **WHEN** a declared attribute field is blank or absent for an agreement
- **THEN** the data map carries `null` for that key and the template renders its placeholder, never a
  literal `null`
