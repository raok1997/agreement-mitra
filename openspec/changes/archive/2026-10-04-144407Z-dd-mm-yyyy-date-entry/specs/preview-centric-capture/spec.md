## ADDED Requirements

### Requirement: Dates are entered in day-month-year order regardless of host locale

The capture surface SHALL present every date field in **`dd/mm/yyyy`** order, identical on every
operating system, browser, device, and regional setting, and SHALL NOT delegate the displayed date
format to the host platform.

This applies on touch devices too: a host configured for month-first order renders an Indian date in
an order the user cannot detect as wrong by reading it back, and a platform date control that has been
closed reads the picked value back in that host order.

The expected order SHALL be visible to the user as a hint on the field, not only implied by the value
present.

A valid entry SHALL be submitted in the ISO `yyyy-mm-dd` form the API expects, so the change of display
order SHALL NOT alter any submitted payload, stored value, or rendered document.

#### Scenario: Day-first order on a month-first host

- **GIVEN** a device whose regional settings use month-first date order
- **WHEN** the user opens a section containing a date field holding 8 January 2026
- **THEN** the field shows `08/01/2026` in day-month-year order
- **AND** the field shows a `dd/mm/yyyy` hint

#### Scenario: The submitted value stays ISO

- **WHEN** the user enters `08/01/2026` into the tenancy start date and saves the section
- **THEN** the value saved for that field is `2026-01-08`

#### Scenario: A stored date is displayed day-first when resumed

- **WHEN** a draft holding an ISO date is resumed into the capture surface
- **THEN** the date field displays it in `dd/mm/yyyy` order

#### Scenario: Display does not shift with the host time zone

- **GIVEN** a host whose time zone is behind UTC
- **WHEN** a date field displays the ISO date `2026-01-08`
- **THEN** it shows `08/01/2026`, not the previous day

### Requirement: An invalid date is reported against the field and blocks saving its section

The capture surface SHALL report a validation error against a date field whose entry is not an
acceptable calendar date, and SHALL refuse to save the section while that entry stands. It SHALL NOT
submit or store such an entry, SHALL NOT silently replace it with the field's previous value, and SHALL
NOT silently discard it.

An entry SHALL be treated as invalid when any of the following holds:

- it names a day that the given month and year do not have (for example `31/02/2026` or
  `31/04/2026`);
- it is incomplete;
- it is not a date at all;
- its year falls outside the range the server accepts (1900 to 2199).

A **leap day** SHALL be accepted in a leap year and reported as invalid in a common year.

A **missing** date SHALL remain non-blocking, as any missing field is: it is reported as required, and
the section can still be saved and completed on a later visit.

The error message SHALL name the field and the expected format, and SHALL NOT restate the rejected
entry.

#### Scenario: A day the month does not have is rejected

- **WHEN** the user enters `31/02/2026` into a date field and leaves the field
- **THEN** an error is shown against that field

#### Scenario: Editing a saved date to an invalid one does not save the old date

- **GIVEN** a section whose start date was saved as 8 January 2026
- **WHEN** the user changes it to `31/02/2026` and saves the section
- **THEN** the section is not saved and stays open with the error shown
- **AND** the saved start date is neither the invalid entry nor silently kept as 8 January 2026 in
  place of the user's edit

#### Scenario: An incomplete entry is rejected

- **WHEN** the user leaves a date field holding `08/01/20`
- **THEN** an error is shown against that field rather than a value being inferred

#### Scenario: A leap day is accepted only in a leap year

- **WHEN** the user enters `29/02/2028`
- **THEN** it is accepted
- **AND** entering `29/02/2027` is reported as invalid

#### Scenario: A year outside the accepted range is rejected

- **WHEN** the user enters `31/12/1899` or `01/01/2200`
- **THEN** an error is shown against that field
- **AND** `01/01/1900` and `31/12/2199` are accepted

#### Scenario: A pasted date in another format is reported, not reinterpreted

- **WHEN** the user pastes `03-02-2001` into a date field and leaves the field
- **THEN** an error is shown against that field
- **AND** no date is inferred from its digits

#### Scenario: A pasted year-first ISO date is accepted

- **WHEN** the user pastes `2001-02-03` into a date field and leaves the field
- **THEN** it is accepted as 3 February 2001 and shown as `03/02/2001`

#### Scenario: A required empty date is still reported as required and does not block saving

- **WHEN** a required date field is left empty and the section is saved
- **THEN** the field is reported as required, as any other required field is
- **AND** the section saves

### Requirement: Date entry remains operable by keyboard and assistive technology

The capture surface SHALL keep date entry fully operable without a pointer and legible to assistive
technology.

A user SHALL be able to complete a date field using the keyboard alone, without opening any picker.
Where a calendar picker is offered, it SHALL be reachable and dismissable by keyboard, SHALL move
focus predictably on open and on close, and dismissing it SHALL NOT dismiss the section containing it.

Each date field SHALL expose its label, its expected format, and its invalid state to assistive
technology.

#### Scenario: A date is completed without a pointer

- **WHEN** a user tabs to a date field and types a date
- **THEN** the value is accepted without any picker being opened

#### Scenario: The picker is keyboard operable

- **WHEN** a user opens the calendar picker from the keyboard
- **THEN** it can be navigated and dismissed from the keyboard, and focus returns to the field on
  close

#### Scenario: Escape closes only the picker

- **GIVEN** the calendar picker is open inside a section
- **WHEN** the user presses Escape
- **THEN** the picker closes and the section stays open with its entries intact

#### Scenario: Label, format, and invalid state reach assistive technology

- **WHEN** a date field holds an invalid entry
- **THEN** the field exposes its label, is exposed as invalid, and has its format hint and error
  message associated with it
