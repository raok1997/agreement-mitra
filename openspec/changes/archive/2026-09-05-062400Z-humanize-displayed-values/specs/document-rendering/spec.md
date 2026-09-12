## ADDED Requirements

### Requirement: Enum values render humanised in the document body

The renderer SHALL render every enum-typed field value with its human display label -- the **same**
label the form list box shows (Initial Caps, `_` replaced with a space, acronyms upper-case,
already-capitalised tokens preserved) -- wherever it renders an enum value in the preview or generated
document (a key/value cell or a clause slot). Humanisation SHALL occur at render time from the raw
token; the data map SHALL retain the raw value so conditional (`showWhen`) evaluation, defaults, and
validation are unaffected. The list-box label and the document body SHALL be produced by one shared
derivation so they never diverge.

#### Scenario: Enum value humanised in a cell and a clause

- **GIVEN** an agreement with `paymentMode` `bank_transfer`
- **WHEN** the document is rendered (preview or generate-as-draft)
- **THEN** the value reads `Bank Transfer` in both the Financial key/value cell and the rent clause
- **AND** the raw token `bank_transfer` does not appear in the output

#### Scenario: Conditional gating still evaluates on the raw token

- **GIVEN** a clause gated on `parkingType != "none"` with `parkingType` `two_wheeler`
- **WHEN** the document renders
- **THEN** the clause is included (the condition compared the raw token) and its rendered text shows
  `Two Wheeler`

### Requirement: Rendered dates use a single dd-MMM-yyyy format

The renderer SHALL format every date-typed field value to a single `dd-MMM-yyyy` form (e.g.
`13-Jul-2026` -- zero-padded day, title-case 3-letter English month, 4-digit year) wherever a date is
rendered: clause slots, key/value cells, party cards, the annexure, and the header execution line, in
both the preview and generate projections. Formatting SHALL occur at render time from the underlying
ISO value; the data map SHALL retain the ISO value so conditional (`showWhen`) evaluation is
unaffected. A date-typed field with no value SHALL render the existing labelled placeholder, and a
value that does not parse as an ISO date SHALL render unchanged rather than causing a failure.

#### Scenario: Term dates render in dd-MMM-yyyy

- **GIVEN** an agreement with `startDate` `2026-08-05` and `endDate` `2027-06-30`
- **WHEN** the document is rendered (preview or generate-as-draft)
- **THEN** the rendered term reads the dates as `05-Aug-2026` and `30-Jun-2027`
- **AND** neither the raw ISO (`2026-08-05`) nor an `MM/DD/YYYY` form appears in the output

#### Scenario: The execution line uses the same format

- **GIVEN** an agreement whose resolved execution date is `2026-07-13`
- **WHEN** the header execution line renders
- **THEN** it reads `13-Jul-2026` (the same format as every other rendered date), not `1 July 2026`
  or a raw ISO string

#### Scenario: Conditional gating still evaluates on the ISO value

- **GIVEN** a clause whose `showWhen` compares a date field
- **WHEN** the document renders
- **THEN** the clause is included or dropped exactly as before (the condition evaluates on the ISO
  value; only the displayed date is reformatted)

#### Scenario: Missing or unparseable date is safe

- **GIVEN** a date-typed field with no submitted value
- **WHEN** the document renders
- **THEN** the labelled placeholder renders (as today), never a bare `null`
- **AND GIVEN** a date field whose value is not a valid ISO date, **WHEN** it renders, **THEN** the
  value renders unchanged and the render does not throw

#### Scenario: Reproducible under the version pin

- **GIVEN** a generated draft whose effective template is pinned
- **WHEN** the document is re-rendered from the pin
- **THEN** the formatted dates reproduce byte-stable (the format is deterministic and locale-fixed)
