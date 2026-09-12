## Purpose

Recovering access to a paid, unowned agreement for a customer who drafted and paid anonymously
and no longer holds the agreement's unguessable identifier. A link carrying that identifier is
emailed to a signer address on file -- unprompted at payment, and again on request. The
tracking reference selects the agreement but never grants access to it. Recovery restores what
the customer already had; it grants no capability they did not hold before closing the tab.

## ADDED Requirements

### Requirement: A tracking reference alone SHALL NOT grant access to an agreement

The system SHALL NOT return agreement data, or any field of it, in response to a request that
presents only a tracking reference. Possession of a reference SHALL remain non-authorising, as
the reference carries materially less entropy than the agreement identifier and appears in
documents, emails, and support conversations.

Access SHALL be granted only through a link delivered out of band to a contact address already
recorded for that agreement.

#### Scenario: A valid reference returns no agreement data

- **WHEN** a recovery request presents a well-formed reference for an existing paid agreement
- **THEN** the response contains no agreement fields, no identifier, and no indication that
  the reference matched

#### Scenario: Enumeration yields no signal

- **WHEN** recovery requests are submitted for many references, some existing and some not
- **THEN** every response is identical in body, status, and timing characteristics, so
  existence cannot be inferred

### Requirement: The recovery link is emailed at payment confirmation

On payment being confirmed by the server, the system SHALL send a recovery link to every party
on that agreement, at a contact recorded for them on an enabled channel, without the customer
having to request it.

This SHALL be the primary means by which a customer retains access. The reference-entry path
SHALL exist as a fallback for a customer who no longer has that email.

#### Scenario: Payment confirmation sends the link unprompted

- **WHEN** payment for an agreement is confirmed by the server
- **THEN** a recovery link is emailed to a signer address on file, with no customer action

#### Scenario: A customer who never visits the recovery page is still covered

- **WHEN** a customer pays anonymously and closes the browser immediately
- **THEN** the emailed link already in their inbox restores access to that agreement

### Requirement: The recovery link carries the agreement identifier and does not expire

The link SHALL carry the agreement identifier and SHALL itself constitute access. There SHALL
be no separate credential to redeem, and the link SHALL NOT expire on a timer.

Opening the link SHALL make the agreement accessible through the same means that serve any
unowned agreement to a caller holding its identifier.

#### Scenario: The link works after an arbitrary delay

- **WHEN** a recovery link is opened long after it was emailed
- **THEN** the agreement is accessible, provided it remains paid and unowned

#### Scenario: The link resolves without a redemption step

- **WHEN** a recovery link is opened
- **THEN** the agreement is reached directly, with no intermediate credential exchange

### Requirement: Claiming an agreement revokes every emailed link

When an agreement acquires an owning identity, previously emailed links SHALL cease to grant
access, because anonymous access to a claimed agreement is refused.

The system SHALL tell the customer this, at the confirmation view and in the email, so that
signing in is understood as the way to end anonymous link access.

#### Scenario: A claimed agreement refuses a previously working link

- **WHEN** an agreement is claimed into an account and a link emailed earlier is opened
- **THEN** access is refused

#### Scenario: The revocation is disclosed to the customer

- **WHEN** the confirmation view is shown or a recovery email is generated
- **THEN** it states that signing in and saving the agreement ends access by link

### Requirement: Recovery is offered only for paid, unowned agreements

The system SHALL email a recovery link on request only when the referenced agreement has a
payment state of `PAID` or `WAIVED`, and only while the agreement has no owning identity.

#### Scenario: An unpaid agreement is not recoverable

- **WHEN** a recovery request names an agreement whose payment state is `UNPAID`
- **THEN** no mail is sent, and the response is indistinguishable from the paid case

#### Scenario: A claimed agreement is not recoverable anonymously

- **WHEN** a recovery request names an agreement that has an owning identity
- **THEN** no mail is sent, and the response is unchanged from every other outcome

### Requirement: The link is sent to every party, only at contacts already on file

The system SHALL send the recovery link to **every** party on the agreement, at a contact
already recorded for them on an enabled delivery channel. It SHALL NOT restrict delivery to the
party who paid.

The system SHALL NOT accept a destination from the requester, and SHALL NOT disclose to any
caller which parties were contacted or at which addresses.

The message SHALL contain the tracking reference, the link, and the revocation notice, and
SHALL NOT contain party names, the property address, rent, deposit, or any other agreement
content.

