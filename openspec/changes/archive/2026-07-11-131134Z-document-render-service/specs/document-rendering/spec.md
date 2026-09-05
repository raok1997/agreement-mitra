## ADDED Requirements

### Requirement: Render a template and data to a PDF

The system SHALL provide a document renderer that turns a template id and a data map into a PDF
using an external render service (Gotenberg, which runs headless Chromium). The renderer SHALL
populate the identified template with the supplied data and return the resulting PDF bytes. It
SHALL be domain-agnostic -- it SHALL depend only on a template id and a generic data map, not on
any agreement or signing type.

With the template catalog out of scope for this capability, the system SHALL provide exactly one
bundled rental-agreement template as the default; an unknown template id SHALL be an error, not a
silent empty document.

#### Scenario: A template plus data yields a PDF

- **WHEN** the renderer is asked to render the rental-agreement template with a data map
- **THEN** it returns non-empty PDF bytes that begin with the PDF signature and contain the
  supplied field values laid out by the template

#### Scenario: An unknown template id is rejected

- **WHEN** the renderer is asked for a template id that is not bundled
- **THEN** it fails with an error and returns no document

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
level. The renderer is the pipeline through which party PII will later flow (CR-3b/CR-3c), so it
establishes the no-log guarantee from the start.

#### Scenario: A render leaves no rendered content in logs

- **WHEN** the renderer renders a template with a data map
- **THEN** no log line contains the composed HTML or the rendered PDF bytes
