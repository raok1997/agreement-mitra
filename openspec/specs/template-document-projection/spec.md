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

### Requirement: Enum options carry a human display label

The projected form schema SHALL carry, for every enum field, a human display **label** for each
option alongside its stored **value**. The label SHALL be derived from the value by replacing `_`
with a space, applying Initial Capitals to each word, keeping a defined acronym set upper-case (e.g.
`UPI`, `PG`), and preserving a token that already contains an upper-case letter (e.g. `1BHK`)
verbatim. The stored/submitted value SHALL be unchanged: submission, persistence, `showWhen`
evaluation, defaults, and enum-membership validation continue to use the value, not the label. The
label is a pure, deterministic function of the value, so the form schema stays data-independent and
cacheable.

#### Scenario: Each enum option exposes value and humanised label

- **GIVEN** an enum field `propertyType` with options `apartment`, `independent_house`, `pg_room`
- **WHEN** the form schema is projected
- **THEN** each option carries its value and a label -- `apartment`/"Apartment",
  `independent_house`/"Independent House", `pg_room`/"PG Room"
- **AND** the list box displays the labels while the submitted value remains the token
  (`independent_house`)

#### Scenario: Acronyms and already-capitalised tokens

- **GIVEN** options `upi` (paymentMode) and `1BHK` (bhkConfiguration)
- **WHEN** the labels are derived
- **THEN** `upi` renders as `UPI` (acronym set) and `1BHK` is preserved verbatim as `1BHK`

#### Scenario: Submission and gating use the value, not the label

- **GIVEN** a user selects the option shown as "Two Wheeler"
- **WHEN** the form is submitted
- **THEN** the submitted value is `two_wheeler`
- **AND** a `showWhen` such as `parkingType != "none"` and the field default evaluate on the value
  exactly as before (the label change is presentation-only)

### Requirement: The compiled document carries a screen-only provenance line for the on-screen preview

The system's `TemplateCompiler` SHALL emit a **system-owned provenance line at the foot of the compiled
document body**: the document's **tracking reference** and the **platform URL**, rendered as
`{reference} . {platformUrl}`. The line SHALL be **screen-only** -- styled `display:none` by default and
shown only under `@media screen` -- so it appears in the on-screen HTML preview (which the preview iframe
renders as screen media) but is hidden when the same HTML is rendered to a PDF under print media (so it
never orphans onto its own page or overlaps content; the PDF shows the reference + URL as per-page footer
furniture instead -- see `document-rendering`).

Because the live HTML preview and the PDF are compiled from the **one** compiler output, the HTML source
SHALL be **byte-for-byte identical** for both (preview<->PDF body parity preserved); only the render
medium differs. The reference and the platform URL SHALL be **resolved values passed into `compile`** --
the reference from the projection request, the platform URL resolved by the projection service from
configuration -- so the compiler stays a **pure function** of its inputs, reads no clock or configuration,
and the `documents` module carries **no hardcoded brand string**. Both values SHALL be **HTML-escaped**;
a **blank** reference or platform URL SHALL omit that part (a pre-identifier preview shows the URL and a
marker with no number). The provenance line SHALL be system-owned markup (like the signature block) and
SHALL NOT be treated as template-content-hash input (the effective-template identity/pin is unaffected).

#### Scenario: The provenance line is in the preview HTML source

- **WHEN** a document is compiled for the on-screen preview with a reference and platform URL
- **THEN** the compiled HTML contains, at the document foot, the escaped reference and escaped platform
  URL, and the preview HTML is byte-for-byte the HTML the PDF is rendered from (parity)

#### Scenario: The provenance line is screen-only

- **WHEN** the compiled HTML is rendered under print media (the PDF)
- **THEN** the body provenance line is hidden (`display:none` outside `@media screen`), so it does not
  appear in the PDF body or orphan onto its own page

#### Scenario: Provenance values are escaped and cannot inject markup

- **WHEN** a reference or platform-URL value containing angle-bracket markup is bound into the line
- **THEN** the compiled HTML shows that value as literal text and never as markup or active content

#### Scenario: A blank reference or URL omits that part

- **WHEN** the reference is blank (a pre-identifier preview) or the platform URL is unset/blank
- **THEN** the provenance line omits the blank part (no empty or literal value) and renders only the
  present part

#### Scenario: The compiler stays pure and the module holds no brand literal

- **WHEN** the provenance line is rendered
- **THEN** the reference and URL are values passed into `compile` (the compiler reads no clock or
  configuration), and the `documents` module code contains no hardcoded brand URL (the value is app
  configuration resolved at the projection layer)

### Requirement: A commercial lease product line resolves from its own layer set

The system SHALL serve a **Commercial Lease Agreement** product line at the dimensions `(state, type)
= (IN, commercial)` and `(TG, commercial)`, resolved from a layer set separate from the residential
one. The commercial base SHALL declare its own document header -- title `Commercial Lease Agreement`,
its own subtitle, and its own execution line -- because the effective definition takes `meta` verbatim
from the base and no patch operation edits `meta`; reusing the residential base would title a
commercial document "Residential Tenancy".

The commercial set SHALL carry commercial party roles (Lessor / Lessee, mapped onto the same
aggregate name keys the residential set uses) and commercial-specific fields and clauses, and SHALL
NOT carry the residential-only fields (BHK, furnishing, pets, occupants). The Telangana layers SHALL
apply over the composed commercial structure along the engine's fixed precedence chain
(`base -> type -> state -> state_type`), so `state-TG` layers over the base and type layer and
`state_type-TG-commercial` applies above it -- exactly as for residential.

