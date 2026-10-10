## ADDED Requirements

### Requirement: A manual confirmation is refused as a reused reference only for the reference rule
The system SHALL refuse a manual payment confirmation as a reused payment reference only when the rule that one payment reference is recorded against at most one agreement is what refused it, and SHALL report any other refusal by the database as a server error.

A manual confirmation that fails this way records nothing: the agreement's payment state, amount,
reference, actor and time are unchanged. The error response SHALL NOT carry the database's own
message, the refused rule's name, or the reference.

#### Scenario: A reused reference is still refused as a conflict
- **GIVEN** a payment reference already recorded against one agreement
- **WHEN** a STAFF user records it against another agreement
- **THEN** the request is refused as a reused payment reference
- **AND** the second agreement's payment state is unchanged

#### Scenario: Another database refusal is not reported as a reused reference
- **GIVEN** an unpaid agreement
- **WHEN** a STAFF user's payment confirmation is refused by the database for a reason other than the reference rule
- **THEN** the request fails with a server error and not as a reused payment reference
- **AND** the agreement's payment state is unchanged
