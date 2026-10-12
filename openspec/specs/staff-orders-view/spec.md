# staff-orders-view Specification

## Purpose
TBD - created by archiving change staff-orders-view. Update Purpose after archive.
## Requirements
### Requirement: Staff can list payment orders that need a look

The system SHALL provide a STAFF-only read, `GET /api/staff/payment-orders`, that returns payment
orders in two groups, **paid but stuck** and **not paid**, built only from data already stored.

The read SHALL change nothing: it writes no row, calls no gateway and raises no alert.

Only a payment order whose status is `CREATED`, `EXPIRED` or `FAILED` SHALL be listed. An order
whose status is `PAID`, or any status not named here, SHALL appear in neither group. An agreement that has no
payment order - including one staff confirmed or waived by hand - SHALL NOT appear, and neither
SHALL a ledger attempt for an order the system does not hold.

When no order belongs in a group, the group SHALL be returned empty.

#### Scenario: A paid order is not listed

- **GIVEN** a payment order whose status is `PAID`
- **WHEN** staff request the orders list
- **THEN** the order is in neither group

#### Scenario: A hand-confirmed agreement with no payment order is not listed

- **GIVEN** an agreement staff confirmed by hand that never had a payment order
- **WHEN** staff request the orders list
- **THEN** nothing for that agreement is in either group

#### Scenario: Nothing to list

- **GIVEN** no payment order belongs in either group
- **WHEN** staff request the orders list
- **THEN** the response is 200 with both groups empty

#### Scenario: Reading the list changes nothing

- **GIVEN** payment orders and ledger attempts exist
- **WHEN** staff request the orders list twice
- **THEN** both responses list the same entries in the same order and no payment order, ledger or
  alert row has changed

### Requirement: An order with a captured payment that was not recorded is listed as paid but stuck

The paid-but-stuck group SHALL hold every payment order of a listed status that has at least one
ledger attempt that carries a gateway payment id and whose outcome is `RECORDING_FAILED`,
`DUPLICATE_REFERENCE`, `AMOUNT_MISMATCH` or `ERRORED`. It has no date limit.

An attempt that carries no gateway payment id SHALL NOT put an order in this group: it is an event
without payment detail, not a captured payment.

Each entry SHALL name every one of those four outcomes the order's attempts ended with, once each.
An order SHALL be one entry however many attempts or outcomes it has.

The group SHALL be ordered by the most recent such attempt, newest first, with the payment order id
deciding between equal times.

The set of outcomes that raises a staff alert SHALL NOT change: an order whose attempts only ever
ended `ERRORED` is listed here and still raises no alert.

#### Scenario: A recording failure is listed

- **GIVEN** an order with status `CREATED` and a `RECORDING_FAILED` attempt carrying a payment id
- **WHEN** staff request the orders list
- **THEN** the order is in the paid-but-stuck group with the outcome `RECORDING_FAILED`

#### Scenario: An order that only ever errored is listed

- **GIVEN** an order with status `CREATED` whose only attempts ended `ERRORED` and carry a payment id
- **WHEN** the staff alert sweep has run and staff request the orders list
- **THEN** the order is in the paid-but-stuck group with the outcome `ERRORED`
- **AND** no staff alert exists for it

#### Scenario: An old stuck order is still listed

- **GIVEN** an `EXPIRED` order created 90 days ago with an `AMOUNT_MISMATCH` attempt carrying a
  payment id
- **WHEN** staff request the orders list
- **THEN** the order is in the paid-but-stuck group

#### Scenario: An attempt without a payment id does not make an order stuck

- **GIVEN** an order created today whose only attempt is `AMOUNT_MISMATCH` with no payment id
- **WHEN** staff request the orders list
- **THEN** the order is not in the paid-but-stuck group
- **AND** it is in the not-paid group

#### Scenario: Only attempts with a payment id name an outcome

- **GIVEN** an order with a `RECORDING_FAILED` attempt carrying a payment id and an
  `AMOUNT_MISMATCH` attempt with no payment id