#### Scenario: Every party receives the link

- **WHEN** a recovery link is sent for an agreement with an owner and a tenant
- **THEN** both parties receive it at their own contact on an enabled channel

#### Scenario: A requester-supplied destination is ignored

- **WHEN** a recovery request carries a contact address in its body
- **THEN** the value is ignored and delivery goes only to contacts on file

#### Scenario: The message does not disclose agreement contents

- **WHEN** a recovery message is generated
- **THEN** it contains the reference, the link, and the revocation notice only, and no party,
  property, or money detail appears in it

#### Scenario: No contact on file means no recovery

- **WHEN** the referenced agreement has no party contactable on an enabled channel
- **THEN** nothing is sent, and the response is identical to the successful case

### Requirement: Any party may restore access and complete fulfilment

Any party holding a recovery link SHALL be able to restore access and drive the remaining
fulfilment steps to completion. The system SHALL NOT restrict continuation to the party who
paid, and SHALL NOT require that the continuing party be the one who drafted the agreement.

This SHALL NOT extend to signing on another party's behalf: each signature remains an
individual authenticated act by that signer.

#### Scenario: A non-paying party continues fulfilment

- **WHEN** a party who did not pay opens a recovery link
- **THEN** they can carry out the remaining fulfilment steps

#### Scenario: Continuing does not sign for others

- **WHEN** a party continues fulfilment through a recovery link
- **THEN** every other party's signature is still required from that party individually

#### Scenario: Claiming by one party retires the links of the others

- **WHEN** one party claims the agreement into an account
- **THEN** links held by other parties no longer grant access, and the message shown explains
  that the agreement is now held in an account

### Requirement: Recovered access permits continuation but not alteration of terms

A customer who opens a recovery link SHALL be able to view the agreement, obtain its document,
and continue through the remaining fulfilment steps to completion. They SHALL NOT be able to
alter the agreement's terms or its parties.

Opening a link SHALL NOT change the agreement's ownership or its payment state, and SHALL NOT
make any other agreement reachable.

#### Scenario: Fulfilment can be completed

- **WHEN** an agreement is reached through a recovery link
- **THEN** the remaining fulfilment steps can be carried out

#### Scenario: Terms cannot be edited

- **WHEN** a caller holding only a recovery link attempts to change terms or parties
- **THEN** the change is refused

#### Scenario: Recovery does not change ownership

- **WHEN** an agreement is reached through a recovery link
- **THEN** it remains unowned and remains claimable into an account afterwards

### Requirement: The agreement identifier is kept out of referrer headers and telemetry

Because the identifier travels in a link, the system SHALL prevent its disclosure through
cross-origin referrer headers on the landing route, and SHALL NOT transmit it to analytics or
error-reporting destinations.

#### Scenario: Outbound navigation does not leak the identifier

- **WHEN** a customer navigates from the recovery landing route to an external site
- **THEN** the identifier is not disclosed in the referrer

#### Scenario: Telemetry does not carry the identifier

- **WHEN** an error or analytics event is emitted from a recovered session
- **THEN** the payload contains no agreement identifier

### Requirement: The recovery request endpoint is rate limited and audited

The system SHALL limit the rate of recovery requests per source and per reference, and SHALL
apply a lockout after repeated failures. Rate limiting SHALL NOT change the shape of the
response in a way that reveals whether a reference exists.

Every recovery request SHALL produce an audit record capturing the reference, the outcome, and
the time. Logs and audit records SHALL record recipient addresses only in redacted form.

#### Scenario: Repeated requests are throttled

- **WHEN** recovery requests exceed the configured rate from one source
- **THEN** further requests are refused for a lockout period

#### Scenario: Throttling is not an oracle

- **WHEN** a throttled request is answered
- **THEN** its response is identical in shape to an unthrottled one

#### Scenario: Recipients are redacted in audit output

- **WHEN** any recovery request is processed
- **THEN** recipient addresses appear redacted in every log line and audit record

### Requirement: Recovery fails closed when it cannot be delivered

Where no email provider is configured, the system SHALL send no recovery mail. The response to
the requester SHALL remain unchanged; the condition SHALL be visible to operators through logs
and audit records only.

#### Scenario: A stub mail provider sends nothing

- **WHEN** recovery is requested in an environment with no real mail provider configured
- **THEN** no mail is attempted and the caller sees the standard response
