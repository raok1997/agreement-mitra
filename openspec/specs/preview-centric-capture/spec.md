# preview-centric-capture Specification

## Purpose

The preview-centric capture shell: the customer edits an agreement section by section with a live
document preview beside it, and nothing is persisted until they choose to save.

Created 2026-09-05 by archiving change `preview-centric-capture`. The `MODIFIED` delta of
`capture-mandatory-optional-ux` (archived 2026-07-13 without its spec fold running, see
`openspec/BASELINE-FOLD-GAP.md`) is applied over it here, since that change refined these same three
requirements and its fold was never run.

## Requirements

### Requirement: Preview an in-progress agreement without saving it

The system SHALL provide a **stateless** preview that renders an **in-progress** agreement supplied
in the request body (not a persisted agreement) and returns the rendered document, storing nothing.
`POST /api/templates/document/preview` SHALL accept the in-progress agreement data and return an
inline document (`application/pdf`, `Content-Disposition: inline`, `Cache-Control: no-store`), and
SHALL also be able to return the composed **HTML** for an embeddable live preview (content-negotiated
via `Accept`). The preview request SHALL carry the set of **added optional section titles**
(`activeSections`), and the client SHALL send `activeSections` on **every** preview request -- both the
live HTML face and the Download PDF face -- so that an added optional section renders in the previewed
document (even if partially filled) while an optional section that has not been added does not, and
mandatory sections always render. The preview SHALL render **partial** data -- missing sections or
fields SHALL render as placeholders rather than fail. No agreement, draft, or preview artifact SHALL be
persisted.

#### Scenario: Partial data renders a placeholder document

- **WHEN** a client POSTs an in-progress agreement with only the property filled (no parties yet)
- **THEN** the system responds `200 OK` with an inline document that shows the property and
  placeholders where the parties are not yet provided
- **AND** nothing is persisted (no agreement row, no draft, no stored preview)

#### Scenario: The live preview is served as HTML, non-cacheable

- **WHEN** a client POSTs an in-progress agreement with `Accept: text/html`
- **THEN** the system responds with the composed, escaped HTML of the document, marked
  `Cache-Control: no-store`

#### Scenario: Added optional sections render only when active, on both faces

- **WHEN** the client sends a preview request whose `activeSections` includes an optional section's
  title (for either the HTML or the PDF face)
- **THEN** the previewed document renders that optional section's content, and a preview request whose
  `activeSections` omits that title renders without it, while mandatory sections render in both cases

#### Scenario: The in-progress preview still bounds its input

- **WHEN** a client POSTs an in-progress agreement whose party list or field values exceed the allowed
  bounds (e.g. more than the maximum number of parties, or over-long fields)
- **THEN** the system rejects it with a `400` and renders nothing
- **AND** required-ness is still relaxed (blank/missing fields alone do not cause a rejection)

### Requirement: Section-based editing refreshes the preview

Capture SHALL be organised as **sections** edited independently, and each section SHALL be marked
**mandatory** or **optional** from the fetched `FormSchema`'s section semantics (`FormSection.optional`)
rather than inferred from field-level required-ness. Mandatory sections SHALL always be present in the
rail and always contribute to the document; **optional** sections SHALL be presented in an **"Add
optional" catalog** and SHALL be **opt-in** -- absent from the preview until the user adds them. Adding
an optional section SHALL move it into the active set and SHALL make its content contribute to the
previewed document; removing it SHALL take its content back out. Saving a section SHALL update the
in-progress working set and the preview SHALL reflect the change. Party and property **data values
SHALL remain escaped** in the rendered preview.

#### Scenario: Sections are marked mandatory or optional from the schema

- **WHEN** the capture surface renders a fetched `FormSchema` whose sections carry `optional` flags
- **THEN** the sections with `optional: false` are shown as mandatory sections in the rail and the
  sections with `optional: true` are shown in the Add-optional catalog, driven by the schema flag and
  not by whether a section happens to contain a required field

#### Scenario: An optional section is absent until added, then contributes to the preview

- **WHEN** the user adds an optional section from the Add-optional catalog
- **THEN** its title is included in the `activeSections` sent on the next preview request and the
  previewed document updates to include that section, and before it was added the previewed document did
  not include it

#### Scenario: Saving a section updates the previewed document

- **WHEN** the user edits and saves the Owner section
- **THEN** the previewed document updates to show the owner's details in place of the owner
  placeholder

#### Scenario: Markup in a field is shown as text in the preview

- **WHEN** a party field contains angle-bracket markup
- **THEN** the previewed document shows it as literal text, never interpreted as document structure

### Requirement: Completeness is shown and persistence is deliberate

The capture surface SHALL indicate which **mandatory** sections are complete and which still need
input; the completeness rules SHALL agree with the server's final-save validation, so a surface marked
complete does not then fail creation. "Save & continue" SHALL be **hard-blocked** -- the action
disabled/blocked, not merely warned -- until **every mandatory section is complete**, and the surface
SHALL show a clear "complete N more required section(s)" affordance naming how many mandatory sections
remain. Optional sections (whether added or not, complete or not) SHALL NOT block Save. Nothing SHALL
be persisted **server-side** while editing; the agreement and its draft SHALL be persisted only on an
explicit final action, through the existing create and generate-as-draft paths. Any in-progress working
set held **client-side** for refresh-resume -- including the set of added optional sections -- SHALL be
cleared once the agreement is saved and on an explicit reset.

#### Scenario: Required-section status is visible

- **WHEN** a mandatory section has not yet been provided
- **THEN** the capture surface shows that section as still needing input

#### Scenario: Save is blocked until every mandatory section is complete

- **WHEN** one or more mandatory sections are still incomplete
- **THEN** the "Save & continue" action is disabled/blocked and the surface shows a
  "complete N more required section(s)" affordance with N equal to the number of incomplete mandatory
  sections, and an incomplete optional section never contributes to N or blocks the action

#### Scenario: The final action persists via the existing paths

- **WHEN** the user completes the mandatory sections and confirms
- **THEN** the system creates the agreement and generates its draft through the existing endpoints,
  and only then is anything persisted server-side
- **AND** any client-held in-progress working set, including the added-optional-section set, is cleared
  after the successful save
