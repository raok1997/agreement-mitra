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
  input**, identical on every operating system and browser, with a visible `dd/mm/yyyy` hint.
- Typed entry is accepted and normalised: the widget stores the ISO `yyyy-mm-dd` value the API and
  the document projection already expect, so **nothing outside the widget changes** -- no API
  contract, no payload shape, no stored value, no rendered output.
- A **calendar picker** replaces the native one, since suppressing native rendering also removes the
  native picker. It opens from the field, supports keyboard navigation, and writes the same ISO value.
- Entry is validated in place: an impossible date (`31/02/2026`), an incomplete one, or a
  non-date is reported against the field rather than silently producing an empty or wrong value.
- **On touch devices the native input is retained**, because the OS date wheel is materially better
  on a phone than any in-page calendar and the ambiguity is much reduced when the user is spinning a
  labelled picker rather than typing digits. See `design.md` -- this is the one place the design
  deliberately keeps two code paths.
- **Not breaking.** The widget's public contract (`modelValue` in, `update:modelValue` out, both ISO)
  is unchanged, so every call site, test, and server expectation continues to hold.

### Deliberately out of scope

- **The deed's date format.** It stays `dd-MMM-yyyy`. A month name cannot be misread, which is what a
  legal instrument wants; moving the deed to numeric `dd/mm/yyyy` would reduce clarity and would force
  a template version bump for no gain.
- **The status and listing views.** Already `en-IN` with a short month name; unambiguous as they are.
- **A general date-localisation or i18n layer.** This product is single-locale (India). One convention
  is hard-coded, deliberately, rather than introducing a locale framework for one format.

## Capabilities

### New Capabilities

_None. This refines how an existing capture surface collects an existing field._

### Modified Capabilities

- `preview-centric-capture`: the capture surface SHALL collect dates in `dd/mm/yyyy` order
  independently of host locale, SHALL report an invalid or impossible date against the field, and
  SHALL continue to submit ISO date values.

## Impact

- **Frontend**: `components/widgets/DateWidget.vue` (the rewrite), a new calendar-picker component
  beside it, a new date parse/format/validate helper module, and `views/formModel.ts` if date
  validity is surfaced through the shared validation path rather than inside the widget.
- **Frontend tests**: a new `DateWidget` component test (the widget has none today -- no widget does),
  unit tests for the parse/format helpers, and the existing `CaptureForm.test.ts` updated where it
  drives a date field.
- **Backend**: none. The widget's emitted value is unchanged ISO, so `SubmittedDataValidator`,
  `TemplateCompiler`, the form projection, and every stored value are untouched.
- **No migration.** No persisted value, column, or API field changes shape or meaning.
- **Accessibility**: this is the main cost. A native date input carries built-in screen-reader
  semantics, keyboard behaviour, and mobile pickers for free; replacing it means owning all three.
  Treated as a first-class requirement in the spec, not an afterthought.

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
- **Signing status FSM**: untouched. This is pre-`PDF_GENERATED` capture behaviour and adds, removes,
  and reorders no `SignatureStatus` transition.
- **Trust boundary**: unchanged. Client-side date validation remains a UX affordance; the server
  continues to validate and normalise submitted dates (`SubmittedDataValidator` parses to ISO) and
  continues to reject an end date not strictly after the start date. A client that bypasses the
  widget gains nothing.
