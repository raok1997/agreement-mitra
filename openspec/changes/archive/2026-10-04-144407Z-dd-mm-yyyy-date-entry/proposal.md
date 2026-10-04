## Why

The capture form collects dates through a native `<input type="date">`, whose **displayed** format is
chosen by the browser and operating system, not by the page. On a machine set to US regional settings
a tenancy running from 8 January 2026 renders in the form as `08/01/2026` **in month-first order** --
indistinguishable from 1 August 2026 to an Indian reader. Declaring `lang="en-IN"` on the document
does not change this: Chrome takes the format from the host locale and ignores the page language, and
no CSS or attribute can override it.

This is a public, all-India product where `dd/mm/yyyy` is the universal convention, and the field in
question fixes the term of a legal instrument: a tenancy entered as 1 August when 8 January was meant
misstates the commencement date of the deed, the term it is stamped for, and the date from which
notice and lock-in run. The user cannot even detect the error by reading the form back, because the
form will keep showing it the way their OS chose to.

The rendered deed is **not** affected and is not in scope: it already prints `dd-MMM-yyyy`
(`08-Jan-2026`) from a locale-fixed formatter, and the status view already prints `8 Jan 2026` under
`en-IN`. Both are unambiguous by construction. The defect is confined to data **entry**.

## What Changes

- The date widget stops using the browser's native date rendering and presents a **`dd/mm/yyyy`
  input**, identical on every operating system, browser, and device, with a visible `dd/mm/yyyy` hint.
  On a phone it opens a numeric keypad (`inputmode="numeric"`).
- A valid entry is normalised to the ISO `yyyy-mm-dd` value the API and the document projection already
  expect, so **no API contract, payload shape, stored value, or rendered output changes**.
- A **calendar picker** replaces the native one, since suppressing native rendering also removes the
  native picker. It opens from the field, supports keyboard navigation, and writes the same ISO value.
- An invalid entry -- an impossible date (`31/02/2026`), an incomplete one, a non-date, or a year
  outside the server's accepted range -- is **reported against the field and blocks saving the
  section**. A *missing* date stays non-blocking, as every missing field is today (capture is
  progressive). The rule is: missing is allowed, wrong is not. This closes the path by which an edited
  date could be silently replaced by the previously saved one.
- The **staff console's certificate issue date** -- the only other date input in the app, with the
  same host-locale defect on a date that matters to the stamp's validity -- uses the same widget.

### Deliberately out of scope

- **The deed's date format.** It stays `dd-MMM-yyyy`. A month name cannot be misread, which is what a
  legal instrument wants; moving the deed to numeric `dd/mm/yyyy` would reduce clarity and would force
  a template version bump for no gain.
- **The status and listing views.** Already `en-IN` with a short month name; unambiguous as they are.
- **A general date-localisation or i18n layer.** This product is single-locale (India). One convention
  is hard-coded, deliberately, rather than introducing a locale framework for one format.
- **A native date control on touch devices.** Considered and rejected (`design.md` Decision 4): a
  phone set to month-first regional settings reads the picked date back month-first, which is the
  defect this change exists to remove.

## Capabilities

### New Capabilities

_None. This refines how existing surfaces collect an existing kind of field._

### Modified Capabilities

- `preview-centric-capture`: the capture surface SHALL collect dates in `dd/mm/yyyy` order
  independently of host locale, SHALL report an invalid date against the field and refuse to save the
  section while it stands, and SHALL continue to submit ISO date values.
- `estamp-intake`: the staff console SHALL collect the certificate issue date in `dd/mm/yyyy` order and
  SHALL NOT allow an upload while the issue date is invalid.

## Impact

- **Frontend**:
  - `components/widgets/DateWidget.vue` -- the rewrite, plus a new calendar-picker component beside it.
  - New pure module `views/dateEntry.ts` -- parse, format, calendar validity, year bounds. It becomes
    the single owner of date validity: the private `parseIsoDate` in `formModel.ts` moves into it.
  - `views/formModel.ts` -- `validateField` gains a date branch, and a malformed date joins the
    cross-field rules as save-blocking.
  - `views/CaptureForm.vue` -- `saveSection` blocks on the widened blocking set; `loadDraft` drops a
    non-ISO stored date; the sidebar summary shows dates as `dd/mm/yyyy`.
  - `views/StaffConsole.vue` -- the issue date uses `DateWidget`, and `canSubmit` requires a valid date.
- **Widget contract** (internal only): `update:modelValue` carries ISO for a valid entry, and the
  **raw typed text** for anything else, emitted on every input so the model always mirrors what the
  field shows. The shared validation path sees and reports it, and the text never leaves the client:
  the section save and the stamp upload both refuse it.
- **Frontend tests**:
  - a new `DateWidget` component test (no widget has one today);
  - unit tests for `dateEntry.ts` and the new `formModel.ts` date branch;
  - the existing `CaptureForm.test.ts` and `StaffConsole.test.ts` updated where they drive a date field.
- **Backend**: none. `SubmittedDataValidator`, `StampIntakeRequest`, `TemplateCompiler`, the form
  projection, and every stored value are untouched. The client's year bounds mirror `PlausibleDates`
  (1900..2199); the server remains authoritative.
- **No migration.** No persisted value, column, or API field changes shape or meaning.
- **Accessibility**: this is the main cost. A native date input carries screen-reader semantics and
  keyboard behaviour for free; replacing it means owning both. Treated as a first-class requirement in
  the spec, not an afterthought.

## PII / security review

- **New or moved PII**: none. The change handles calendar dates -- the tenancy start, end, and
  agreement dates -- which are contract terms, not personal identifiers. No Aadhaar number, OTP,
  virtual ID, KYC attribute, or signer contact detail is read, derived, stored, or transmitted.
- **New outbound flows**: none. The widget is entirely client-side; it adds no endpoint, no request,
  and no persisted field. No new dependency is introduced -- the picker is written in-repo rather
  than pulled from npm, which also keeps the frontend dependency-scan surface unchanged.
- **Logging**: none added. The widget logs nothing; a rejected entry produces an on-screen message
  naming the field and the expected format, never the captured value.
- **Sandbox + dummy data only**: preserved. No credential, secret, or environment change.
- **Signing status FSM**: untouched. Capture is pre-`PDF_GENERATED`; the staff issue date feeds the
  existing stamp upload (`PDF_GENERATED -> STAMPED`) with the same ISO value it sends today. No
  `SignatureStatus` transition is added, removed, or reordered.
- **Trust boundary**: unchanged. Client-side date validation remains a UX affordance; the server
  continues to validate and normalise submitted dates (`SubmittedDataValidator` parses to ISO) and
  continues to reject an end date not strictly after the start date. A client that bypasses the
  widget gains nothing.
