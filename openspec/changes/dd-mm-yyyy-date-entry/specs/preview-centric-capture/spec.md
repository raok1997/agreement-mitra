## ADDED Requirements

### Requirement: Dates are entered in day-month-year order regardless of host locale

The capture surface SHALL present every date field in **`dd/mm/yyyy`** order, and that order SHALL be
identical on every operating system, browser, and regional setting. The surface SHALL NOT delegate the
displayed date format to the host platform, because a host configured for month-first order renders an
Indian date in an order the user cannot detect as wrong by reading it back.

The expected order SHALL be visible to the user as a hint on the field, not only implied by the value
present.

The value the surface submits SHALL remain the ISO `yyyy-mm-dd` form the API expects, so the change of
display order SHALL NOT alter any submitted payload, stored value, or rendered document.

#### Scenario: Day-first order on a month-first host

- **GIVEN** a device whose regional settings use month-first date order
- **WHEN** the user opens a section containing a date field holding 8 January 2026
- **THEN** the field shows `08/01/2026` in day-month-year order
- **AND** the field shows a `dd/mm/yyyy` hint

#### Scenario: The submitted value stays ISO

- **WHEN** the user enters `08/01/2026` into the tenancy start date
- **THEN** the value submitted for that field is `2026-01-08`

#### Scenario: A stored date is displayed day-first when resumed

- **WHEN** a draft holding an ISO date is resumed into the capture surface
- **THEN** the date field displays it in `dd/mm/yyyy` order

### Requirement: An invalid or incomplete date is reported against the field

The capture surface SHALL report a validation error against a date field whose entry is not a real
calendar date, and SHALL NOT submit, store, or silently discard such an entry.

An entry SHALL be treated as invalid when it names a day that the given month and year do not have
(for example `31/02/2026` or `31/04/2026`), when it is incomplete, or when it is not a date at all. A
**leap day** SHALL be accepted in a leap year and reported as invalid in a common year.

The error message SHALL name the field and the expected format and SHALL NOT restate the rejected
entry.

#### Scenario: A day the month does not have is rejected

- **WHEN** the user enters `31/02/2026` into a date field
- **THEN** an error is shown against that field
- **AND** no value is submitted for it

#### Scenario: An incomplete entry is rejected

- **WHEN** the user leaves a date field partially filled
- **THEN** an error is shown against that field rather than a value being inferred

#### Scenario: A leap day is accepted only in a leap year

- **WHEN** the user enters `29/02/2028`
- **THEN** it is accepted
- **AND** entering `29/02/2027` is reported as invalid

#### Scenario: A required empty date is still reported as required

- **WHEN** a required date field is left empty
- **THEN** it is reported as required, as any other required field is

### Requirement: Date entry remains operable by keyboard, assistive technology, and touch

The capture surface SHALL keep date entry fully operable without a pointer and legible to assistive
technology, and SHALL keep the platform's own date picker where that picker serves the user better
than an in-page one.

A user SHALL be able to complete a date field using the keyboard alone, without opening any picker.
Where a calendar picker is offered, it SHALL be reachable and dismissable by keyboard, and SHALL move
focus predictably on open and on close.

Each date field SHALL expose its label, its expected format, and its invalid state to assistive
technology.

On **touch devices** the surface SHALL use the platform's native date control, whose picker presents
day, month, and year as distinct labelled components and so does not carry the month-first ambiguity
that typed digits do.

#### Scenario: A date is completed without a pointer

- **WHEN** a user tabs to a date field and types a date
- **THEN** the value is accepted without any picker being opened

#### Scenario: The picker is keyboard operable

- **WHEN** a user opens the calendar picker from the keyboard
- **THEN** it can be navigated and dismissed from the keyboard, and focus returns to the field on
  close

#### Scenario: Invalid state reaches assistive technology

- **WHEN** a date field holds an invalid entry
- **THEN** the field is exposed as invalid to assistive technology, with its error message associated
  with it

#### Scenario: A touch device keeps the native picker

- **GIVEN** a touch device
- **WHEN** the user opens a date field
- **THEN** the platform's native date control is used
