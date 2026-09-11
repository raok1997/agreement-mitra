## Purpose

The party-facing landing reached from the link AgreementMitra emails after payment. It answers
"where has my agreement got to?" by showing the agreement's position in the fulfilment pipeline.

## ADDED Requirements

### Requirement: The emailed agreement link lands on a status view

A party opening the emailed agreement link SHALL be shown the agreement's current status. The
system SHALL NOT open an editing surface as the landing screen.

The landing SHALL identify the agreement by its tracking reference so the customer can quote one
number in a support conversation, and SHALL show the agreement's own terms (property address,
rent, dates) and each party's name and role.

The address of the landing SHALL remain the agreement link itself, so that reloading or
bookmarking it returns to the same status view.

#### Scenario: The link opens the status view

- **WHEN** a party opens the emailed agreement link
- **THEN** the agreement's status is displayed and no editing surface is presented

#### Scenario: The status view is reloadable

- **WHEN** a party reloads or bookmarks the page they landed on
- **THEN** the same agreement's status view is shown again

#### Scenario: The tracking reference, terms and parties are shown

- **WHEN** the status view renders for a known agreement
- **THEN** the tracking reference, property address, rent, dates, and each party's name and role
  are displayed

### Requirement: The status view shows the fulfilment pipeline as ordered milestones

The status view SHALL present the agreement's progress as an ordered set of milestones covering:
the agreement being drafted, payment, the e-stamp, each party's signature individually, and
completion.

Each milestone SHALL be shown as done, current, failed, or not yet reached, derived from
explicit server-reported values and never from the order of an enumeration. The e-stamp
milestone SHALL NOT be shown as current until payment is settled, because an order exists
before payment. The per-party signature milestones SHALL be distinguishable from one another,
and only the party next in signing order SHALL be shown as current.

Where a milestone has failed or the agreement has ended without completing (the stamp could not
be applied, signing was rejected or expired), the view SHALL say so plainly -- naming the party
whose signature was rejected or expired -- rather than showing the milestone as merely
outstanding.

#### Scenario: Mid-flight progress is legible

- **WHEN** an agreement is paid, stamped, and out for signature with the first party signed
- **THEN** the drafted, payment, and e-stamp milestones show as done, the first party's signature
  shows as done, and the second party's signature shows as current

#### Scenario: Awaiting the e-stamp is distinguishable from out for signature

- **WHEN** an agreement has been paid but no e-stamp has been attached yet
- **THEN** the view shows the e-stamp milestone as the current step and does not present any
  party's signature as current

#### Scenario: An order placed but unpaid shows payment as the current step

- **WHEN** an agreement has a signing request awaiting its stamp but payment is not settled
- **THEN** the payment milestone is current and the e-stamp milestone is not yet reached

#### Scenario: A terminal failure is stated, not hidden

- **WHEN** an agreement's signing has expired or been rejected by a party, or its stamp step has
  failed
- **THEN** the view states that outcome, names the party where one is responsible, and shows the
  remaining parties as not reached rather than pending

### Requirement: The status view reports only what the server has established

A milestone SHALL be shown as reached only on the strength of state the server reports. The
system SHALL NOT advance a milestone on the strength of anything that merely happened in the
customer's browser.

In particular, payment SHALL be shown as settled only when the server reports the agreement as
paid or its payment waived, and never because a payment window closed or a payment handler
returned in the page.

#### Scenario: A closed payment window does not claim payment

- **WHEN** a payment attempt ends in the browser without the server reporting the agreement paid
- **THEN** the payment milestone is not shown as done

#### Scenario: Server-confirmed payment settles the milestone

- **WHEN** the server reports the agreement as paid or its payment waived
- **THEN** the payment milestone is shown as done

### Requirement: The status view keeps itself current without inferring when to stop

The view SHALL re-read progress -- and payment state while payment is outstanding -- periodically,
no more often than every twenty seconds, backing off on error to a bounded maximum, for any
agreement that has an order or a settled payment; a draft with neither SHALL NOT be polled. A
refusal from either read SHALL be treated alike. It SHALL stop when the server
reports the stage as terminal, except that a signed agreement whose document is not yet ready
SHALL continue to be re-read at the bounded maximum interval until it is. It SHALL pause while
the page is hidden and re-read on becoming visible without breaching the floor. It SHALL stop
and show the saved-to-an-account message if a re-read is refused. After an in-page payment
attempt returns, the view SHALL re-read payment state and resume polling.

