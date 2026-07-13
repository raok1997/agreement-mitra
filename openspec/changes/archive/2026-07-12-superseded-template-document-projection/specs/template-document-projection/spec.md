## ADDED Requirements

### Requirement: Compile an effective template plus user data into escaped HTML

The system SHALL provide a `TemplateCompiler` that maps an **effective template** plus a **user data
map** into a self-contained HTML document. The compiler SHALL render the effective template's sections
and clauses as **system-owned markup**, and SHALL fill each clause's declared `{{slot}}` with the
corresponding value from the data map **HTML-escaped**, so a user value containing markup renders as
literal text and never as document structure or active content. For every clause carrying a `showWhen`
condition, the compiler SHALL evaluate that condition against the user data map through the resolution
engine's sandboxed boolean DSL evaluator and SHALL **include the clause only when the condition is
true**, dropping it (and closing up its numbering) otherwise. The compiler SHALL NOT evaluate any
condition through Thymeleaf, SpringEL, or any general expression engine.

#### Scenario: Slots are filled with escaped user data

- **WHEN** the compiler renders a clause with a `{{slot}}` bound to a field whose submitted value
  contains angle-bracket markup (for example a script tag)
- **THEN** the composed HTML shows that value as literal text in the clause and does not interpret it
  as markup or active content

#### Scenario: A true showWhen includes its clause

- **WHEN** a clause declares a `showWhen` that evaluates to true against the submitted data
- **THEN** the composed document contains that clause

#### Scenario: A false showWhen drops its clause

- **WHEN** a clause declares a `showWhen` that evaluates to false against the submitted data
- **THEN** the composed document omits that clause and the surrounding clause numbering closes up

#### Scenario: Conditions run only through the sandboxed DSL

- **WHEN** any `showWhen` is evaluated during compilation
- **THEN** it is evaluated by the resolution engine's hand-written boolean DSL over declared fields and
  literals only, with no method call, property navigation, indexing, or expression-engine evaluation

### Requirement: The live preview and the signed PDF come from one compiler (parity)

The system SHALL source the **live HTML preview** and the **PDF** from the **same** `TemplateCompiler`
output for a given effective template and data map: the HTML returned to the live pane SHALL be the
identical HTML that is handed to the PDF renderer. There SHALL be no separate client-side or alternate
server-side renderer that could diverge, so the document a user previews is the document that is
signed.

#### Scenario: Preview HTML equals the PDF's HTML source

- **WHEN** the same effective template and data map are rendered for the live pane and for the PDF
- **THEN** the HTML returned for the live pane is byte-for-byte the HTML from which the PDF is produced

#### Scenario: The PDF is produced from the compiled HTML via the offline renderer

- **WHEN** a PDF is requested for a given effective template and data map
- **THEN** the system compiles the HTML once and renders that HTML to a PDF through the offline,
  network-denied Gotenberg leg, returning PDF bytes that begin with the PDF signature

### Requirement: Validate submitted data against the effective field schema before rendering

The system SHALL validate a submitted data map against the **effective template's field schema** before
compiling or rendering any document. Validation SHALL check each present value against its field `type`
and its declared `FieldValidation` (numeric `min`/`max`, text `minLength`/`maxLength`/`pattern`, and
`enum` membership). For a **stateless preview** the system SHALL validate present values but SHALL
tolerate missing values (rendered as placeholders) and SHALL NOT enforce `required`. For
**generate-as-draft** the system SHALL validate fully, enforcing that every `required` field is present
and valid. Invalid data SHALL be rejected using the application's RFC 9457 error contract, and the
error SHALL name offending field keys or rules but SHALL NOT echo any submitted data value. No document
SHALL be drawn from invalid data.

#### Scenario: Out-of-bounds value is rejected before rendering

- **WHEN** a submitted value violates its field's declared bounds (for example a `money` field below
  its `min`, or an `enum` value not in `options`)
- **THEN** the system responds with an RFC 9457 error, renders no document, and the response body
  contains no submitted data value

