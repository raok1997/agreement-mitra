## ADDED Requirements

### Requirement: A payment order closed by staff is terminal
The system SHALL treat a payment order that staff have closed as terminal: no confirmation is applied to it, its status never changes again, the reconciliation job does not re-read it, and it is never the order a returning customer is sent back to.

A payment order is closed only by a staff member marking it resolved (see "Staff can mark a
payment order resolved"). A gateway confirmation that arrives for a closed order is acknowledged
to the gateway and writes nothing to the order or the agreement. A closed order does not stop a
new order being created for the same agreement, exactly as an expired or failed order does not.

A closed order is not a paid order: it is not reported to a returning customer as already paid,
and its frozen stamp quote is not the quote shown.

A customer-facing payment read never reports the closed status. It reports a closed order as
expired, which is what the order is to the customer: no longer payable, and replaceable by a new
one.

#### Scenario: A confirmation arrives for a closed order
- **GIVEN** a payment order staff closed as refunded
- **WHEN** a verified gateway webhook reports a captured payment for it
- **THEN** the webhook is acknowledged
- **AND** the order is still closed and the agreement's payment state is unchanged

#### Scenario: A closed order is not reconciled
- **GIVEN** a payment order staff closed, older than the reconciliation age threshold
- **WHEN** the reconciliation job runs
- **THEN** the gateway is not asked about that order

#### Scenario: A closed order does not expire, fail or become paid
- **GIVEN** a payment order staff closed
- **WHEN** marking it expired, failed or paid is attempted
- **THEN** the order is still closed, with its reason, actor and time unchanged

#### Scenario: A customer returns after their order was closed as refunded
- **GIVEN** an unpaid agreement whose latest payment order staff closed as refunded
- **WHEN** the customer starts checkout
- **THEN** a new payment order is created

#### Scenario: A customer reads payment progress after their order was closed
- **GIVEN** an agreement whose latest payment order staff closed
- **WHEN** the customer reads the agreement's payment progress
- **THEN** the order status reported is expired, not closed

#### Scenario: An order is closed while checkout is expiring it
- **GIVEN** an outstanding payment order past its useful life
- **WHEN** the customer starts checkout and a staff member closes that order at the same moment
- **THEN** the order ends closed
- **AND** the customer's checkout succeeds with a new order, not a server error

### Requirement: Staff can mark a payment order resolved
The system SHALL let a staff member close a payment order by marking it resolved with a reason, and SHALL accept this only for an order that is neither paid nor closed and for which the payment event ledger holds a captured-but-unrecorded attempt.

A **captured-but-unrecorded attempt** is a ledger record for the order that carries a gateway
payment identifier and whose outcome is a recording failure, a refused duplicate reference, an
amount mismatch, or an error.

The reason is one of two: the payment was refunded, or the payment was recorded elsewhere. There
is no free-text note. The system does not check that a refund happened; the reason and the actor
are the record.

The action is refused, and nothing changes, when:

- the caller is not an authenticated staff member (decided before any order is looked up, so the
  answer is the same for an order that exists and one that does not);
- the reason is absent or is not one of the two;
- no payment order has the given identifier;
- the order is paid or already closed, or the ledger holds no captured-but-unrecorded attempt for
  it;
- the agreement is already paid under a payment identifier that one of those attempts carries,
  compared ignoring case and surrounding spaces. That order is to be settled as paid, not closed.

A closed order records the reason, the staff member who closed it and when. The staff member is
the authenticated caller, never a field of the request. These three are written once, with the
close, and no later action changes them. Closing an order changes nothing on the agreement, calls
no gateway and sends nothing.

The response identifies the order by its payment order identifier and carries no agreement
identifier and no gateway identifier.

#### Scenario: A refunded amount mismatch is resolved
- **GIVEN** an outstanding payment order with an amount mismatch in the ledger that carries a payment identifier, on an unpaid agreement
- **WHEN** a staff member marks it resolved as refunded
- **THEN** the order is closed with that reason, that staff member and the time
- **AND** the agreement's payment state is unchanged

#### Scenario: A reference recorded elsewhere is resolved
- **GIVEN** an expired payment order with a refused duplicate reference in the ledger
- **WHEN** a staff member marks it resolved as recorded elsewhere
- **THEN** the order is closed with that reason

#### Scenario: An order whose attempts only ended in an error is resolved
- **GIVEN** an outstanding payment order whose only ledger attempt ended in an error and carries a payment identifier
- **WHEN** a staff member marks it resolved as refunded
- **THEN** the order is closed

#### Scenario: A healthy outstanding order cannot be closed
- **GIVEN** an outstanding payment order with no confirmation attempt in the ledger
- **WHEN** a staff member marks it resolved
- **THEN** it is refused as a conflict and the order is still outstanding

#### Scenario: An attempt with no payment identifier does not qualify
- **GIVEN** an outstanding payment order whose only ledger attempt is an amount mismatch with no gateway payment identifier
- **WHEN** a staff member marks it resolved
- **THEN** it is refused as a conflict and the order is still outstanding

#### Scenario: A paid order cannot be closed
- **GIVEN** a payment order with a recording failure in the ledger that a later delivery confirmed as paid
- **WHEN** a staff member marks it resolved
- **THEN** it is refused as a conflict and the order stays paid

#### Scenario: A closed order cannot be closed again
- **GIVEN** a payment order staff closed as refunded
- **WHEN** a staff member marks it resolved as recorded elsewhere
- **THEN** it is refused as a conflict
- **AND** the order's reason, actor and time are unchanged

#### Scenario: The agreement is already paid under the captured payment
- **GIVEN** a payment order with a recording failure in the ledger for a payment identifier, and an agreement staff have since confirmed as paid under that identifier written in capitals with surrounding spaces
- **WHEN** a staff member marks the order resolved
- **THEN** it is refused as a conflict that says the agreement is paid under this payment
- **AND** the order is not closed

#### Scenario: The agreement is paid under another payment
- **GIVEN** a payment order with an amount mismatch in the ledger, and an agreement paid under a different payment identifier
- **WHEN** a staff member marks the order resolved as refunded
- **THEN** the order is closed

#### Scenario: An unknown reason
- **WHEN** a staff member marks an order resolved with a reason that is not one of the two, or with none
- **THEN** it is refused as a bad request and the order is unchanged

#### Scenario: An unknown order
- **WHEN** a staff member marks resolved a payment order identifier the system does not hold
- **THEN** it is refused as not found

#### Scenario: Unauthenticated caller
- **WHEN** a caller with no session marks an order resolved, presenting a valid CSRF token
- **THEN** it is refused as unauthenticated, identically for an order that exists and one that does not

#### Scenario: Non-staff caller
- **WHEN** a signed-in customer marks an order resolved, presenting a valid CSRF token
- **THEN** it is refused as forbidden, identically for an order that exists and one that does not

#### Scenario: A request without a CSRF token
- **WHEN** a staff member marks an order resolved without a CSRF token
- **THEN** it is refused with the CSRF refusal and the order is unchanged

#### Scenario: Response content
- **WHEN** a staff member marks an order resolved and it is accepted
- **THEN** the response carries the payment order identifier, the reason and the time
- **AND** it carries no agreement identifier, no receipt and no gateway order or payment identifier
