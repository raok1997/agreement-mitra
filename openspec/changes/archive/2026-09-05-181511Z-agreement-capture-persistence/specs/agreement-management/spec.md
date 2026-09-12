## ADDED Requirements

### Requirement: An agreement persists its full capture state

An agreement SHALL persist its **capture state**: the flat working-set field map (field key -> value)
that produced its document, plus the list of added **optional sections**. The system SHALL accept an
optional `captureData` map and an optional `activeSections` list on the create body
(`POST /api/agreements`) and on the edit body (`PUT /api/agreements/{id}`), and SHALL store them on the
agreement as server-managed content. The capture map SHALL NOT be a channel for server-managed fields:
the agreement id, `owner_identity_id`, creation timestamp, derived duration, and template pin SHALL
remain server-managed and SHALL be ignored if present as map keys (anti-mass-assignment). The fixed
rental columns (property address, monthly rent, security deposit, dates, parties) SHALL remain
authoritative; where the capture map repeats them it SHALL NOT override the typed values. When no
`captureData` is supplied (an API client sending only fixed fields), the agreement SHALL persist a null
capture state and behave exactly as before.

#### Scenario: Create stores the capture state

- **WHEN** a client POSTs a valid agreement whose body includes `captureData` and `activeSections`
- **THEN** the system persists the agreement together with its capture state and returns `201 Created`

#### Scenario: A capture map cannot set server-managed fields

- **WHEN** a create or edit body's `captureData` contains keys for the id, owner, createdAt, duration, or
  template pin
- **THEN** the system ignores those keys; those fields stay server-managed

#### Scenario: An API client sending only fixed fields is unaffected

- **WHEN** a client POSTs a valid agreement with no `captureData`
- **THEN** the system persists a null capture state and the agreement renders exactly as it did before
  this change

### Requirement: A saved agreement round-trips its capture state on read

`GET /api/agreements/{id}` and the "my agreements" edit-reload SHALL return the agreement's stored
`captureData` and `activeSections` (subject to the existing owner-scoping) so a client can restore the
optional sections and dynamic field values a user entered. An agreement with a null capture state SHALL
return no capture state (the fixed fields and parties, as before).

#### Scenario: Reopening an owned agreement restores its optional sections

- **GIVEN** an owner who saved an agreement with an added optional section and a dynamic field value
- **WHEN** the owner reads it for edit
- **THEN** the response includes the stored `captureData` and `activeSections`, so the form re-activates
  the optional section and repopulates the dynamic value

## MODIFIED Requirements

### Requirement: Generate the signable draft from the agreement

The system SHALL provide `POST /api/agreements/{id}/document` that renders the agreement's rental
document and stores it as the signable draft, then pins the effective template's identity. The render
SHALL use the agreement's **stored capture state** when present -- feeding the stored working-set data
map (reconciled so the authoritative fixed columns win) and the stored `activeSections` into the
document projection -- so the **stored/signed draft matches the live preview the user saw** (preview and
draft parity). When the agreement has **no** stored capture state, the render SHALL fall back to the
fixed-column mapping with no optional sections, exactly as before. The document reference in the
provenance line SHALL remain the agreement's derived tracking number. Storing the draft SHALL remain
frozen once a signing request exists (`409`), and the pin SHALL be recorded only after a successful
store.

#### Scenario: The stored draft renders the added optional sections

- **GIVEN** an agreement saved with an added optional section and a dynamic field value
- **WHEN** the draft is generated
- **THEN** the stored draft PDF contains that optional section and the dynamic value, matching what the
  preview showed

#### Scenario: A legacy agreement with no capture state renders as before

- **GIVEN** an agreement persisted before this change (null capture state)
- **WHEN** its draft is generated
- **THEN** the render uses the fixed-column mapping with no optional sections, unchanged from prior
  behaviour
