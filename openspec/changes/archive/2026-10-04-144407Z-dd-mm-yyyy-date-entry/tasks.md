## 1. Pure date-entry module (`frontend/src/views/dateEntry.ts`)

- [x] 1.1 Add `dateEntry.ts` beside `formModel.ts`, following the same pure-module convention: no Vue, no DOM
- [x] 1.2 Export `MIN_YEAR = 1900` / `MAX_YEAR = 2199`, with a comment citing `backend/.../PlausibleDates.java` as the authority they mirror
- [x] 1.3 `parseIso(iso)` -- split the string into `{year, month, day}` or null, validated by a **round trip** through `Date.UTC` / `getUTC*`. Calendar validity only, **no year bounds** (bounds live in `validateField`). **Never** `new Date(isoString)`; it reads as the previous day in negative-offset zones
- [x] 1.4 Move the private `parseIsoDate` out of `formModel.ts` and replace its uses (`tenancyMonths`, `crossFieldErrors`) with `parseIso`, leaving one owner of date validity
- [x] 1.5 `formatIso(iso)` -- ISO to `dd/mm/yyyy`; empty or non-ISO input returns empty
- [x] 1.6 `parseEntry(text)` -- accept `d/m/yyyy`, `dd/mm/yyyy`, eight digits, or ISO `yyyy-mm-dd` (anything over ten characters is `not-a-date`), returning ISO or a failure: `incomplete` | `not-a-date` | `impossible-date` | `out-of-range`. Check the year bounds **before** the round trip, so a year below 100 is `out-of-range`, not `impossible-date`. Never guess

## 2. Shared validation (`frontend/src/views/formModel.ts`, `CaptureForm.vue`)

- [x] 2.1 `validateField`: add a `type === "date"` branch. Empty falls through to the existing required handling; ISO out of bounds gives the range message; any other non-ISO value goes through `parseEntry` and the message table in design Decision 5. No message restates the entry
- [x] 2.2 Export a save-blocking predicate: cross-field errors plus any date field with a non-empty value that `validateField` rejects
- [x] 2.3 `CaptureForm.vue` `saveSection` blocks on that predicate in place of `hasModalCrossFieldErrors`. A blank required date must still save. The Save button's `:disabled` binding stays on `hasModalCrossFieldErrors` only (design Decision 5)
- [x] 2.4 `CaptureForm.vue` `loadDraft`: drop a stored date value that is not valid ISO, alongside the existing stale-enum drop
- [x] 2.5 `CaptureForm.vue` `summary()`: show a date field's value through `formatIso`

## 3. The date widget (`components/widgets/DateWidget.vue`)

- [x] 3.1 Render `<input type="text" inputmode="numeric">` (no `maxlength`) with a `dd/mm/yyyy` placeholder and a visible hint. Keep the `data-testid="field-<key>"` and `field-error-<key>` ids, and render the `error` prop as today. The widget owns no error state
- [x] 3.2 Mask, filtered on `beforeinput` by `inputType` (not `keydown` -- Android IMEs send keyCode 229): reject typed text other than digits and `/` and typing past ten characters; when a digit is typed at the end and the text is exactly `dd` or `dd/mm`, insert `/` before it; leave backspace and mid-string edits alone. Paste: read `clipboardData.getData("text")`, trim, insert whole -- never stripped to digits, never truncated
- [x] 3.3 Emit on every input, paste, and picker selection: `""` when empty, ISO when valid, the raw trimmed text otherwise, so the model always mirrors the text. On blur, redisplay a valid entry in canonical `dd/mm/yyyy`
- [x] 3.4 Hide the `error` prop while the input is focused **and** the text is under ten characters; render it otherwise. Display only -- `aria-invalid` follows the prop
- [x] 3.5 Resync: initialise the text from `modelValue` on mount and seed the last-emitted record with it; afterwards, when `modelValue` changes to anything other than the last emitted value, replace the text with `formatIso(value)` for ISO or with the raw value otherwise
- [x] 3.6 Every `<button>` in the widget and picker is `type="button"` (the staff issue date sits inside a `<form>`)

## 4. Calendar picker

- [x] 4.1 Add an in-repo picker component beside the widget -- **no new npm dependency**
- [x] 4.2 Month grid starting on Sunday, with month/year navigation bounded to `MIN_YEAR..MAX_YEAR`. Open on the current valid value's month, else on today's
- [x] 4.3 Open from a button on the field; render inline inside the widget, not teleported, so the section modal's focus trap contains it; arrow-key navigable
- [x] 4.4 Escape closes the picker and calls `stopPropagation()` so the section modal stays open; focus returns to the input on any close; a selection emits per 3.3

