# preview-centric-capture Specification

## Purpose

The preview-centric capture shell: the customer edits an agreement section by section with a live
document preview beside it, and nothing is persisted until they choose to save.

Created 2026-09-05 by archiving change `preview-centric-capture`. The `MODIFIED` delta of
`capture-mandatory-optional-ux` (archived 2026-07-13 without its spec fold running, see
`openspec/BASELINE-FOLD-GAP.md`) is applied over it here, since that change refined these same three
requirements and its fold was never run.
## Requirements
### Requirement: Preview an in-progress agreement without saving it

The system SHALL provide a **stateless** preview that renders an **in-progress** agreement supplied
in the request body (not a persisted agreement) and returns the rendered document, storing nothing.
`POST /api/templates/document/preview` SHALL accept the in-progress agreement data and return an
inline document (`application/pdf`, `Content-Disposition: inline`, `Cache-Control: no-store`), and
SHALL also be able to return the composed **HTML** for an embeddable live preview (content-negotiated
via `Accept`). The preview request SHALL carry the set of **added optional section titles**
(`activeSections`), and the client SHALL send `activeSections` on **every** preview request -- both the
live HTML face and the Download PDF face -- so that an added optional section renders in the previewed
document (even if partially filled) while an optional section that has not been added does not, and
mandatory sections always render. The preview SHALL render **partial** data -- missing sections or
fields SHALL render as placeholders rather than fail. No agreement, draft, or preview artifact SHALL be
persisted.

#### Scenario: Partial data renders a placeholder document

- **WHEN** a client POSTs an in-progress agreement with only the property filled (no parties yet)
- **THEN** the system responds `200 OK` with an inline document that shows the property and
  placeholders where the parties are not yet provided
- **AND** nothing is persisted (no agreement row, no draft, no stored preview)

#### Scenario: The live preview is served as HTML, non-cacheable

- **WHEN** a client POSTs an in-progress agreement with `Accept: text/html`
- **THEN** the system responds with the composed, escaped HTML of the document, marked
  `Cache-Control: no-store`

#### Scenario: Added optional sections render only when active, on both faces

- **WHEN** the client sends a preview request whose `activeSections` includes an optional section's
  title (for either the HTML or the PDF face)
- **THEN** the previewed document renders that optional section's content, and a preview request whose
  `activeSections` omits that title renders without it, while mandatory sections render in both cases

#### Scenario: The in-progress preview still bounds its input

- **WHEN** a client POSTs an in-progress agreement whose party list or field values exceed the allowed
  bounds (e.g. more than the maximum number of parties, or over-long fields)
- **THEN** the system rejects it with a `400` and renders nothing
- **AND** required-ness is still relaxed (blank/missing fields alone do not cause a rejection)

### Requirement: Section-based editing refreshes the preview

Capture SHALL be organised as **sections** edited independently, and each section SHALL be marked
**mandatory** or **optional** from the fetched `FormSchema`'s section semantics (`FormSection.optional`)
rather than inferred from field-level required-ness. Mandatory sections SHALL always be present in the
rail and always contribute to the document; **optional** sections SHALL be presented in an **"Add
optional" catalog** and SHALL be **opt-in** -- absent from the preview until the user adds them. Adding
an optional section SHALL move it into the active set and SHALL make its content contribute to the
previewed document; removing it SHALL take its content back out. Saving a section SHALL update the
in-progress working set and the preview SHALL reflect the change. Party and property **data values
SHALL remain escaped** in the rendered preview.

#### Scenario: Sections are marked mandatory or optional from the schema

- **WHEN** the capture surface renders a fetched `FormSchema` whose sections carry `optional` flags
- **THEN** the sections with `optional: false` are shown as mandatory sections in the rail and the
  sections with `optional: true` are shown in the Add-optional catalog, driven by the schema flag and
  not by whether a section happens to contain a required field

#### Scenario: An optional section is absent until added, then contributes to the preview

- **WHEN** the user adds an optional section from the Add-optional catalog
- **THEN** its title is included in the `activeSections` sent on the next preview request and the
  previewed document updates to include that section, and before it was added the previewed document did
  not include it

#### Scenario: Saving a section updates the previewed document

- **WHEN** the user edits and saves the Owner section
- **THEN** the previewed document updates to show the owner's details in place of the owner
  placeholder

#### Scenario: Markup in a field is shown as text in the preview

- **WHEN** a party field contains angle-bracket markup
- **THEN** the previewed document shows it as literal text, never interpreted as document structure

### Requirement: Completeness is shown and persistence is deliberate