This SHALL require no engine change: the existing resolver, compiler, catalog, and projection API
SHALL serve the new dimensions from added classpath content alone. The catalog SHALL remain the
dimension-validation authority -- an unpublished or unknown dimension SHALL `404`.

#### Scenario: The commercial document declares the commercial header, not the residential one

- **WHEN** the `(IN, commercial)` template resolves
- **THEN** its document title is `Commercial Lease Agreement`
- **AND** its subtitle is not the residential `Residential Tenancy (Leave & Licence)`

#### Scenario: Telangana overlays the commercial structure

- **WHEN** the `(TG, commercial)` template resolves
- **THEN** the effective template carries the Telangana fields (`stampDutyAmount`,
  `registrationChargesBorneBy`) and defaults `jurisdictionCity` to `Hyderabad`
- **AND** the `Statutory (Telangana)` section is ordered after the covenant and annexure sections and
  before the `In Witness Whereof` execution block

#### Scenario: The execution block renders for the commercial line too

- **WHEN** a commercial agreement document renders
- **THEN** its `In Witness Whereof` section renders as a signature block carrying the `esign:owner`
  and `esign:tenant` anchors, the same as the residential line

#### Scenario: Every required commercial field is aggregate-backed or defaulted

- **WHEN** the commercial template resolves
- **THEN** each required field is either backed by a key the agreement aggregate holds or carries a
  default, so generate-as-draft reaches parity without a capture gap

### Requirement: The commercial type layer pins the commercial character of the document

The commercial `type` layer SHALL make the `permittedUse` field **required** and SHALL default it to
`commercial`, so a generated draft always carries a commercial-use covenant even when the agreement
aggregate supplies no value for it. Because the default satisfies generate's required-check, this
SHALL NOT break generate/preview parity: `permittedUse` is not an aggregate-backed key, and the
required-with-default shape keeps it out of the capture gap.

#### Scenario: A generated draft carries the commercial use covenant without user input

- **GIVEN** only the aggregate-backed field keys are supplied
- **WHEN** a commercial agreement is generated as a draft
- **THEN** `permittedUse` resolves to `commercial` from its default
- **AND** generate succeeds -- the required-check is satisfied by the default, not by captured data

### Requirement: The Telangana statutory overlay is opt-in, and supersedes the national stamp clause

The commercial state layer SHALL declare its `Statutory (Telangana)` section **optional**, so the
engine's existing opt-in gating keeps it out of the preview and out of a generated draft until the
user adds it via `activeSections` -- matching the residential set's treatment rather than making the
overlay unconditional. Its fields SHALL be optional or defaulted so opting in never introduces a
capture gap.

For `(TG, commercial)` the `state_type` layer SHALL remove the base's generic national
stamp/registration clause, because the state layer's Telangana-specific stamp/registration clause
supersedes it, and SHALL re-author the `Now This Agreement Witnesseth` covenant list without the
removed clause so no section entry dangles at a clause that no longer exists.

#### Scenario: The statutory section is absent until opted into

- **WHEN** a `(TG, commercial)` document is previewed or generated without `Statutory (Telangana)` in
  `activeSections`
- **THEN** the `Statutory (Telangana)` section does not render
- **AND** the same document with that section active renders it without requiring new captured data

#### Scenario: The national stamp clause is superseded, leaving no dangling entry

- **WHEN** the `(TG, commercial)` template resolves
- **THEN** the base's generic national stamp/registration clause is absent
- **AND** the `Now This Agreement Witnesseth` section's entries reference only clauses that exist in
  the effective template

### Requirement: Templates register by layer-set discovery, not per-template code

The catalog SHALL be seeded by **discovering layer-set folders on the classpath** under the templates
root, so a new template is registered by dropping in its folder with no per-template code change. For
each discovered set the seeder SHALL derive the national `(state, type)` row from the base's `meta`
and one further row per `state-<XX>` overlay beside it, taking the row's version, name, and
description **from the base definition** so catalog metadata cannot drift from the layer set it
points at. A row's display name composes the base's document title with a human name for the state
code, which the seeder holds as a lookup -- so a **new state code** needs a display-name entry or its
row shows the raw code; a new **template type** needs no code change at all.

Only sets whose base is `published` SHALL be seeded, and every seeded row SHALL be `published`.
Seeding SHALL be idempotent per `(state, type)` and SHALL deduplicate by classpath-relative root,
because one set can surface under more than one classpath entry. Test fixture sets SHALL live outside
the production templates root so discovery sees only production sets. The catalog SHALL continue to
store **metadata only, never template bodies**. Seeding SHALL be confined to the non-production
profiles that carry sandbox/dummy data.

#### Scenario: A dropped-in layer set appears in the catalog with no code change

- **GIVEN** a published layer set on the classpath under the templates root
- **WHEN** the application starts under the `local` or `sandbox` profile
- **THEN** the catalog holds its national row plus one row per state overlay beside it
- **AND** each row's version, name, and description derive from that base definition

#### Scenario: Unpublished sets and test fixtures are not seeded

- **GIVEN** a layer set whose base is not `published`, and a test fixture set outside the production
  templates root
- **WHEN** the application starts under the `local` or `sandbox` profile
- **THEN** neither is seeded into the catalog

#### Scenario: Seeding is idempotent and duplicate-safe

- **GIVEN** the application runs under the `local` or `sandbox` profile
- **WHEN** the same layer set surfaces under more than one classpath entry, or the application starts
  more than once
- **THEN** each `(state, type)` is seeded once, first-wins, with no duplicate rows

#### Scenario: No seeding outside the sandbox profiles

- **WHEN** the application starts under a profile other than `local` or `sandbox`
- **THEN** no catalog row is seeded from classpath discovery
