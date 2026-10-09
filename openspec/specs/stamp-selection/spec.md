# stamp-selection Specification

## Purpose
The customer's view of stamp duty before paying: the quote, the bounded set of stamp values they may
buy with a recommended default, the audited acknowledgement required to buy less than the legal duty,
and fixing that choice once an order exists.
## Requirements
### Requirement: The customer is shown the stamp quote before paying

The system SHALL show the owner of an agreement, before any payment order is created, the legal stamp duty, its line-by-line breakdown, whether registration is required, the source of the rule with its review status, and each stamp option with the total payable for it.

The quote SHALL be computed by the server from the agreement's stored terms and SHALL be available
only to a caller who may view the agreement's payment. It SHALL carry no party names, contact details
or address.

#### Scenario: The quote precedes the payment window

- **WHEN** the owner of an eligible Telangana agreement proceeds to payment
- **THEN** they are shown the legal duty, the breakdown, the registration notice and the stamp options
  with their totals
- **AND** the payment window does not open until they confirm a stamp option

#### Scenario: A caller who cannot view the payment cannot read the quote

- **WHEN** a caller without access to the agreement's payment requests its stamp quote
- **THEN** the request is refused without disclosing any amount

#### Scenario: An agreement with no quotable duty shows no options

- **WHEN** the stamp quote is requested for an agreement whose duty outcome is not Quoted
- **THEN** the response states that stamping is not available for it and offers no option

### Requirement: Stamp options are bounded by the jurisdiction's offer policy and one option is pre-selected

The stamp options SHALL be exactly those the jurisdiction's stamp paper catalog offer policy allows, with exactly one option pre-selected (the recommended option), and no free-form amount SHALL be accepted.

Under the **planned** offer policy (the default), the options SHALL be the recommended option, being the
smallest plannable stamp value at or above the legal duty across the jurisdiction's stamp media, and
each single stamp paper denomination strictly below the legal duty; when no medium can plan the legal
duty there SHALL be no recommended option and the agreement SHALL NOT be payable.

Where a jurisdiction issues a stamp certificate for any amount, the planned policy therefore
pre-selects the **exact legal duty**, and the lower denominations remain offered so the customer may
deliberately choose a lesser value. A jurisdiction SHALL NOT be reduced to a fixed denomination when
it can in fact issue the exact duty.

Under the **single papers** offer policy, the options SHALL be exactly the single paper denominations
the policy names, whether above or below the legal duty, with the policy's pre-selected denomination as
the recommended option. The legal duty SHALL still be computed and shown.

#### Scenario: Planned offer for an INR 440 duty

- **GIVEN** a jurisdiction with the planned offer policy, stamp paper of INR 10, 20, 50 and 100, and challan
- **WHEN** an agreement with a legal duty of INR 440 is quoted
- **THEN** the recommended option is INR 440 and is pre-selected
- **AND** the other options are INR 100, 50, 20 and 10 single stamp papers

#### Scenario: Telangana offers only a single INR 100 paper

- **GIVEN** the Telangana catalog, whose offer policy is single papers of INR 100 with INR 100 pre-selected
- **WHEN** an agreement with a legal duty of INR 832 is quoted
- **THEN** the only option is a single INR 100 stamp paper, pre-selected and marked below the duty
- **AND** no INR 832 option is offered

#### Scenario: Karnataka pre-selects the exact duty and still offers an override

- **GIVEN** the Karnataka catalog, whose offer policy is planned over an any-amount e-stamp medium and
  physical paper denominations
- **WHEN** an agreement with a legal duty of INR 1,700 is quoted
- **THEN** the recommended, pre-selected option is INR 1,700 and is not marked below the duty
- **AND** each physical paper denomination below INR 1,700 is offered as a further option marked below
  the duty

#### Scenario: A value not in the options is rejected

- **WHEN** checkout is requested with a stamp value that is not one of the recomputed options
- **THEN** the request is refused with a validation error
- **AND** no payment order is created

### Requirement: Choosing below the legal duty requires an audited acknowledgement

The system SHALL require an explicit acknowledgement, bound to the current warning version, before accepting a stamp option below the legal duty.