## 5. Accessibility

- [x] 5.1 Input carries `aria-label` from `field.label`, `aria-describedby` pointing at the hint and (when present) the error element, and `aria-invalid` when an error is shown
- [x] 5.2 The picker toggle has an accessible name and `aria-expanded`; the grid has a role and an accessible name
- [x] 5.3 **Not run before archive -- deferred to register row `dd-mm-yyyy-date-entry-device-checks`.** Manual screen-reader pass over a date field: label, expected format, invalid state, picker open/close. **Record the result in the CR journal** -- this is the capability the native input was providing for free, so it is verified, not assumed

## 6. Staff console (`views/StaffConsole.vue`)

- [x] 6.1 Replace the native issue-date input with `DateWidget`, given a local field descriptor (`key: "issue-date"`, label `Issue date`, `type`/`widget` `date`, `required: false`); change the field's `<label>` wrapper to a `<div>` + text `<span>`
- [x] 6.2 `issueDateError = validateField(descriptor, issueDate)` feeds the widget's `error`; `canSubmit` requires the issue date non-empty **and** `issueDateError` null (one validity decision, not a second `parseIso` gate); the today default (`todayIso()`) is unchanged

## 7. Frontend tests

- [x] 7.1 Unit (`dateEntry.test.ts`): `parseIso` / `formatIso` round-trip and return null/empty on empty or malformed input without throwing; `formatIso("2026-01-08")` is `08/01/2026` under `TZ=America/New_York`, set via `process.env.TZ` in `beforeAll` and restored in `afterAll` (Vitest's default forks pool isolates files; there is no per-file TZ option), with a guard assertion that the zone took effect (`new Date(2026,0,8).getTimezoneOffset() === 300`). A regression guard: `formatIso` never renders through a host-locale formatter (`toLocale*` / `Intl.DateTimeFormat`)
- [x] 7.2 Unit: `parseEntry` covers:
  - `08/01/2026`, `8/1/2026`, and `08012026` all give `2026-01-08`;
  - `31/02/2026` and `31/04/2026` are `impossible-date`;
  - `08/01/20` and `08/01/26` are `incomplete`;
  - `2001-02-03` gives `2001-02-03`; `2026-02-31` is `impossible-date`;
  - `03-02-2001`, `03.02.2001`, and `abc` are `not-a-date`;
  - `29/02/2028` is accepted and `29/02/2027` rejected.
- [x] 7.3 Unit: month-length table for all twelve months; century leap years (`29/02/2000` valid, `29/02/2100` invalid); year bounds `31/12/1899`, `01/01/2200`, `08/01/0026`, and `29/02/2200` all `out-of-range`; `01/01/1900` and `31/12/2199` accepted
- [x] 7.4 Unit (`formModel.test.ts`): the `validateField` date branch returns each message in the table and none contains the entry; an empty required date gives the required message; an ISO-shaped impossible `2026-02-31` gets the not-a-real-date message; the save-blocking predicate is true for a malformed date and false for an empty one; `isSectionComplete` is false for a malformed required date; the existing `tenancyMonths` and range tests stay green after 1.4
- [x] 7.5 Component (`DateWidget.test.ts`, the first widget test in the repo):
  - typing `08/01/2026` emits `2026-01-08` without a blur, and `8/1/2026` is redisplayed as `08/01/2026` on blur;
  - an ISO `modelValue` displays as `08/01/2026` on mount;
  - `31/02/2026` emits the raw text, and its `error` prop is rendered while focused (full length);
  - a partial `08/0` emits the raw text, but its `error` prop is hidden while focused and shown after blur;
  - auto-slash: typing `0`,`8`,`0`,`1` gives `08/01`; a non-digit `insertText` is rejected (drive these with `beforeinput` events -- `setValue` bypasses the mask);
  - pasting `03-02-2001` (via `trigger("paste", { clipboardData: { getData: () => "03-02-2001" } })` -- jsdom has no `DataTransfer`) is not stripped and emits the raw text; a pasted `2001-02-03` emits `2001-02-03` and redisplays as `03/02/2001` on blur; a pasted `08/01/20261` is not truncated;
  - a picker selection followed by its `modelValue` echo does not re-format the text;
  - every button is `type="button"`;
  - an external `modelValue` change replaces the text, and a non-ISO `modelValue` is shown as-is;
  - `inputmode="numeric"`, `aria-label`, `aria-describedby`, and `aria-invalid` (when the `error` prop is set) are present.
- [x] 7.6 Component: the picker opens from its button, is arrow-key navigable, emits ISO on selection, returns focus to the input on close, and stops Escape propagation
- [x] 7.7 `CaptureForm.test.ts`:
  - move the date-driving helpers (`openTermWith` and the other `setValue("<iso>")` sites) to type `dd/mm/yyyy` (`setValue` fires `input`, which now emits; no blur needed for a valid date);
  - add: a saved start date edited to `31/02/2026` (then `blur`, then Save) blocks the save, keeps the modal open, and the stored `startDate` -- in `working` and in the localStorage draft -- is still `2026-01-08`;
  - add: a draft holding a non-ISO date loads with that value dropped;
  - add: the Term sidebar card reads `dd/mm/yyyy`;
  - add: a blank required date still saves with the required message;
  - add: Escape inside an open picker leaves the modal open.
- [x] 7.8 `StaffConsole.test.ts`: move every issue-date `setValue("<iso>")` site (`:105`, `:232`, `:263`, `:376`, `:501`) to `dd/mm/yyyy`; `:297` expects the displayed `formatIso(todayIso())`; the `:81` upload test asserts the uploaded `issueDate`; add: an invalid issue date disables upload; a typed `08/01/2026` followed by **Enter** (form submit, no blur) uploads `2026-01-08`; opening the picker and selecting a day does not submit the form
- [x] 7.9 Confirm no test asserts a host-locale-dependent date rendering

## 8. Verification and close-out

- [x] 8.1 Run the frontend tests and `npm run lint` from `frontend/`
- [x] 8.2 Run `npm run build` and confirm the chained `security:scan` still passes with **no new dependency** in the lockfile
- [x] 8.3 Manually verify on a machine set to **US regional settings** that the tenancy start date reads `08/01/2026` for 8 January 2026 -- the reported case -- and the staff console issue date likewise
- [x] 8.4 **Not run before archive (user verified on Mac only) -- deferred to register row `dd-mm-yyyy-date-entry-device-checks`.** Manually verify on a phone, or with device emulation, that a date field opens the numeric keypad and reads back `dd/mm/yyyy`
- [x] 8.5 Manually verify the deed still renders `08-Jan-2026` and the status view `8 Jan 2026`, both unchanged by this CR
- [x] 8.6 Run `openspec validate --strict dd-mm-yyyy-date-entry`

## Coverage

| Requirement | Scenario | Disposition | Where |
|---|---|---|---|
| Day-month-year order | Day-first order on a month-first host | COVERED | 7.5 (display from own formatter, host-locale independent); manual 8.3 |
| Day-month-year order | The submitted value stays ISO | COVERED | 7.5, 7.7 |
| Day-month-year order | A stored date is displayed day-first when resumed | GROUPED | Day-first order on a month-first host -- 7.5 |
| Day-month-year order | Display does not shift with the host time zone | COVERED | 7.1 |
| Invalid date blocks save | A day the month does not have is rejected | COVERED | 7.2, 7.4 |
| Invalid date blocks save | Editing a saved date to an invalid one does not save the old date | COVERED | 7.7 |
| Invalid date blocks save | An incomplete entry is rejected | COVERED | 7.2, 7.4 |
| Invalid date blocks save | A leap day is accepted only in a leap year | COVERED | 7.2 |
| Invalid date blocks save | A year outside the accepted range is rejected | COVERED | 7.3 |
| Invalid date blocks save | A pasted date in another format is reported, not reinterpreted | COVERED | 7.2, 7.5 |
| Invalid date blocks save | A pasted year-first ISO date is accepted | COVERED | 7.2, 7.5 |
| Invalid date blocks save | A required empty date is still reported as required and does not block saving | COVERED | 7.4, 7.7 |
| Keyboard / AT | A date is completed without a pointer | GROUPED | The submitted value stays ISO -- 7.5 |
| Keyboard / AT | The picker is keyboard operable | COVERED | 7.6 |
| Keyboard / AT | Escape closes only the picker | COVERED | 7.6, 7.7 |
| Keyboard / AT | Label, format, and invalid state reach assistive technology | COVERED | 7.5; screen-reader experience MANUAL 5.3 |
| Staff issue date | Issue date reads day-first on a month-first host | GROUPED | Day-first order on a month-first host -- 7.5 (same widget); manual 8.3 |
| Staff issue date | An invalid issue date blocks the upload | COVERED | 7.8 |
| Staff issue date | A valid issue date is submitted as ISO | COVERED | 7.8 (incl. Enter-submit with no blur) |
