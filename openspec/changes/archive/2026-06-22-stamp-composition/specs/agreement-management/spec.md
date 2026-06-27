## ADDED Requirements

### Requirement: Agreement carries server-managed stamp info

The `Agreement` aggregate SHALL carry **server-managed stamp data** as an embedded,
all-nullable `StampInfo` value object — `serial`, `stampedPdfKey` (object-storage key),
`denomination`, `jurisdiction`, `dutyPaid`, and `procuredAt` — null/empty until a stamp
is procured. This data is **descriptive only**: it SHALL NOT introduce a signing-status
field and the agreement SHALL remain **status-less** (the stamp lifecycle lives on the
signing request, per `signing-request`). `StampInfo` SHALL be set only by the server
(during the signing-request stamp step) and SHALL NOT be client-settable on create or any
other request (anti-mass-assignment). `StampInfo` SHALL NOT appear on the public
`AgreementResponse`; it SHALL be reachable only through an internal accessor (mirroring
`draftPdfKey`). The stamped PDF bytes SHALL NOT be stored in PostgreSQL — only the key is
persisted.

The stamp columns SHALL be added to the `agreement` table by a new forward-only Flyway
migration (`V6__stamp.sql`) as **nullable** columns, and the JPA mapping SHALL match the
migrated schema so the application boots under `ddl-auto: validate`. The migration SHALL
NOT edit any existing migration (`V1`–`V5`).

#### Scenario: New agreement has empty stamp info

- **WHEN** an agreement is created via `POST /api/agreements`
- **THEN** its `StampInfo` fields are all null (no stamp procured yet)
- **AND** the create request cannot set any `StampInfo` field even if supplied in the body

#### Scenario: Stamp info is set by the server when a stamp is procured

- **WHEN** a signing request procures a stamp for the agreement
- **THEN** the agreement's `StampInfo` is populated by the server (serial, stamped-PDF
  key, denomination, `jurisdiction = "KA"`, `dutyPaid`, `procuredAt`) and persists across
  reads

#### Scenario: Stamp info is not exposed on the public response

- **WHEN** a client GETs `/api/agreements/{id}` for an agreement that has a stamp
- **THEN** the `AgreementResponse` body does not include any `StampInfo` field

#### Scenario: Application boots against the migrated schema

- **WHEN** the application starts against a database where the Flyway migrations
  (including `V6`) have been applied
- **THEN** Hibernate schema validation passes (the entity mapping matches the migrated
  `agreement` stamp columns) and the context starts
