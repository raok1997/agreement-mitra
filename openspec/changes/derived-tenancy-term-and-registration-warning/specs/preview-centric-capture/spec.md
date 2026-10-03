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

### Requirement: The capture surface warns when the term crosses the registrability line

The capture surface SHALL display a warning, at the point the term is captured, whenever the derived
term **exceeds eleven months**, stating that an agreement of that term must be registered with the
Sub-Registrar and that registration is separate from, and not included in, the stamp duty the platform
quotes.

The warning SHALL be advisory: it SHALL NOT block editing, saving, or proceeding, because a term over
eleven months is a lawful choice the parties may make knowingly, and the platform's role is to ensure
they are not making it unknowingly.

The warning SHALL be phrased for a non-technical, all-India audience and SHALL NOT restate any captured
value other than the term itself.

#### Scenario: A term over eleven months warns

- **WHEN** the captured dates yield a term of 24 months
- **THEN** a registration warning is shown in the Term section
- **AND** the warning states that registration is not included in the stamp duty quoted

#### Scenario: A term of exactly eleven months does not warn

- **WHEN** the captured dates yield a term of 11 months
- **THEN** no registration warning is shown

#### Scenario: The warning does not block progress

- **WHEN** the registration warning is shown
- **THEN** the user can still save the section and continue, and the section counts as complete if its
  required fields are valid