- **WHEN** staff request the orders list
- **THEN** the order is in the paid-but-stuck group naming `RECORDING_FAILED` only
- **AND** both attempts are in its attempt history

#### Scenario: Stuck orders are newest attempt first

- **GIVEN** two stuck orders, the first last attempted an hour ago and the second a minute ago
- **WHEN** staff request the orders list
- **THEN** the second order is listed before the first

#### Scenario: Two outcomes are one entry

- **GIVEN** an order with a `RECORDING_FAILED` attempt and a `DUPLICATE_REFERENCE` attempt, each
  carrying a payment id
- **WHEN** staff request the orders list
- **THEN** the paid-but-stuck group holds one entry for the order naming both outcomes

#### Scenario: A stuck order since recorded as paid leaves the list

- **GIVEN** an order with a `RECORDING_FAILED` attempt whose status is now `PAID`
- **WHEN** staff request the orders list
- **THEN** the order is in neither group

### Requirement: A recent order that was not paid is listed as not paid

The not-paid group SHALL hold every payment order of a listed status created in the last 30 days
that is not in the paid-but-stuck group, ordered newest first, with the payment order id deciding
between equal times.

The entry SHALL carry the order's status. The system SHALL NOT report whether the gateway declined
a payment for the order: that is not stored, so a declined payment and an abandoned checkout are
the same entry.

#### Scenario: An open checkout is listed

- **GIVEN** an order created an hour ago with status `CREATED` and no ledger attempt
- **WHEN** staff request the orders list
- **THEN** the order is in the not-paid group with status `CREATED`

#### Scenario: An expired order is listed

- **GIVEN** an order created three days ago with status `EXPIRED` and no ledger attempt
- **WHEN** staff request the orders list
- **THEN** the order is in the not-paid group with status `EXPIRED`

#### Scenario: Not-paid orders are newest first

- **GIVEN** two not-paid orders, the first created yesterday and the second an hour ago
- **WHEN** staff request the orders list
- **THEN** the second order is listed before the first

#### Scenario: An order older than 30 days is not listed

- **GIVEN** an `EXPIRED` order created 31 days ago with no ledger attempt
- **WHEN** staff request the orders list
- **THEN** the order is in neither group

#### Scenario: A stuck order is in one group only

- **GIVEN** an order created today with status `CREATED` and a `RECORDING_FAILED` attempt carrying
  a payment id
- **WHEN** staff request the orders list
- **THEN** the order is in the paid-but-stuck group
- **AND** it is not in the not-paid group

### Requirement: An entry shows what staff need and withholds the agreement's credentials

Each entry SHALL carry the agreement's tracking reference, the payment order id, the order's
amount and currency, its status, when it was created, the agreement's payment state, and the
order's ledger attempts.

The agreement's payment state SHALL be one of: unpaid, waived, paid under this order's payment, or
paid under another payment. It is "paid under this order's payment" when the agreement is paid and
its recorded reference is the gateway payment id of one of the order's attempts, compared the way
a payment confirmation compares references (ignoring case and surrounding spaces).

Each attempt SHALL carry its source, its outcome, the gateway payment id, the amount and currency
the gateway reported, the refused rule and SQL state where recorded, when it was first and last
received, and how many times it was seen.

An entry SHALL NOT carry the agreement id, the order's receipt, the gateway order id, the
agreement's recorded payment reference, or any party's name, contact or address.

#### Scenario: An entry carries the tracking reference and not the agreement id

- **GIVEN** a listed order
- **WHEN** staff request the orders list
- **THEN** its entry carries the agreement's tracking reference and the payment order id
- **AND** the response contains no agreement id, no receipt and no gateway order id, as a field or
  as a value

#### Scenario: An amount mismatch shows both amounts

- **GIVEN** an order for 49900 paise with an `AMOUNT_MISMATCH` attempt reporting 4990 paise
- **WHEN** staff request the orders list
- **THEN** the entry carries the order amount 49900 and the attempt carries the amount 4990

#### Scenario: A repeated attempt reports its count

