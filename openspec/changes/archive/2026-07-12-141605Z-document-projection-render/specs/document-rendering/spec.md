## MODIFIED Requirements

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
