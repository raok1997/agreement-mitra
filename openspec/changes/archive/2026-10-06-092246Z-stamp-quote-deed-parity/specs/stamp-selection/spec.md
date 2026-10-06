## ADDED Requirements

### Requirement: The quote is computed from the terms the deed renders

The system SHALL compute the stamp quote of a template-generated agreement from the same effective
rent escalation and execution date that its deed renders, so the duty quoted is the duty of the deed
the parties sign.

- **Rent escalation**: the captured `rentEscalationPercent` when it is a whole number from 0 to 100,
  the deed field's own type and range; otherwise the integer default that field declares in the
  template the agreement's state and type resolve to; otherwise no escalation. A captured value
  outside that type or range is treated as absent -- the deed refuses to render it, so no signable
  deed exists for it, and it SHALL NOT reach the duty arithmetic.
- **Execution date**: the captured `agreementDate` when it parses and lies within the range of dates
  the deed accepts; otherwise the agreement's recorded draft execution date; otherwise today in
  India.

The template default is consulted only when the captured escalation is not usable. A template that
declares no default for the field, or a default that is not a whole number, means no escalation. A
template that can no longer be found for the agreement's state and type SHALL make the quote
unavailable, as an unresolvable template already does -- never a quote at 0% against a deed that
printed the default.

#### Scenario: A cleared escalation field is quoted at the deed's default

- **GIVEN** a residential agreement of 24 months at INR 10,000 per month whose capture state has no
  `rentEscalationPercent`
- **AND** its template declares a `rentEscalationPercent` default of 5
- **WHEN** its stamp quote is computed
- **THEN** the rent schedule escalates by 5% every 12 months, matching the escalation clause the
  deed renders

#### Scenario: A captured escalation wins over the default

- **GIVEN** an agreement whose capture state has `rentEscalationPercent` = `0`
- **WHEN** its stamp quote is computed
- **THEN** no escalation is applied, matching the deed, which omits its escalation clause

#### Scenario: No template default means no escalation

- **GIVEN** an agreement with no captured escalation whose template declares no default for it
- **WHEN** its stamp quote is computed
- **THEN** no escalation is applied and the quote is still produced

#### Scenario: A template that cannot be found makes the quote unavailable

- **GIVEN** an agreement with no captured escalation whose template no longer resolves for its state
  and type
- **WHEN** its stamp quote is computed
- **THEN** stamping is reported as not available for it, and no quote at 0% escalation is produced

#### Scenario: An out-of-range or non-integer escalation never reaches the arithmetic

- **GIVEN** an agreement whose captured `rentEscalationPercent` is `150`, `4.5`, `5.0`, `-5`, `abc`
  or `1E+999999999`
- **AND** its template declares a default of 5
- **WHEN** its stamp quote is computed
- **THEN** the captured value is treated as absent and the quote escalates by the default 5%

#### Scenario: A stored draft's execution date selects the rule

- **GIVEN** an agreement with no captured `agreementDate` whose draft was rendered with execution
  date `2026-10-05`
- **WHEN** its stamp quote is computed on `2026-10-06`
- **THEN** the rule is selected for execution date `2026-10-05`, the date printed on the draft

### Requirement: The shown breakdown explains rent escalation

When a quote's breakdown carries an escalation line, the stamp-quote screen SHALL show it within
"How this was calculated", stating the escalation rate, its interval and the amount it added to the
consideration, and SHALL NOT present it as a change to the duty.

#### Scenario: Escalation is named in the breakdown

- **GIVEN** a quote whose breakdown has an escalation line for 5% every 12 months adding INR 3.00
- **WHEN** the owner opens "How this was calculated"
- **THEN** a line reads "Includes 5% rent escalation every 12 months" with INR 3.00 shown unsigned,
  as an amount and not a delta

#### Scenario: No escalation, no line

- **GIVEN** a quote whose breakdown has no escalation line
- **WHEN** the owner opens "How this was calculated"
- **THEN** no escalation line is shown