- **GIVEN** an order whose `RECORDING_FAILED` attempt was received 7 times
- **WHEN** staff request the orders list
- **THEN** the attempt carries the count 7 and its first and last received times

#### Scenario: The agreement is paid under this order's payment

- **GIVEN** a stuck order whose attempt carries payment id `pay_ABC`, and an agreement recorded as
  paid by hand under the reference `PAY_ABC`
- **WHEN** staff request the orders list
- **THEN** the entry reports the agreement as paid under this order's payment

#### Scenario: The agreement is paid under another payment

- **GIVEN** a stuck order whose attempt carries payment id `pay_ABC`, and an agreement paid under
  the reference `pay_XYZ`
- **WHEN** staff request the orders list
- **THEN** the entry reports the agreement as paid under another payment
- **AND** `pay_XYZ` appears nowhere in the response

#### Scenario: The agreement was waived

- **GIVEN** a listed order whose agreement was waived
- **WHEN** staff request the orders list
- **THEN** the entry reports the agreement as waived

### Requirement: A reused payment id names the agreement that holds it

An entry SHALL carry the tracking reference of every agreement that holds a payment the entry was
refused for: for each attempt whose outcome is `DUPLICATE_REFERENCE` and that carries a gateway
payment id, an agreement whose recorded payment reference is that id, and the agreement of any
other payment order recorded under that id. The entry's own payment order SHALL NOT count as a holder.

The entry SHALL carry the holder's tracking reference only, never its agreement id. When nothing
holds the payment id any more, the entry SHALL carry no holder.

#### Scenario: The payment is held by another agreement

- **GIVEN** a stuck order with a `DUPLICATE_REFERENCE` attempt for `pay_ABC`, and another agreement
  with tracking reference `AM7K2P9Q` recorded as paid by hand under `PAY_ABC`
- **WHEN** staff request the orders list
- **THEN** the entry names `AM7K2P9Q` as the holder

#### Scenario: The payment is held by another payment order

- **GIVEN** a stuck order with a `DUPLICATE_REFERENCE` attempt for `pay_ABC`, and a `PAID` payment
  order of another agreement recorded under `pay_ABC`
- **WHEN** staff request the orders list
- **THEN** the entry names that other agreement's tracking reference as the holder

#### Scenario: Nothing holds the payment id

- **GIVEN** a stuck order with a `DUPLICATE_REFERENCE` attempt for `pay_ABC`, and no agreement or
  other order recorded under it
- **WHEN** staff request the orders list
- **THEN** the entry carries no holder

#### Scenario: Other kinds carry no holder

- **GIVEN** a stuck order whose only stuck attempt is `RECORDING_FAILED`
- **WHEN** staff request the orders list
- **THEN** the entry carries no holder

### Requirement: Each group is bounded

The system SHALL return at most 200 entries in each group and SHALL say, for each group, whether
entries were left out. The entries kept SHALL be the first 200 in the group's order.

#### Scenario: A group over the limit is cut short and says so

- **GIVEN** 201 orders that belong in the not-paid group
- **WHEN** staff request the orders list
- **THEN** the not-paid group holds the 200 newest and is marked as cut short

#### Scenario: A group within the limit is complete

- **GIVEN** 3 orders that belong in the not-paid group
- **WHEN** staff request the orders list
- **THEN** the not-paid group holds all 3 and is not marked as cut short

### Requirement: Only staff may read the orders list

The system SHALL refuse the orders list to a caller without the STAFF role, in the filter chain and
before any order or agreement is read. The response SHALL be marked not to be stored by a cache.

#### Scenario: An unauthenticated caller is refused

- **GIVEN** no session
- **WHEN** the orders list is requested
- **THEN** the response is 401

#### Scenario: A signed-in customer is refused

- **GIVEN** a session without the STAFF role
- **WHEN** the orders list is requested
- **THEN** the response is 403

#### Scenario: Staff are served

- **GIVEN** a session with the STAFF role
- **WHEN** the orders list is requested
- **THEN** the response is 200 with both groups and `Cache-Control: no-store`

