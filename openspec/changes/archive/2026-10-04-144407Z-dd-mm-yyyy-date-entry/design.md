## Context

See `proposal.md` -- Why. The design-relevant state:

- `DateWidget.vue` is a thin wrapper over `<input type="date">`: value in, `update:modelValue` out,
  plus the shared error slot every widget renders. It is one of seven widgets dispatched by
  `FieldWidget.vue` on the schema's `widget` token, and it has **no test** -- no widget does.
- A native date input's `value` is **always** ISO `yyyy-mm-dd`; only its *rendering* follows the host
  locale.
- **The widget does not own its error.** `CaptureForm.vue` computes `modalErrors` from the section's
  working copy (`sectionErrors(fields, modalForm)`) and passes each field's message down as the `error`
  prop. `validateField` in `formModel.ts` has **no date branch** today: any non-empty string passes.
- **Saving a section blocks only on cross-field rules** (`saveSection`). Per-field errors, including
  "required", are deliberately non-blocking, because capture is progressive.
- `formModel.ts` already holds a private `parseIsoDate` that round-trips through `Date` to reject a day
  the month does not have. It backs the derived tenancy term and the date-range rule.
- The server parses submitted dates with `LocalDate.parse` and rejects years outside
  `PlausibleDates.MIN_YEAR..MAX_YEAR` (1900..2199).
- `StaffConsole.vue` carries a second native `<input type="date">`, for the stamp certificate's issue
  date, defaulted to today and gated by `canSubmit` on non-emptiness only.
- The repo has **no date library** and a deliberately small frontend dependency surface, with a
  fail-on-any OSV gate over the whole npm lockfile including dev dependencies.

## Goals / Non-Goals

**Goals:**

- The same date order on every machine and device, so a user can verify what they entered by reading
  it back.
- An entered date is either saved as typed or visibly refused -- never silently replaced by the
  previous value, never silently dropped.
- No change visible to the server: same ISO values, same payloads, same deed.
- Keep the replacement honest about what a native input was giving us for free (accessibility) rather
  than silently losing it.

**Non-Goals:**

- A locale or i18n framework. One hard-coded convention, because the product is single-locale.
- Changing the deed, the status view, or any other date *presentation*. See proposal -- out of scope.
- A general-purpose date-picker component. The widget serves the app's two date-entry surfaces.

## Decisions

### 1. A masked text input, not a native date input

The widget renders `<input type="text" inputmode="numeric">` with a `dd/mm/yyyy` placeholder and a
visible hint, parses the entry, and emits ISO. There is no `maxlength` attribute: the browser would
truncate a paste *before* it is trimmed, and `08/01/20261` would silently become a valid `08/01/2026`.

*Why not keep `type="date"` and restyle it:* the display format of a native date input is not
addressable. There is no attribute, no CSS property, and no pseudo-element that sets field order;
Chrome reads the OS locale and ignores the page `lang`, which `index.html` already sets to `en-IN`
with no effect.

*Why not `type="text"` with no mask:* free text invites `8.1.26`, `Jan 8 2026`, and `8-1-2026`, each of
which we would then have to guess at -- and guessing month-vs-day order is the original bug.

**Mask rules** (kept small so caret handling stays trivial):

- Filtering happens on `beforeinput`, keyed on `inputType`, **not on `keydown`**. Android IMEs report
  every key as keyCode 229, so a keydown filter does nothing there.
- Typed text (`insertText`) other than digits and `/` is rejected, and so is typing past ten
  characters.
- Slashes are inserted **lazily**. When a digit is typed at the end of the text and the text is exactly
  `dd` or `dd/mm`, a `/` goes in *before* the digit (`08` + `1` gives `08/1`). Phone number pads have
  no `/` key, so this is also how a user who backspaced over a slash gets it back. Backspace, deletion,
  and edits in the middle of the text are left alone: the mask never rewrites text the caret is inside.
- A **paste** (`insertFromPaste`) is handled by the widget. It reads `clipboardData.getData("text")`,
  trims it, and inserts it whole, never stripped to its digits and never truncated. Stripping would turn
  a pasted `2001-02-03` into `20/01/0203`, a real date. Unstripped, a paste in an ambiguous format
  (`03-02-2001`) fails parsing and is reported, and anything over ten characters is `not-a-date`.
