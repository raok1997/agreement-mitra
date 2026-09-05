## ADDED Requirements

### Requirement: The rendered PDF stamps a per-page footer with the reference, platform URL, and page numbers

The system SHALL stamp a **per-page footer** onto the rendered agreement PDF as Chromium print
**furniture** in the **reserved bottom margin** (not as body flow content), so it appears on **every**
page without orphaning onto its own page or overlapping the document content. The footer SHALL carry the
escaped **reference** (the agreement's persisted tracking reference for a saved agreement, or the
`PREVIEW - NOT FOR EXECUTION` marker before save) with the escaped **platform URL** on the left, and
**`Page <pageNumber> of <totalPages>`** on the right. The page total (`totalPages`) SHALL render (not be
blank); the footer SHALL use a layout in which Chromium reliably fills the page-count placeholders (a
table, not a flex row).

The render SHALL emulate **print** media, so a screen-only body element (the on-screen provenance line --
see the `template-document-projection` capability) does **not** appear in the PDF. The reference and URL
SHALL be **HTML-escaped**; the URL SHALL be inert display text (no anchor, no fetch); the render SHALL
stay offline (Gotenberg's outbound network denied) and SHALL NOT log the rendered HTML or PDF bytes. The
`documents` module SHALL hold **no hardcoded brand string** -- the platform URL is application
configuration; a blank URL renders the reference alone.

#### Scenario: Every PDF page carries the reference, URL, and page number

- **WHEN** an agreement document is rendered to PDF
- **THEN** every page carries a footer showing the escaped reference and platform URL on the left and
  `Page <n> of <total>` on the right, with the total rendered (not blank)

#### Scenario: The footer does not orphan or duplicate

- **WHEN** a multi-page document is rendered to PDF
- **THEN** the reference appears exactly once per page (as margin furniture) and never as an extra body
  line on its own page (the on-screen provenance line is hidden under print media)

#### Scenario: The reference renders alone when the platform URL is blank

- **WHEN** the platform URL configuration is unset or blank
- **THEN** the footer shows the reference and page numbers with no URL, and no empty or literal value
