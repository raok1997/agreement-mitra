## 1. Pure date-entry module

- [ ] 1.1 Add `frontend/src/views/dateEntry.ts` (beside `formModel.ts`, same pure-module convention): no Vue, no DOM
- [ ] 1.2 `formatIso(iso)` -- ISO `yyyy-mm-dd` to `dd/mm/yyyy`; empty in, empty out; a non-ISO input returns empty rather than throwing
- [ ] 1.3 `parseEntry(text)` -- `dd/mm/yyyy` to an ISO string or a typed failure reason (`incomplete` | `not-a-date` | `impossible-date` | `two-digit-year`), never a guess
- [ ] 1.4 Compute calendar validity by **round-trip**: construct from the typed components, then assert the constructed date reports back the same day, month, and year, so `31/02/2026` is rejected rather than rolling over to 3 March
- [ ] 1.5 Reject a two-digit year explicitly rather than expanding it

## 2. The date widget

- [ ] 2.1 Rewrite `components/widgets/DateWidget.vue` to render a masked `dd/mm/yyyy` text input (`inputmode="numeric"`) with a visible format hint, keeping the exact existing contract: `modelValue` in as ISO, `update:modelValue` out as ISO
- [ ] 2.2 Insert `/` separators as the user types and constrain entry to digits, without fighting backspace or mid-string editing
- [ ] 2.3 Validate on commit (blur / section save), not per keystroke; emit no value while the entry is invalid, so a partial or coerced date can never reach the working set
- [ ] 2.4 Surface the failure through the existing `error` prop and `field-error-<key>` element, with a message naming the field and the expected format and never restating the entry
- [ ] 2.5 Keep `data-testid="field-<key>"` on the input so existing selectors keep resolving
- [ ] 2.6 Select the render path once on mount from a coarse-pointer media query; do not re-evaluate on resize
- [ ] 2.7 Retain `<input type="date">` on the touch path, emitting identical ISO through the same seam

## 3. Calendar picker

- [ ] 3.1 Add an in-repo calendar picker component beside the widget -- **no new npm dependency** (the frontend OSV gate scans the whole lockfile)
- [ ] 3.2 Month grid with month/year navigation, emitting the same ISO value
- [ ] 3.3 Open from a button on the field; keyboard reachable, arrow-key navigable, dismissable with Escape, with focus returning to the field on close
- [ ] 3.4 Confirm a date can be completed by keyboard **without** opening the picker at all

## 4. Accessibility

- [ ] 4.1 Associate the format hint and the error message with the input so both reach assistive technology; keep `aria-invalid` on the invalid state
- [ ] 4.2 Give the picker an accessible name and role, and keep its open state exposed
- [ ] 4.3 Manual screen-reader pass over a date field: label, expected format, invalid state, picker open/close. **Record the result in the CR journal** -- this is the capability the native input was providing for free, so it is verified, not assumed

## 5. Frontend tests

- [ ] 5.1 Unit (`dateEntry.test.ts`): `formatIso` round-trips ISO to `dd/mm/yyyy`, and handles empty and malformed input without throwing
- [ ] 5.2 Unit (`dateEntry.test.ts`): `parseEntry` accepts `08/01/2026` as `2026-01-08`; rejects `31/02/2026` and `31/04/2026` as impossible; rejects an incomplete entry; rejects `08/01/26` as a two-digit year; accepts `29/02/2028` and rejects `29/02/2027`
- [ ] 5.3 Unit: month-length table across all twelve months, plus a century-leap case (2000 valid, 2100 invalid)
- [ ] 5.4 Component (`DateWidget.test.ts`, the first widget test in the repo): typing `08/01/2026` emits `2026-01-08`; an ISO `modelValue` displays as `08/01/2026`; an impossible date shows the error and emits nothing
- [ ] 5.5 Component: the touch path renders the native input and emits identical ISO for the same date
- [ ] 5.6 Component: the picker opens, is keyboard navigable, emits ISO on selection, and returns focus on close
- [ ] 5.7 Update `CaptureForm.test.ts` where it drives a date field, so it exercises the new entry path
- [ ] 5.8 Confirm no test asserts a host-locale-dependent date rendering

## 6. Verification and close-out

- [ ] 6.1 Run the frontend tests and `npm run lint` from `frontend/`
- [ ] 6.2 Run `npm run build` and confirm the chained `security:scan` still passes with **no new dependency** in the lockfile
- [ ] 6.3 Manually verify on a machine set to **US regional settings** that the tenancy start date reads `08/01/2026` for 8 January 2026 -- the reported case
- [ ] 6.4 Manually verify the deed still renders `08-Jan-2026` and the status view `8 Jan 2026`, both unchanged by this CR
- [ ] 6.5 Manually verify on a touch device (or device emulation) that the native picker still appears
- [ ] 6.6 Run `openspec validate --strict dd-mm-yyyy-date-entry`
