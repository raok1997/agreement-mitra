## ADDED Requirements

### Requirement: The live preview and the signed PDF come from one compiler (parity)

The system SHALL source the **live HTML preview** and the **PDF** from the **same** `TemplateCompiler`
output for a given effective template and data map: the HTML returned to the live pane SHALL be the
identical HTML that is handed to the PDF renderer. There SHALL be no separate client-side or alternate
server-side renderer that could diverge, so the document a user previews is the document that is
signed. The PDF SHALL be produced by handing that compiled HTML to a public `HtmlPdfRenderer` seam over
the offline, network-denied Gotenberg leg.

#### Scenario: Preview HTML equals the PDF's HTML source

- **WHEN** the same effective template and data map are rendered for the live pane and for the PDF
- **THEN** the HTML returned for the live pane is byte-for-byte the HTML from which the PDF is produced

#### Scenario: The PDF is produced from the compiled HTML via the offline renderer

- **WHEN** a PDF is requested for a given effective template and data map
- **THEN** the system compiles the HTML once and renders that HTML to a PDF through the offline,
  network-denied Gotenberg leg, returning PDF bytes that begin with the PDF signature

### Requirement: Serve a stateless document preview that persists nothing

The system SHALL expose `POST /api/templates/document/preview` that accepts a partial working-set data
map (optionally with `dimensions`), resolves the effective template (default `(state, type)` when
omitted), validates the data (partial mode), compiles it, and returns the result. With `Accept:
text/html` it SHALL return the compiled **escaped HTML** for the live pane; with `Accept:
application/pdf` it SHALL return a **Gotenberg PDF**. The response SHALL be served `Cache-Control:
no-store`, SHALL persist **nothing** server-side (no row, no blob, no draft), and SHALL render
**placeholders** for fields absent from the submitted data. The rendered HTML/PDF and submitted values
SHALL NOT be logged.

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

#### Scenario: An out-of-bounds value is rejected over HTTP without echoing a value

- **WHEN** a client POSTs a data map whose value violates its field's declared bounds
- **THEN** the system responds with an RFC 9457 `application/problem+json` error, renders no document,
  and the body contains no submitted data value

### Requirement: PDF is generated only on explicit download or the final commit, never per keystroke

The system SHALL compile **HTML only** for the live preview on each section save and SHALL invoke the
Gotenberg **HTML -> PDF** step only for an explicit "Download PDF" request and for the final
generate-as-draft. A section save that refreshes the live pane SHALL NOT invoke Gotenberg.

#### Scenario: A section-save live preview does not invoke Gotenberg

- **WHEN** the live pane is refreshed after a section save (an `Accept: text/html` preview)
- **THEN** the system compiles HTML and returns it without invoking the Gotenberg PDF renderer

#### Scenario: Download PDF invokes Gotenberg

- **WHEN** a client explicitly requests the PDF (an `Accept: application/pdf` preview)
- **THEN** the system compiles the HTML and renders it to a PDF through Gotenberg

### Requirement: Rendering embeds party PII safely -- escaped, offline, and never logged

Document projection SHALL uphold the render-path safety invariants for the party PII it embeds. All
user data SHALL remain **HTML-escaped at compile time** (the markup/data boundary). Rendering SHALL
remain **offline**: the compiled HTML SHALL be self-contained (no external URLs) and Gotenberg's
outbound network SHALL stay denied, so no data value triggers an outbound request. The system SHALL NOT
write the composed HTML, the rendered PDF bytes, or any submitted data value to any log at any level.
Document projection SHALL be exposed only through the existing `documents.api` named interface
(extended), with the projection service and compiler package-private in `documents.template`.

#### Scenario: Rendered content and submitted values never reach logs

- **WHEN** a document is compiled and rendered (stateless preview or id-bound preview)
- **THEN** no log line contains the composed HTML, the rendered PDF bytes, or a submitted data value

#### Scenario: A remote reference triggers no outbound request

- **WHEN** a document is rendered whose data or template content references an external URL
- **THEN** the renderer makes no outbound network request and still returns a document

#### Scenario: The module boundary stays clean

- **WHEN** `ModularityTests` runs after this change
- **THEN** it passes: document projection is exposed through the existing `documents.api` named
  interface (extended with the projection port and controller), the `TemplateCompiler` and projection
  service stay package-private in `documents.template`, `signing` depends only on the `documents`
  public interface, and no disallowed cross-module dependency is introduced