#### Scenario: A partial preview tolerates missing fields

- **WHEN** a stateless preview is requested with some fields absent but all present values valid
- **THEN** the system renders the document with placeholders for the absent fields and does not reject
  the request for the missing values

#### Scenario: Generate-as-draft rejects incomplete data

- **WHEN** generate-as-draft is requested and a `required` field is missing or invalid
- **THEN** the system responds with an RFC 9457 error and generates no draft

### Requirement: Serve a stateless document preview that persists nothing

The system SHALL expose `POST /api/templates/document/preview` that accepts a partial working-set data
map (optionally with `dimensions`), resolves the effective template, validates the data (partial mode),
compiles it, and returns the result. With `Accept: text/html` it SHALL return the compiled **escaped
HTML** for the live pane; with `Accept: application/pdf` it SHALL return a **Gotenberg PDF**. The
response SHALL be served `Cache-Control: no-store`, SHALL persist **nothing** server-side (no row, no
blob, no draft), and SHALL render **placeholders** for fields absent from the submitted data.

#### Scenario: A stateless preview returns escaped HTML and stores nothing

- **WHEN** a client POSTs a partial data map to `/api/templates/document/preview` with `Accept:
  text/html`
- **THEN** the system responds `200 OK` with the compiled HTML, sets `Cache-Control: no-store`, and
  persists nothing for the request (no agreement, draft, or blob is created)

#### Scenario: A stateless preview renders placeholders for missing fields

- **WHEN** a client POSTs a data map missing some fields
- **THEN** the compiled document shows a placeholder for each absent field rather than a blank or a
  literal null

#### Scenario: Download PDF renders from the same stateless preview route

- **WHEN** a client POSTs the same data map with `Accept: application/pdf`
- **THEN** the system responds with an inline `application/pdf` body, `Cache-Control: no-store`, and
  still persists nothing

### Requirement: PDF is generated only on explicit download or the final commit, never per keystroke

The system SHALL compile **HTML only** for the live preview on each section save and SHALL invoke the
Gotenberg **HTML -> PDF** step only for an explicit "Download PDF" request and for the final
generate-as-draft. A section save that refreshes the live pane SHALL NOT invoke Gotenberg.

#### Scenario: A section-save live preview does not invoke Gotenberg

- **WHEN** the live pane is refreshed after a section save (an `Accept: text/html` preview)
- **THEN** the system compiles HTML and returns it without invoking the Gotenberg PDF renderer

#### Scenario: Download PDF invokes Gotenberg

- **WHEN** a client explicitly requests the PDF (an `Accept: application/pdf` preview, or
  generate-as-draft)
- **THEN** the system compiles the HTML and renders it to a PDF through Gotenberg

### Requirement: Generate-as-draft pins the effective-template identity for reproducibility

The system SHALL, when generating and storing the signable draft (generate-as-draft), render the
document definition-driven and **pin** the effective template's identity onto the agreement: its
`templateId`, `contentHash`, and the resolved **layer versions**. The pin SHALL be server-managed
(never client-settable). A stored/signed draft SHALL therefore be reproducible from its pin and SHALL
NOT be silently re-rendered against newer layers. A **stateless preview** SHALL NOT pin anything.

#### Scenario: Generate-as-draft records the pin

- **WHEN** a client invokes generate-as-draft for an agreement
- **THEN** the system stores the generated PDF as the draft and records the effective template's
  `templateId`, `contentHash`, and layer versions on the agreement

#### Scenario: A stateless preview records no pin

- **WHEN** a client requests a stateless document preview
- **THEN** no template identity is pinned and nothing is persisted

### Requirement: Rendering embeds party PII safely -- escaped, offline, and never logged

Document projection SHALL uphold the render-path safety invariants for the party PII it embeds. All
user data SHALL be **HTML-escaped at compile time** (the markup/data boundary). Rendering SHALL remain
**offline**: the compiled HTML SHALL be self-contained (no external URLs) and Gotenberg's outbound
network SHALL stay denied, so no data value triggers an outbound request. The system SHALL NOT write the
composed HTML, the rendered PDF bytes, or any submitted data value to any log at any level.

