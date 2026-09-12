## Purpose

Lets a customer see what stamp duty their agreement statutorily attracts and choose which e-stamp
denomination to buy from the ones their state actually sells, with an audited warning when they choose
one that does not cover the duty.

## ADDED Requirements

### Requirement: The customer is shown the assessed duty and the state's denominations

Before payment, the system SHALL show the customer, for their agreement: the **assessed statutory
duty**, the **basis** it was computed on (the rate, what the base was built from, and any cap
applied), whether **registration is compulsory** at that term, and the **denominations purchasable in
their state**.

The denomination that satisfies the statutory duty SHALL be **pre-selected**. A customer who takes no
action SHALL therefore be correctly stamped.

Where the agreement's jurisdiction is not yet resolved - a national template with no state chosen -
the customer SHALL be asked to choose the property's state before any duty, basis, or denomination is
shown, since none of them exist until a jurisdiction does.

#### Scenario: The correct denomination is pre-selected

- **WHEN** a customer reaches stamp selection for a supported jurisdiction
- **THEN** they see the assessed duty, its basis, the registration position, and the state's
  denominations
- **AND** the denomination satisfying the statutory duty is pre-selected

#### Scenario: A national template asks for the state first

- **GIVEN** an agreement pinned to a national template with no state chosen
- **WHEN** the customer reaches stamp selection
- **THEN** they are asked to choose the property's state before a duty or denomination is shown

### Requirement: Selection is bounded to the state's denomination master

The customer SHALL be able to select **only** a denomination present in their state's denomination
master. The system SHALL NOT accept a free-form duty amount from the client on any request.

A submitted denomination SHALL be validated server-side against the master for that agreement's
jurisdiction. A value absent from the master SHALL be rejected, and the payable amount SHALL be
recomputed server-side from the validated selection rather than from anything the client sent.

#### Scenario: A denomination outside the master is refused

- **WHEN** a selection names an amount not in the agreement's jurisdiction denomination master
- **THEN** the request is rejected and no selection is recorded

#### Scenario: A tampered client cannot set the duty

- **WHEN** a request carries a duty amount, a total, or a fee value alongside the selection
- **THEN** those values are ignored and the payable amount is recomputed server-side from the
  validated denomination

### Requirement: Selecting at or above the statutory duty needs no warning

Selecting the denomination that satisfies the statutory duty SHALL be accepted without a
warning or an acknowledgement, as SHALL any higher denomination in the master. Over-stamping is
lawful and is a legitimate customer choice.

#### Scenario: A higher denomination is accepted plainly

- **WHEN** a customer selects a denomination above the one satisfying the statutory duty
- **THEN** the selection is recorded with no warning and no acknowledgement required

### Requirement: Selecting below the statutory duty requires an audited acknowledgement

A customer MAY select a denomination **below** the assessed statutory duty, but the system SHALL
first present an explicit warning stating that an under-stamped instrument is **inadmissible in
evidence until impounded** and that the deficit carries a **penalty of up to ten times** the amount
short.

The selection SHALL NOT be recorded until the customer has explicitly acknowledged that warning. An
implicit acknowledgement - a default-checked control, a dismissal, or a timeout - SHALL NOT satisfy
this requirement.

#### Scenario: An under-stamped selection without acknowledgement is refused

- **WHEN** a customer submits a denomination below the assessed statutory duty with no
  acknowledgement
- **THEN** the request is refused, the selection is not recorded, and no payment order is created

#### Scenario: An acknowledged under-stamped selection is accepted

- **WHEN** a customer submits a denomination below the assessed statutory duty together with an
  explicit acknowledgement of the warning
- **THEN** the selection is recorded and checkout may proceed

### Requirement: The acknowledgement is persisted as an audit record without PII

Each acknowledged under-stamped selection SHALL persist an audit record carrying the **agreement
identifier**, the **assessed statutory duty**, the **denomination chosen**, the **shortfall**, an
identifier of the **warning text version** shown, and the **timestamp**.

The record SHALL NOT carry any party name, contact detail, or the property address, and SHALL NOT be
written to logs verbatim. The record SHALL be immutable once written.

#### Scenario: The audit record captures the informed choice

- **WHEN** a customer acknowledges the warning and selects below the statutory duty
- **THEN** an audit record persists the agreement identifier, assessed duty, chosen denomination,
  shortfall, warning version, and timestamp

#### Scenario: The audit record carries no PII

- **WHEN** an acknowledgement audit record is written
- **THEN** it contains no party name, contact detail, or property address
- **AND** no log line on that path emits the record verbatim

### Requirement: The selection is fixed once the order is placed

Once a payment order has been created for an agreement, its stamp denomination SHALL NOT change. A
selection request against an agreement with an outstanding or paid order SHALL be refused.

Changing the denomination after payment would mean the customer paid for one duty amount and a
different certificate was purchased.

#### Scenario: Selection after order creation is refused

- **GIVEN** an agreement with a payment order already created
- **WHEN** a request attempts to change its stamp denomination
- **THEN** the request is refused and the recorded denomination is unchanged
