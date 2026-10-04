## Context

See `proposal.md` -- Why. The design-relevant state:

- `DateWidget.vue` is a thin wrapper over `<input type="date">`: value in, `update:modelValue` out,
  plus the shared error slot every widget renders. It is one of seven widgets dispatched by
  `FieldWidget.vue` on the schema's `widget` token, and it has **no test** -- no widget does.
- A native date input's `value` is **always** ISO `yyyy-mm-dd`; only its *rendering* follows the host
  locale. So the current widget's contract is already the one we want to keep; only the presentation
  layer is wrong.
- The server already parses and normalises submitted dates to ISO (`SubmittedDataValidator`), and the
  compiler already formats for display (`dd-MMM-yyyy`). Neither needs to know how the value was typed.
- The repo has **no date library** and a deliberately small frontend dependency surface, with a
  fail-on-any OSV gate over the whole npm lockfile including dev dependencies.

## Goals / Non-Goals

**Goals:**

- The same date order on every machine, so a user can verify what they entered by reading it back.
- No change visible to anything outside the widget -- same emitted value, same payloads, same deed.
- Keep the widget's replacement honest about what a native input was giving us for free
  (accessibility, mobile pickers) rather than silently losing it.

**Non-Goals:**

- A locale or i18n framework. One hard-coded convention, because the product is single-locale.
- Changing the deed, the status view, or any other date presentation. See proposal -- out of scope.
- A general-purpose date-picker component for the wider app. This serves the capture form's date
  fields; generalising it is not a goal and would invite scope the spec does not cover.

## Decisions

### 1. A masked text input, not a native date input

The widget renders `<input type="text" inputmode="numeric">` with a `dd/mm/yyyy` placeholder and hint,
parses the entry, and emits ISO.

*Why not keep `type="date"` and restyle it:* the display format of a native date input is not
addressable. There is no attribute, no CSS property, and no pseudo-element that sets field order;
Chrome reads the OS locale and ignores the page `lang`, which `index.html` already sets to `en-IN`
with no effect. Restyling is not an option that exists.

*Why not `type="text"` with no mask:* free text entry invites `8.1.26`, `Jan 8 2026`, and `8-1-2026`,
each of which we would then have to guess at -- and guessing month-vs-day order is the original bug.
A fixed mask makes the order a property of the field rather than an inference from the value.

*Consequence, accepted:* losing `type="date"` also loses the native picker, native screen-reader date
semantics, and the mobile OS wheel. Decisions 2 and 3 address the first two; Decision 4 keeps the
third where it matters most.

### 2. An in-repo calendar picker, not an npm date picker

The picker is written in this repo, in Vue, with no new dependency.

*Why not a library:* the frontend OSV gate scans the **entire** lockfile including dev and transitive
dependencies and fails on any unsuppressed finding. A date picker is a deep transitive tree for a
component that needs a month grid, arrow-key movement, and an ISO emit -- perhaps two hundred lines.
Taking on a permanent supply-chain and upgrade obligation to avoid that is a bad trade in a repo whose
scan policy is deliberately strict, and this is identity/legal infrastructure.

*Why a picker at all:* removing the native one without replacement is a usability regression for
anyone who does not know the date offhand, and a date many months out is tedious to type.

### 3. Parsing, formatting, and validity live in a pure module, not in the component

A `dateEntry.ts` beside `formModel.ts` exposes parse (`dd/mm/yyyy` -> ISO or a typed failure), format
(ISO -> `dd/mm/yyyy`), and calendar validity. No Vue, no DOM.

*Why:* it is where every subtle case lives -- month lengths, leap years, two-digit-year rejection,
range bounds -- and those deserve table-driven unit tests without mounting a component. It mirrors how
`formModel.ts` already separates pure validation from the shell.

