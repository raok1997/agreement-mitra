# template-document-projection Specification

## Purpose

The data-dependent projection of an effective template + a user data map into the rendered
agreement. This capability is built up across the four increments that superseded the
`template-document-projection` umbrella. Created by archiving change `template-html-compiler` (CR-1),
which delivers the **pure engine** *unwired*: a package-private `TemplateCompiler` that fills
HTML-escaped `{{slots}}` and fires the resolution engine's sandboxed `showWhen` evaluator with real
data to include/drop clauses, and a package-private `SubmittedDataValidator` that validates + coerces a
submitted data map against the effective field schema (preview vs generate tiers), raising a structured
validation error that maps to the RFC 9457 contract. Change `document-projection-render` (CR-2) then wired the compiler
into the Gotenberg render path behind a public `HtmlPdfRenderer` seam, added the stateless
`POST /api/templates/document/preview` endpoint (content-negotiated HTML/PDF, `no-store`, persists
nothing), locked single-renderer parity (the live-pane HTML is byte-for-byte the PDF's HTML source),
and established the two render tiers. The reproducibility pin at generate-as-draft is added by a later
increment (`agreement-template-pin`).

## Requirements

### Requirement: Compile an effective template plus user data into escaped HTML

The system SHALL provide a `TemplateCompiler` that maps an **effective template** plus a **user data
map** into a self-contained HTML document. The compiler SHALL render the effective template's sections
and clauses as **system-owned markup**, and SHALL fill each clause's declared `{{slot}}` with the
corresponding value from the data map **HTML-escaped**, so a user value containing markup renders as
literal text and never as document structure or active content. A slot whose value is missing or blank
SHALL render an **escaped placeholder** (never a bare `null`, never unescaped). For every clause
carrying a `showWhen` condition, the compiler SHALL evaluate that condition against the user data map
through the resolution engine's sandboxed boolean DSL evaluator and SHALL **include the clause only
when the condition is true**, dropping it (and closing up its numbering) otherwise. The compiler SHALL
NOT evaluate any condition through Thymeleaf, SpringEL, or any general expression engine. The composed
HTML SHALL be self-contained (fonts by family name, no external URLs).

#### Scenario: Slots are filled with escaped user data

- **WHEN** the compiler renders a clause with a `{{slot}}` bound to a field whose submitted value
  contains angle-bracket markup (for example a script tag)
- **THEN** the composed HTML shows that value as literal text in the clause and does not interpret it
  as markup or active content

#### Scenario: A missing slot renders an escaped placeholder

- **WHEN** the compiler renders a clause with a `{{slot}}` whose field has no value in the data map
- **THEN** the composed HTML shows an escaped placeholder for that slot rather than a blank or a
  literal `null`

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

### Requirement: Validate and coerce submitted data against the effective field schema

The system SHALL provide a `SubmittedDataValidator` that validates a submitted data map against the
**effective template's field schema** and returns a **coerced** value map for the compiler. Validation
SHALL check each present value against its field `type` and its declared `FieldValidation` (numeric
`min`/`max`, text `minLength`/`maxLength`/`pattern`, and `enum` membership), and SHALL coerce each
value to the operand type the resolver validated. A declared default SHALL fill an absent field. In
**preview** mode the validator SHALL validate present values, tolerate missing values, and SHALL NOT
enforce `required`; in **generate** mode it SHALL enforce that every `required` field is
present-or-defaulted and valid. On failure the validator SHALL raise a structured validation error
naming offending field **keys** and **rule tokens** only -- it SHALL NOT carry any submitted data
value -- and no document SHALL be compiled from invalid data. The structured validation error SHALL map
to the application's RFC 9457 error contract (`application/problem+json`) with an `errors[]` list of
field-key + rule entries.

#### Scenario: An out-of-bounds present value is rejected

- **WHEN** a submitted value violates its field's declared bounds (for example a `money` field below
  its `min`, or an `enum` value not in `options`)
- **THEN** validation fails, no document is compiled, and the raised error names the field key and rule
  token but contains no submitted data value

#### Scenario: Preview tolerates missing fields

- **WHEN** the validator runs in preview mode with some fields absent but all present values valid
- **THEN** it succeeds without enforcing `required`, leaving the absent fields for the compiler to
  render as placeholders

#### Scenario: Generate enforces required fields

- **WHEN** the validator runs in generate mode and a `required` field is missing (and has no default)
  or invalid
- **THEN** it fails and names the offending field key + rule token, and no document is compiled

#### Scenario: The RFC 9457 body never echoes a data value

- **WHEN** the structured validation error is mapped to an HTTP response
- **THEN** the `application/problem+json` body lists offending field keys and rule tokens in `errors[]`
  and contains no submitted data value

### Requirement: The compiler and validators never log rendered content or submitted values

The compiler and validators SHALL uphold the never-log invariant: they SHALL NOT write the composed
HTML or any submitted data value to any log at any level. Validation errors SHALL cite field keys /
rule tokens only.

#### Scenario: Compiling and validating leave no PII in logs

- **WHEN** a document is compiled and its data validated
- **THEN** no log line contains the composed HTML or a submitted data value

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