- A **year-first ISO** `yyyy-mm-dd` entry is accepted, however it arrives (paste, drop, autofill). Its
  order cannot be misread, and blur redisplays it as `dd/mm/yyyy`. This is required, not a kindness:
  the widget emits an unparseable entry as its raw text (Decision 5), and a raw `2001-02-03` *is* a
  valid stored value, so refusing it in `parseEntry` would show an error the save then ignores.
- On blur, a valid entry is redisplayed in canonical form: `8/1/2026` and `08012026` both become
  `08/01/2026`. Both are unambiguous, since the order is fixed by the field.

### 2. An in-repo calendar picker, not an npm date picker

The picker is written in this repo, in Vue, with no new dependency.

*Why not a library:* the frontend OSV gate scans the **entire** lockfile and fails on any unsuppressed
finding. A date picker is a deep transitive tree for a component that needs a month grid, arrow-key
movement, and an ISO emit. Taking on a permanent supply-chain and upgrade obligation for that is a bad
trade in identity/legal infrastructure.

*Why a picker at all:* removing the native one without replacement is a usability regression for anyone
who does not know the date offhand, and a date many months out is tedious to type.

**Behaviour:**

- It opens on the month of the current valid value, or on today's month when the field is empty or
  invalid.
- Weeks start on Sunday. Navigation is bounded to the accepted year range (Decision 3).
- Selecting a date sets the text, emits ISO, closes the picker, and returns focus to the input.
- It renders **inline, inside the widget's own DOM**, never teleported, so it sits inside the section
  modal's focus trap (`CaptureForm.vue` `trapTab` collects focusables within the dialog).
- Escape closes the picker **and stops propagation**. The section modal closes itself on a
  document-level Escape and does not check `defaultPrevented`, so without that the picker's Escape
  would discard the whole section.

### 3. One pure module owns date parsing, formatting, and validity

`views/dateEntry.ts` (beside `formModel.ts`; no Vue, no DOM) exposes:

- `parseIso(iso)` -- ISO text to `{year, month, day}`, or null. It checks **calendar validity only,
  not year bounds**: `tenancyMonths` and the date-range rule keep their current behaviour, and bounds
  are applied in exactly one place, `validateField`.
- `formatIso(iso)` -- ISO to `dd/mm/yyyy`; empty or non-ISO input returns empty.
- `parseEntry(text)` -- `d/m/yyyy`, `dd/mm/yyyy`, eight digits, or ISO `yyyy-mm-dd` to ISO, or a typed failure:
  `incomplete` | `not-a-date` | `impossible-date` | `out-of-range`.
- `MIN_YEAR` / `MAX_YEAR`.

**It is the single owner of date validity.** The private `parseIsoDate` in `formModel.ts` moves here and
`formModel.ts` imports it. Two round-trip implementations side by side are the drift this repo keeps
paying for.

**Validity is checked by round trip.** Build the date from its components, then check the constructed
date reports back the same day, month, and year. `new Date(2026, 1, 31)` silently rolls over to
3 March, so without the round trip `31/02/2026` would be accepted. Construction uses `Date.UTC` and
`getUTC*`, so the host time zone never takes part.

**`parseEntry` checks the year bounds *before* the round trip.** Both `new Date(y, m, d)` and
`Date.UTC` map years 0..99 to 1900+, so `08/01/0026` would otherwise fail the round trip and be
mislabelled `impossible-date` ("check the day and month"). With the bounds checked first it is
`out-of-range`, and so is `29/02/2200`. Inside 1900..2199 that mapping can never apply.

**ISO strings are split, never handed to `new Date(iso)`.** `new Date("2026-01-08")` is UTC midnight,
which reads as 7 January in any negative-offset zone -- including the US-configured hosts this change
exists for. The tests run the formatter under a US time zone to pin this.

**The year bounds mirror `PlausibleDates.MIN_YEAR..MAX_YEAR` (1900..2199).** This is deliberately the
same fact in two places: the server cannot be called per keystroke, and an out-of-range year caught
only by the server surfaces as a preview failure, away from the field. The constants cite the Java
source, and boundary tests (1899/1900, 2199/2200) catch drift on the client side. The server stays
authoritative.

A two-digit year needs no reason of its own. Under the mask, `08/01/26` is simply `incomplete`.