#### Scenario: No other method is opened on the route

- **GIVEN** a session with the STAFF role and a valid CSRF token
- **WHEN** a POST is sent to the orders list route
- **THEN** the response is 403

### Requirement: The staff console presents the orders list as a read-only tab

The staff console SHALL offer an Orders tab beside the e-stamp queue. The e-stamp queue SHALL
remain the tab the console opens on, and the orders list SHALL be requested only when the Orders
tab is opened. Opening the Orders tab SHALL NOT discard a stamp upload the staff member has begun.

The tab SHALL show the paid-but-stuck group first and the not-paid group second, with these
labels: `RECORDING_FAILED` "Not recorded", `DUPLICATE_REFERENCE` "Payment id reused",
`AMOUNT_MISMATCH` "Amount mismatch" with the order amount and the amount paid, `ERRORED` "Error
while recording"; status `CREATED` "Checkout open", status `EXPIRED` "Abandoned". An outcome or
status without a label, including an outcome this build does not know, SHALL be shown as its
stored code.

Each attempt SHALL be shown with "seen N times" ("seen 1 time" for a count of one).

The tab SHALL offer no action on an order. It SHALL name the runbook section "A captured payment
that was refused or not recorded" in `docs/DEPLOYMENT.md`.

An entry that names a holder of a reused payment id SHALL show the holder's tracking reference,
whatever its next step is.

Each paid-but-stuck entry SHALL state one next step, the first of these that applies:

| When | Next step shown |
|---|---|
| The agreement is paid under this order's payment | Already recorded on this agreement. Nothing to record. |
| The entry names the outcome `AMOUNT_MISMATCH` | Amount differs. Do not record until someone decides to refund or to accept. |
| The entry names the outcome `DUPLICATE_REFERENCE` and names a holder | This payment is already recorded on the holder's tracking reference ("this agreement" when the holder is the entry's own). Do not alter the id to get past the refusal. |
| The agreement is waived, or paid under another payment | The agreement is already settled. Do not record this payment. Check whether it is a double charge before refunding. |
| The entry names the outcome `DUPLICATE_REFERENCE` and names no holder | Refused as already recorded, but nothing holds this payment id now. Confirm the capture in the gateway dashboard, then record it by hand under the payment id shown. |
| Otherwise (`RECORDING_FAILED`, `ERRORED`) | Confirm the capture in the gateway dashboard, then record it by hand under the payment id shown. |

Where the agreement is waived, or paid under another payment, and the next step is the amount
mismatch or the named holder, the entry SHALL add that this may be a double charge and to check
before refunding. An entry SHALL NOT tell staff to record a payment on an agreement that is waived
or paid under another payment. A not-paid entry SHALL state no next step.

The tab SHALL state that an agreement staff confirmed or waived by hand has no payment order and
is not listed, and SHALL state when a group was cut short.

Every string from the response SHALL be rendered as text, never as markup. A currency code the
browser does not know SHALL NOT stop the tab rendering: the amount is shown followed by the code.

#### Scenario: The console opens on the e-stamp queue

- **GIVEN** a staff member
- **WHEN** they open the staff console
- **THEN** the e-stamp queue is shown and the orders list has not been requested

#### Scenario: Opening the Orders tab shows both groups

- **GIVEN** the orders list holds one stuck order and one open checkout
- **WHEN** the staff member opens the Orders tab
- **THEN** the stuck order is shown first under "Paid but stuck" and the open checkout under "Not
  paid" as "Checkout open"

#### Scenario: A stuck order shows its kind, both amounts and its attempts

- **GIVEN** a stuck order for ₹499.00 with an `AMOUNT_MISMATCH` attempt of ₹49.90 seen 3 times
- **WHEN** the staff member opens the Orders tab
- **THEN** the entry shows "Amount mismatch", both amounts, and "seen 3 times"

#### Scenario: The agreement's payment state is shown

- **GIVEN** a stuck order whose agreement is paid under this order's payment
- **WHEN** the staff member opens the Orders tab
- **THEN** the entry shows that the agreement is paid under this payment

