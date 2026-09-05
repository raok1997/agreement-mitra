## Purpose

Gateway-backed payment for an agreement: creating a priced order, taking payment through a
hosted checkout, confirming it authoritatively, and recording the result against the
`payment-gate` confirmation seam. Razorpay is the first provider; the contract stays
vendor-neutral so a second is an adapter.

## ADDED Requirements

### Requirement: The amount is computed by the server and never accepted from the client

The payable amount SHALL be produced by a **server-side calculation** for the agreement. The
system SHALL NOT accept an amount, currency, or discount from the client on any request, and
SHALL ignore such a value if supplied.

The calculation SHALL be a named, testable operation from the outset even while it returns a
single configured flat price, so that introducing state-and-rent-dependent stamp duty later
changes the calculation only - not the order-creation flow, the API, or the stored record.

Money SHALL be represented in **minor units (paise) as an integer** everywhere it is
calculated, stored, transmitted, or compared. Floating-point types SHALL NOT be used for
monetary values at any point. The currency SHALL be recorded explicitly alongside every amount.

#### Scenario: A client-supplied amount is ignored

- **WHEN** a create-order request carries an amount, currency, or discount field
- **THEN** the value is ignored and the order is created for the server-calculated amount

#### Scenario: Amount is computed through the pricing operation

- **WHEN** an order is created for an agreement
- **THEN** the amount comes from the pricing calculation, and changing the configured price
  changes the amount charged with no other code change

#### Scenario: Money is integer minor units

- **WHEN** an amount is calculated, persisted, or sent to the provider
- **THEN** it is an integer number of minor units with an explicit currency, and no
  floating-point representation is used

### Requirement: An order is created server-side and joined to the agreement

The system SHALL create an order with the payment provider before checkout begins, and SHALL
persist the provider's order id against the agreement.

The order SHALL carry a **receipt** that is our own identifier for the agreement, so the
provider's record can be joined back to ours without relying on any client-held value. The
receipt SHALL be unique and SHALL respect the provider's length limit.

Creating an order SHALL be **idempotent** per agreement: repeated attempts while an order is
outstanding and unpaid SHALL reuse the existing order rather than creating another, so a
customer who reloads the page does not accumulate orders. A **new** order MAY be created once
an outstanding one has expired or failed.

The provider order id SHALL be unique in our records, so one provider order cannot be recorded
against two agreements.

#### Scenario: Order creation persists the join

- **WHEN** an order is created for an agreement
- **THEN** the provider order id, receipt, amount, currency, and status are persisted against
  that agreement

#### Scenario: Reload reuses the outstanding order

- **WHEN** create-order is called again for an agreement with an outstanding unpaid order
- **THEN** the existing order is returned and no second provider order is created

#### Scenario: A provider order id cannot be reused across agreements

- **WHEN** a provider order id already recorded against one agreement is recorded against
  another
- **THEN** the second attempt is refused

#### Scenario: Only the agreement's owner can start payment

- **WHEN** a caller who neither owns the agreement nor holds the STAFF role requests an order
- **THEN** the request is refused and no order is created

### Requirement: Checkout receives only public credentials

To start checkout the system SHALL return to the browser only the provider's **public
identifier** and the order details needed to open the payment interface.

The provider's **key secret** and **webhook secret** SHALL NEVER be sent to the client,
embedded in frontend code or configuration, or exposed by any endpoint.

Card, UPI, netbanking, and wallet credentials SHALL be collected entirely by the provider's
hosted checkout. The system SHALL NOT accept, proxy, store, or log any such credential, and no
request field SHALL exist that could carry one.

#### Scenario: Only the public key reaches the browser

- **WHEN** the client requests what it needs to open checkout
- **THEN** it receives the public key identifier, order id, amount, and currency - and no
  secret

#### Scenario: No payment instrument data is accepted

- **WHEN** any request is made to the payment endpoints
- **THEN** there is no field that accepts card, UPI, or bank credentials, and none is stored
  or logged

### Requirement: The webhook is the authoritative confirmation

Payment SHALL be marked confirmed **only** on the basis of a verified provider webhook, or an
authoritative read of the order from the provider's API.

The value returned to the browser by the checkout handler (the payment id, order id, and
handler signature) is **client-supplied** and SHALL be treated as a **user-experience signal
only**. A verified handler signature MAY advance the UI, and MAY trigger an authoritative read,
but SHALL NOT by itself mark the agreement paid. This matters because the browser can be
closed, altered, or replayed, and a customer who closes the tab after paying must still end up
`PAID`.

Confirmation SHALL record the provider payment id, the confirmed amount, and the confirmation
time, and SHALL update the agreement's payment state through the `payment-gate` confirmation
seam.

The confirmed amount SHALL be checked against the order's expected amount and currency. A
mismatch SHALL NOT be recorded as a successful payment.

#### Scenario: A verified webhook confirms payment

- **WHEN** a webhook passes signature verification and reports the order captured for the
  expected amount
- **THEN** the agreement's payment state becomes `PAID` and the provider payment id, amount,
  and time are recorded

#### Scenario: The browser callback alone does not confirm payment

- **WHEN** the checkout handler returns a valid signature but no webhook has arrived
- **THEN** the agreement is not marked `PAID` on that basis alone

#### Scenario: Closing the browser does not lose the payment