#### Scenario: Polling stops on a terminal stage

- **WHEN** a re-read reports the stage as terminal and the document ready (or the stage is not
  signed)
- **THEN** no further re-reads are made

#### Scenario: A signed agreement keeps polling until its document is ready

- **WHEN** a re-read reports the stage as signed and the document as not ready
- **THEN** re-reads continue at the bounded maximum interval until the document is ready

#### Scenario: A refused re-read switches to the saved-to-an-account message

- **GIVEN** a view polling an unowned agreement
- **WHEN** a re-read is refused because the agreement was claimed meanwhile
- **THEN** the view shows the saved-to-an-account message and stops polling

#### Scenario: A draft with no order is not polled

- **WHEN** the view renders an agreement that is unpaid, has no order, and has no signing request
- **THEN** no periodic re-read is scheduled

#### Scenario: A finalised but unpaid agreement is polled

- **WHEN** the view renders an agreement with a signing request whose payment is not settled
- **THEN** periodic re-reads are scheduled

#### Scenario: A hidden page pauses and a visible page resumes within the floor

- **WHEN** the page becomes hidden and later visible again
- **THEN** no re-read is made while hidden, and a re-read on becoming visible happens only if at
  least twenty seconds have passed since the last one

### Requirement: The status view offers the document and the payment route when they apply

Where the agreement is signed and the server reports the document ready, the view SHALL offer
the signed document for download, fetching it with the caller's session so that a signed-in
owner of a claimed agreement can retrieve it; it SHALL NOT place a session credential in a URL.
Where payment is still outstanding, the view SHALL offer the route to complete it. The view
SHALL NOT offer an edit action.

#### Scenario: The signed document is offered on completion

- **WHEN** the status view renders for a signed agreement whose document is ready
- **THEN** the signed document can be downloaded from it, with the session sent as a header

#### Scenario: The download is withheld until the document is ready

- **WHEN** the status view renders for a signed agreement whose document is not yet ready
- **THEN** no download is offered and the view says the document is being prepared

#### Scenario: Outstanding payment is actionable

- **WHEN** the status view renders for an agreement whose payment is outstanding
- **THEN** the route to complete payment is offered

#### Scenario: No edit is offered

- **WHEN** the status view renders for any agreement
- **THEN** no edit action is presented

### Requirement: The status view discloses nothing beyond the agreement it opens

The status view SHALL NOT display eKYC-derived signer data returned by the eSign provider, any
party's signing URL, any provider or payment credential, any staff-only fulfilment detail
(failure reasons, certificate details, operator identity, queue position), or any party's
contact details, father's name, or current address. It SHALL NOT render the agreement's raw
capture data. It SHALL NOT make any other agreement reachable.

#### Scenario: Provider-derived and staff-only detail is absent

- **WHEN** the status view renders for an agreement mid-signing
- **THEN** it contains no eKYC-derived signer data, no signing URL, no provider or payment
  credential, and no failure reason or certificate detail

#### Scenario: Party contact details are absent

- **WHEN** the status view renders
- **THEN** no party's email, mobile, father's name or current address is displayed, and no raw
  capture data is rendered

#### Scenario: The view is scoped to one agreement

- **WHEN** a party opens the status view
- **THEN** no agreement other than the one the link identifies is reachable from it

### Requirement: A claimed or unknown agreement is handled without revealing which

Where the agreement has been claimed into an account, an anonymous holder of the link SHALL be
refused, indistinguishably from an unknown agreement. The system SHALL NOT reveal which of the
two occurred.

The message shown SHALL explain that the agreement may now be held in an account and that
signing in is the way to open it, and SHALL offer a route to sign in. It SHALL NOT read as a
bare "not found", which would strand a party whose counterparty saved the agreement.

#### Scenario: A claimed agreement directs the holder to sign in

- **GIVEN** an agreement that has been claimed into an account
- **WHEN** an anonymous party opens their emailed link for it
- **THEN** they are told the agreement may be held in an account and are offered a way to sign in

#### Scenario: Claimed and unknown are indistinguishable

- **WHEN** an anonymous party opens a link for a claimed agreement, and one for an agreement that
  does not exist
- **THEN** the outcome is the same in both cases and neither reveals which occurred