#### Scenario: Rendered content and submitted values never reach logs

- **WHEN** a document is compiled and rendered (preview or generate-as-draft)
- **THEN** no log line contains the composed HTML, the rendered PDF bytes, or a submitted data value

#### Scenario: A remote reference triggers no outbound request

- **WHEN** a document is rendered whose data or template content references an external URL
- **THEN** the renderer makes no outbound network request and still returns a document

#### Scenario: The module boundary stays clean

- **WHEN** `ModularityTests` runs after this change
- **THEN** it passes: document projection is exposed through the existing `documents.api` named
  interface (extended with the projection port and controller), the `TemplateCompiler` and projection
  service stay package-private in `documents.template`, and no disallowed cross-module dependency is
  introduced

## MODIFIED Requirements

### Requirement: Render a template and data to a PDF

The system SHALL render a rental-agreement document from an **effective template definition plus a data
map**, returning the resulting PDF bytes. The HTML source SHALL be the **`TemplateCompiler`** output --
the effective template's sections and clauses composed as system-owned markup with `{{slots}}` filled
by HTML-escaped user data and `showWhen` clauses included/dropped by the sandboxed DSL evaluator -- and
this HTML SHALL be rendered to a PDF through the Gotenberg (headless Chromium) leg. The prior single
hardcoded Thymeleaf template (`documents/rental-agreement.html`) and its `TemplateAssembler` SHALL be
retired. The renderer SHALL remain domain-agnostic in the `documents` module (it takes a data map, not
a signing type). All supplied data SHALL remain HTML-escaped; the composed document skeleton and clause
text are system-owned, trusted content.

#### Scenario: A definition plus data yields a PDF

- **WHEN** the renderer is asked to render the resolved effective template with a data map
- **THEN** it returns non-empty PDF bytes that begin with the PDF signature and contain the supplied
  field values laid out by the effective template's sections and clauses

#### Scenario: The composed document reflects the definition, not a hardcoded template

- **WHEN** the effective template's clauses or fields change (via its layers/definition)
- **THEN** the rendered document reflects that change, because the HTML is compiled from the effective
  template rather than a static bundled template file

#### Scenario: Markup in a field renders as text

- **WHEN** a data value contains angle-bracket markup
- **THEN** the rendered document shows that text verbatim and does not interpret it as markup

### Requirement: Preview the filled agreement document

The system SHALL provide preview of the filled rental-agreement document rendered on demand and **not
stored**. It SHALL continue to serve the id-bound `GET /api/agreements/{id}/preview` for a persisted
agreement (inline PDF, `Cache-Control: no-store`, 404 for an unknown id, 400 for a non-UUID id), and it
SHALL additionally serve a **stateless** preview (`POST /api/templates/document/preview`) that renders
an in-progress working-set data map with no persisted agreement. Both previews SHALL source their HTML
from the **same `TemplateCompiler`** that produces the signed PDF (parity), SHALL be served
`Cache-Control: no-store`, and SHALL NOT write the rendered bytes or the composed party details to any
log.

#### Scenario: The id-bound preview still returns the filled document inline

- **WHEN** a client GETs `/api/agreements/{id}/preview` for an existing agreement
- **THEN** the system responds `200 OK` with an inline `application/pdf` body that composes the
  agreement's parties, property, money, and tenancy dates, sourced from the compiler, and persists
  nothing

#### Scenario: The stateless preview renders an unsaved working set

- **WHEN** a client POSTs an in-progress data map to `/api/templates/document/preview`
- **THEN** the system renders the document from that data with `Cache-Control: no-store`, persists
  nothing, and shows placeholders for any missing fields

#### Scenario: Previews leave no PII in logs

- **WHEN** any preview render runs
- **THEN** no log line contains the rendered bytes or the composed party details
