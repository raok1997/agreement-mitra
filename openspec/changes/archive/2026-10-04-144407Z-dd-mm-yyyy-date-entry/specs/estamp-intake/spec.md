## ADDED Requirements

### Requirement: Staff enter the certificate issue date in day-month-year order

The staff console SHALL collect the stamp certificate's issue date in **`dd/mm/yyyy`** order,
identically on every operating system, browser, and regional setting, with the same entry behaviour
and validation as the capture surface's date fields.

The console SHALL NOT allow an upload while the issue date is empty or invalid, and SHALL submit a
valid issue date in ISO `yyyy-mm-dd` form, unchanged from today.

#### Scenario: Issue date reads day-first on a month-first host

- **GIVEN** a staff machine whose regional settings use month-first date order
- **WHEN** the console shows an issue date of 8 January 2026
- **THEN** the field shows `08/01/2026`

#### Scenario: An invalid issue date blocks the upload

- **WHEN** a staff member enters `31/02/2026` as the issue date
- **THEN** an error is shown against the issue date
- **AND** the upload cannot be submitted

#### Scenario: A valid issue date is submitted as ISO

- **WHEN** a staff member enters `08/01/2026` as the issue date and uploads
- **THEN** the upload carries the issue date `2026-01-08`
