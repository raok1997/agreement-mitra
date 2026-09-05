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
