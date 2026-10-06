## Why

The stamp quote and the deed can describe two different agreements. The deed fills an unset
`rentEscalationPercent` with the template default (5%), but the quote reads the capture state only
and assumes 0% -- so a customer who clears the field signs a deed with 5% escalation against a duty
computed without it, under-quoting any term past twelve months (register row
`stamp-quote-capture-defaults`). And when escalation *is* applied, the "How this was calculated"
breakdown never says so: manual testing on 2026-10-04 showed "Average annual rent INR 123" on a
INR 10 / 24-month agreement, a correct figure that reads as a bug because nothing explains the 5%
(register row `rental-default-commercial-terms`).

Product decision (2026-10-06): the template's default lock-in, escalation and notice period
**stay**. Both parties review the complete deed before signing, so a default is not a hidden term.
What must hold is that the quote is computed from the deed the customer will sign, and that the
breakdown says what moved the number.

## What Changes

- The stamp quote resolves `rentEscalationPercent` the way the deed does: the captured value when
  present and non-blank, else the field's default in the agreement's current template. A
  missing template or field default still falls back to no escalation.
- The stamp quote resolves the execution date the way a stored draft's re-render does: the captured
  `agreementDate`, else the draft's recorded execution date, else today in India. Today the quote
  skips the middle step, so a draft rendered yesterday is quoted under today's rule window.
- The duty breakdown gains an informational **escalation** line whenever escalation raised the
  consideration: its label states the rate and interval, its amount is the rupees escalation added.
  It is neither a quantity nor a delta, so the existing replay invariant (`BASE` + deltas = quoted
  amount) is unchanged.
- The stamp-quote screen renders that line beneath the quantities it moved, e.g. "Includes 5% rent
  escalation every 12 months — INR 3.00" (unsigned: it explains the consideration, it does not
  change the duty).
- The captured escalation is parsed the way the deed field is typed -- a whole number 0-100 -- so an
  out-of-range or exotic value (e.g. `1E+999999999`) never reaches the exact rent arithmetic, where
  today it can pin the JVM.
- Closes register rows `stamp-quote-capture-defaults` and `rental-default-commercial-terms`, and
  updates the v1 list in `docs/ROADMAP.md`.

Out of scope: changing any template default or clause text; the commercial set's own defaults
(commercial is hidden for v1 and is served by the same code path unchanged); BYO-uploaded
agreements, which carry no template default (`byo-document-upload` requires the fact in the
declaration).

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `stamp-selection`: the quote is computed from the same effective terms the deed renders, and the
  shown breakdown explains escalation.
- `stamp-duty-calculation`: the breakdown carries an informational escalation line outside the
  replay sum.

## Impact

- **Backend `signing`**: `DutyBasisMapper` (effective escalation + execution date), `StampQuoting`
  (supplies the template's field default).
- **Backend `documents` API**: `TemplateFormApi.findForm`, empty only when no published template
  exists (consulted lazily, only when the captured escalation is unusable).
- **Backend `rules`**: `DutyLine.Kind.ESCALATION` (non-delta), `DutyBasis.withoutEscalation()`, and
  a duty too large for paise becomes `Unsupported` instead of an uncaught `ArithmeticException`.
- **API**: `StampQuoteResponse.Line` gains `delta: boolean` and `kind` may now be `ESCALATION`;
  frozen quotes on new orders persist it with the rest of the breakdown. Older frozen quotes simply
  lack it. No migration.
- **Frontend**: `StampQuoteStep.vue` signs lines by the server's `delta` flag and renders
  `ESCALATION` as an informational line.
- **Signing FSM**: none -- no `SignatureStatus` transition is touched.
- **PII / security**: none. Only a percentage, an interval and a date cross into `rules`; the
  calculator still receives no party, contact or address. No secrets, Aadhaar, OTP or VID flow.
  Sandbox + dummy data only is preserved.
