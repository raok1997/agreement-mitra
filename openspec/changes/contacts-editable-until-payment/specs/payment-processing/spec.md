## MODIFIED Requirements

### Requirement: Confirmed contacts are saved against the agreement

The system SHALL persist confirmed party contacts against the agreement, through a route that
accepts contact details and nothing else.

That route SHALL be usable by an anonymous caller holding the agreement identifier, and SHALL also
be usable by the agreement's **own owner** when they are authenticated. It SHALL refuse an
agreement owned by somebody else. It SHALL NOT accept any other agreement field, and SHALL NOT
change the party list.

Contacts SHALL remain changeable **after the order is placed and until payment is confirmed**, and
SHALL be refused once the agreement is paid or its payment is waived, or once the agreement is
closed. Placing the order SHALL NOT by itself freeze them.

Contacts are not terms: they do not appear in the rendered agreement, so changing one SHALL NOT
alter, re-render, or unpin the document the parties were shown. The terms freeze is unaffected --
rent, dates, the property address and the party list SHALL remain frozen from the moment the order
is placed.

Payment is the boundary because every artifact addressed **to** a party's contact -- the recovery
link, the purchased e-stamp, the signing invitation -- is produced at or after payment. Changing an
address once any of those exists would leave a message in one mailbox and a different address
expecting it. Before payment none of them exists, so a correction costs nothing and rescues a
customer who mistyped their own address.

A refusal because the agreement is already paid SHALL be distinguishable by the client from a
refusal because the terms are frozen, so that a client can state the actual reason rather than
inviting a retry that can never succeed.

An owner SHALL NOT be locked out of the step every other caller can complete: the contacts step is
the same screen whether the customer signed in or not, so refusing the owner would strand a
signed-in customer with no way to save contacts and no way to receive the draft, which is sent
immediately afterwards.

A refusal for an agreement owned by somebody else SHALL be indistinguishable from a refusal for an
unknown agreement, so that ownership cannot be probed through this route.

Stored contacts SHALL be the record used for subsequent notification, signing invitations, and
delivery of the signed agreement.

#### Scenario: An anonymous caller saves contacts

- **WHEN** an anonymous caller holding the agreement identifier submits contacts for an unowned
  agreement
- **THEN** the contacts are stored against that agreement

#### Scenario: The owner saves contacts on their own agreement

- **WHEN** an authenticated caller submits contacts for an agreement they own
- **THEN** the contacts are stored against that agreement, and the draft is sent to the parties as
  it is for an anonymous caller

#### Scenario: Only contacts can be changed

- **WHEN** a contacts request also carries rent, dates, the property address, or a party list
- **THEN** those values are ignored and only contacts are updated

#### Scenario: An agreement owned by somebody else is refused

- **WHEN** contacts are submitted for an agreement owned by a different identity, whether the
  caller is anonymous or authenticated as somebody else
- **THEN** the request is refused

#### Scenario: A mistyped address is corrected after the order is placed

- **WHEN** contacts are submitted for an agreement that has been finalised but not yet paid
- **THEN** the contacts are stored against that agreement

#### Scenario: The corrected address receives the agreement

- **WHEN** a party's email is changed on an unpaid agreement
- **THEN** the current draft agreement is sent to the party at the new address

#### Scenario: Contacts are frozen once payment is confirmed

- **WHEN** contacts are submitted for an agreement that is already paid, or whose payment has been
  waived
- **THEN** the request is refused and no contact is changed

#### Scenario: Contacts are frozen on a closed agreement

- **WHEN** contacts are submitted for an agreement that has been closed
- **THEN** the request is refused and no contact is changed

#### Scenario: The paid refusal is distinguishable from the terms freeze

- **WHEN** a contacts change is refused because the agreement is already paid
- **THEN** the refusal identifies that condition distinctly from a refusal caused by the terms
  being frozen

#### Scenario: Placing the order does not freeze contacts

- **WHEN** an order is placed for an agreement and no payment has been confirmed
- **THEN** a subsequent contacts change is accepted

#### Scenario: Changing a contact leaves the document untouched

- **WHEN** a party's contact is changed on an unpaid, finalised agreement
- **THEN** the rendered agreement the parties were shown is unchanged and stays pinned
