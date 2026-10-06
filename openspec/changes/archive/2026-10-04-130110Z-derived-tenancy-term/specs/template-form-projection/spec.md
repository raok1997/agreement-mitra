## ADDED Requirements

### Requirement: Derived fields are projected read-only, not collected

The FormSchema SHALL project a **derived** field as a read-only field, so the capture surface can
display the value the server computes without offering it for capture.

A derived field is one whose value the server computes from other captured values. It differs from a
**system-sourced** field, which is excluded from the schema entirely: system-sourced expresses "the
server supplies this, never show it", while derived expresses "the server computes this, show it but
do not ask for it". Both remain distinct from an ordinary user-sourced field.

A derived field SHALL carry `required: false` in the projected schema regardless of any requiredness
the template declares for it, because the customer cannot supply it and a required-but-uncollectable
field would block every capture. A template definition declaring a derived field SHALL be accepted
whether or not it also declares the field required.

Projecting a derived field SHALL NOT change the projection's determinism: the same effective template
SHALL always yield the same FormSchema.

#### Scenario: The tenancy duration is projected read-only

- **GIVEN** an effective template whose `durationMonths` field is declared derived
- **WHEN** the FormSchema is projected
- **THEN** the `Term` section still lists a field with the key `durationMonths`
- **AND** that field is marked read-only and carries `required: false`

#### Scenario: A derived field is distinct from a system-sourced one

- **GIVEN** an effective template declaring both a derived field and a system-sourced field
- **WHEN** the FormSchema is projected
- **THEN** the derived field is present and marked read-only
- **AND** the system-sourced field is absent from the schema entirely

#### Scenario: Ordinary fields are unaffected

- **GIVEN** an effective template that declares no derived field
- **WHEN** the FormSchema is projected
- **THEN** it is identical to the schema projected before this requirement existed
- **AND** no field is marked read-only
