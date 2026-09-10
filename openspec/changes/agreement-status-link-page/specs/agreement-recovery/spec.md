## MODIFIED Requirements

### Requirement: Recovered access permits continuation but not alteration of terms

A customer who opens a recovery link SHALL be able to view the agreement's status, obtain its
document, and continue through the remaining fulfilment steps to completion.

> NOTE: the requirement name is retained verbatim so this delta matches its source at archive
> time. The name no longer describes the rule -- alteration of terms IS now permitted before
> stamping. Rename the requirement to "Recovered access permits continuation, and correction
> of terms before stamping" in a follow-up once `post-payment-continuity` is archived.

They SHALL additionally be able to **correct the agreement's terms and its parties while no
stamp is attached**. Once a stamp is attached the terms SHALL be frozen and any such attempt
SHALL be refused.

This is a deliberate widening of the link's capability: the identifier the link carries is
accepted as authority to correct the agreement, not merely to read it. It SHALL be bounded --
the link SHALL NOT confer the ability to change the agreement's ownership, its payment state,
its stamp information, or its tracking reference, and SHALL NOT make any other agreement
reachable. Opening a link SHALL NOT by itself change ownership or payment state.

Where the agreement has been claimed into an account, the link SHALL confer nothing: read and
edit alike SHALL be refused, indistinguishably from an unknown agreement.

#### Scenario: Fulfilment can be completed

- **WHEN** an agreement is reached through a recovery link
- **THEN** the remaining fulfilment steps can be carried out

#### Scenario: Terms can be corrected before the stamp

- **WHEN** a caller holding only a recovery link changes terms or parties on an agreement with
  no stamp attached
- **THEN** the change is accepted and the document is regenerated from the corrected terms

#### Scenario: Terms cannot be edited once stamped

- **WHEN** a caller holding only a recovery link attempts to change terms or parties on an
  agreement whose stamp is attached
- **THEN** the change is refused

#### Scenario: The link confers no authority beyond the agreement's terms

- **WHEN** a caller holding only a recovery link attempts to change the agreement's ownership,
  payment state, stamp information, or tracking reference
- **THEN** the attempt has no effect on those values

#### Scenario: Recovery does not change ownership

- **WHEN** an agreement is reached through a recovery link
- **THEN** it remains unowned and remains claimable into an account afterwards

#### Scenario: A claimed agreement refuses the link for editing as well as reading

- **GIVEN** an agreement claimed into an account
- **WHEN** a caller holding only the link attempts to read or to edit it
- **THEN** both are refused, indistinguishably from an unknown agreement

## ADDED Requirements

### Requirement: Terms editing through a recovery link is rate limited

Because the link is a durable bearer credential sitting in a mailbox, the system SHALL limit the
rate at which terms edits are accepted per source and per agreement, and SHALL refuse further
attempts beyond the configured limit for a lockout period.

Rate limiting SHALL NOT change the shape of the response in a way that reveals whether an
agreement exists or who owns it.

#### Scenario: Repeated edits are throttled

- **WHEN** terms edits through a link exceed the configured rate from one source
- **THEN** further edits are refused for a lockout period

#### Scenario: Throttling is not an oracle

- **WHEN** a throttled edit is answered
- **THEN** its response reveals nothing about whether the agreement exists or who owns it
