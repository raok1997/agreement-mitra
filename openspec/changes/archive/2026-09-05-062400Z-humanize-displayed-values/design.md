## Context

Two independent display surfaces, one theme (humanise what the user sees):

- **Dates.** The compiler (`TemplateCompiler`) is a pure function of `(effective template, data map,
  resolved date, active sections)`. Date fields arrive as **ISO strings** (`SubmittedDataValidator`
  normalises `DATE -> ISO`; `AgreementDocumentMapper` emits `startDate`/`endDate` as
  `LocalDate.toString()`). Today `valueOrPlaceholder` renders `String.valueOf(value)` = raw ISO, and
  `DocumentProjectionService` separately pre-formats the execution date to `1 July 2026`. `showWhen`
  (`ShowWhenEvaluator`) compares dates via `LocalDate.parse` on the **data map** (ISO), not the
  rendered output.
- **Enum labels.** `FormProjector` projects each `Field` to a public `FormField`; an enum field's
  `options` cross as a plain `List<String>` of the stored values, and the frontend `<select>` shows
  them verbatim. `FormField` already carries a human field-level `label`, so per-option display
  labels are a consistent extension of the same schema.

Constraints unchanged: pure/deterministic compiler; cacheable, data-independent form schema; keep
`ModularityTests` green; version-pinned byte-stable re-render; never log values; sandbox + dummy only.

## Decisions

### D1: Dates -- format at render time in the compiler, never in the data map
The compiler formats a **DATE** field's value to `dd-MMM-yyyy` only in the **output** paths
(`valueOrPlaceholder` / `renderSlotText` / `appendKvRow` / party card / annexure), keying off
`FieldType` (already in `fieldsByKey`). The **data map keeps ISO**, so `ShowWhenEvaluator` still parses
ISO and every date comparison is unchanged. **Rejected:** formatting dates in the data map -- it
would break `showWhen` and couple presentation to logic.

### D2: Dates -- one formatting locus (unify the execution line)
`DocumentProjectionService.resolveExecutionDate` binds the resolved execution date as **ISO** (the
submitted `agreementDate` when present, else `LocalDate.now(clock).toString()`); the compiler formats
`agreementDate` like any other date field. The old `EXECUTION_DATE_FORMAT` (`d MMMM yyyy`) is removed.
This **supersedes** `rental-document-content-v2` for the execution-line date. **Rejected:** two
formatters -- divergent and forces the compiler to detect an already-formatted value.

### D3: Date format = `dd-MMM-yyyy`, Locale.ENGLISH
`DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH)` -> `13-Jul-2026`. Locale-fixed ->
deterministic regardless of host locale (parity + reproducibility). Defensive: format only when the
value parses as an ISO `LocalDate`; a missing date keeps the `[ label ]` placeholder; an unparseable
value renders raw (never throw, never leak).

### D4: Enum labels -- one shared humaniser, used in the form projection AND the compiler
A single package-private `OptionLabels.humanize(value)` (in `documents.template`) is the sole source
of the display label, used in **two** places so the list box and the rendered document read
identically:
- `FormProjector` computes the label per enum option; `FormField.options` becomes a list of
  `{value, label}` (was `List<String>`); the frontend `<select>` renders `label`, submits `value`.
- `TemplateCompiler.formatForDisplay` humanises an enum value wherever it renders it in the body (a
  key/value cell or a clause slot), the same funnel that formats dates -- presentation-only.

Derived (not authored in the definition) because the label is a pure, mechanical function of the
value -- no per-enum YAML edit, no definition-schema/value change, no migration. **Rejected:** (a)
frontend-only humanisation -- would fix the list box but not the document body, and duplicate the
rule in TS; (b) explicit `{value,label}` authored on every enum in `base.yaml` + patches -- larger
surface for a purely mechanical label; (c) two separate humanisers (form vs compiler) -- risks the
list box and the document diverging.

### D5: Enum label rule (handles the reviewed set + acronyms)
`OptionLabels.humanize(value)`: split on `_` (and spaces) -> Title-Case each word; **preserve** a
token that already contains an upper-case letter (`1BHK` .. `5BHK`) verbatim; map a small **acronym
set** to upper-case (`upi -> UPI`, `pg -> PG`). This yields the reviewed mapping (e.g.
`independent_house -> Independent House`, `pg_room -> PG Room`, `bank_transfer -> Bank Transfer`,
`upi -> UPI`). The stored value, `showWhen`, defaults, and validation (`enum` membership is checked
on the **value**) are untouched. In the document body an enum in a sentence reads Title-Cased (e.g.
"used for Residential purposes only") -- accepted for consistency with the list box; exact clause
prose remains a `rental-document-content-v2` concern.

### D6: Boundaries + reproducibility
Both changes are deterministic, pure steps. The compiler stays a pure function (pinned re-render
byte-stable); the form schema stays data-independent and cacheable per `(state, type,
layer-version-set)`. `documents` stays domain-agnostic; `ModularityTests` unaffected. No template,
schema, migration, or dependency change. Frontend: the `<select>` widget renders `{value,label}`
(only the option shape changes); the native date picker is untouched (by decision).

## Risks / Trade-offs

- **Existing assertions.** Tests asserting raw ISO / `1 July 2026` (dates) or a bare option string
  (`FormProjectorTest`, the form-schema E2E, `templateForm.test.ts`) must move to the new forms.
  Mechanical but spread across a few files, backend + frontend.
- **Public DTO shape change.** `FormField.options` `List<String> -> List<Option{value,label}>` is an
  API-contract change; the frontend `templateForm.ts` type + `SelectWidget` + their tests update in
  lockstep. No other consumer.
- **Input vs rendered mismatch (accepted).** The native date picker still shows `MM/DD/YYYY`; only the
  rendered preview/agreement change. Accepted by decision.

## Open Questions

- None blocking. Month case = title-case `Jul`; input widget unchanged; enum labels derived with the
  `{UPI, PG}` acronym set -- all decided. New acronyms later just extend the set.
