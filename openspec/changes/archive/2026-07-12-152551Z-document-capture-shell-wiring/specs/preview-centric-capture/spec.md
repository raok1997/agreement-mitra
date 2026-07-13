## MODIFIED Requirements

### Requirement: The capture shell previews live from the document-projection endpoint

The `preview-centric-capture` shell SHALL render its live preview from the document-projection endpoint
`POST /api/templates/document/preview`, sending the working-set **field-key data map** and selecting the
tier by `Accept`: `text/html` for the live pane (rendered in a sandboxed iframe) and `application/pdf`
for "Download PDF". "Save & continue" SHALL create the agreement and invoke generate-as-draft (which
pins the effective template). The shell SHALL keep all API calls in `src/api/`, SHALL NOT log the
working-set data map or the rendered document, and SHALL revoke any object URL created from a PDF blob.
The shell layout, section modals, and completeness bar SHALL be unchanged; the removed
`POST /api/agreements/preview` client SHALL no longer be used.

#### Scenario: A section save refreshes the live pane from the HTML variant

- **WHEN** a section is saved in the capture shell
- **THEN** the shell POSTs the working-set field-key data map to `/api/templates/document/preview` with
  `Accept: text/html` and renders the returned HTML in the sandboxed live-preview pane

#### Scenario: Download PDF uses the PDF variant and revokes its object URL

- **WHEN** the user clicks "Download PDF"
- **THEN** the shell POSTs the same data map with `Accept: application/pdf`, downloads the returned PDF,
  and revokes any object URL it created

#### Scenario: Save & continue creates and generates the pinned draft

- **WHEN** the user clicks "Save & continue"
- **THEN** the shell creates the agreement and invokes generate-as-draft, which stores the draft and
  pins the effective template

#### Scenario: The working set and rendered document are never logged

- **WHEN** the shell previews, downloads, or saves
- **THEN** no console/log line contains the working-set data map or the rendered HTML/PDF

### Requirement: The capture flow is constrained to the parity-safe template until the draft path is dimension-aware

Until generate-as-draft persists per-agreement attributes and resolves the agreement's chosen
dimensions, the capture flow SHALL be constrained so the **live preview cannot diverge from the signed
draft**. The template picker SHALL make only the parity-safe default `(state, type)` selectable -- the
exact reference template the generate-as-draft path renders -- and SHALL render every other pair
disabled ("Coming soon"); the capture shell SHALL default to that same `(state, type)`. The shell SHALL
hide template-declared fields that generate-as-draft does not persist (so they render from the effective
template's defaults in both the preview and the draft) and SHALL strip them from the preview data map,
so a user cannot set a value the signed draft would ignore.

#### Scenario: Only the parity-safe default template is selectable

- **WHEN** the picker lists published templates
- **THEN** only the parity-safe default `(state, type)` -- the one generate-as-draft renders -- is
  selectable, and every other pair is disabled and marked "Coming soon"

#### Scenario: Non-persisted template fields are hidden from capture and preview

- **WHEN** the capture shell renders the form for the parity-safe template
- **THEN** template-declared fields that generate-as-draft does not persist are not shown for edit and
  are not sent in the preview data map, so the live preview matches what the signed draft will render
