## MODIFIED Requirements

### Requirement: Stamp intake is audited without leaking certificate contents

Every intake attempt - accepted or rejected - SHALL be recorded with the acting staff
identity, the target agreement, the submitted reference, the outcome, and the time. When the
target agreement is later deleted by its owner (an unpaid draft only), the record SHALL be kept
with its target agreement cleared; the submitted reference, outcome, staff identity and time
SHALL be unchanged.

The scan bytes SHALL be written to object storage only; they SHALL NOT be stored in
PostgreSQL and SHALL NOT be written to logs. The certificate number SHALL be **redacted** in
logs (last 4 characters only), because it is the single-use token evidencing duty payment.
The scanned certificate carries party names and a property description, so logs SHALL NOT
echo any submitted metadata value verbatim, and error bodies SHALL NOT echo submitted values.

#### Scenario: Intake attempts are audited

- **WHEN** a stamp upload succeeds or is rejected
- **THEN** an audit record captures the acting staff identity, target agreement, outcome, and
  timestamp

#### Scenario: An intake audit record outlives a deleted draft

- **GIVEN** a refused intake attempt recorded against an unpaid draft
- **WHEN** the draft's owner deletes the draft
- **THEN** the audit record still exists with the same submitted reference, outcome, staff
  identity and time, and no target agreement

#### Scenario: No certificate contents or PII in logs

- **WHEN** the system logs around stamp intake
- **THEN** no scan bytes, no full certificate number, and no party names or property
  description appear; the certificate number appears only in redacted form
