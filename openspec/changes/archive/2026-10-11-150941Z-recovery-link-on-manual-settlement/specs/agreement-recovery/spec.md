## RENAMED Requirements

- FROM: `### Requirement: The recovery link is emailed at payment confirmation`
- TO: `### Requirement: The recovery link is emailed when payment is settled`

## MODIFIED Requirements

### Requirement: The recovery link is emailed when payment is settled

The system SHALL send a recovery link to every party on an unowned agreement whose payment becomes
settled — that is, whose payment state moves out of `UNPAID` to either `PAID` or `WAIVED` — at
a contact recorded for them on an enabled channel, without the customer having to request it.

This SHALL hold for **every** path that settles payment, including a payment recorded or
waived by a staff member acting out of band. Settlement by a payment gateway SHALL NOT be
privileged over settlement by hand: a customer whose payment was recorded manually SHALL
receive the same link as a customer whose payment the gateway confirmed.

A waiver SHALL send the link. A waived customer never saw a payment screen and is therefore
the least likely party to hold the tracking reference.

The link SHALL be sent once per agreement, on its first transition out of `UNPAID`, and SHALL
NOT be resent for a subsequent write to an agreement that is already settled. Correcting a
mis-recorded payment reference on an already-settled agreement SHALL NOT send a further link.
A payment recorded against an agreement that was already `WAIVED`, by the gateway or by a
staff member, SHALL be recorded and SHALL NOT send a further link. A gateway
confirmation of a payment that staff already recorded by hand under the same reference SHALL
NOT send a further link. A surplus gateway payment SHALL NOT send a link.

The system SHALL NOT send the unprompted link for an agreement that has an owning identity
when the link would be sent. Anonymous access to a claimed agreement is refused, so the link
would not open; its owner reaches it by signing in. This is the same eligibility rule the
reference-entry path applies, and the two SHALL share one definition.

The payment confirmation screen SHALL NOT tell a customer that a link was emailed when the
agreement is saved to their account. It SHALL tell them to open the agreement by signing in.

This SHALL be the primary means by which a customer retains access. The reference-entry path
SHALL exist as a fallback for a customer who no longer has that email.

#### Scenario: Payment confirmation sends the link unprompted

- **WHEN** payment for an unowned `UNPAID` agreement is confirmed by the gateway
- **THEN** a recovery link is emailed to every party at a contact on file, with no customer
  action

#### Scenario: A customer who never visits the recovery page is still covered

- **WHEN** a customer pays anonymously and closes the browser immediately
- **THEN** the emailed link already in their inbox restores access to that agreement

#### Scenario: A staff-recorded payment sends the link

- **WHEN** a staff member records a payment against an unowned `UNPAID` agreement out of band
- **THEN** a recovery link is emailed to every party at a contact on file, exactly as it is
  for a payment settled by the gateway

#### Scenario: A waiver sends the link

- **WHEN** a staff member waives payment for an unowned `UNPAID` agreement
- **THEN** a recovery link is emailed to every party at a contact on file

#### Scenario: Correcting a reference on a settled agreement sends nothing further

- **WHEN** a payment is recorded a second time against an agreement that is already `PAID`,
  to correct a mistyped reference
- **THEN** the corrected reference is stored and no further recovery link is sent

#### Scenario: A payment after a waiver is recorded without a second link

- **GIVEN** an agreement whose payment was waived, for which the link was sent at the waiver
- **WHEN** a gateway payment for it is confirmed, or a staff member records a payment for it
- **THEN** the agreement becomes `PAID` with that payment recorded
- **AND** no further recovery link is sent

#### Scenario: A surplus payment sends nothing

- **WHEN** a gateway payment is confirmed for an agreement that is already `PAID` under
  another reference
- **THEN** no recovery link is sent

#### Scenario: A gateway confirmation of a payment staff already recorded sends nothing further

- **GIVEN** an `UNPAID` agreement for which staff recorded a payment by hand under a gateway
  payment id, and the link was sent at that moment
- **WHEN** the gateway confirms the payment order carrying that payment id
- **THEN** no further recovery link is sent

#### Scenario: Settling a claimed agreement sends no link

- **GIVEN** an `UNPAID` agreement that has an owning identity
- **WHEN** its payment is settled by the gateway, recorded by staff, or waived
- **THEN** no recovery link is sent

#### Scenario: A refused staff confirmation sends nothing

- **WHEN** a staff member records a payment under a reference already recorded against another
  agreement, and the confirmation is refused
- **THEN** the agreement stays `UNPAID` and no recovery link is sent

#### Scenario: The confirmation screen does not promise a link for an agreement saved to an account

- **GIVEN** a signed-in customer whose agreement is saved to their account
- **WHEN** their payment is confirmed and the confirmation screen is shown
- **THEN** the screen does not say a link was emailed
- **AND** it says the agreement is in their account and is opened by signing in