### 4. One render path on every device; no native control on touch

The masked input is used everywhere. On a phone, `inputmode="numeric"` opens the number pad.

*Considered and rejected -- keeping `type="date"` on touch devices.* The OS wheel is pleasant **while
picking**, but once picked the closed field displays the value in the device's regional order.
Android in particular typically shows numerals, so a phone set to US regional settings reads 8 January
back as `01/08/2026` -- the original defect, on the device most customers use. It would also have
cost a second render path, a `matchMedia` probe that jsdom does not provide, and a second set of
component tests.

### 5. An invalid entry reaches the form as text, and a malformed date blocks the save

The widget owns no error state. It hands what the user committed to the form, and the form's existing
validation path decides what to say. That keeps a single source of error messages, shared with every
other field.

**The model always mirrors the text.** The widget emits on every input, every picker selection, and
every paste:

| Entry | Emitted |
|---|---|
| empty | `""` |
| valid | the ISO string |
| anything else | the raw trimmed text |

**Correctness never depends on a blur.** Round 2 found two blur-only gaps:

- **Enter in the staff console** submits its `<form>` without a blur, so the old date would be uploaded.
- **Save clicks** blur first on some browsers and not others, and never under `@vue/test-utils`
  `trigger("click")`.

Emitting on input closes both. Whatever the field shows is what the form holds, at every moment, so no
click or key path can save a value other than the one on screen. It also keeps the derived tenancy
term live as the user types, as it is today.

**Only the error's visibility waits.** A half-typed `08/0` is someone typing, not a mistake. So while
the input has focus **and** the text is shorter than a full date (ten characters), the widget does not
render the `error` prop it is given. On blur it does. A complete-length but impossible `31/02/2026`
shows its error at once.

This is display only: the form's error state, the save block, and `aria-invalid` are unaffected. A
Save click on a still-incomplete field is still refused. The click moves focus off the input on every
real browser, and that reveals the message.

**Resync from `modelValue`.** The widget's text is initialised from `modelValue` on mount, and that
value seeds the last-emitted record. Both surfaces remount the widget each time they open:
`CaptureForm.vue`'s modal and `StaffConsole.vue`'s row form are both `v-if`.

Afterwards, when `modelValue` changes to anything other than the last value emitted, the text is
replaced:

- with `formatIso(value)` when the value is ISO;
- with the raw value otherwise.

The echo of the user's own emit therefore never overwrites what they are typing. A picker selection
updates the last-emitted record like any other emit.

**`validateField` gains a date branch** for `type === "date"`:

- an empty value falls through to the existing "required" handling;
- an ISO value outside the year bounds gives the range message;
- any other value is passed to `parseEntry` for the reason and its message. That includes an
  ISO-shaped impossible value such as `2026-02-31`, which reads as `impossible-date`. A well-formed
  `dd/mm/yyyy` reaching the model is reported as `not-a-date`: only ISO is storable, and the widget
  never emits one.

Messages name the field and the format and never restate the entry:

| Reason | Message |
|---|---|
| `incomplete` | `<Label> is incomplete -- enter it as dd/mm/yyyy.` |
| `not-a-date` | `<Label> must be a date in dd/mm/yyyy format.` |
| `impossible-date` | `<Label> is not a real date -- check the day and month.` |
| `out-of-range` | `<Label> must be between 1900 and 2199.` |

**Missing is allowed; wrong is not.** `formModel.ts` exposes a single save-blocking set: the existing
cross-field rules plus any date field holding a non-empty value that `validateField` rejects.
`saveSection` blocks on that set, where today it blocks on the cross-field rules alone. A blank date
still saves, as every blank field does. A malformed one keeps the modal open with its error showing.

The Save button's `:disabled` binding stays on the **cross-field rules only**. Disabling it for a
malformed date as well would make a click on it do nothing. A click on a disabled button does not
reliably move focus, so the error would stay hidden under the rule above, leaving a dead button and no
explanation. Instead, the click is accepted, the handler refuses, and the blur shows why.

`isSectionComplete` already runs every required field through `validateField`, so a section holding a
malformed required date now counts as incomplete. That is intended.

