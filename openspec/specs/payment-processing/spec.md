# payment-processing Specification

## Purpose

Delta to `payment-processing`: what the customer is shown, and sent, at the moment payment is
confirmed. The existing capability establishes that payment is confirmed by the server and
never by the browser. This delta adds the customer-facing consequence of that confirmation --
a distinct confirmation view, and an emailed link that survives the tab being closed.

## Requirements

### Requirement: Every party is reachable on an enabled delivery channel before an order is created

The system SHALL NOT create a payment order for an agreement unless every signer on it -- each
owner and each tenant -- is reachable on at least one **enabled** delivery channel.

Delivery channels SHALL be a modelled concept with per-channel enablement in configuration, so
that the rule is expressed once and does not change when a channel is switched on or off. A
channel that is not enabled SHALL NOT satisfy this requirement, whatever contact details are
held for it.

The refusal SHALL identify which parties are unreachable and what is needed, so the customer
can correct it. The system SHALL NOT accept contact details as part of the order-creation
request itself; they are agreement data, corrected through the agreement.

This requirement binds at order creation. Drafting, previewing, and saving an agreement SHALL
remain possible without complete contact details, so anonymous self-serve drafting is
unaffected.

#### Scenario: An order is refused when a party is unreachable

- **WHEN** an order is requested for an agreement where any signer has no contact on any
  enabled channel
- **THEN** no order is created and the response identifies the unreachable party and what is
  needed

#### Scenario: A contact on a disabled channel does not satisfy the rule

- **WHEN** a party's only contact detail belongs to a channel that is not enabled
- **THEN** that party counts as unreachable and no order is created

#### Scenario: Drafting is unaffected

- **WHEN** an agreement is created, edited, or previewed without complete contact details
- **THEN** the operation succeeds

#### Scenario: Contact details are not accepted at the payment boundary

- **WHEN** an order-creation request carries signer contact details in its body
- **THEN** the values are ignored and reachability is evaluated from the stored agreement

#### Scenario: A reachable agreement proceeds to checkout

- **WHEN** an order is requested for an agreement where every signer is reachable on an enabled
  channel
- **THEN** the order is created

### Requirement: Contact details are confirmed in a dedicated step before checkout

The system SHALL present a distinct step between finalising an agreement and starting payment,
showing every party and the contact details held for each, and allowing the customer to
complete or correct them before proceeding.

The step SHALL state what each detail is used for. It SHALL NOT present a channel that is not
enabled as a means by which the agreement will be delivered.

Where every party is already reachable, the step SHALL read as a confirmation rather than
demanding re-entry of details already held.

#### Scenario: Missing details are collected before payment begins

- **WHEN** a customer finalises an agreement where a party is unreachable
- **THEN** the confirmation step asks for what is missing before checkout can start

#### Scenario: Complete details are confirmed, not re-entered

- **WHEN** every party is already reachable on an enabled channel
- **THEN** the step presents the details for confirmation and does not require re-entry

#### Scenario: Disabled channels are not offered as delivery routes

- **WHEN** the confirmation step describes how a party will receive the agreement
- **THEN** only enabled channels are described as delivery routes, and details captured for
  disabled channels are not presented as such

#### Scenario: The step is not the enforcement

- **WHEN** an order is requested for an unreachable agreement without passing through the step
- **THEN** the order is still refused by the server

### Requirement: Confirmed contacts are saved against the agreement

The system SHALL persist confirmed party contacts against the agreement, through a route that
accepts contact details and nothing else.

That route SHALL be usable by an anonymous caller holding the agreement identifier, and SHALL also
be usable by the agreement's **own owner** when they are authenticated. It SHALL refuse an
agreement owned by somebody else, and SHALL refuse one that already has a signing request. It SHALL
NOT accept any other agreement field, and SHALL NOT change the party list.

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

### Requirement: The draft agreement is sent to both parties before payment

Once contacts are confirmed and before checkout begins, the system SHALL send the current draft
agreement to every party, at their contact on an enabled channel.

This message is distinct from the recovery link and SHALL carry the agreement itself. Failure to
send SHALL NOT prevent payment from proceeding.

#### Scenario: Both parties receive the draft

- **WHEN** contacts are confirmed for an agreement with an owner and a tenant
- **THEN** both parties are sent the current draft agreement before payment begins

#### Scenario: A send failure does not block payment

- **WHEN** sending the draft to a party fails
- **THEN** checkout can still proceed and the failure is recorded

#### Scenario: The recovery message stays free of agreement content

- **WHEN** the draft is sent to the parties
- **THEN** the recovery link message remains separate and still carries no agreement content

### Requirement: Confirmed payment presents a distinct confirmation view

On payment confirmed by the server, the system SHALL present a dedicated confirmation view
rather than an inline notice within the capture form.

The view SHALL display the agreement's tracking reference prominently, the confirmed amount
and currency, and the next step in fulfilment.

The displayed amount and currency SHALL be those reported by the server as confirmed. The view
SHALL NOT derive them from the client-side checkout handler.

#### Scenario: Confirmation shows the reference and confirmed amount

- **WHEN** payment for an agreement is confirmed by the server
- **THEN** a confirmation view is shown carrying the tracking reference, the server-confirmed
  amount and currency, and the next step

#### Scenario: The browser handler alone does not produce a confirmation

- **WHEN** the checkout handler reports success but the server has not confirmed payment
- **THEN** no confirmation view claiming payment is shown

### Requirement: The confirmation view states how the customer returns

The confirmation view SHALL tell the customer that a link has been emailed to them, that the
link is how they return to this agreement, and that signing in to save the agreement will end
access by that link.

Where no signer email is on file, the view SHALL say so plainly and SHALL instruct the customer
to keep the tracking reference, rather than implying that mail was sent.

#### Scenario: The customer is told the link was emailed

- **WHEN** the confirmation view is shown for an agreement with a signer email on file
- **THEN** it states that a link has been emailed and that the link restores access later

#### Scenario: The revocation condition is disclosed

- **WHEN** the confirmation view is shown
- **THEN** it states that signing in and saving the agreement ends access by link

#### Scenario: No address on file is stated plainly

- **WHEN** the confirmation view is shown for an agreement with no signer email recorded
- **THEN** the customer is told to keep the reference themselves, and nothing implies an email
  was sent