The warning SHALL state that an under-stamped instrument is inadmissible in evidence until the
deficient duty and any penalty are paid. The system SHALL persist, for each accepted below-duty
choice, the agreement, the payment order, the legal duty, the chosen value, the warning version, the
acting identity and the time. That record SHALL NOT contain party names, contact details or the
address. Choosing any option at or above the duty SHALL require no acknowledgement; a pre-selected
option that is below the duty SHALL still require it.

#### Scenario: Below-duty choice without acknowledgement is refused

- **WHEN** checkout is requested with an option below the legal duty and no acknowledgement
- **THEN** the request is refused
- **AND** no payment order is created

#### Scenario: A stale warning version is refused

- **WHEN** checkout is requested with a below-duty option acknowledged against a warning version that
  is not current
- **THEN** the request is refused and the customer is shown the current warning

#### Scenario: An acknowledged below-duty choice is recorded

- **WHEN** checkout succeeds with an acknowledged below-duty option
- **THEN** an acknowledgement record exists with the legal duty, the chosen value, the warning version,
  the acting identity and the time
- **AND** the record contains no party name, contact or address

### Requirement: The stamp choice is fixed once an order exists

Once a payment order exists for an agreement, the system SHALL keep that order's frozen stamp choice and SHALL NOT create a different order for a different choice while that order is outstanding or settled.

#### Scenario: Resuming checkout returns the original choice

- **GIVEN** an outstanding order created with a stamp value of INR 440
- **WHEN** checkout is started again with a stamp value of INR 100
- **THEN** the outstanding order with its INR 440 stamp value is returned
- **AND** no second order is created

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

### Requirement: The customer is shown the saved key terms before paying

The stamp-quote step SHALL show, above the stamp quote, a summary of the agreement's key terms as the server has them stored when the step opens: the property address, monthly rent, security deposit, start date, end date, the term in months as stored, and each party's name with their role.

The summary SHALL be read from the stored agreement for the step's agreement id each time the step
opens. It SHALL NOT be read from the capture form's on-screen values, from the browser-stored draft or
from a copy held by the screen behind the step. The summary SHALL show only the fields listed above.
No other party detail (email, mobile number, address, father's name) and no other captured value
SHALL appear in it.

The summary SHALL load independently of the quote. While the stored terms are loading, the step SHALL
say so, and the action to continue to payment SHALL be disabled. If the stored agreement cannot be
read, the step SHALL show an error in place of the summary and SHALL offer no stamp option and no way
to continue to payment. The step's Back action SHALL stay available in every state.

The summary SHALL tell the customer to go back before paying if anything in it is wrong. It SHALL NOT
offer a way to change the terms from the step itself.

#### Scenario: The summary shows the stored terms, not the on-screen ones

- **GIVEN** an agreement stored with a monthly rent of ₹25,000
- **AND** the capture form's working values show a monthly rent of ₹24,000
- **WHEN** the customer reaches the stamp-quote step for that agreement
- **THEN** the summary shows a monthly rent of ₹25,000
- **AND** it shows the stored security deposit, start and end dates, term in months, property address
  and each party's name and role

#### Scenario: Only the listed fields are shown

- **WHEN** the summary is shown for an agreement whose parties have an email, mobile number, address
  and father's name on record
- **THEN** none of those details appear anywhere on the step

#### Scenario: Payment waits for the summary

- **GIVEN** the stamp quote has loaded but the stored agreement has not yet been read
- **WHEN** the customer looks at the step
- **THEN** the step says the saved terms are loading
- **AND** the continue-to-payment action is disabled
- **AND** when the stored agreement is then read, the summary appears and the action becomes enabled

#### Scenario: Unreadable terms block payment

- **WHEN** reading the stored agreement fails
- **THEN** the step shows an error in place of the summary
- **AND** it shows no stamp option and no continue-to-payment action
- **AND** the Back action is still available

#### Scenario: A wrong term sends the customer back, not into an edit

- **WHEN** the summary is shown
- **THEN** it asks the customer to go back before paying if anything is wrong
- **AND** the step offers no control that changes the agreement's terms

