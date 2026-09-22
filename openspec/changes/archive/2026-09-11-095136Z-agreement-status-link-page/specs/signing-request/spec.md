## MODIFIED Requirements

### Requirement: Signing progress is visible per party

The system SHALL expose the signing progress of an agreement, showing **each party's**
individual status rather than only the aggregate.

The progress view SHALL additionally report the agreement's **fulfilment stage** as one of
`NOT_STARTED`, `AWAITING_STAMP`, `STAMPED`, `OUT_FOR_SIGNATURE`, `SIGNED`, `EXPIRED`, `FAILED`,
`STAMP_FAILED`. Awaiting-the-stamp, stamped, and out-for-signature SHALL be individually
reported and SHALL NOT be collapsed into a single in-progress value. The view SHALL report
whether the stage is **terminal** and whether the **signed document is ready** for download, both
as server-decided values; a client SHALL NOT be required to infer either. The existing aggregate
`status` SHALL be unchanged. The stage SHALL carry no failure reason, certificate detail, staff
identity, or queue position.

A party SHALL be able to see the progress of their own agreement. The view SHALL NOT expose
another customer's agreement, and SHALL NOT expose eKYC-derived signer PII returned by the
provider, signing URLs belonging to other parties, or any provider credential.

#### Scenario: Per-party progress is visible

- **WHEN** an authorised caller reads the signing progress of an agreement mid-flow
- **THEN** the response shows each party's individual status and the aggregate state

#### Scenario: Awaiting-stamp is distinguishable from out-for-signature

- **GIVEN** two agreements, one resting in `PDF_GENERATED` and one in `SIGN_REQUESTED`
- **WHEN** progress is read for each
- **THEN** the first reports `AWAITING_STAMP` and the second `OUT_FOR_SIGNATURE`

#### Scenario: Terminality is reported by the server

- **WHEN** progress is read for an agreement in `SIGN_REQUESTED`, and for one in `SIGNED`
- **THEN** the first reports not terminal and the second reports terminal

#### Scenario: Signed-document readiness is reported by the server

- **GIVEN** an agreement in `SIGNED` whose signed PDF has not yet been stored, and one whose
  signed PDF has been stored
- **WHEN** progress is read for each
- **THEN** the first reports the document as not ready and the second as ready

#### Scenario: Progress does not leak PII or capabilities

- **WHEN** signing progress is read
- **THEN** the response contains no eKYC-derived signer data, no other party's signing URL,
  no provider credential, and no field beyond the aggregate status, stage, terminal,
  document-ready, and per-party statuses

#### Scenario: Progress is not readable across customers

- **WHEN** a caller requests the progress of an agreement they do not own and have no staff
  role for
- **THEN** the request is refused
