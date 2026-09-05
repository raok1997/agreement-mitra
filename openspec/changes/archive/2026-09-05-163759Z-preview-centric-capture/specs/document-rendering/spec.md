## ADDED Requirements

### Requirement: The renderer tolerates partial data and can emit HTML

The renderer SHALL render a template from a data map that is **incomplete** -- missing keys, blank
values, or empty party lists SHALL render as placeholders (or be omitted), never as an error and never
as a bare `null`, so an in-progress agreement produces a coherent document. This SHALL cover **scalar**
fields (dates, money, agreement date) as well as party lists. In addition to the PDF, the renderer
SHALL be able to return the composed, self-contained **HTML** (the same escaped, offline template
output, pre-PDF) so a caller can embed a fast live preview without a PDF round-trip. The embeddable
HTML SHALL be **font-self-contained** -- the fonts needed to shape complex/Indic scripts SHALL be
embedded in the HTML (not resolved from the render host), so a browser preview shapes those scripts
faithfully rather than falling back to system fonts. The markup/data boundary is unchanged: the
template is trusted markup; all supplied data is escaped.

#### Scenario: A partial data map renders without error

- **WHEN** the renderer is given the rental-agreement template with a data map that omits the parties
- **THEN** it returns a document that renders the present fields and placeholders for the missing
  parties, without failing

#### Scenario: The renderer returns composed HTML

- **WHEN** a caller requests the composed HTML for a data map
- **THEN** the renderer returns the self-contained, escaped HTML of the document (no external
  references, fonts embedded), suitable for embedding in a sandboxed preview pane

#### Scenario: A blank scalar field renders as a placeholder, not null

- **WHEN** the renderer is given a data map whose rent or dates are blank/absent
- **THEN** the document shows a placeholder in those positions (e.g. "[ rent not set ]"), never a
  literal `null` or an error
