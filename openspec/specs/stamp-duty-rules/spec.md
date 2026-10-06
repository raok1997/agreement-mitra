# stamp-duty-rules Specification

## Purpose
Which jurisdictions have seeded stamp duty rules and stamp paper catalogs, where each figure came from
and how sure we are of it, and the counsel-review gate that decides whether a rule may be used to
charge a customer.
## Requirements
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

### Requirement: Karnataka lease duty is seeded for residential and commercial use

The system SHALL provide stamp duty rules for Karnataka leases, one each for residential and
commercial usage, computed under the Karnataka Stamp Act 1957 Schedule Article 30(1) ("Lease of
immoveable property").

The seeded rules SHALL encode, subject to the verification marker below:

| Term | Residential | Commercial |
|---|---|---|
| 1-12 months | 0.5% of (average annual rent + refundable deposit), capped at INR 500 | 0.5% of (average annual rent + refundable deposit), uncapped |
| 13-60 months | 1% of (average annual rent + refundable deposit) | 1% of (average annual rent + refundable deposit) |
| 61-120 months | -- (beyond the rental template's range) | 1% of (average annual rent + refundable deposit) |
| 121-240 months | -- | 2% of (average annual rent + refundable deposit) |

Two properties of this table distinguish Karnataka from Telangana and SHALL be preserved:

- The consideration is the **average annual rent** on every slab, including the slab for terms of one
  year or less. It is never the total rent over the term.
- Above one year the rate does **not** depend on usage: residential and commercial are both 1% up to
  ten years. The usage split exists only in the first slab, and there only as the INR 500 cap.

The INR 500 maximum SHALL apply to the residential first slab alone and SHALL NOT bound any other
slab of that rule.

Registration SHALL be reported as required when the term exceeds twelve months, reflecting the
unamended Registration Act 1908 s.17(1)(d) as it applies in Karnataka -- unlike Telangana, where a
state amendment makes every lease registrable.

A term outside a rule's slabs SHALL be Unsupported rather than approximated.

#### Scenario: An 11-month residential lease where the cap binds

- **GIVEN** a Karnataka residential agreement of 11 months at INR 20,000 per month with a refundable
  deposit of INR 100,000, executed while the rule is in effect
- **WHEN** it is quoted
- **THEN** the average annual rent is INR 240,000 and the consideration is INR 340,000
- **AND** the computed 0.5% duty of INR 1,700 is reduced to the slab maximum of INR 500
- **AND** registration is reported as not required

#### Scenario: A short residential lease where the cap does not bind

- **GIVEN** a Karnataka residential agreement of 12 months at INR 5,000 per month with no deposit
- **WHEN** it is quoted
- **THEN** the legal duty is INR 300 (0.5% of an average annual rent of INR 60,000)

#### Scenario: The residential cap does not leak into the next slab

- **GIVEN** a Karnataka residential agreement of 13 months at INR 5,000 per month with no deposit
- **WHEN** it is quoted
- **THEN** the legal duty is INR 600 (1% of an average annual rent of INR 60,000)
- **AND** the duty is not reduced to INR 500

#### Scenario: A one-year commercial lease is uncapped

- **GIVEN** a Karnataka commercial agreement of 12 months at INR 20,000 per month with a refundable
  deposit of INR 100,000
- **WHEN** it is quoted
- **THEN** the legal duty is INR 1,700 (0.5% of INR 340,000), with no maximum applied

#### Scenario: A ten-year commercial boundary

- **GIVEN** a Karnataka commercial agreement of 120 months at INR 50,000 per month with no escalation
  and a refundable deposit of INR 300,000
- **WHEN** it is quoted
- **THEN** the legal duty is INR 9,000 (1% of an average annual rent of INR 600,000 plus the deposit)

#### Scenario: Beyond ten years the commercial rate doubles

- **GIVEN** the same commercial agreement at 121 months
- **WHEN** it is quoted
- **THEN** the legal duty is INR 18,000 (2% of the same INR 900,000 consideration)

#### Scenario: Registration follows the twelve-month threshold

- **WHEN** Karnataka agreements of 12 and 13 months are quoted
- **THEN** registration is reported as not required for the 12-month agreement
- **AND** registration is reported as required for the 13-month agreement

#### Scenario: A residential term beyond the seeded slabs

- **WHEN** a Karnataka residential agreement of 61 months is quoted
- **THEN** the outcome is Unsupported

#### Scenario: A commercial term beyond the seeded slabs

- **WHEN** a Karnataka commercial agreement of 241 months is quoted
- **THEN** the outcome is Unsupported

### Requirement: Karnataka stamp paper is catalogued as any-amount e-stamp and physical paper

The system SHALL provide a Karnataka stamp paper catalog declaring an e-stamp medium that issues any
amount, alongside physical non-judicial stamp paper in fixed denominations.

The Karnataka catalog SHALL declare the **planned** offer policy -- the default -- so that the
recommended, pre-selected option is the exact legal duty and the lower denominations remain available
as acknowledged overrides. It SHALL NOT declare the single-papers policy Telangana uses, because that
policy exists only to model a jurisdiction that cannot issue a certificate for an arbitrary amount.

#### Scenario: The Karnataka offer policy

- **WHEN** the Karnataka catalog is loaded
- **THEN** its offer policy is planned
- **AND** it declares a medium that can be planned for any amount

#### Scenario: A duty no physical paper can reach is still plannable

- **WHEN** a Karnataka agreement with a legal duty of INR 1,700 is quoted
- **THEN** the e-stamp plan totals exactly INR 1,700
- **AND** the recommended stamp value is INR 1,700 rather than a shortfall

### Requirement: The Karnataka figures are seeded unverified and are not chargeable by default

Every Karnataka duty figure, threshold and denomination SHALL be recorded as unverified, carrying its
source and a per-figure confidence marker, with no counsel review attached -- so that Karnataka is
refused for paid fulfilment unless unreviewed rules are explicitly allowed.

The recorded provenance SHALL state that the primary source consulted is self-dated to 2017 and that
a later amending Act may have moved the Article 30 rates, so that whoever performs the counsel review
knows the first thing to check rather than re-deriving it.

#### Scenario: Karnataka is not chargeable without counsel review

- **GIVEN** a deployment that does not allow unreviewed rules
- **WHEN** paid fulfilment is attempted for a Karnataka agreement
- **THEN** it is refused as an ineligible jurisdiction
- **AND** drafting, previewing and downloading the same agreement continue to work

#### Scenario: Karnataka is chargeable where unreviewed rules are allowed

- **GIVEN** a deployment that allows unreviewed rules
- **WHEN** the chargeable jurisdictions are listed
- **THEN** Karnataka is among them

#### Scenario: The provenance names the open verification question

- **WHEN** the Karnataka rule provenance is read
- **THEN** it identifies the consulted source and its 2017 vintage
- **AND** it names the later amending Act as the first thing a reviewer must check