The capture surface SHALL indicate which **mandatory** sections are complete and which still need
input; the completeness rules SHALL agree with the server's final-save validation, so a surface marked
complete does not then fail creation. "Save & continue" SHALL be **hard-blocked** -- the action
disabled/blocked, not merely warned -- until **every mandatory section is complete**, and the surface
SHALL show a clear "complete N more required section(s)" affordance naming how many mandatory sections
remain. Optional sections (whether added or not, complete or not) SHALL NOT block Save. Nothing SHALL
be persisted **server-side** while editing; the agreement and its draft SHALL be persisted only on an
explicit final action, through the existing create and generate-as-draft paths. Any in-progress working
set held **client-side** for refresh-resume -- including the set of added optional sections -- SHALL be
cleared once the agreement is saved and on an explicit reset.

#### Scenario: Required-section status is visible

- **WHEN** a mandatory section has not yet been provided
- **THEN** the capture surface shows that section as still needing input

#### Scenario: Save is blocked until every mandatory section is complete

- **WHEN** one or more mandatory sections are still incomplete
- **THEN** the "Save & continue" action is disabled/blocked and the surface shows a
  "complete N more required section(s)" affordance with N equal to the number of incomplete mandatory
  sections, and an incomplete optional section never contributes to N or blocks the action

#### Scenario: The final action persists via the existing paths

- **WHEN** the user completes the mandatory sections and confirms
- **THEN** the system creates the agreement and generates its draft through the existing endpoints,
  and only then is anything persisted server-side
- **AND** any client-held in-progress working set, including the added-optional-section set, is cleared
  after the successful save

### Requirement: A derived field is displayed, never collected

The capture surface SHALL render a field the FormSchema marks read-only as a **displayed, non-editable
value**, and SHALL NOT accept keyboard entry for it, include it in the section's saved values, or
count it toward section completeness.

For the tenancy term specifically, the displayed value SHALL be the term in whole months computed from
the captured start and end dates, using the same whole-month, end-inclusive, truncating count the
server uses, so the value on screen matches the value the rendered document states. While either date
is missing, the surface SHALL show that the term is not yet determined rather than show a default or a
stale number.

#### Scenario: The duration follows the dates instead of being typed

- **WHEN** the user opens the Term section and sets a start date of 2026-01-08 and an end date of
  2028-01-08
- **THEN** the duration is shown as 24 months
- **AND** the duration cannot be edited directly

#### Scenario: The displayed duration matches the previewed document

- **WHEN** the user saves a Term section with dates spanning 24 whole months
- **THEN** the duration shown in the capture surface and the term stated in the previewed document are
  both 24 months

#### Scenario: The duration is undetermined until both dates are set

- **WHEN** the user has set a start date but no end date
- **THEN** the surface shows the term as not yet determined rather than a default value

### Requirement: The capture surface rejects an end date that is not after the start date

The capture surface SHALL report a validation error when the captured end date is **on or before** the
captured start date, and SHALL surface it against the end date field before the agreement is
submitted.

The error SHALL also **block the section from being saved** and SHALL prevent the section counting as
complete, because a saved reversed range reaches the preview, where the derived term is non-positive.
Blocking applies to cross-field errors only: a per-field "required" error SHALL remain saveable, since
capture is progressive and a section may be filled over more than one visit.

This SHALL be understood as a usability affordance, not the trust boundary: the server independently
rejects the same condition, and the surface's check exists so the user is corrected in place rather
than by a submission failure.

#### Scenario: An end date before the start date is reported

- **WHEN** the user sets a start date of 2026-06-01 and an end date of 2026-01-01
- **THEN** an error is shown against the end date

#### Scenario: An end date equal to the start date is reported

- **WHEN** the user sets an end date equal to the start date
- **THEN** an error is shown against the end date

#### Scenario: A valid range is accepted

- **WHEN** the user sets an end date strictly after the start date
- **THEN** no date-range error is shown

#### Scenario: A reversed range cannot be saved

- **WHEN** the user sets a start date of 2026-06-01 and an end date of 2026-01-01
- **THEN** the save control for the section is disabled and the section does not count as complete

#### Scenario: A part-filled section can still be saved

- **WHEN** the user sets a start date and leaves the end date empty
- **THEN** the section can still be saved, because a missing required value is not a cross-field error

### Requirement: Dates are entered in day-month-year order regardless of host locale

The capture surface SHALL present every date field in **`dd/mm/yyyy`** order, identical on every
operating system, browser, device, and regional setting, and SHALL NOT delegate the displayed date
format to the host platform.

This applies on touch devices too: a host configured for month-first order renders an Indian date in
an order the user cannot detect as wrong by reading it back, and a platform date control that has been
closed reads the picked value back in that host order.

The expected order SHALL be visible to the user as a hint on the field, not only implied by the value
present.

A valid entry SHALL be submitted in the ISO `yyyy-mm-dd` form the API expects, so the change of display
order SHALL NOT alter any submitted payload, stored value, or rendered document.

#### Scenario: Day-first order on a month-first host

