## ADDED Requirements

### Requirement: A derived field is displayed, never collected

The capture surface SHALL render a field the FormSchema marks read-only as a **displayed, non-editable
value**, and SHALL NOT accept keyboard entry for it, include it in the section's saved values, or
count it toward section completeness.

For the tenancy term specifically, the displayed value SHALL be the term in whole months computed from
the captured start and end dates, using the same whole-month, end-inclusive, truncating count the
server uses, so the value on screen matches the value the rendered document states. While either date
is missing, the surface SHALL show that the term is not yet determined rather than show a default or a
stale number.

#### Scenario: The duration follows the dates instead of being typed

- **WHEN** the user opens the Term section and sets a start date of 2026-01-08 and an end date of
  2028-01-08
- **THEN** the duration is shown as 24 months
- **AND** the duration cannot be edited directly

#### Scenario: The displayed duration matches the previewed document

- **WHEN** the user saves a Term section with dates spanning 24 whole months
- **THEN** the duration shown in the capture surface and the term stated in the previewed document are
  both 24 months

#### Scenario: The duration is undetermined until both dates are set

- **WHEN** the user has set a start date but no end date
- **THEN** the surface shows the term as not yet determined rather than a default value

### Requirement: The capture surface rejects an end date that is not after the start date

The capture surface SHALL report a validation error when the captured end date is **on or before** the
captured start date, and SHALL surface it against the end date field before the agreement is
submitted.

The error SHALL also **block the section from being saved** and SHALL prevent the section counting as
complete, because a saved reversed range reaches the preview, where the derived term is non-positive.
Blocking applies to cross-field errors only: a per-field "required" error SHALL remain saveable, since
capture is progressive and a section may be filled over more than one visit.

This SHALL be understood as a usability affordance, not the trust boundary: the server independently
rejects the same condition, and the surface's check exists so the user is corrected in place rather
than by a submission failure.

#### Scenario: An end date before the start date is reported

- **WHEN** the user sets a start date of 2026-06-01 and an end date of 2026-01-01
- **THEN** an error is shown against the end date

#### Scenario: An end date equal to the start date is reported

- **WHEN** the user sets an end date equal to the start date
- **THEN** an error is shown against the end date

#### Scenario: A valid range is accepted

- **WHEN** the user sets an end date strictly after the start date
- **THEN** no date-range error is shown

#### Scenario: A reversed range cannot be saved

- **WHEN** the user sets a start date of 2026-06-01 and an end date of 2026-01-01
- **THEN** the save control for the section is disabled and the section does not count as complete

#### Scenario: A part-filled section can still be saved

- **WHEN** the user sets a start date and leaves the end date empty
- **THEN** the section can still be saved, because a missing required value is not a cross-field error
