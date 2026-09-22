## Purpose

Which jurisdictions have seeded stamp duty rules and stamp paper catalogs, where each figure came from
and how sure we are of it, and the counsel-review gate that decides whether a rule may be used to
charge a customer.

## ADDED Requirements

### Requirement: Telangana lease duty is seeded for residential and commercial use

The system SHALL provide stamp duty rules for Telangana leases, one each for residential and commercial usage, computed under Indian Stamp Act Schedule I-A Article 31 as applied in Telangana.

The seeded rules SHALL encode, subject to the verification marker below:

| Term | Residential | Commercial |
|---|---|---|
| 1-11 months | 0.4% of (total rent + refundable deposit) | 0.4% of (total rent + refundable deposit) |
| 12-60 months | 0.5% of (average annual rent + refundable deposit) | 1% of (average annual rent + refundable deposit) |
| 61-120 months | -- (beyond the rental template's range) | 2% of (average annual rent + refundable deposit) |
| 121-240 months | -- | 6% of (average annual rent + refundable deposit) |

with no minimum or maximum, rounding up to the whole rupee, counterpart duty of INR 50 per copy
beyond the original, and registration reported as required for every term.

A term outside a rule's slabs SHALL be Unsupported rather than approximated.

#### Scenario: An 11-month residential lease

- **GIVEN** a Telangana residential agreement of 11 months at INR 15,000 per month with a refundable
  deposit of INR 45,000, executed while the rule is in effect
- **WHEN** it is quoted
- **THEN** the legal duty is INR 840 (0.4% of 165,000 + 45,000)
- **AND** registration is reported as required

#### Scenario: A 36-month commercial lease

- **GIVEN** a Telangana commercial agreement of 36 months at INR 50,000 per month with no escalation
  and a refundable deposit of INR 300,000
- **WHEN** it is quoted
- **THEN** the legal duty is INR 9,000 (1% of 600,000 + 300,000)

#### Scenario: A residential term beyond the seeded slabs

- **WHEN** a Telangana residential agreement of 61 months is quoted
- **THEN** the outcome is Unsupported

### Requirement: Telangana stamp paper is catalogued as physical paper and challan

The system SHALL provide a Telangana stamp paper catalog with two media: physical non-judicial stamp paper in denominations of INR 10, 20, 50 and 100 with at most five papers per instrument, and challan for any amount.

The Telangana catalog SHALL declare a single papers offer policy offering only a single INR 100 stamp
paper, pre-selected. Plans for every medium are still computed, but only the policy's options are
offered to the customer.

#### Scenario: The Telangana offer policy

- **WHEN** the Telangana catalog is loaded
- **THEN** its offer policy is single papers, offering INR 100 with INR 100 pre-selected

#### Scenario: Duty within reach of stamp paper

- **WHEN** a Telangana agreement with a legal duty of INR 840 is quoted
- **THEN** the stamp paper plan is unplannable (five papers reach at most INR 500)
- **AND** the challan plan is INR 840

#### Scenario: Duty payable on paper

- **WHEN** a Telangana agreement with a legal duty of INR 440 is quoted
- **THEN** the stamp paper plan is four INR 100 papers and one INR 50 paper, totalling INR 450
- **AND** the challan plan totals INR 440

### Requirement: Every seeded figure carries its source and an unverified marker

Each seeded rule and catalog SHALL cite the source of its figures, and each figure not confirmed from a primary government source SHALL be marked UNVERIFIED in the rule data.

The source record SHALL distinguish a primary source (a Government Order, gazette, or the department's
own publication) from a secondary one (a legal publisher's copy, a commercial website). An open conflict
between sources SHALL be recorded beside the figure it affects.

#### Scenario: A reviewer can see what is unconfirmed

- **WHEN** a reviewer reads the Telangana residential rule
- **THEN** each rate states its source and whether it is confirmed
- **AND** the treatment of the refundable deposit is recorded as an open conflict between sources

### Requirement: Charging requires a counsel-reviewed rule

The system SHALL NOT use a duty rule without a recorded counsel review to admit an agreement to paid fulfilment, unless unreviewed rules have been explicitly allowed by configuration.

The allowance SHALL default to off. When unreviewed rules are allowed, the application SHALL record
that at startup, naming each unreviewed rule in use, so a deployment charging on unreviewed law is
visible rather than silent. A counsel review SHALL be recorded in the rule data against the rule's
content hash, so editing a reviewed rule invalidates its review.

#### Scenario: An unreviewed rule is refused by default

- **GIVEN** the Telangana residential rule has no recorded counsel review
- **AND** unreviewed rules are not allowed
- **WHEN** checkout is started for a Telangana residential agreement
- **THEN** the agreement is refused as an unsupported jurisdiction

#### Scenario: Beta deployments may allow unreviewed rules visibly

- **GIVEN** unreviewed rules are allowed by configuration
- **WHEN** the application starts
- **THEN** the startup log names every unreviewed rule that may be charged

#### Scenario: Editing a reviewed rule invalidates the review

- **GIVEN** a rule whose recorded review names its content hash
- **WHEN** a slab rate in the rule is changed
- **THEN** the recorded review no longer matches the rule's content hash
- **AND** the rule is treated as unreviewed
