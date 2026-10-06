## MODIFIED Requirements

### Requirement: A refusal whose remedy differs is explained in its own words

Where a refused customer action has a remedy different from "try again", the interface SHALL show a message naming that remedy, and SHALL NOT invite a retry that cannot succeed. At minimum this SHALL hold for:

- **The unsupported-jurisdiction refusal, wherever payment is started.** The message SHALL say that stamping and eSign are not available for this agreement's jurisdiction and that the draft can still be previewed and downloaded. The capture form, the status view and the stamp-value step's unavailable notice SHALL use the same wording, except that when the stamp quote is unavailable only because no stamp-paper plan covers the duty (status `UNPLANNABLE`), the stamp-value step SHALL instead say that stamping is not available for this agreement yet, without naming the jurisdiction.
- **The draft-frozen refusal of an edit.** The message SHALL say that the agreement's order has been placed, so its terms can no longer be changed.
- **The contacts-frozen refusal of a contact change.** Its existing message is unchanged.
- **The not-available refusal, wherever payment is started.** When any call on the capture form's pay path is refused `404` with the not-found type (loading the agreement for the contact step, saving contacts, finalising, or the checkout-order call that follows finalise), or the status view's "Complete payment" is refused `404` (its stamp-quote load, or its checkout-order call with the not-found type), the message SHALL say that this agreement is not available here and that, if it has been saved to an account, signing in with that account lets the customer continue, and the interface SHALL re-check whether the customer is still signed in. It SHALL read identically whether the agreement is unknown or claimed by another account, and SHALL NOT name or describe the account that claimed it.

#### Scenario: Jurisdiction refused at finalise in the capture form

- **GIVEN** a saved agreement whose jurisdiction became ineligible for paid fulfilment after its stamp quote loaded (the stamp step hides the choice for an ineligible jurisdiction, so this is the race path)
- **WHEN** the customer chooses a stamp value and the finalise call is refused `409` with the unsupported-jurisdiction type
- **THEN** the capture form shows the jurisdiction message
- **AND** it does not show `Agreement request failed: 409` or any "please try again" wording

#### Scenario: Jurisdiction refused at checkout in the capture form

- **GIVEN** finalise succeeded
- **WHEN** the checkout-order call is refused `409` with the unsupported-jurisdiction type
- **THEN** the capture form shows the same jurisdiction message

#### Scenario: Jurisdiction refused from the status view

- **GIVEN** an agreement whose payment is outstanding, opened in the status view
- **WHEN** the customer starts payment and the checkout-order call is refused `409` with the unsupported-jurisdiction type
- **THEN** the status view shows the same jurisdiction message, not the "contact support" message

#### Scenario: An unplannable duty is not called a jurisdiction refusal

- **GIVEN** a stamp quote that is unavailable with status `UNPLANNABLE`
- **WHEN** the stamp-value step renders
- **THEN** its notice says stamping is not available for this agreement yet and does not mention the jurisdiction
- **AND** it offers no payment

#### Scenario: An edit refused because the order is placed

- **GIVEN** an owner editing an agreement whose order has since been placed
- **WHEN** the save is refused `409` with the draft-frozen type
- **THEN** the capture form says the order has been placed and the terms can no longer be changed
- **AND** it does not show `Agreement request failed: 409`

#### Scenario: The pay path's first step finds the agreement not available

- **GIVEN** a customer in the capture form whose agreement has been claimed and who is no longer signed in as its owner (their session ended, or another account claimed it)
- **WHEN** they choose to finalise and pay, and loading the agreement for the contact step is refused `404` with the not-found type
- **THEN** the capture form shows the not-available message, not "Could not load the party details. Please try again."
- **AND** the interface re-checks whether the customer is still signed in, so the header no longer shows a session that has ended

#### Scenario: Saving contacts finds the agreement not available

- **GIVEN** the contact step is open
- **WHEN** saving the contacts is refused `404` with the not-found type
- **THEN** the capture form shows the same not-available message

#### Scenario: Finalise refused because the agreement is not available to this caller

- **GIVEN** a customer whose agreement stopped being available to them after the contact step loaded
- **WHEN** the finalise call is refused `404` with the not-found type
- **THEN** the capture form shows the same not-available message, not "Could not start payment. Please try again."

#### Scenario: The checkout call after finalise is refused as not available

- **GIVEN** finalise succeeded
- **WHEN** the checkout-order call that follows it is refused `404` with the not-found type
- **THEN** the capture form shows the same not-available message

#### Scenario: The not-available message never identifies the claiming account

- **GIVEN** the not-available message is shown
- **THEN** it contains no name, email or other detail of any account, and is the same text for an unknown agreement and for one claimed by someone else

#### Scenario: The status view's payment finds the agreement not available

- **GIVEN** an agreement whose payment is outstanding, opened in the status view by a customer who is no longer signed in as its owner
- **WHEN** they choose "Complete payment" and the stamp-quote load is refused `404`, or the checkout-order call is refused `404` with the not-found type
- **THEN** the status view shows the not-available message, not "Payment cannot be started yet. Contact support quoting your reference."
- **AND** the interface re-checks whether the customer is still signed in
