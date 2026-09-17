## Purpose

The customer's view of stamp duty before paying: the quote, the bounded set of stamp values they may
buy with a recommended default, the audited acknowledgement required to buy less than the legal duty,
and fixing that choice once an order exists.

## ADDED Requirements

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
