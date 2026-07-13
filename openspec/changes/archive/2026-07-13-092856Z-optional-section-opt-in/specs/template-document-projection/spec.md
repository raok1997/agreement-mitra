## MODIFIED Requirements

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

The compiler SHALL additionally take an **active set** of optional section titles and SHALL render a
section **only when** the section is mandatory (`section.optional == false`) **or** the section's
`title` is present in the active set. An optional section whose title is **not** in the active set
SHALL contribute **nothing** to the composed HTML -- no section header, no field rows, no clauses, no
markup at all. A title in the active set that matches **no** declared section SHALL be **ignored**: it
SHALL render nothing and SHALL raise no error (no existence oracle). Title matching SHALL be an exact,
case-sensitive match on the declared section `title`. Section activation SHALL be the outer gate and
`showWhen` the inner gate: a section that does not render SHALL evaluate none of its clauses'
`showWhen` conditions. The active set SHALL be able only to **toggle a template-declared optional
section on**; it SHALL NOT introduce any section, field, clause, or markup the effective template did
not declare. The compiler SHALL remain a **pure function** of its inputs (effective template, data
map, active set).

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

#### Scenario: A mandatory section always renders

- **WHEN** the compiler renders an effective template with a mandatory section (`optional == false`)
- **THEN** that section renders regardless of the active set -- whether the active set is empty or
  names other sections

#### Scenario: An un-added optional section contributes nothing

- **WHEN** the compiler renders a template containing an optional section whose title is **not** in the
  active set
- **THEN** the composed HTML contains none of that section's content -- no header, no field rows, and
  no clauses

#### Scenario: An added optional section renders

- **WHEN** the same optional section's title is present in the active set
- **THEN** the composed HTML contains that section's header and its field rows / clauses, in its
  declared position

#### Scenario: An unknown active-set title is ignored, never an error

- **WHEN** the active set contains a title that matches no declared section (a typo or a stale title)
- **THEN** compilation succeeds, raises no error, and adds nothing to the composed HTML for that title
  -- indistinguishable from an optional section that is simply not active (no existence oracle)

### Requirement: Serve a stateless document preview that persists nothing

The system SHALL expose `POST /api/templates/document/preview` that accepts a partial working-set data
map (optionally with `dimensions`, and optionally with an `activeSections` list of added optional
section titles), resolves the effective template (default `(state, type)` when omitted), validates the
data (partial mode), compiles it, and returns the result. The preview SHALL drive the compiler with
the request's `activeSections`, so an optional section the request added renders in the response and an
optional section the request did not add is absent from it; mandatory sections always render; an
`activeSections` title matching no declared section is ignored. An omitted, null, or empty
`activeSections` SHALL mean "no optional sections added". With `Accept: text/html` the preview SHALL
return the compiled **escaped HTML** for the live pane; with `Accept: application/pdf` it SHALL return
a **Gotenberg PDF**. The response SHALL be served `Cache-Control: no-store`, SHALL persist **nothing**
server-side (no row, no blob, no draft), and SHALL render **placeholders** for fields absent from the
submitted data. The rendered HTML/PDF, the submitted values, and the `activeSections` list SHALL NOT be
logged.

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

#### Scenario: A preview reflects the added optional sections and omits the un-added ones

- **WHEN** a client POSTs the same `(dimensions, data)` twice -- once with an optional section's title
  in `activeSections` and once without it
- **THEN** the two compiled previews differ exactly by that section's content: the with-run contains
  the section's header and its fields/clauses, the without-run contains none of them, and every other
  section is identical between the two

### Requirement: The live preview and the signed PDF come from one compiler (parity)

The system SHALL source the **live HTML preview** and the **PDF** from the **same** `TemplateCompiler`
output for a given effective template, data map, and active set: the HTML returned to the live pane
SHALL be the identical HTML that is handed to the PDF renderer. There SHALL be no separate client-side
or alternate server-side renderer that could diverge, so the document a user previews is the document
that is signed. The PDF SHALL be produced by handing that compiled HTML to a public `HtmlPdfRenderer`
seam over the offline, network-denied Gotenberg leg.

Section activation SHALL be resolved per **projection face** from a single gating rule in the one
compiler: the **preview** face SHALL take its active set from the request's `activeSections`; the
**generate** face SHALL render mandatory sections plus any active set it is explicitly given, which is
**none by default** because recording a persisted agreement's added optional sections is a deferred
follow-on. Consequently the system SHALL uphold a **documented parity caveat**: a preview that added
optional sections MAY differ from the signed draft (which renders mandatory sections only) until a
named follow-on records the agreement's active set and passes it into the generate face. This caveat
SHALL NOT relax the byte-for-byte parity **within** either face -- the preview HTML SHALL still equal
its own PDF's HTML source, and a generated draft's HTML SHALL still equal its own PDF's HTML source.

#### Scenario: Preview HTML equals the PDF's HTML source

- **WHEN** the same effective template, data map, and active set are rendered for the live pane and for
  the PDF
- **THEN** the HTML returned for the live pane is byte-for-byte the HTML from which the PDF is produced

#### Scenario: The PDF is produced from the compiled HTML via the offline renderer

- **WHEN** a PDF is requested for a given effective template, data map, and active set
- **THEN** the system compiles the HTML once and renders that HTML to a PDF through the offline,
  network-denied Gotenberg leg, returning PDF bytes that begin with the PDF signature

#### Scenario: The generate face's active set is deferred (documented parity caveat)

- **WHEN** an agreement is generated as a draft while the preview had added optional sections
- **THEN** the generate face renders mandatory sections plus only the active set it was explicitly
  given (none by default), so the signed draft may contain fewer optional sections than the preview did
  -- a documented, temporary gap that persists until a named follow-on records the agreement's active
  set, and that does not break the byte-for-byte parity within the preview face or within the generate
  face
