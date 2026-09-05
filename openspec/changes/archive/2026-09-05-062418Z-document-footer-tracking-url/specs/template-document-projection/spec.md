## ADDED Requirements

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