- **GIVEN** a device whose regional settings use month-first date order
- **WHEN** the user opens a section containing a date field holding 8 January 2026
- **THEN** the field shows `08/01/2026` in day-month-year order
- **AND** the field shows a `dd/mm/yyyy` hint

#### Scenario: The submitted value stays ISO

- **WHEN** the user enters `08/01/2026` into the tenancy start date and saves the section
- **THEN** the value saved for that field is `2026-01-08`

#### Scenario: A stored date is displayed day-first when resumed

- **WHEN** a draft holding an ISO date is resumed into the capture surface
- **THEN** the date field displays it in `dd/mm/yyyy` order

#### Scenario: Display does not shift with the host time zone

- **GIVEN** a host whose time zone is behind UTC
- **WHEN** a date field displays the ISO date `2026-01-08`
- **THEN** it shows `08/01/2026`, not the previous day

### Requirement: An invalid date is reported against the field and blocks saving its section

The capture surface SHALL report a validation error against a date field whose entry is not an
acceptable calendar date, and SHALL refuse to save the section while that entry stands. It SHALL NOT
submit or store such an entry, SHALL NOT silently replace it with the field's previous value, and SHALL
NOT silently discard it.

An entry SHALL be treated as invalid when any of the following holds:

- it names a day that the given month and year do not have (for example `31/02/2026` or
  `31/04/2026`);
- it is incomplete;
- it is not a date at all;
- its year falls outside the range the server accepts (1900 to 2199).

A **leap day** SHALL be accepted in a leap year and reported as invalid in a common year.

A **missing** date SHALL remain non-blocking, as any missing field is: it is reported as required, and
the section can still be saved and completed on a later visit.

The error message SHALL name the field and the expected format, and SHALL NOT restate the rejected
entry.

#### Scenario: A day the month does not have is rejected

- **WHEN** the user enters `31/02/2026` into a date field and leaves the field
- **THEN** an error is shown against that field

#### Scenario: Editing a saved date to an invalid one does not save the old date

- **GIVEN** a section whose start date was saved as 8 January 2026
- **WHEN** the user changes it to `31/02/2026` and saves the section
- **THEN** the section is not saved and stays open with the error shown
- **AND** the saved start date is neither the invalid entry nor silently kept as 8 January 2026 in
  place of the user's edit

#### Scenario: An incomplete entry is rejected

- **WHEN** the user leaves a date field holding `08/01/20`
- **THEN** an error is shown against that field rather than a value being inferred

#### Scenario: A leap day is accepted only in a leap year

- **WHEN** the user enters `29/02/2028`
- **THEN** it is accepted
- **AND** entering `29/02/2027` is reported as invalid

#### Scenario: A year outside the accepted range is rejected

- **WHEN** the user enters `31/12/1899` or `01/01/2200`
- **THEN** an error is shown against that field
- **AND** `01/01/1900` and `31/12/2199` are accepted

#### Scenario: A pasted date in another format is reported, not reinterpreted

- **WHEN** the user pastes `03-02-2001` into a date field and leaves the field
- **THEN** an error is shown against that field
- **AND** no date is inferred from its digits

#### Scenario: A pasted year-first ISO date is accepted

- **WHEN** the user pastes `2001-02-03` into a date field and leaves the field
- **THEN** it is accepted as 3 February 2001 and shown as `03/02/2001`

#### Scenario: A required empty date is still reported as required and does not block saving

- **WHEN** a required date field is left empty and the section is saved
- **THEN** the field is reported as required, as any other required field is
- **AND** the section saves

### Requirement: Date entry remains operable by keyboard and assistive technology

The capture surface SHALL keep date entry fully operable without a pointer and legible to assistive
technology.

A user SHALL be able to complete a date field using the keyboard alone, without opening any picker.
Where a calendar picker is offered, it SHALL be reachable and dismissable by keyboard, SHALL move
focus predictably on open and on close, and dismissing it SHALL NOT dismiss the section containing it.

Each date field SHALL expose its label, its expected format, and its invalid state to assistive
technology.

#### Scenario: A date is completed without a pointer

- **WHEN** a user tabs to a date field and types a date
- **THEN** the value is accepted without any picker being opened

#### Scenario: The picker is keyboard operable

- **WHEN** a user opens the calendar picker from the keyboard
- **THEN** it can be navigated and dismissed from the keyboard, and focus returns to the field on
  close

#### Scenario: Escape closes only the picker

- **GIVEN** the calendar picker is open inside a section
- **WHEN** the user presses Escape
- **THEN** the picker closes and the section stays open with its entries intact

#### Scenario: Label, format, and invalid state reach assistive technology

- **WHEN** a date field holds an invalid entry
- **THEN** the field exposes its label, is exposed as invalid, and has its format hint and error
  message associated with it

