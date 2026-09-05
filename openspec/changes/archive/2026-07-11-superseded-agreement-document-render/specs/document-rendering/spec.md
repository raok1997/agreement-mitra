## ADDED Requirements

### Requirement: Render a template and data to a PDF

The system SHALL provide a document renderer that turns a template id and a data map into a
PDF using an external render service (Gotenberg, which runs headless Chromium). The renderer
SHALL populate the identified template with the supplied data and return the resulting PDF
bytes. It SHALL be domain-agnostic -- it SHALL
depend only on a template id and a generic data map, not on any agreement or signing type.

With the template catalog out of scope for this capability, the system SHALL provide exactly
one bundled rental-agreement template as the default; an unknown template id SHALL be an
error, not a silent empty document.

#### Scenario: A template plus data yields a PDF

- **WHEN** the renderer is asked to render the rental-agreement template with a data map
- **THEN** it returns non-empty PDF bytes that begin with the PDF signature and contain the
  supplied field values laid out by the template

#### Scenario: An unknown template id is rejected

- **WHEN** the renderer is asked for a template id that is not bundled
- **THEN** it fails with an error and returns no document

### Requirement: Rendering is offline and loads no external resource

The renderer SHALL render using only **bundled** resources (the template, its CSS, and
embedded fonts) and SHALL block all network access during rendering. A template or data value
that references a remote URL SHALL NOT cause any outbound request; the document SHALL still
render (with the remote resource simply absent).

#### Scenario: A remote reference triggers no outbound request

- **WHEN** a document is rendered whose content references an external URL
- **THEN** the renderer makes no outbound network request and still returns a PDF

### Requirement: User-supplied data cannot inject document content

The renderer SHALL treat template data as untrusted and SHALL escape it when binding into the
template, so a data value containing markup renders as literal text rather than as document
structure or active content.

#### Scenario: Markup in a field renders as text

- **WHEN** a party's name contains angle-bracket markup (for example a script tag)
- **THEN** the rendered document shows that text verbatim and does not interpret it as markup

### Requirement: Preview the filled agreement document

The system SHALL provide `GET /api/agreements/{id}/preview` that renders the agreement's data
into the rental-agreement template and returns the result as an **inline PDF**
(`Content-Type: application/pdf`, shown in the browser, with caching disabled). The preview
SHALL be rendered on demand and SHALL NOT be stored. For an unknown agreement id it SHALL
respond `404 Not Found`.

#### Scenario: Preview returns the filled document inline

- **WHEN** a client GETs `/api/agreements/{id}/preview` for an existing agreement
- **THEN** the system responds `200 OK` with an inline `application/pdf` body showing the
  agreement's parties, property, money, and tenancy dates composed into the template
- **AND** the response is marked non-cacheable and nothing is persisted

#### Scenario: Preview of an unknown agreement is not found

- **WHEN** a client GETs `/api/agreements/{id}/preview` for an id that does not exist
- **THEN** the system responds `404 Not Found`

### Requirement: Generate the agreement document as its draft

The system SHALL provide `POST /api/agreements/{id}/document` that renders the agreement into
the rental-agreement template and **stores the result as the agreement's draft PDF** through
the existing draft mechanism (object storage), so the generated document feeds stamping and
eSign without a manual upload. On success it SHALL respond `200 OK`. For an unknown agreement
id it SHALL respond `404 Not Found`.

#### Scenario: Generating a document stores it as the draft

- **WHEN** a client POSTs `/api/agreements/{id}/document` for an existing agreement
- **THEN** the system renders the template and stores the PDF as that agreement's draft
- **AND** a subsequent signing request finds a draft present (the generated document), with
  no manual upload required

### Requirement: Rendered documents are never logged and stream without caching

The system SHALL NOT write rendered PDF bytes or the composed document content to any log at
any level. Preview responses SHALL be served with caching disabled so intermediaries do not
retain the party PII in the document.

#### Scenario: A render leaves no PII in logs

- **WHEN** a preview or generate render runs
- **THEN** no log line contains the rendered PDF bytes or the composed party details
