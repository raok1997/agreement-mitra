# document-rendering Specification

## Purpose

Turn a template id + a generic data map into a PDF using headless Chromium via a Gotenberg
service, with bundled Noto fonts and fully offline rendering. The domain-agnostic
`DocumentRenderer` (the `documents` module's public API), the single bundled rental-agreement
template, HTML-escaping of untrusted data, and the outbound-network-deny (SSRF/exfil) guard.
(Created by archiving change `document-render-service` (CR-3a); the bundled template was upgraded to
a complete India-standard residential rental agreement by `agreement-preview` (CR-3b). The
agreement-to-data mapping and preview live in the `agreement-preview` capability; generate-as-draft
is added by `agreement-generate-draft` (CR-3c).)

## Requirements

### Requirement: Render a template and data to a PDF

The system SHALL render a rental-agreement document from an **effective template definition plus a data
map**, returning the resulting PDF bytes. The HTML source SHALL be the **`TemplateCompiler`** output --
the effective template's sections and clauses composed as system-owned markup with `{{slots}}` filled
by HTML-escaped user data and `showWhen` clauses included/dropped by the sandboxed DSL evaluator -- and
this HTML SHALL be rendered to a PDF through the Gotenberg (headless Chromium) leg behind a public
`HtmlPdfRenderer` seam. The prior single hardcoded Thymeleaf template
(`documents/rental-agreement.html`), its `TemplateAssembler`, and the templateId-gated
`DocumentRenderer.renderPdf` seam SHALL be retired. The renderer SHALL remain domain-agnostic in the
`documents` module (it takes a data map, not a signing type). All supplied data SHALL remain
HTML-escaped; the composed document skeleton and clause text are system-owned, trusted content; the
render SHALL remain offline (Gotenberg's outbound network denied) and SHALL NOT log the composed HTML or
the PDF bytes.

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

#### Scenario: The render makes no outbound request

- **WHEN** a document is rendered whose content references an external URL
- **THEN** Gotenberg's outbound network stays denied, the renderer makes no outbound request, and it
  still returns a PDF

### Requirement: Rendering is offline and loads no external resource

The renderer SHALL render using only **bundled** resources (the template, its CSS, and embedded
fonts) and SHALL block all network access during rendering. A template or data value that
references a remote URL SHALL NOT cause any outbound request; the document SHALL still render (with
the remote resource simply absent).

#### Scenario: A remote reference triggers no outbound request

- **WHEN** a document is rendered whose content references an external URL
- **THEN** the renderer makes no outbound network request and still returns a PDF

### Requirement: User-supplied data cannot inject document content

The renderer SHALL treat template data as untrusted and SHALL escape it when binding into the
template, so a data value containing markup renders as literal text rather than as document
structure or active content.

#### Scenario: Markup in a field renders as text

- **WHEN** a data value contains angle-bracket markup (for example a script tag)
- **THEN** the rendered document shows that text verbatim and does not interpret it as markup

### Requirement: The renderer never logs rendered content

The system SHALL NOT write the composed template HTML or the rendered PDF bytes to any log at any
level. The renderer is the pipeline through which party PII will later flow, so it establishes the
no-log guarantee from the start.

#### Scenario: A render leaves no rendered content in logs

- **WHEN** the renderer renders a template with a data map
- **THEN** no log line contains the composed HTML or the rendered PDF bytes

### Requirement: Execution / signature block with eSign anchors

The renderer SHALL render an execution / signature block for the agreement's signer set, filling the
previously-empty "In Witness Whereof" section. For each signer it SHALL render a signature zone
(signature area, the signer's name, and the field's role label) and SHALL emit a stable, non-PII eSign **anchor**
identified by role (`esign:<role>`). The block, its clauses, and its anchors are part of the effective
template (system-owned markup); all signer-supplied values are escaped. Witness lines SHALL render
only when the optional Witnesses section is added (opt-in via activeSections -- the engine's standard
optional-section gating; the section and its witness fields default off), as printed escaped data
without an eSign anchor. The renderer SHALL remain eSign-agnostic (it emits an anchor token, not a
provider signature field).

The zone SHALL NOT render a date or place line: an eSigned instrument takes its date from the eSign
appearance, so those blanks could never be completed. The label SHALL state the signer's role only
and SHALL NOT claim the name matches an Aadhaar record, because nothing in this flow verifies the
captured name against one.

#### Scenario: Signature zone + anchor per signer

- **GIVEN** an agreement with an Owner and a Tenant
- **WHEN** the document is rendered (preview or generate-as-draft)
- **THEN** the "In Witness Whereof" section renders an execution block with one signature zone for the
  Owner and one for the Tenant
- **AND** each zone shows a signature area, the signer's name, and the role label -- and no date or
  place line
- **AND** the output contains a stable anchor `esign:owner` and `esign:tenant`, one per zone

#### Scenario: Signer data is escaped in the execution block

- **GIVEN** a signer whose name contains `<script>alert(1)</script>`
- **WHEN** the execution block renders
- **THEN** the value appears escaped as literal text (`&lt;script&gt;...`), never as active markup

#### Scenario: Witnesses are an optional section, default off

- **GIVEN** an agreement whose optional Witnesses section is not added (the residential default)
- **WHEN** the document renders
- **THEN** no witness lines appear
- **AND GIVEN** the Witnesses section is added with witness name / address, **WHEN** it renders,
  **THEN** the corresponding witness lines appear as printed (escaped) data, without an eSign anchor

#### Scenario: Anchors are reproducible under the version pin

- **GIVEN** a generated draft whose effective template is pinned
- **WHEN** the document is re-rendered from the pin
- **THEN** the execution block and both eSign anchors reproduce byte-stable

### Requirement: Boilerplate clauses and document furniture

The renderer SHALL render the boilerplate clauses declared in the template (notices, governing law,
severability, entire-agreement / amendment) and SHALL render document furniture: a unique, non-PII
agreement reference on the document and a page indicator (page X of Y).

#### Scenario: Boilerplate clauses present

- **WHEN** a rental-agreement document renders
- **THEN** it includes a Notices clause, a Governing-law clause, a Severability clause, and an
  Entire-agreement / Amendment clause

#### Scenario: Agreement reference and page numbers

- **WHEN** a rental-agreement document renders
- **THEN** every page shows a unique agreement reference and a "page X of Y" indicator
- **AND** the reference contains no Aadhaar / OTP / VID / secret

### Requirement: Enum values render humanised in the document body

The renderer SHALL render every enum-typed field value with its human display label -- the **same**
label the form list box shows (Initial Caps, `_` replaced with a space, acronyms upper-case,
already-capitalised tokens preserved) -- wherever it renders an enum value in the preview or generated
document (a key/value cell or a clause slot). Humanisation SHALL occur at render time from the raw
token; the data map SHALL retain the raw value so conditional (`showWhen`) evaluation, defaults, and
validation are unaffected. The list-box label and the document body SHALL be produced by one shared
derivation so they never diverge.

#### Scenario: Enum value humanised in a cell and a clause

- **GIVEN** an agreement with `paymentMode` `bank_transfer`
- **WHEN** the document is rendered (preview or generate-as-draft)
- **THEN** the value reads `Bank Transfer` in both the Financial key/value cell and the rent clause
- **AND** the raw token `bank_transfer` does not appear in the output

#### Scenario: Conditional gating still evaluates on the raw token

- **GIVEN** a clause gated on `parkingType != "none"` with `parkingType` `two_wheeler`
- **WHEN** the document renders
- **THEN** the clause is included (the condition compared the raw token) and its rendered text shows
  `Two Wheeler`

### Requirement: Rendered dates use a single dd-MMM-yyyy format

The renderer SHALL format every date-typed field value to a single `dd-MMM-yyyy` form (e.g.
`13-Jul-2026` -- zero-padded day, title-case 3-letter English month, 4-digit year) wherever a date is
rendered: clause slots, key/value cells, party cards, the annexure, and the header execution line, in
both the preview and generate projections. Formatting SHALL occur at render time from the underlying
ISO value; the data map SHALL retain the ISO value so conditional (`showWhen`) evaluation is
unaffected. A date-typed field with no value SHALL render the existing labelled placeholder, and a
value that does not parse as an ISO date SHALL render unchanged rather than causing a failure.

#### Scenario: Term dates render in dd-MMM-yyyy

- **GIVEN** an agreement with `startDate` `2026-08-05` and `endDate` `2027-06-30`
- **WHEN** the document is rendered (preview or generate-as-draft)
- **THEN** the rendered term reads the dates as `05-Aug-2026` and `30-Jun-2027`
- **AND** neither the raw ISO (`2026-08-05`) nor an `MM/DD/YYYY` form appears in the output

#### Scenario: The execution line uses the same format

- **GIVEN** an agreement whose resolved execution date is `2026-07-13`
- **WHEN** the header execution line renders
- **THEN** it reads `13-Jul-2026` (the same format as every other rendered date), not `1 July 2026`
  or a raw ISO string

#### Scenario: Conditional gating still evaluates on the ISO value

- **GIVEN** a clause whose `showWhen` compares a date field
- **WHEN** the document renders
- **THEN** the clause is included or dropped exactly as before (the condition evaluates on the ISO
  value; only the displayed date is reformatted)

#### Scenario: Missing or unparseable date is safe

- **GIVEN** a date-typed field with no submitted value
- **WHEN** the document renders
- **THEN** the labelled placeholder renders (as today), never a bare `null`
- **AND GIVEN** a date field whose value is not a valid ISO date, **WHEN** it renders, **THEN** the
  value renders unchanged and the render does not throw

#### Scenario: Reproducible under the version pin

- **GIVEN** a generated draft whose effective template is pinned
- **WHEN** the document is re-rendered from the pin
- **THEN** the formatted dates reproduce byte-stable (the format is deterministic and locale-fixed)

### Requirement: The rendered PDF stamps a per-page footer with the reference, platform URL, and page numbers

The system SHALL stamp a **per-page footer** onto the rendered agreement PDF as Chromium print
**furniture** in the **reserved bottom margin** (not as body flow content), so it appears on **every**
page without orphaning onto its own page or overlapping the document content. The footer SHALL carry the
escaped **reference** (the agreement's persisted tracking reference for a saved agreement, or the
`PREVIEW - NOT FOR EXECUTION` marker before save) with the escaped **platform URL** on the left, and
**`Page <pageNumber> of <totalPages>`** on the right. The page total (`totalPages`) SHALL render (not be
blank); the footer SHALL use a layout in which Chromium reliably fills the page-count placeholders (a
table, not a flex row).

The render SHALL emulate **print** media, so a screen-only body element (the on-screen provenance line --
see the `template-document-projection` capability) does **not** appear in the PDF. The reference and URL
SHALL be **HTML-escaped**; the URL SHALL be inert display text (no anchor, no fetch); the render SHALL
stay offline (Gotenberg's outbound network denied) and SHALL NOT log the rendered HTML or PDF bytes. The
`documents` module SHALL hold **no hardcoded brand string** -- the platform URL is application
configuration; a blank URL renders the reference alone.

#### Scenario: Every PDF page carries the reference, URL, and page number

- **WHEN** an agreement document is rendered to PDF
- **THEN** every page carries a footer showing the escaped reference and platform URL on the left and
  `Page <n> of <total>` on the right, with the total rendered (not blank)

#### Scenario: The footer does not orphan or duplicate

- **WHEN** a multi-page document is rendered to PDF
- **THEN** the reference appears exactly once per page (as margin furniture) and never as an extra body
  line on its own page (the on-screen provenance line is hidden under print media)

#### Scenario: The reference renders alone when the platform URL is blank

- **WHEN** the platform URL configuration is unset or blank
- **THEN** the footer shows the reference and page numbers with no URL, and no empty or literal value