#### Scenario: An unlabelled status is shown as its code

- **GIVEN** a not-paid order whose status is `FAILED`
- **WHEN** the staff member opens the Orders tab
- **THEN** the entry shows `FAILED`

#### Scenario: The tab has no action and points at the runbook

- **GIVEN** the Orders tab is open with a stuck order
- **WHEN** the staff member reads the entry
- **THEN** the tab holds no button or link that acts on the order
- **AND** it names the runbook section and says hand-confirmed and waived agreements are not listed

#### Scenario: An already recorded payment needs nothing

- **GIVEN** a stuck order with a `RECORDING_FAILED` attempt whose agreement is paid under this
  order's payment
- **WHEN** the staff member opens the Orders tab
- **THEN** the entry's next step is "Already recorded on this agreement. Nothing to record."

#### Scenario: An amount mismatch waits for a decision

- **GIVEN** a stuck order with an `AMOUNT_MISMATCH` attempt and a `DUPLICATE_REFERENCE` attempt
  whose agreement is unpaid
- **WHEN** the staff member opens the Orders tab
- **THEN** the entry's next step says the amount differs and not to record until a decision

#### Scenario: A reused payment id names where it is recorded

- **GIVEN** a stuck order with a `DUPLICATE_REFERENCE` attempt whose holder is `AM7K2P9Q`
- **WHEN** the staff member opens the Orders tab
- **THEN** the entry's next step says the payment is already recorded on `AM7K2P9Q`

#### Scenario: An unrecorded payment is to be confirmed and recorded

- **GIVEN** a stuck order with an `ERRORED` attempt whose agreement is unpaid
- **WHEN** the staff member opens the Orders tab
- **THEN** the entry's next step says to confirm the capture and record it under the payment id shown

#### Scenario: A possible double charge is flagged

- **GIVEN** a stuck order with a `RECORDING_FAILED` attempt whose agreement is paid under another
  payment
- **WHEN** the staff member opens the Orders tab
- **THEN** the entry's next step says the agreement is already settled, not to record the
  payment, and to check for a double charge before refunding
- **AND** it does not say to record the payment by hand

#### Scenario: A mismatch on a settled agreement adds the double-charge note

- **GIVEN** a stuck order with an `AMOUNT_MISMATCH` attempt whose agreement was waived
- **WHEN** the staff member opens the Orders tab
- **THEN** the entry's next step says the amount differs
- **AND** it adds that this may be a double charge and to check before refunding

#### Scenario: A reused payment id that nothing holds is to be recorded

- **GIVEN** a stuck order with a `DUPLICATE_REFERENCE` attempt, no holder, and an unpaid agreement
- **WHEN** the staff member opens the Orders tab
- **THEN** the entry's next step says nothing holds the payment id now, and to confirm the capture
  and record it by hand
- **AND** it does not say the payment is recorded elsewhere

#### Scenario: A not-paid entry states no next step

- **GIVEN** an open checkout in the not-paid group
- **WHEN** the staff member opens the Orders tab
- **THEN** the entry shows no next step

#### Scenario: A begun stamp upload survives a look at the Orders tab

- **GIVEN** a staff member has opened a stamp upload and typed a certificate number
- **WHEN** they open the Orders tab and return to the queue
- **THEN** the upload form is still open with the certificate number in it

#### Scenario: An unknown currency does not break the tab

- **GIVEN** an attempt whose currency is `NOT-A-CODE`
- **WHEN** the staff member opens the Orders tab
- **THEN** the attempt is shown with its amount followed by `NOT-A-CODE`, and the other entries
  are shown

#### Scenario: Response text is not rendered as markup

- **GIVEN** an attempt whose gateway payment id is `<img src=x onerror=alert(1)>`
- **WHEN** the staff member opens the Orders tab
- **THEN** that text is shown literally and no element is created from it

#### Scenario: A failed load is reported

- **GIVEN** the orders list request fails
- **WHEN** the staff member opens the Orders tab
- **THEN** the tab says the orders could not be loaded and shows no entry

