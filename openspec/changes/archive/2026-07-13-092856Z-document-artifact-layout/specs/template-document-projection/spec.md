## MODIFIED Requirements

### Requirement: Compile an effective template plus user data into escaped HTML

The system SHALL provide a `TemplateCompiler` that maps an **effective template**, a **user data
map**, and a **resolved execution date** into a self-contained HTML document laid out as the reference
rental-agreement artifact. The compiler SHALL emit a **centred document header** built from the
effective template's `meta.document`: the `title`, the `subtitle`, and an **execution line** whose
authored text carries `{{slot}}` fills. The compiler SHALL render each section's body **dispatched by
that section's declared `render` kind**: `parties` SHALL render a **party card** (a label/value
block), `keyvalue` SHALL render a **label/value table**, `clauses` SHALL render a **numbered ordered
list**, and `annexure` SHALL render a **bulleted list**. Section structure (the header, headings,
party cards, tables, ordered and bulleted lists, the signature block) SHALL remain **system-owned
markup**.

The compiler SHALL fill each `{{slot}}` -- in a clause **and** in the header execution line -- with the
corresponding value from the data map **HTML-escaped**, and SHALL HTML-escape **every** piece of
system-authored template text as literal text: the header `title`, `subtitle`, and execution-line
text, every field label, and every clause text. A user value containing markup SHALL render as literal
text and never as document structure or active content. A slot whose value is missing or blank SHALL
render an **escaped placeholder** (never a bare `null`, never unescaped). For every clause carrying a
`showWhen` condition, the compiler SHALL evaluate that condition against the user data map through the
resolution engine's sandboxed boolean DSL evaluator and SHALL **include the clause only when the
condition is true**, dropping it (and closing up its numbering) otherwise. The compiler SHALL NOT
evaluate any condition through Thymeleaf, SpringEL, or any general expression engine.

The compiler SHALL bind the **resolved execution date** under the **reserved date-binding key**
`agreementDate` before rendering, so the header execution line (and any clause slot referencing that
key) fills from the resolved value; the resolved value SHALL override any submitted `agreementDate`.
The compiler SHALL be a **pure function** of `(effective template, data, resolved execution date)`: it
SHALL NOT read a clock or any ambient state. The composed HTML SHALL be self-contained (fonts by
family name / embedded data-URI faces, no external URLs).

#### Scenario: Slots are filled with escaped user data

- **WHEN** the compiler renders a clause with a `{{slot}}` bound to a field whose submitted value
  contains angle-bracket markup (for example a script tag)
- **THEN** the composed HTML shows that value as literal text in the clause and does not interpret it
  as markup or active content

#### Scenario: A missing slot renders an escaped placeholder

- **WHEN** the compiler renders a clause with a `{{slot}}` whose field has no value in the data map
- **THEN** the composed HTML shows an escaped placeholder for that slot rather than a blank or a
  literal `null`

#### Scenario: The header renders escaped title, subtitle, and execution line from meta.document

- **WHEN** the compiler renders an effective template whose `meta.document` carries a `title`,
  `subtitle`, and an execution line with a `{{agreementDate}}` fill
- **THEN** the composed HTML begins with a centred header showing the escaped title and subtitle and
  the execution line with `{{agreementDate}}` filled by the resolved execution date, all HTML-escaped;
  a title, subtitle, or execution-line text containing markup renders as literal text

#### Scenario: A parties section renders an escaped party card

- **WHEN** the compiler renders a section whose `render` kind is `parties`
- **THEN** the section body is a party card (a label/value block) whose labels and values are all
  HTML-escaped

#### Scenario: A keyvalue section renders an escaped label/value table

- **WHEN** the compiler renders a section whose `render` kind is `keyvalue`
- **THEN** the section body is a label/value table whose labels and values are all HTML-escaped

#### Scenario: A clauses section renders a numbered list with showWhen gating

- **WHEN** the compiler renders a section whose `render` kind is `clauses` and one clause declares a
  `showWhen` that evaluates to false against the submitted data
- **THEN** the section body is a numbered ordered list of the included clauses (each HTML-escaped), the
  false-condition clause is omitted, and the surrounding numbering closes up

#### Scenario: An annexure section renders an escaped bulleted list

- **WHEN** the compiler renders a section whose `render` kind is `annexure`
- **THEN** the section body is a bulleted list whose entries are all HTML-escaped

#### Scenario: The compiler binds the resolved execution date under the reserved key

- **WHEN** the compiler is given a resolved execution date and an effective template whose execution
  line references `{{agreementDate}}`
- **THEN** the header execution line is filled with that resolved date, overriding any submitted
  `agreementDate`, and the compiler reads no clock

#### Scenario: Conditions run only through the sandboxed DSL

- **WHEN** any `showWhen` is evaluated during compilation
- **THEN** it is evaluated by the resolution engine's hand-written boolean DSL over declared fields and
  literals only, with no method call, property navigation, indexing, or expression-engine evaluation

### Requirement: The live preview and the signed PDF come from one compiler (parity)

The system SHALL source the **live HTML preview** and the **PDF** from the **same** `TemplateCompiler`
output for a given effective template, data map, and **resolved execution date**: the HTML returned to
the live pane SHALL be the identical HTML that is handed to the PDF renderer, including the artifact
header, the render-kind-dispatched section bodies, and the page margins. There SHALL be no separate
client-side or alternate server-side renderer that could diverge, so the document a user previews is
the document that is signed, for both the National (`IN`) and Telangana (`TG`) jurisdictions. Preview
and PDF SHALL be produced from the same resolved execution date so their headers match. The PDF SHALL
be produced by handing that compiled HTML to a public `HtmlPdfRenderer` seam over the offline,
network-denied Gotenberg leg.

