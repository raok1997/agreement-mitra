## ADDED Requirements

### Requirement: A field may be declared system-sourced

A field SHALL be declarable as **system-sourced** (`source: system`); a field that declares no source SHALL be user-sourced, which is the existing behaviour.

A system-sourced field's value SHALL be supplied only by the server, through a channel separate from submitted data. Any value for that field in submitted data SHALL be discarded before validation and compilation. It SHALL NOT raise a validation error, and it SHALL NOT render.

A system-sourced field SHALL NOT be `required: true`; the definition SHALL be rejected at load if one is.

When a system-sourced field has no server-supplied value:

- when the field declares a `placeholder`, its key/value, party-card and annexure entries SHALL render with that text as a visible provision (`[ placeholder ]`);
- when it declares none, those entries SHALL be **omitted** rather than rendered as a `[ label ]` blank;
- a clause slot referencing it SHALL still render the placeholder. Clauses that reference a system-sourced field are expected to be gated with `showWhen` on that field.

Any field MAY declare `placeholder` text; a blank value of that field SHALL render as `[ placeholder ]` instead of `[ label ]`.

The `source` and `placeholder` attributes SHALL be part of the canonical form when declared, so declaring or changing either changes the effective template's content hash.

#### Scenario: A client-submitted value for a system-sourced field is discarded

- **GIVEN** an effective template declaring `stampDutyAmount` as a system-sourced money field
- **WHEN** a preview or generate projection receives submitted data containing `stampDutyAmount: 5000` and no server-supplied value
- **THEN** the projection succeeds with no validation error
- **AND** the rendered document contains no "5000"

#### Scenario: An unset system-sourced field with a placeholder renders a provision

- **GIVEN** a mandatory key/value section listing a system-sourced field `stampDutyAmount` labelled "Stamp duty paid (INR)" with placeholder "Provision for stamp duty"
- **WHEN** the document is compiled with no value for it
- **THEN** the section renders the row "Stamp duty paid (INR)" with the value `[ Provision for stamp duty ]`
- **AND** a clause gated `showWhen: stampDutyAmount > 0` is not rendered

#### Scenario: An unset system-sourced field without a placeholder renders no row

- **GIVEN** a mandatory key/value section listing a system-sourced field and a user-sourced field
- **WHEN** the document is compiled with no value for the system-sourced field
- **THEN** the section renders the user-sourced field's row
- **AND** it renders no row and no `[ label ]` placeholder for the system-sourced field

#### Scenario: A server-supplied value renders like any other value

- **GIVEN** a system-sourced money field `stampDutyAmount`
- **WHEN** the document is compiled with a server-supplied value of 100.00
- **THEN** its row renders with its label and the value 100.00
- **AND** a clause gated `showWhen: stampDutyAmount > 0` is included with the value filled

#### Scenario: A required system-sourced field is rejected at load

- **GIVEN** a layer that declares a field with `source: system` and `required: true`
- **WHEN** the layer set is loaded
- **THEN** loading fails with a validation error naming the field
- **AND** no partial template is registered

#### Scenario: Declaring a field system-sourced changes the content hash

- **GIVEN** two otherwise identical definitions that differ only in one field's `source`
- **WHEN** both are canonicalized
- **THEN** their content hashes differ
