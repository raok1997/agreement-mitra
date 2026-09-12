## ADDED Requirements

### Requirement: An attached stamp is a precondition of the provider call

`createSignRequest` SHALL require that a stamp is **already attached** to the agreement before
the eSign provider is called. The system SHALL NOT procure, generate, or composite a stamp as
part of the signing-request flow.

If the agreement has **no stamp attached** (its stamp info is empty), the request SHALL be
rejected with `409` before any signing-request row is persisted and before any provider call.
The rejection SHALL be distinguishable from other `409` conditions (such as a missing draft or
an uncontactable party) so an operator can tell why signing could not start.

If the agreement **has a stamp attached**, the system SHALL submit the **stamped PDF** to the
provider - never the bare draft - and persist the `SIGN_REQUESTED` transition after the
provider call. The stamp itself is not re-composited at signing time.

#### Scenario: Signing without a stamp is refused

- **WHEN** `createSignRequest` runs for an agreement whose stamp info is empty
- **THEN** the request is rejected with `409`, no signing-request row is persisted, and the
  provider is not called

#### Scenario: The stamped PDF is what reaches the provider

- **WHEN** `createSignRequest` runs for an agreement with an attached stamp
- **THEN** the document submitted to the provider is the stored stamped PDF, not the draft

#### Scenario: Missing-stamp rejection is distinguishable

- **WHEN** signing is refused because no stamp is attached
- **THEN** the error identifies the missing stamp as the cause, distinctly from a missing
  draft or an uncontactable party

### Requirement: The order is placed when the customer finalises, and the draft freezes then

The customer's involvement SHALL end when they **finalise** the agreement. At that point the
system SHALL place the order: it SHALL create the signing request in `PDF_GENERATED`, freeze the
agreement's terms against further editing, and surface the agreement's tracking reference to the
customer.

Freezing SHALL happen at **finalisation**, not at stamp upload. The document that staff stamp and
that the parties sign SHALL be the document the customer finalised; it SHALL NOT be editable in
the window between finalisation and stamp intake.

After finalising, the customer SHALL have nothing further to do until they are invited to sign.
Stamp procurement and intake are staff work and SHALL NOT require customer action.

Where the payment gate is enforced (per `payment-gate`), order placement SHALL follow payment
confirmation. While the gate is permissive, finalisation alone SHALL place the order.

#### Scenario: Finalising places the order and freezes the draft

- **WHEN** a customer finalises their agreement
- **THEN** a signing request is created in `PDF_GENERATED`, the agreement is no longer editable,
  and the tracking reference is available to the customer

#### Scenario: The draft cannot change between finalisation and stamping

- **WHEN** an edit is attempted after finalisation but before a stamp is uploaded
- **THEN** the edit is refused and the finalised document is unchanged

#### Scenario: The customer has nothing to do while staff stamp

- **WHEN** an agreement is awaiting stamp intake
- **THEN** no customer action is required or requested until signing begins

### Requirement: PDF_GENERATED is the durable awaiting-stamp state

`PDF_GENERATED` SHALL be a **durable, long-lived** state, not a momentary pre-request step. A
signing request SHALL rest in `PDF_GENERATED` for as long as it takes staff to purchase the
e-stamp out-of-band and upload it - potentially hours or days.

The transition `PDF_GENERATED -> STAMPED` SHALL be driven by a **staff stamp upload** (per
`estamp-intake`), not by the signing-request flow. The transition `PDF_GENERATED ->
STAMP_FAILED` SHALL be driven by an upload that is accepted for processing but whose
composition fails. A rejected upload that never reaches composition (bad role, bad file,
duplicate certificate) SHALL leave the request in `PDF_GENERATED` so staff can retry.

Because `PDF_GENERATED` is now durable, an agreement resting in it SHALL NOT be treated as an
orphan or reaped by any reconciliation or cleanup process.

#### Scenario: A request rests in PDF_GENERATED awaiting the stamp

- **WHEN** an agreement's instrument has been generated but no stamp has been uploaded
- **THEN** the signing request remains in `PDF_GENERATED` indefinitely and is not failed,
  expired, or reaped

#### Scenario: Staff upload drives the STAMPED transition

- **WHEN** a staff user successfully uploads a stamp for a request in `PDF_GENERATED`
- **THEN** the request transitions to `STAMPED`

#### Scenario: A rejected upload leaves the state unchanged

- **WHEN** a stamp upload is rejected before composition (unauthorized caller, invalid image,
  or duplicate certificate number)
- **THEN** the request remains in `PDF_GENERATED` and staff can retry

#### Scenario: A failed composition drives STAMP_FAILED

- **WHEN** an accepted upload fails during composition
- **THEN** the request transitions to `STAMP_FAILED` and the provider is not called

## REMOVED Requirements

### Requirement: Auto-stamp before the provider call

**Reason**: Stamping is no longer something the system can do by itself. A real e-stamp is
purchased from the SHCIL portal by staff, out-of-band and asynchronously, then scanned and
uploaded. An automatic in-process procurement step inside `createSignRequest` cannot express a
human step that takes hours or days, and the synthetic stamp it produced (`dutyPaid = false`)
was never a legally stampable instrument.

**Migration**: The stamp step moves out of `createSignRequest` into the staff-driven intake
flow specified by `estamp-intake`, and `createSignRequest` gains the `409` precondition above.
The `PDF_GENERATED -> STAMPED` transition survives unchanged in shape but is now triggered by
the upload rather than by the signing flow. The dormant reuse branch (documented as
unreachable under v1 lock-forever) is dropped with the requirement; re-stamping an
already-stamped agreement is refused with `409` by `estamp-intake`, and the reuse-vs-rebuy
legal question remains deferred to the future supersede flow. The D9 discipline that no
database transaction spans the provider HTTP call is unaffected - it is retained by the
surrounding `createSignRequest` flow.