#### Scenario: Preview HTML equals the PDF's HTML source

- **WHEN** the same effective template, data map, and resolved execution date are rendered for the live
  pane and for the PDF
- **THEN** the HTML returned for the live pane is byte-for-byte the HTML from which the PDF is produced,
  including the header, the render-kind-dispatched section bodies, and the page margins

#### Scenario: The artifact layout renders for both IN and TG

- **WHEN** the reference `IN` and `TG` sets are each resolved and compiled
- **THEN** each produces the artifact layout -- the `meta.document` header, party card(s), a
  "Terms of Tenancy" key/value table, and a numbered witnesseth clause list -- from the one compiler,
  with the signature block at the foot

#### Scenario: The PDF is produced from the compiled HTML via the offline renderer

- **WHEN** a PDF is requested for a given effective template, data map, and resolved execution date
- **THEN** the system compiles the HTML once and renders that HTML to a PDF through the offline,
  network-denied Gotenberg leg, returning PDF bytes that begin with the PDF signature

### Requirement: Rendering embeds party PII safely -- escaped, offline, and never logged

Document projection SHALL uphold the render-path safety invariants for the party PII it embeds across
the reference-artifact layout. All user data **and** all system-authored template text -- the header
`title`, `subtitle`, and execution-line text, every field label, and every clause text, across party
cards, key/value tables, the numbered clause list, and the annexure -- SHALL remain **HTML-escaped at
compile time** (the markup/data boundary). Rendering SHALL remain **offline**: the compiled HTML SHALL
be self-contained (the serif body and page margins add no external URL; the Noto faces stay embedded as
data-URIs) and Gotenberg's outbound network SHALL stay denied, so no data value triggers an outbound
request. The system SHALL NOT write the composed HTML (including the header), the rendered PDF bytes, or
any submitted data value to any log at any level. Document projection SHALL be exposed only through the
existing `documents.api` named interface (unchanged by this change), with the projection service and
compiler package-private in `documents.template`.

#### Scenario: Injection-as-data renders inert across every layout region

- **WHEN** a document is compiled whose header text, a field value, a field label, or a clause slot
  value contains angle-bracket markup
- **THEN** the composed HTML shows that content as literal text in the header, party card, table, or
  clause list, and never as document structure or active content

#### Scenario: Rendered content and submitted values never reach logs

- **WHEN** a document is compiled and rendered (stateless preview or id-bound preview)
- **THEN** no log line contains the composed HTML (including the header), the rendered PDF bytes, or a
  submitted data value

#### Scenario: A remote reference triggers no outbound request

- **WHEN** a document is rendered whose data or template content references an external URL
- **THEN** the renderer makes no outbound network request and still returns a document

#### Scenario: The module boundary stays clean

- **WHEN** `ModularityTests` runs after this change
- **THEN** it passes: document projection is exposed through the existing `documents.api` named
  interface (unchanged by this change), the `TemplateCompiler`, the projection service, and the
  resolved-execution-date plumbing stay package-private in `documents.template`, and no disallowed
  cross-module dependency is introduced

### Requirement: Serve a stateless document preview that persists nothing

The system SHALL expose `POST /api/templates/document/preview` that accepts a partial working-set data
map (optionally with `dimensions`), resolves the effective template (default `(state, type)` when
omitted), **resolves the execution date** (the submitted `agreementDate` value when present and
non-blank, else the current system date read from an injected `Clock`), validates the data (partial
mode), compiles it **once** with that resolved execution date, and returns the result. With `Accept:
text/html` it SHALL return the compiled **escaped HTML** for the live pane -- carrying the artifact
header and page margins; with `Accept: application/pdf` it SHALL return a **Gotenberg PDF**. The
response SHALL be served `Cache-Control: no-store`, SHALL persist **nothing** server-side (no row, no
blob, no draft -- resolving the execution date reads a clock but writes no state), and SHALL render
**placeholders** for fields absent from the submitted data. The rendered HTML/PDF and submitted values
SHALL NOT be logged.

#### Scenario: A stateless preview returns escaped HTML and stores nothing

- **WHEN** a client POSTs a partial data map to `/api/templates/document/preview` with `Accept:
  text/html`
- **THEN** the system responds `200 OK` with the compiled HTML (including the artifact header and page
  margins), sets `Cache-Control: no-store`, and persists nothing for the request (no agreement, draft,
  or blob is created)

#### Scenario: The execution date falls back to the injected clock when the agreement date is blank

- **WHEN** a client POSTs a data map with `agreementDate` absent or blank
- **THEN** the header execution line shows the current system date read from the injected `Clock`, the
  render persists nothing, and a fixed clock yields a deterministic date

#### Scenario: A submitted agreement date is used for the execution line

- **WHEN** a client POSTs a data map with a non-blank `agreementDate`
- **THEN** the header execution line shows the submitted `agreementDate` value rather than the clock's
  date

#### Scenario: A stateless preview renders placeholders for missing fields

- **WHEN** a client POSTs a data map missing some fields
- **THEN** the compiled document shows a placeholder for each absent field rather than a blank or a
  literal null

#### Scenario: Download PDF renders from the same stateless preview route

- **WHEN** a client POSTs the same data map with `Accept: application/pdf`
- **THEN** the system responds with an inline `application/pdf` body, `Cache-Control: no-store`, and
  still persists nothing
