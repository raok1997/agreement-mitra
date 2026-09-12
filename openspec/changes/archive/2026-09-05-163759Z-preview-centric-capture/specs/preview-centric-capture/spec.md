## ADDED Requirements

### Requirement: Preview an in-progress agreement without saving it

The system SHALL provide a **stateless** preview that renders an **in-progress** agreement supplied
in the request body (not a persisted agreement) and returns the rendered document, storing nothing.
`POST /api/agreements/preview` SHALL accept the in-progress agreement data and return an inline
document (`application/pdf`, `Content-Disposition: inline`, `Cache-Control: no-store`), and SHALL
also be able to return the composed **HTML** for an embeddable live preview (content-negotiated via
`Accept`). The preview SHALL render **partial** data -- missing sections or fields SHALL render as
placeholders rather than fail. No agreement, draft, or preview artifact SHALL be persisted.

#### Scenario: Partial data renders a placeholder document

- **WHEN** a client POSTs an in-progress agreement with only the property filled (no parties yet)
- **THEN** the system responds `200 OK` with an inline document that shows the property and
  placeholders where the parties are not yet provided
- **AND** nothing is persisted (no agreement row, no draft, no stored preview)

#### Scenario: The live preview is served as HTML, non-cacheable

- **WHEN** a client POSTs an in-progress agreement with `Accept: text/html`
- **THEN** the system responds with the composed, escaped HTML of the document, marked
  `Cache-Control: no-store`

#### Scenario: The in-progress preview still bounds its input

- **WHEN** a client POSTs an in-progress agreement whose party list or field values exceed the allowed
  bounds (e.g. more than the maximum number of parties, or over-long fields)
- **THEN** the system rejects it with a `400` and renders nothing
- **AND** required-ness is still relaxed (blank/missing fields alone do not cause a rejection)

### Requirement: Section-based editing refreshes the preview

Capture SHALL be organised as **sections** (at minimum Tenant, Owner, Property) edited independently;
saving a section SHALL update the in-progress working set and the preview SHALL reflect the change.
Party and property **data values SHALL remain escaped** in the rendered preview.

#### Scenario: Saving a section updates the previewed document

- **WHEN** the user edits and saves the Owner section
- **THEN** the previewed document updates to show the owner's details in place of the owner
  placeholder

#### Scenario: Markup in a field is shown as text in the preview

- **WHEN** a party field contains angle-bracket markup
- **THEN** the previewed document shows it as literal text, never interpreted as document structure

### Requirement: Completeness is shown and persistence is deliberate

The capture surface SHALL indicate which required sections are complete and which still need input;
the completeness rules SHALL agree with the server's final-save validation, so a surface marked
complete does not then fail creation. Nothing SHALL be persisted **server-side** while editing; the
agreement and its draft SHALL be persisted only on an explicit final action, through the existing
create and generate-as-draft paths. Any in-progress working set held **client-side** for
refresh-resume SHALL be cleared once the agreement is saved and on an explicit reset.

#### Scenario: Required-section status is visible

- **WHEN** a required section has not yet been provided
- **THEN** the capture surface shows that section as still needing input

#### Scenario: The final action persists via the existing paths

- **WHEN** the user completes the required sections and confirms
- **THEN** the system creates the agreement and generates its draft through the existing endpoints,
  and only then is anything persisted server-side
- **AND** any client-held in-progress working set is cleared after the successful save
