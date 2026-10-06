## ADDED Requirements

### Requirement: Policy texts state the stamp channel per state

The terms of service and the privacy policy SHALL describe how the stamp on an agreement is
obtained per state, as ops actually buys it:
- for Karnataka, an e-stamp certificate bought through the Stock Holding Corporation of India
  (SHCIL);
- for Telangana, which SHCIL does not serve, physical non-judicial stamp paper bought from a
  licensed stamp vendor.

Neither text SHALL describe one medium or one channel as the way every stamp is bought.
"Certificate" SHALL appear only as part of "e-stamp certificate". Neither text SHALL say a stamp
is bought on a portal.

The privacy policy SHALL name both stamp recipients by role, not by company: the "Karnataka
e-stamp issuer" and the "Telangana licensed stamp vendor". It SHALL say what each receives,
worded as non-exhaustive where the form's fields are not confirmed, and how each keeps it: the
issuer under its own rules, where it can be looked up by e-stamp certificate number, and the
vendor in its own register under the state's rules. Only the terms SHALL name SHCIL.

Terms-of-service §8 SHALL state, for a Telangana agreement, that the paper original of the stamp
is kept for one year from purchase and then shredded, that the attached scan is not the paper
itself, and that a customer who asks within that year can have it sent. It SHALL NOT promise free
delivery. The privacy policy SHALL disclose that the original is kept for one year, and SHALL name
a courier as a recipient only for a delivery the customer asks for.

The counsel gap of terms-of-service §8 SHALL include two questions: whether stamp paper bought
separately and attached to an electronically signed agreement stamps it validly, and whether the
paper original must accompany the agreement.

#### Scenario: The stamp-duty clause names the channel per state

- **GIVEN** the terms-of-service clause with id `stamp-duty`
- **WHEN** its body is read
- **THEN** it ties "SHCIL" and "e-stamp certificate" to Karnataka, and "non-judicial stamp paper" and "licensed stamp vendor" to Telangana
- **AND** its gap asks counsel whether stamp paper attached to an electronically signed agreement stamps it validly

#### Scenario: The Telangana paper original is accounted for

- **GIVEN** the terms-of-service clause with id `stamp-duty` and the privacy policy
- **WHEN** they are read
- **THEN** §8 says the Telangana paper original is kept for one year and then shredded, and can be sent on request within that year
- **AND** the privacy policy's collected-data clause says the paper original is kept for one year
- **AND** the privacy recipient roles include a courier used only when the customer asks for the original

#### Scenario: No text assumes one medium

- **GIVEN** every clause body and gap of the terms of service and the privacy policy, and the privacy policy's collected-data categories and recipient roles
- **WHEN** they are searched case-insensitively
- **THEN** "portal" and "government channel" are not found
- **AND** every occurrence of "certificate" is part of "e-stamp certificate"
- **AND** "SHCIL" occurs in the terms only in the clauses with ids `stamp-duty` and `availability-and-support`, and does not occur in the privacy policy

#### Scenario: The privacy policy names both stamp recipients

- **GIVEN** the privacy policy's recipient roles
- **WHEN** they are read
- **THEN** they include "Karnataka e-stamp issuer" and "Telangana licensed stamp vendor"
- **AND** each appears verbatim in the privacy clause that says who receives data, together with how that recipient keeps the data

#### Scenario: The refunds page follows the clause

- **GIVEN** `/refunds` renders the terms-of-service clause with id `refunds`
- **WHEN** the whole page text is searched case-insensitively for "certificate"
- **THEN** it is not found
