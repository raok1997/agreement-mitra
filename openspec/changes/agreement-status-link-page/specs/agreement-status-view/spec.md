## Purpose

The party-facing landing reached from the link AgreementMitra emails after payment. It answers
"where has my agreement got to?" by showing the agreement's position in the fulfilment pipeline,
and offers the actions that are legitimate at that position.

## ADDED Requirements

### Requirement: The emailed agreement link lands on a status view

A party opening the emailed agreement link SHALL be shown the agreement's current status. The
system SHALL NOT open an editing surface as the landing screen.

The landing SHALL identify the agreement by its tracking reference so the customer can quote one
number in a support conversation, and SHALL show the agreement's own terms (property address,
rent, dates) that the link already entitles the holder to read.

The address of the landing SHALL remain the agreement link itself, so that reloading or
bookmarking it returns to the same status view.

#### Scenario: The link opens the status view

- **WHEN** a party opens the emailed agreement link
- **THEN** the agreement's status is displayed, and no editing surface is presented until the
  party asks for one

#### Scenario: The status view is reloadable

- **WHEN** a party reloads or bookmarks the page they landed on
- **THEN** the same agreement's status view is shown again

#### Scenario: The tracking reference is shown

- **WHEN** the status view renders for a known agreement
- **THEN** the agreement's tracking reference is displayed

### Requirement: The status view shows the fulfilment pipeline as ordered milestones

The status view SHALL present the agreement's progress as an ordered set of milestones covering:
the agreement being drafted, payment, the e-stamp, each party's signature individually, and
completion.

Each milestone SHALL be shown in one of three conditions -- done, current, or not yet reached --
and the per-party signature milestones SHALL be distinguishable from one another, so that a
customer can see that (for example) the owner has signed and the tenant has not.

Where a milestone has failed or the agreement has ended without completing (payment failed, the
stamp could not be applied, signing was rejected or expired), the view SHALL say so plainly
rather than showing the milestone as merely outstanding.

#### Scenario: Mid-flight progress is legible

- **WHEN** an agreement is paid, stamped, and out for signature with one of two parties signed
- **THEN** the drafted, payment, and e-stamp milestones show as done, that party's signature
  shows as done, and the other party's signature shows as outstanding

#### Scenario: Awaiting the e-stamp is distinguishable from out for signature

- **WHEN** an agreement has been paid but no e-stamp has been attached yet
- **THEN** the view shows the e-stamp milestone as the current step and does not present the
  agreement as being out for signature

#### Scenario: A terminal failure is stated, not hidden

- **WHEN** an agreement's signing has expired, been rejected, or its stamp step has failed
- **THEN** the view states that outcome rather than showing the milestone as still pending

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

### Requirement: The status view discloses nothing beyond the agreement it opens

The status view SHALL NOT display eKYC-derived signer data returned by the eSign provider, any
party's signing URL, any provider or payment credential, or any staff-only fulfilment detail. It
SHALL NOT make any other agreement reachable.

#### Scenario: Provider-derived detail is absent

- **WHEN** the status view renders for an agreement mid-signing
- **THEN** it contains no eKYC-derived signer data, no signing URL, and no provider or payment
  credential

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

### Requirement: The status view offers the actions legitimate at the current stage

The status view SHALL offer an edit action when, and only when, the server reports the agreement
as still editable. Where the agreement is no longer editable, the view SHALL NOT present an edit
action, and SHALL say why in terms the customer can act on.

Where the agreement is signed, the view SHALL offer the signed document for download. Where
payment is still outstanding, the view SHALL offer the route to complete it.

Editability SHALL be taken from the server's own decision, never inferred by the view from the
milestones it happens to be displaying.

#### Scenario: Edit is offered before the document is committed to a stamp

- **GIVEN** an agreement the server reports as editable
- **WHEN** the status view renders
- **THEN** an edit action is offered, and choosing it opens the agreement for editing

#### Scenario: Edit is withheld once the agreement is frozen

- **GIVEN** an agreement the server reports as not editable
- **WHEN** the status view renders
- **THEN** no edit action is offered and the view explains that the agreement can no longer be
  changed

#### Scenario: The signed document is offered on completion

- **WHEN** the status view renders for a signed agreement
- **THEN** the signed document can be downloaded from it
