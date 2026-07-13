## ADDED Requirements

### Requirement: Agreement carries a server-managed template-attribute store

The `Agreement` aggregate SHALL carry a **server-managed** store of **template-declared** field values
-- a flexible key->value map keyed by the resolved template's `FormSchema` field `key`, holding only
fields the fixed columns do not carry. The store SHALL be set only by the server (from the validated
capture flow) and SHALL NOT be client-settable on create or any other request (anti-mass-assignment).
It SHALL default to **empty** on a newly created agreement. Persisting this store SHALL NOT introduce a
signing-status field -- the aggregate remains **status-less**. The store's values MAY be PII and SHALL
never be logged; the aggregate's `toString` SHALL remain id-only.

The store SHALL be persisted as a JSON column (`attributes`) added by the forward-only additive Flyway
migration below, and the JPA mapping SHALL match the migrated schema so the application boots under
`ddl-auto: validate`.

#### Scenario: New agreement has an empty attribute store

- **WHEN** an agreement is created via `POST /api/agreements`
- **THEN** its attribute store is empty (no template-declared extras yet)
- **AND** the create request cannot set any attribute even if supplied in the body

#### Scenario: Attributes are set by the server from the capture flow

- **WHEN** template-declared field values are captured for the agreement
- **THEN** the server populates the attribute store and the values persist across reads, keyed by their
  `FormSchema` field key

### Requirement: Agreement carries a server-managed version pin

The `Agreement` aggregate SHALL carry a **server-managed version pin** recording the **resolved**
template that produced its document: a `templateHash` (content hash of the resolved effective template)
and `layerVersions` (the composed layer versions), with `templateRef` recorded as the existing
`template_id`. The pin SHALL be **null until the document is first generated** and SHALL be set only by
the server at generate-as-draft (never client-settable; anti-mass-assignment). Recording the pin SHALL
NOT introduce a signing-status field -- the aggregate remains **status-less**. The pin is non-PII
template metadata.

The pin columns (`template_content_hash`, `template_layer_versions`) SHALL be added by the forward-only
additive Flyway migration below; the migration SHALL NOT re-add `template_id` (already added by
`V8__template_catalog.sql`). The JPA mapping SHALL match the migrated schema so the application boots
under `ddl-auto: validate`.

#### Scenario: New/ungenerated agreement has no pin

- **WHEN** an agreement is created but no document has been generated
- **THEN** its `templateHash` and `layerVersions` are null (nothing pinned yet)
- **AND** the create request cannot set the pin even if supplied in the body

#### Scenario: Pin is set by the server at generate-as-draft

- **WHEN** the agreement's document is generated as its draft
- **THEN** the server records the resolved `templateHash` and `layerVersions` on the agreement, and
  they persist across reads

### Requirement: Attribute + pin schema is Flyway-managed

The `attributes` (JSON), `template_content_hash`, and `template_layer_versions` columns SHALL be added
to the `agreement` table by a new forward-only **additive** Flyway migration
(`V9__agreement_attributes_and_pin.sql`) -- `attributes` **NOT NULL** with a default of an empty JSON
object, the two pin columns **nullable**. The migration SHALL NOT re-add `template_id` (owned by `V8`)
and SHALL NOT edit any prior migration (`V1`..`V8`); the JPA mapping SHALL match the migrated schema so
the application boots under `ddl-auto: validate`.

#### Scenario: Application boots against the migrated schema

- **WHEN** the application starts against a database where the Flyway migrations (including `V9`) have
  been applied
- **THEN** Hibernate schema validation passes (the entity mapping matches the migrated `agreement`
  attribute + pin columns) and the context starts
