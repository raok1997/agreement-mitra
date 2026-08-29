## ADDED Requirements

### Requirement: The agreement response exposes a display-only tracking number

The agreement API response (`AgreementResponse`) SHALL expose a **`trackingNumber`** field: the
human-friendly, **display-only** reference `AM-<LAST6>-<DDMMYY>` -- the uppercased last six
hexadecimal characters of the agreement's identifier and the agreement's start date as a zero-padded
`DDMMYY`. The tracking number SHALL be **derived server-side** from data the aggregate already holds
(nothing is persisted for it), by the same derivation the rendered document uses, so the client shows
the **identical** value the document carries.

The tracking number SHALL be **display-only**: its last-six-hex fragment is not collision-free, so it
SHALL NOT be used as a unique key, and the response SHALL continue to expose the agreement's **full
identifier** as the canonical reference. The tracking number SHALL carry no Aadhaar/OTP/VID/PII and SHALL
NOT be logged.

#### Scenario: The response carries the derived tracking number alongside the raw id

- **WHEN** an agreement whose identifier ends in `a5e4d7` with start date 1 July 2026 is created or fetched
- **THEN** the response's `trackingNumber` is `AM-A5E4D7-010726` and its `id` remains the full,
  canonical agreement identifier

#### Scenario: The tracking number is derived, not persisted

- **WHEN** the response is built for any agreement
- **THEN** the `trackingNumber` is derived at response time from the agreement's id and start date, with
  no stored column backing it
