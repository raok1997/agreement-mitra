## ADDED Requirements

### Requirement: The stamped instrument states the duty amount from the attached certificate

When the agreement's instrument was rendered from a template, stamp intake SHALL re-render that instrument with the attached certificate's **duty amount** as the value of every system-sourced stamp-duty field, and SHALL composite the scan onto that re-render rather than onto the pre-stamp draft.

The duty amount the instrument states SHALL come only from the staff-submitted certificate metadata; a value the customer submitted for that field SHALL NOT appear.

The re-render SHALL reproduce the agreement's **pinned effective template** and the **execution date** printed on the pre-stamp draft. Apart from the stamp-duty content it SHALL change nothing in the document body.

The system SHALL fall back to compositing onto the stored pre-stamp draft, exactly as before this requirement, when any of these holds:

- the stored draft is **not a recorded render**: it was uploaded, or it was rendered before the draft's execution date was recorded;
- the agreement has **no selected template**;
- the effective template resolved now **differs from the pin** recorded when the draft was rendered.

The fallback SHALL NOT be an error, and it SHALL NOT change the outcome of the intake.

If the renderer is **unavailable** during the re-render, intake SHALL be rejected as a retryable service error (`503`). Nothing SHALL be written, no stamp info SHALL be persisted, the certificate SHALL NOT be recorded as used, the signing request SHALL stay in `PDF_GENERATED`, and the agreement SHALL NOT be closed. A renderer outage SHALL NOT transition the request to `STAMP_FAILED`.

The pre-stamp draft SHALL remain stored unchanged.

#### Scenario: A Telangana deed states the certificate's duty amount

- **GIVEN** a paid Telangana agreement whose draft was rendered from its template with no stamp duty amount entered
- **WHEN** staff attach a certificate with a duty amount of INR 100.00
- **THEN** the stamped instrument's `Statutory (Telangana)` section shows the stamp duty paid as 100.00
- **AND** it includes the clause "The stamp duty paid on this Agreement is INR 100.00."
- **AND** no `[ Stamp duty paid (INR) ]` placeholder appears anywhere in the stamped instrument
- **AND** the stored pre-stamp draft is unchanged

#### Scenario: The re-render changes nothing but the stamp duty content

- **GIVEN** an agreement whose draft was rendered on one date from a pinned template
- **WHEN** staff attach a certificate on a later date
- **THEN** the stamped instrument's body text equals the draft's body text except for the stamp duty row and clause
- **AND** the execution date printed is the draft's execution date, not the intake date

#### Scenario: A customer-entered amount never reaches the stamped instrument

- **GIVEN** an agreement whose stored capture data carries a stamp duty amount of INR 5,000 submitted by a client
- **WHEN** staff attach a certificate with a duty amount of INR 100.00
- **THEN** the stamped instrument states 100.00
- **AND** the figure 5,000 appears nowhere in it

#### Scenario: An uploaded draft falls back to composite-only

- **GIVEN** an agreement whose draft was generated from its template and then replaced by an uploaded PDF
- **WHEN** staff attach a certificate
- **THEN** the scan is composited onto the uploaded draft as before
- **AND** the intake succeeds and the signing request transitions to `STAMPED`

#### Scenario: A drifted template falls back to composite-only

- **GIVEN** an agreement pinned to a template version that has since been superseded
- **WHEN** staff attach a certificate
- **THEN** the scan is composited onto the stored pre-stamp draft
- **AND** the intake succeeds and the signing request transitions to `STAMPED`

#### Scenario: A renderer outage is retryable and spends nothing

- **GIVEN** a paid agreement awaiting a stamp, and the document renderer is unavailable
- **WHEN** staff attach a certificate
- **THEN** the request is rejected with `503` and its own problem type, distinct from a stamping failure
- **AND** no blob is written, no stamp info is persisted, and the certificate number is not recorded as used
- **AND** the signing request remains `PDF_GENERATED` and the agreement remains open
- **AND** a later retry with the same certificate succeeds once the renderer is available
