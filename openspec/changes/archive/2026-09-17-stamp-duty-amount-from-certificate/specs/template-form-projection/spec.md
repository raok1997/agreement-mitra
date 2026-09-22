## ADDED Requirements

### Requirement: System-sourced fields are not offered for capture

The FormSchema SHALL exclude every system-sourced field, so the capture form never asks the customer for a value the server supplies.

Excluding a field SHALL NOT change the projection's determinism: the same effective template SHALL always yield the same FormSchema.

#### Scenario: The Telangana form does not ask for the stamp duty amount

- **GIVEN** the effective template for `(TG, residential)`, where `stampDutyAmount` is system-sourced
- **WHEN** the FormSchema is projected
- **THEN** the `Statutory (Telangana)` section lists `registrationChargesBorneBy`
- **AND** no field in the schema has the key `stampDutyAmount`

#### Scenario: The commercial Telangana form behaves the same

- **GIVEN** the effective template for `(TG, commercial)`
- **WHEN** the FormSchema is projected
- **THEN** no field in the schema has the key `stampDutyAmount`

#### Scenario: A template with no system-sourced fields projects as before

- **GIVEN** an effective template that declares no system-sourced field
- **WHEN** the FormSchema is projected
- **THEN** it is identical to the schema projected before this requirement existed