- **WHEN** a customer completes payment and closes the tab before the callback runs
- **THEN** the webhook still confirms the payment and the agreement becomes `PAID`

#### Scenario: An amount mismatch is not treated as payment

- **WHEN** a verified webhook reports an amount or currency differing from the order's
- **THEN** the payment is not recorded as successful and the discrepancy is surfaced for
  investigation

### Requirement: Webhook signatures are verified over the raw body before any side effect

The system SHALL expose a webhook endpoint for the payment provider and SHALL verify every
request before any state change.

Verification SHALL compute **HMAC-SHA256 over the raw, unparsed request body** using the
**webhook secret**, and compare it to the signature header in **constant time**. The body SHALL
reach verification **byte-for-byte as received**: it SHALL NOT be deserialised and
re-serialised, reformatted, or re-encoded before the digest is computed, since any such
transformation breaks verification.

The **webhook secret SHALL be distinct from the API key secret**, and the two SHALL NOT be
interchangeable in configuration.

A request failing verification SHALL be rejected with no state change. Webhook bodies SHALL
NOT be logged verbatim nor echoed in any error response. A verified webhook for an unknown
order SHALL be acknowledged **indistinguishably** from a known one, so the endpoint is not an
existence oracle.

#### Scenario: A validly signed webhook is accepted

- **WHEN** a webhook arrives whose signature is HMAC-SHA256 of its raw body under the webhook
  secret
- **THEN** verification passes and the system proceeds to apply the confirmation

#### Scenario: A forged or tampered signature is rejected

- **WHEN** a webhook arrives with a missing, altered, or forged signature, or a body altered
  after signing
- **THEN** the request is rejected and no payment state changes

#### Scenario: The raw body is used for verification

- **WHEN** a webhook body is received
- **THEN** the digest is computed over the exact bytes received, not over a re-serialised form

#### Scenario: The key secret does not validate webhooks

- **WHEN** a signature is computed using the API key secret instead of the webhook secret
- **THEN** verification fails and the request is rejected

#### Scenario: Unknown order is acknowledged indistinguishably

- **WHEN** a verified webhook references an order with no matching record
- **THEN** no state changes and the response matches that for a known order

### Requirement: Confirmation is idempotent under redelivery and duplicate events

The provider redelivers webhooks and MAY send more than one event describing the same
successful payment. Applying confirmation SHALL be **idempotent**.

A repeated or duplicate confirmation for an already-confirmed order SHALL leave the payment
state, the recorded amount, and the recorded payment id unchanged, and SHALL NOT produce a
second payment record. Concurrent deliveries SHALL be made safe so that two simultaneous
confirmations cannot both apply.

A confirmation SHALL NOT move an agreement backwards out of a settled state.

#### Scenario: Redelivery changes nothing

- **WHEN** a previously applied webhook is delivered again
- **THEN** the payment state and recorded values are unchanged and no second payment is
  recorded

#### Scenario: Two events for one payment confirm once

- **WHEN** the provider sends two different event types describing the same successful payment
- **THEN** the payment is recorded exactly once

#### Scenario: Concurrent deliveries are safe

- **WHEN** two deliveries for the same order are processed concurrently
- **THEN** at most one confirmation is applied and no state is corrupted

### Requirement: Unconfirmed orders are reconciled

A scheduled job SHALL re-read outstanding orders from the provider, so a missed or failed
webhook cannot leave a customer who has paid stuck unpaid.

The job SHALL consider orders that are outstanding beyond a configured age, SHALL apply
confirmation through the **same code path** as the webhook, and SHALL be safe to run
repeatedly. Orders the provider reports as unpaid, failed, or expired SHALL be left or marked
accordingly and SHALL NOT be confirmed.

#### Scenario: A missed webhook is recovered

- **WHEN** a payment succeeded at the provider but no webhook was applied
- **THEN** the reconciliation job reads the order, applies the confirmation, and the agreement
  becomes `PAID`

#### Scenario: Reconciliation does not invent payments

- **WHEN** the provider reports an order as unpaid, failed, or expired
- **THEN** no confirmation is applied

#### Scenario: Reconciliation reuses the webhook path

- **WHEN** the job confirms a payment
- **THEN** it applies the same confirmation logic as the webhook, with the same idempotency

### Requirement: Payment credentials and identifiers are handled as secrets

The provider key id, key secret, and webhook secret SHALL come from **environment variables
only** and SHALL NEVER be committed. The key secret and webhook secret SHALL never be returned
by any endpoint and SHALL never appear in logs.

Provider order and payment identifiers SHALL be **redacted** in logs, consistent with the
existing redaction discipline. Webhook and API payloads SHALL NOT be logged verbatim.

This repository SHALL use **test-mode credentials only**. The live provider host and live
credentials SHALL NOT be defaults, and the test suite SHALL pass with fabricated secrets and no
live account.

#### Scenario: Secrets come only from the environment

- **WHEN** the application starts
- **THEN** payment credentials are read from environment variables and none is present in
  committed configuration

#### Scenario: Secrets never appear in logs or responses

- **WHEN** the system logs around order creation, checkout, or webhook handling
- **THEN** no key secret, webhook secret, or verbatim payload appears, and identifiers are
  redacted

#### Scenario: Tests need no live account

- **WHEN** the test suite runs with fabricated credentials
- **THEN** every payment test passes against a stubbed provider, and no live credential or real
  money is required
