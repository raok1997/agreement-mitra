## MODIFIED Requirements

### Requirement: Signing progress is visible per party

The system SHALL expose the signing progress of an agreement, showing **each party's**
individual status rather than only the aggregate.

The progress view SHALL additionally report the agreement's **fulfilment stage** at a
resolution sufficient to distinguish awaiting-the-e-stamp from out-for-signature. The stages
`PDF_GENERATED`, `STAMPED` and `SIGN_REQUESTED` SHALL be individually reported and SHALL NOT be
collapsed into a single in-progress value. A terminal outcome (signed, expired, failed, or
stamp-failed) SHALL likewise be reported distinctly.

The progress view SHALL also report whether the agreement is currently **editable**, as a
server-decided value. A client SHALL NOT be required to infer editability from the stage, and
SHALL NOT be relied upon to do so correctly.

A party SHALL be able to see the progress of their own agreement. The view SHALL NOT expose
another customer's agreement, and SHALL NOT expose eKYC-derived signer PII returned by the
provider, signing URLs belonging to other parties, or any provider credential.

#### Scenario: Per-party progress is visible

- **WHEN** an authorised caller reads the signing progress of an agreement mid-flow
- **THEN** the response shows each party's individual status and the aggregate state

#### Scenario: Awaiting-stamp is distinguishable from out-for-signature

- **GIVEN** two agreements, one resting in `PDF_GENERATED` and one in `SIGN_REQUESTED`
- **WHEN** progress is read for each
- **THEN** the reported stage differs between them

#### Scenario: Editability is reported by the server

- **WHEN** progress is read for an agreement that has not yet been stamped, and for one that has
- **THEN** the first reports the agreement as editable and the second reports it as not editable

#### Scenario: Progress does not leak PII or capabilities

- **WHEN** signing progress is read
- **THEN** the response contains no eKYC-derived signer data, no other party's signing URL,
  and no provider credential

#### Scenario: Progress is not readable across customers

- **WHEN** a caller requests the progress of an agreement they do not own and have no staff
  role for
- **THEN** the request is refused

### Requirement: The order is placed when the customer finalises, and the draft freezes then

> NOTE: the requirement name is retained verbatim so this delta matches its source at archive
> time. "freezes then" is no longer accurate -- the freeze falls at stamping. Rename to
> "...and the draft freezes at stamping" in a follow-up once `manual-estamp-upload` is archived.

The customer's involvement SHALL substantially end when they **finalise** the agreement. At that
point the system SHALL place the order: it SHALL create the signing request in `PDF_GENERATED`
and surface the agreement's tracking reference to the customer.

The agreement's terms SHALL freeze when a **stamp is attached** (the signing request reaches
`STAMPED`), not at finalisation. While the request rests in `PDF_GENERATED` the terms SHALL
remain correctable, because the stamp certificate is procured against one specific document and
its duty is a function of the rent and the term -- so the stamp, not the order, is the point
after which the document can no longer change.

The document that staff stamp and that the parties sign SHALL be the document as it stands **at
the moment of stamping**. Where terms are edited after finalisation, the system SHALL clear the
pinned draft so that the stamped document is regenerated from the edited terms, and SHALL make
the edit visible to staff fulfilment so that a stamp is not purchased against superseded terms.

After finalising, the customer SHALL have nothing they are *required* to do until they are
invited to sign. Stamp procurement and intake are staff work and SHALL NOT require customer
action.

Where the payment gate is enforced (per `payment-gate`), order placement SHALL follow payment
confirmation. While the gate is permissive, finalisation alone SHALL place the order.

#### Scenario: Finalising places the order and yields the reference

- **WHEN** a customer finalises their agreement
- **THEN** a signing request is created in `PDF_GENERATED` and the tracking reference is
  available to the customer

#### Scenario: The draft may be corrected between finalisation and stamping

- **WHEN** an edit is attempted after finalisation but before a stamp is attached
- **THEN** the edit is accepted, the pinned draft is cleared, and the document is regenerated
  from the edited terms

#### Scenario: The draft cannot change once stamped

- **WHEN** an edit is attempted after a stamp has been attached
- **THEN** the edit is refused and the stamped document is unchanged

#### Scenario: A post-finalisation edit is visible to fulfilment

- **WHEN** an agreement awaiting stamp intake is edited
- **THEN** staff fulfilment can see that its terms changed after the order was placed, before a
  stamp is purchased against them

#### Scenario: The customer has nothing required of them while staff stamp

- **WHEN** an agreement is awaiting stamp intake
- **THEN** no customer action is required or requested until signing begins
