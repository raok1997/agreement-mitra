## MODIFIED Requirements

### Requirement: Generate the signable draft from the agreement

The system SHALL provide `POST /api/agreements/{id}/document` that renders the agreement's rental
document and stores it as the signable draft, then pins the effective template's identity. The render
SHALL use the agreement's **stored capture state** when present -- feeding the stored working-set data
map (reconciled so the authoritative fixed columns win) and the stored `activeSections` into the
document projection -- so the **stored/signed draft matches the live preview the user saw** (preview and
draft parity). When the agreement has **no** stored capture state, the render SHALL fall back to the
fixed-column mapping with no optional sections. The fixed columns include the first owner's and the
first tenant's father's/spouse's name and current address, so those values reach the document from the
stored party record in both cases; when any of them is blank (a row persisted before structured party
capture, backfilled empty), the render SHALL fail with field-level validation errors naming the missing
keys rather than render a recital with blanks. Validation precedes the freeze check, so such a row
reports the `400` even when a signing request exists. The document reference in the provenance line SHALL
remain the agreement's derived tracking number. Storing the draft SHALL remain frozen once a signing
request exists (`409`), and the pin SHALL be recorded only after a successful store.

#### Scenario: The stored draft renders the added optional sections

- **GIVEN** an agreement saved with an added optional section and a dynamic field value
- **WHEN** the draft is generated
- **THEN** the stored draft PDF contains that optional section and the dynamic value, matching what the
  preview showed

#### Scenario: A legacy agreement with no capture state renders from its fixed columns

- **GIVEN** an agreement with a null capture state whose parties carry a father's name and current
  address
- **WHEN** its draft is generated
- **THEN** the render uses the fixed-column mapping with no optional sections, and the recital carries
  the first owner's and first tenant's father's names and addresses

#### Scenario: A legacy agreement with blank party details fails generate with field errors

- **GIVEN** an agreement with a null capture state whose owner's father's name and current address are
  blank (the empty-string backfill of rows persisted before structured party capture)
- **WHEN** its draft is generated
- **THEN** the response is `400` with `errors[]` citing `ownerFatherName` and `ownerAddress` as
  `required` and echoing no field value
- **AND** no draft is stored and no template pin or execution date is recorded

### Requirement: An agreement persists its full capture state

An agreement SHALL persist its **capture state**: the flat working-set field map (field key -> value)
that produced its document, plus the list of added **optional sections**. The system SHALL accept an
optional `captureData` map and an optional `activeSections` list on the create body
(`POST /api/agreements`) and on the edit body (`PUT /api/agreements/{id}`), and SHALL store them on the
agreement as server-managed content. The capture map SHALL NOT be a channel for server-managed fields:
the agreement id, `owner_identity_id`, creation timestamp, derived duration, and template pin SHALL
remain server-managed and SHALL be ignored if present as map keys (anti-mass-assignment). The fixed
rental columns (property address, monthly rent, security deposit, dates, parties -- including each
party's father's/spouse's name and current address) SHALL remain authoritative; where the capture map
repeats them it SHALL NOT override the typed values. When no `captureData` is supplied (an API client
sending only fixed fields), the agreement SHALL persist a null capture state and render from the fixed
columns alone.

#### Scenario: Create stores the capture state

- **WHEN** a client POSTs a valid agreement whose body includes `captureData` and `activeSections`
- **THEN** the system persists the agreement together with its capture state and returns `201 Created`

#### Scenario: A capture map cannot set server-managed fields

- **WHEN** a create or edit body's `captureData` contains keys for the id, owner, createdAt, duration, or
  template pin
- **THEN** the system ignores those keys; those fields stay server-managed

#### Scenario: A capture map cannot override a party's father's name or address

- **WHEN** a create body's `captureData` carries an `ownerFatherName` that differs from the owner
  signer's validated `fatherName`
- **THEN** the generated draft's recital carries the signer's `fatherName`, not the capture-map value

#### Scenario: An API client sending only fixed fields is unaffected

- **WHEN** a client POSTs a valid agreement with no `captureData`
- **THEN** the system persists a null capture state and the agreement renders from its fixed columns,
  including the parties' father's names and addresses
