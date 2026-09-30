## MODIFIED Requirements

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