Validity is computed by **round-tripping**: build the date from the typed components, then check the
constructed date reports back the same day, month, and year. `new Date(2026, 1, 31)` silently rolls
over to 3 March, so a constructed-date check without the round-trip accepts `31/02/2026`. This is the
same overflow trap the sibling `derived-tenancy-term` change documents for
month arithmetic, and the tests pin it.

A **two-digit year is rejected** rather than expanded. `08/01/26` could mean 1926 or 2026; on a
tenancy date a silent wrong guess is worse than an error message.

### 4. Touch devices keep the native input

The widget selects its rendering once, on mount, from a coarse-pointer media query, and does not
re-evaluate on resize -- a control that changes type underneath a half-typed value is worse than
either choice alone.

*Why keep two paths, against this design's own preference for one:* on a phone, `type="date"` opens
the OS date wheel, which shows day, month, and year as separate labelled spinners. The month-first
ambiguity that motivates this whole change is a property of **typed digits in a row**; a labelled
wheel does not have it. Meanwhile an in-page calendar on a small screen is the weakest form of this
component. So the native control is both safer and better exactly where we would otherwise be
replacing it for no benefit.

The cost is honest: two render paths, two sets of component tests, and a seam that has to keep
emitting identical ISO on both. That cost is contained to one component with one narrow contract.

*Alternative considered and rejected:* masked text everywhere, for a single code path. It would make
the phone experience worse to buy uniformity the user never observes, since no user sees both paths.

### 5. Invalid entries surface through the field's existing error slot

The widget reports an unparseable or impossible entry through the same `error` prop and
`field-error-<key>` element every other widget uses, so the message appears where users and tests
already expect one.

An entry is validated when the field is **committed** (blur, or the section save), not on each
keystroke -- a half-typed `0` in `08/01/2026` is not an error, it is someone typing. While an entry is
invalid the widget emits **no value**, so an impossible date can never reach the working set as a
partial or coerced ISO string.

## Risks / Trade-offs

- **[The replacement is less accessible than the native input it removes]** -> The largest real risk:
  native date inputs carry screen-reader semantics, keyboard behaviour, and platform pickers that are
  easy to lose and hard to notice losing. Mitigated by making accessibility a spec requirement with
  its own scenarios, by keeping full keyboard entry possible without opening the picker at all, and by
  keeping the native control on touch. A manual screen-reader pass is an explicit task, not an assumed
  outcome.
- **[Two render paths drift]** -> Both are driven by the same pure `dateEntry.ts` and the same
  `modelValue` / `update:modelValue` contract, and both are covered by component tests asserting the
  same emitted ISO. The divergence is confined to which element is rendered.
- **[An in-repo picker is code we now own]** -> Accepted deliberately over a dependency (Decision 2).
  Bounded: a month grid, arrow-key movement, and an emit. No date maths beyond `dateEntry.ts`.
- **[Users paste a date in another format]** -> A pasted `2026-01-08` or `8 Jan 2026` fails the mask
  and is reported rather than misread. Accepting ISO on paste is a small future kindness, not needed
  for correctness, and is left out to keep one input convention.
- **[Automated tests that drive the old native input break]** -> Contained and visible: they fail
  loudly at the selector rather than passing with a wrong value. The task list updates them.

## Migration Plan

No data migration and no backend deployment. The widget's emitted value is unchanged ISO, so nothing
persisted, transmitted, or rendered changes meaning, and there is no ordering constraint against any
backend release.

Rollback is a revert of the frontend change; because no stored value was ever written in a new shape,
nothing needs cleaning up afterwards.

**Sequencing with `derived-tenancy-term`:** the two changes are independent
and touch different components (that one changes `FieldWidget`/`CaptureForm` and the Term section;
this one changes `DateWidget`). They overlap only in that both are exercised by `CaptureForm.test.ts`
and both concern the Term section, so whichever lands second updates the shared test file. Landing the
derived-term change first is mildly preferable, since its dd/mm entry then exercises the new widget
during its manual verification, but neither blocks the other.
