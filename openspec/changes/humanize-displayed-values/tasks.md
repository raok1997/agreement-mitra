# Tasks -- humanize-displayed-values

Two display fixes under one theme. Dates: format DATE values at render time in the compiler + unify
the execution line (S1-S2). Enum labels: derive per-option labels in the form projection + render
them in the select (S3-S4). Tests (S5). No template-definition value change, no migration.

## 1. Dates -- format DATE values at render time (`documents` engine)

- [x] 1.1 Add a deterministic date-format helper to `TemplateCompiler` --
  `DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH)` -> `13-Jul-2026` (D3). Pure, no I/O.
- [x] 1.2 Format a field's value **only** when the field is `FieldType.DATE` and the value parses as
  an ISO `LocalDate`; else render as today (D4). Apply in `valueOrPlaceholder` (kv cells, party
  cards, annexure) and `renderSlotText` (clause slots + header execution line). Missing/blank -> the
  `[ label ]` placeholder; unparseable -> raw (never throw / leak).
- [x] 1.3 Leave the **data map untouched** so `ShowWhenEvaluator` still evaluates on the ISO value
  (D1); confirm date `showWhen` comparisons are unchanged.

## 2. Dates -- bind the execution date as ISO (unify the locus) (`documents.template`)

- [x] 2.1 In `DocumentProjectionService.resolveExecutionDate`, bind the resolved execution date as
  **ISO** (submitted `agreementDate` when present, else `LocalDate.now(clock).toString()`) instead of
  the pre-formatted `1 July 2026` (D2).
- [x] 2.2 Remove the now-unused `EXECUTION_DATE_FORMAT` from the projection service; the compiler is
  the single formatting locus. The header line now renders `13-Jul-2026`, superseding
  `rental-document-content-v2` for that date.

## 3. Enum labels -- derive per-option labels in the form projection (`documents.template` + `documents.api`)

- [x] 3.1 Add a pure `humanizeOption(value)` helper (D5): split on `_`/space -> Title-Case each word;
  **preserve** a token already containing an upper-case letter (`1BHK`..`5BHK`); map an **acronym
  set** `{ upi -> UPI, pg -> PG }` to upper-case. Yields the reviewed mapping (e.g.
  `independent_house -> Independent House`, `pg_room -> PG Room`, `upi -> UPI`).
- [x] 3.2 Change the public `FormField.options` from `List<String>` to a list of `{value, label}`
  (an `Option` record in `documents.api`); `FormProjector.projectField` builds one per enum option
  with the derived label. The **value is unchanged**; enum `min/max`/membership validation still runs
  on the value (`SubmittedDataValidator` unaffected). Keep the form schema data-independent + cacheable.
- [x] 3.3 Extract the humaniser into a shared package-private `OptionLabels` (used by both the form
  projection AND the compiler) and humanise an **ENUM** field's value in
  `TemplateCompiler.formatForDisplay` -- so the enum reads humanised in the **preview / PDF body**
  (key/value cells + clause slots), not just the list box. Presentation-only; the data map keeps the
  raw token so `showWhen` still gates on it.

## 4. Enum labels -- render the label in the select (`frontend`)

- [x] 4.1 Update the `Option` type in `frontend/src/api/templateForm.ts` (`string` -> `{ value,
  label }`) and `SelectWidget.vue` to render `option.label` as the text and bind `option.value` as the
  submitted value. The native **date** widget is untouched (decision).

## 5. Tests (pyramid -- required)

- [x] 5.1 **Unit (compiler, dates):** a `DATE` field renders `dd-MMM-yyyy` (`2026-08-05 ->
  05-Aug-2026`, proving zero-pad + title-case) in a clause slot, a kv cell, and the header execution
  line; a non-date field is unchanged; a missing date renders `[ label ]`; an unparseable value
  renders raw (no throw); a date `showWhen` still evaluates on the ISO value.
- [x] 5.2 **Unit (projection, dates):** `resolveExecutionDate` binds ISO; the compiled execution line
  reads `dd-MMM-yyyy` from a fixed clock.
- [x] 5.3 **Unit (form projection, labels):** `humanizeOption` yields the reviewed mapping incl.
  acronyms (`upi->UPI`, `pg_room->PG Room`) and preserved tokens (`1BHK->1BHK`); `FormProjector`
  emits one `{value,label}` per enum option with the value unchanged.
- [x] 5.4 **Integration (Testcontainers PG + MinIO + Gotenberg):** the form-schema endpoint returns
  humanised option labels with unchanged values; generate + id-bound preview render dates as
  `dd-MMM-yyyy` (parity); the pinned effective-template hash is unchanged across re-render.
- [x] 5.5 **Frontend unit:** the select renders labels and submits values; **update** existing
  assertions that expect raw option strings or ISO / `1 July 2026` rendered dates
  (`TemplateCompilerTest`, `DocumentProjectionServiceTest`, `ProductionRentalLayerSetTest`, the format
  E2E test, `templateForm.test.ts`, `CaptureForm.test.ts`). Keep `ShowWhen`/validator tests on the
  raw value/ISO.

## 6. Verify + wrap-up

- [x] 6.1 Live drive (`:8090`, TG agreement): list boxes show human labels (Independent House, PG
  Room, UPI, Two Wheeler, ...); preview + generate show `13-Jul-2026` dates; `showWhen`-gated clauses
  and defaults still behave.
- [x] 6.2 `spotlessApply`; run the `documents.*` unit + integration suites + `ModularityTests`
  (Windows: `TESTCONTAINERS_RYUK_DISABLED=true`, `-Duser.timezone=Asia/Kolkata`) and the frontend unit
  tests. No dependency / lockfile change; no migration.
- [x] 6.3 Coordination note: supersedes the `rental-document-content-v2` execution-line date format;
  amount formatting + clause-numbering remain with that CR; a custom DD-MON-YYYY **input** component
  and humanising enum values in the **document body** are follow-ons.
