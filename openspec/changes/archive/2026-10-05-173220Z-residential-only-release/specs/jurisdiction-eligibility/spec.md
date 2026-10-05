## ADDED Requirements

### Requirement: Chargeability is decided per duty rule, not per state

The system SHALL decide whether an agreement may be charged from the duty rule that quotes it — the rule for the agreement's state **and usage** — so a chargeable rule for one usage SHALL NOT make another usage in the same state chargeable.

The eligible-jurisdiction list served to the picker is per state (a state is listed when any of its
rules is chargeable); it is disclosure only. Order placement and checkout SHALL evaluate the
agreement's own rule, and so SHALL e-stamp intake and eSign initiation for an agreement that is not
already paid with a frozen stamp quote (a paid agreement passes on its frozen quote, per "Paid
fulfilment requires a chargeable, quotable duty rule"). A state listed as eligible can therefore still
refuse an agreement whose usage has no chargeable rule.

#### Scenario: A reviewed residential rule does not admit commercial in the same state

- **GIVEN** unreviewed rules are not allowed by configuration
- **AND** a state's residential rule carries a counsel review matching its content hash
- **AND** that state's commercial rule carries no counsel review
- **WHEN** the residential and commercial rules are checked for chargeability
- **THEN** the residential rule is chargeable and the commercial rule is not
- **AND** the state is in the eligible-jurisdiction list

#### Scenario: A commercial order is refused when unreviewed rules are disallowed

- **GIVEN** unreviewed rules are not allowed by configuration
- **AND** a TG commercial agreement drafted through the API
- **WHEN** its stamp quote is requested
- **THEN** the quote reports that the rule is not chargeable
- **AND WHEN** its order is placed, and when checkout is requested for it
- **THEN** each is refused with `409 JURISDICTION_UNSUPPORTED`

### Requirement: Commercial is withheld from paid fulfilment for the residential-only release

Every shipped stamp duty rule whose usage is commercial SHALL NOT be reviewed — it carries no counsel review matching its content hash — while commercial templates are withheld from the picker, so that with unreviewed rules disallowed no new commercial order is admitted to paid fulfilment.

This governs orders placed after unreviewed rules are disallowed. A commercial agreement already paid
with a frozen stamp quote before then passes fulfilment on that quote; the release removes any such
order operationally rather than through this rule.

Adding a matching counsel review to a commercial rule is the act that brings commercial back. It SHALL
fail a test that names the picker rule and FAQ wording to lift alongside it, so the review and the
un-hiding land together rather than leaving a payable usage that no customer can select.

#### Scenario: Shipped commercial rules are unreviewed

- **WHEN** the shipped stamp duty rules are loaded, test fixtures excluded
- **THEN** the states holding a commercial rule are exactly TG and KA
- **AND** neither commercial rule is reviewed