**Drafts.** `loadDraft` already drops a stored enum value the current template no longer accepts. It
drops a date value that is not valid ISO in the same way. A tampered or foreign draft therefore cannot
put raw text into the working set, which `schedulePreview` sends without passing through
`saveSection`.
That is the only way to honour "never submit, store, or silently discard": saving the typed text would
send garbage to the preview, and saving the previous value is the silent replacement this decision
exists to prevent.

**Consequences, accepted:**

- The modal's working copy can hold non-ISO text while the user types. Its readers tolerate that
  already: `tenancyMonths` and the date-range rule both go through `parseIso` and treat a non-ISO value
  as "not yet a date".
- The text never leaves the client, because the save blocks and drafts are filtered on load.

**The sidebar summary formats dates.** `summary()` in `CaptureForm.vue` shows a section's first
non-empty value as stored, so a Term card today reads `2026-01-08`. That is another place the user
reads back what they entered, so a date field is shown there through `formatIso`.

### 6. The staff console's issue date uses the same widget

`StaffConsole.vue` renders `DateWidget` for the issue date with a local field descriptor:

- `key: "issue-date"`, which keeps the existing `field-issue-date` test id;
- label `Issue date`;
- `type` and `widget` both `date`;
- `required: false`.

`required` is false so that clearing the field shows no "required" message. That matches the sibling
certificate-number check, which is silent when empty. The emptiness gate stays in `canSubmit`, as today.

**Validity is decided once.** `issueDateError = validateField(descriptor, issueDate)` supplies the
widget's `error`. `canSubmit` requires the issue date to be non-empty and `issueDateError` to be null.
Gating separately on `parseIso` would be the same fact checked two ways: it skips the year bounds, so
an out-of-range ISO value could show an error and still upload.

**Markup:**

- The field's `<label>` wrapper becomes a `<div>` with a text `<span>`. A label around an input plus a
  picker toggle plus a grid of day buttons would forward clicks to the wrong control and name the input
  twice.
- **Every button in the widget and the picker is `type="button"`.** The issue date sits inside a
  `<form @submit.prevent>`, where an untyped button is a submit button. Opening the calendar or picking
  a day would otherwise upload the certificate.

**Enter** submits the form with whatever the field shows, because the model mirrors the text
(Decision 5). A malformed date fails `canSubmit`, and `submit()` already refuses when that is false.

The default stays today's date (via `todayIso()`), so the common path is untouched.

*Why in this change:* it is the same defect on a date with legal weight (the certificate's issue
date), and once the widget exists, the fix is a few lines.

## Risks / Trade-offs

- **[The replacement is less accessible than the native input it removes]** -> The largest real risk.
  Mitigated in four ways:
  - accessibility is a spec requirement with its own scenarios;
  - the input carries `aria-label` from the field label, `aria-describedby` for the hint and the error,
    and `aria-invalid`, all asserted in component tests;
  - full keyboard entry works without opening the picker;
  - a manual screen-reader pass is an explicit task.
- **[Phone users lose the OS date wheel]** -> Accepted (Decision 4). A numeric keypad plus the in-page
  picker covers entry, and the read-back is now correct.
- **[An in-repo picker is code we now own]** -> Accepted deliberately over a dependency (Decision 2).
  Bounded: a month grid, arrow-key movement, and an emit. No date maths beyond `dateEntry.ts`.
- **[Client and server year bounds drift]** -> The constants cite `PlausibleDates`, and boundary tests
  pin them. A drift fails toward the server's stricter or looser check, never toward a wrong date.
- **[Users paste a date in another format]** -> Reported rather than misread, because a paste is never
  stripped to digits. The one other format accepted is year-first ISO, which cannot be misread
  (Decision 1).
- **[Automated tests that drive the old native input keep passing without testing anything]** -> Not
  every one fails loudly. The staff console tests type an ISO issue date over a valid today default and
  never assert what was uploaded, so they could pass while the date they typed is ignored. The task list
  moves every date-driving site to `dd/mm/yyyy` and adds an assertion on the uploaded value.

## Migration Plan

No data migration and no backend deployment. Valid entries emit the same ISO as before, and invalid
ones never leave the client, so nothing persisted, transmitted, or rendered changes meaning.

A draft saved before this change holds ISO, or blank, and displays day-first on resume.

Rollback is a revert of the frontend change, with nothing to clean up.
