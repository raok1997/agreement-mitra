## RENAMED Requirements

- FROM: `### Requirement: The recovery link is emailed at payment confirmation`
- TO: `### Requirement: The recovery link is emailed when payment is settled`

## MODIFIED Requirements

### Requirement: The recovery link is emailed when payment is settled

The system SHALL send a recovery link to every party on an agreement whose payment becomes
settled — that is, whose payment state moves out of `UNPAID` to either `PAID` or `WAIVED` — at
a contact recorded for them on an enabled channel, without the customer having to request it.

This SHALL hold for **every** path that settles payment, including a payment recorded or
waived by a staff member acting out of band. Settlement by a payment gateway SHALL NOT be
privileged over settlement by hand: a customer who paid by bank transfer and was settled
manually SHALL receive the same link as a customer who paid by card.

A waiver SHALL send the link. A waived customer never saw a payment screen and is therefore
the least likely party to hold the tracking reference.

The link SHALL be sent once per settlement, on the transition itself, and SHALL NOT be resent
for a subsequent write to an agreement that is already settled. Correcting a mis-recorded
payment reference on an already-settled agreement SHALL NOT send a further link.

This SHALL be the primary means by which a customer retains access. The reference-entry path
SHALL exist as a fallback for a customer who no longer has that email.

#### Scenario: Payment confirmation sends the link unprompted

- **WHEN** payment for an agreement is confirmed by the server
- **THEN** a recovery link is emailed to a signer address on file, with no customer action

#### Scenario: A customer who never visits the recovery page is still covered

- **WHEN** a customer pays anonymously and closes the browser immediately
- **THEN** the emailed link already in their inbox restores access to that agreement

#### Scenario: A staff-recorded payment sends the link

- **WHEN** a staff member records a payment against an `UNPAID` agreement out of band
- **THEN** a recovery link is emailed to every party at a contact on file, exactly as it is
  for a payment settled by the gateway

#### Scenario: A waiver sends the link

- **WHEN** a staff member waives payment for an `UNPAID` agreement
- **THEN** a recovery link is emailed to every party at a contact on file

#### Scenario: Correcting a reference on a settled agreement sends nothing further

- **WHEN** a payment is recorded a second time against an agreement that is already settled,
  to correct a mistyped reference
- **THEN** the corrected reference is stored and no further recovery link is sent
