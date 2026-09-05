## MODIFIED Requirements

### Requirement: Agreement carries server-managed stamp info

The `Agreement` aggregate SHALL carry **server-managed stamp data** as an embedded,
all-nullable `StampInfo` value object - the **certificate number**, `stampedPdfKey`
(object-storage key of the composited instrument), `scanKey` (object-storage key of the
uploaded certificate scan), **duty amount**, `jurisdiction`, **certificate issue date**,
`dutyPaid`, and `attachedAt` - null/empty until a stamp is attached. It MAY additionally carry
the optional **description of document** and **purchased by** values recorded at intake.

This data is **descriptive only**: it SHALL NOT introduce a signing-status field and the
agreement SHALL remain **status-less** (the stamp lifecycle lives on the signing request, per
`signing-request`). `StampInfo` SHALL be set only by the server (during staff stamp intake)
and SHALL NOT be client-settable on create or any other request (anti-mass-assignment).
`StampInfo` SHALL NOT appear on the public `AgreementResponse`; it SHALL be reachable only
through an internal accessor (mirroring `draftPdfKey`). Neither the stamped PDF bytes nor the
scan bytes SHALL be stored in PostgreSQL - only the keys are persisted.

The certificate number SHALL carry a **database-level uniqueness constraint** across all
agreements, enforcing the single-use rule in `estamp-intake`.

The new stamp columns SHALL be added to the `agreement` table by a new forward-only Flyway
migration as **nullable** columns, and the JPA mapping SHALL match the migrated schema so the
application boots under `ddl-auto: validate`. The migration SHALL NOT edit any existing
applied migration.

#### Scenario: New agreement has empty stamp info

- **WHEN** an agreement is created via `POST /api/agreements`
- **THEN** its `StampInfo` fields are all null (no stamp attached yet)
- **AND** the create request cannot set any `StampInfo` field even if supplied in the body

#### Scenario: Stamp info is set by the server at staff intake

- **WHEN** a staff user uploads an e-stamp certificate for the agreement
- **THEN** the agreement's `StampInfo` is populated by the server (certificate number,
  stamped-PDF key, scan key, duty amount, jurisdiction, issue date, `dutyPaid = true`,
  `attachedAt`) and persists across reads

#### Scenario: Certificate number is unique across agreements

- **WHEN** a certificate number already recorded on one agreement is written to another
- **THEN** the database rejects the write and the second agreement keeps empty stamp info

#### Scenario: Stamp info is not exposed on the public response

- **WHEN** a client GETs `/api/agreements/{id}` for an agreement that has a stamp
- **THEN** the `AgreementResponse` body does not include any `StampInfo` field

#### Scenario: Application boots against the migrated schema

- **WHEN** the application starts against a database where the Flyway migrations have been
  applied
- **THEN** Hibernate schema validation passes (the entity mapping matches the migrated
  `agreement` stamp columns) and the context starts

## ADDED Requirements

### Requirement: Agreement carries a single, unique tracking reference

Each agreement SHALL carry **exactly one** externally-visible reference. The **same** value SHALL
be the number shown to the customer, the number staff use to locate the agreement when uploading
an e-stamp, and the reference rendered on the document. The system SHALL NOT maintain two
different customer-facing and staff-facing references for one agreement.

The reference SHALL be **persisted, unique, and collision-free**, assigned by the server at
agreement creation, immutable for the life of the agreement, and carrying a database-level
uniqueness constraint.

It SHALL be safe to read aloud and re-type, because it travels through a manual loop: the
customer receives it, staff read it from the order, purchase the stamp, and type it back in
hours or days later. It SHALL therefore use an unambiguous alphabet and carry a check character,
so a single-character typo is rejected rather than resolving to a different agreement.

The **derived, display-only** `AM-<LAST6>-<DDMMYY>` form SHALL NOT be used as a second reference.
Because it is computed at render time and is not collision-free, it SHALL be replaced by this
persisted reference everywhere it was previously surfaced - including the customer-facing
response and the document provenance line.

The reference SHALL NOT be guessable in a way that lets a caller enumerate other agreements, and
possessing it SHALL NOT by itself authorise any operation - stamp intake still requires the STAFF
role (per `estamp-intake`).

#### Scenario: Every agreement gets a unique reference at creation

- **WHEN** an agreement is created
- **THEN** the server assigns it a reference that is unique across all agreements and is
  persisted with the agreement

#### Scenario: Customer and staff see the same number

- **WHEN** the customer is shown their tracking reference and staff locate the same agreement to
  upload a stamp
- **THEN** both use the identical value, and no second reference exists for that agreement

#### Scenario: The document carries the same reference

- **WHEN** the agreement document is rendered
- **THEN** its provenance line shows the persisted reference, not a separately-derived value

#### Scenario: A single-character typo is rejected

- **WHEN** a reference is submitted with one character mistyped
- **THEN** the check character causes it to be rejected rather than resolving to another
  agreement

#### Scenario: The reference is immutable

- **WHEN** an agreement is updated after creation
- **THEN** its reference is unchanged

#### Scenario: The reference alone grants no access

- **WHEN** a non-staff caller submits a valid reference to the stamp-intake endpoint
- **THEN** the request is refused on authorization grounds, unaffected by the reference being
  correct
