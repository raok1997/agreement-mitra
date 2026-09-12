## Why

Two display problems make the capture form and the rendered document read poorly:

1. **Dates are inconsistent and ambiguous.** In the **Preview** and generated **Agreement** most
   dates render as raw **ISO** (`2026-08-01`) while the header execution line renders `1 July 2026`;
   the form INPUT (native picker) shows locale `MM/DD/YYYY`. Three formats, none the DD-MON-YYYY form
   Indian users expect.
2. **Enum list-box values are raw tokens.** Every select/dropdown shows the stored token --
   `independent_house`, `bank_transfer`, `two_wheeler`, `upi`, `pg_room` -- i.e. lower-case, often
   `_`-separated. They should read as human labels: **Initial Capitals, `_` replaced with a space**,
   with acronyms correct (`UPI`, `PG`).

This change humanises both: one clear date format everywhere it is rendered, and human labels for
every enum list box.

## What Changes

### A. Rendered dates -> a single `13-Jul-2026` form
- All **rendered** dates use `dd-MMM-yyyy` (title-case English month, zero-padded day) in **Preview**
  and the **Agreement**: Term dates (`startDate`, `endDate`), the header execution line
  (`agreementDate`), and any date a clause/table renders.
- Formatted **at render time in the compiler** from the underlying ISO value; `showWhen` keeps
  evaluating on **ISO** (unchanged). The projection stops pre-formatting the execution date
  (`1 July 2026`) -> one formatting locus. This **supersedes** `rental-document-content-v2` for the
  execution-line date.

### B. Enum values -> human labels (list boxes AND the document body)
- Each enum value gains a **display label** derived from it: `_` -> space, Title Case, with an
  acronym set kept upper-case and already-capitalised tokens preserved. The **stored value is
  unchanged** (`independent_house` is still submitted/stored/compared) -- only the **displayed form**
  changes. `showWhen`, defaults, and validation still run on the raw value.
- The label is produced by one shared humaniser used in **two** places, so the list box and the
  rendered document read identically: (1) the backend **form projection** carries the label on each
  option (the frontend select renders label, submits value); (2) the **compiler** humanises an enum
  value wherever it renders it in the **preview / PDF body** -- a key/value cell (`Bank Transfer`) or
  a clause slot ("used for Residential purposes only") -- presentation-only, leaving the data map
  raw.

**Reviewed enum values (all list boxes) -> labels:**

| Field | Value -> Label |
| --- | --- |
| propertyType | apartment->Apartment, independent_house->Independent House, villa->Villa, gated_community->Gated Community, pg_room->PG Room |
| bhkConfiguration | studio->Studio, 1BHK->1BHK, 2BHK->2BHK, 3BHK->3BHK, 4BHK->4BHK, 5BHK->5BHK (already capitalised -- preserved) |
| furnishingStatus | unfurnished->Unfurnished, semi_furnished->Semi Furnished, fully_furnished->Fully Furnished |
| paymentMode | bank_transfer->Bank Transfer, upi->UPI, cheque->Cheque, cash->Cash |
| maintenanceBorneBy / utilitiesBorneBy / registrationChargesBorneBy | tenant->Tenant, owner->Owner, shared->Shared |
| permittedUse | residential->Residential, commercial->Commercial |
| parkingType | none->None, two_wheeler->Two Wheeler, four_wheeler->Four Wheeler, both->Both |
| disputeResolution | courts->Courts, arbitration->Arbitration, mediation->Mediation |

Acronyms needing an explicit rule: **UPI**, **PG**. Everything else is plain Title-Case + `_`->space;
`bhkConfiguration` values already carry capitals and are preserved verbatim.

## Out of scope (deferred / decided)

- **The form INPUT date widget stays the native picker** (decided) -- its locale `MM/DD/YYYY` display
  cannot be forced to `dd-MMM-yyyy` without a custom component; only the *rendered* output changes. A
  custom DD-MON-YYYY input component is a possible follow-on.
- **Amount** formatting and **clause-numbering** -- remain with `rental-document-content-v2`.
- No enum **value** rename, no migration, no template-definition value change (labels are derived, not
  authored).

## Capabilities

### Modified Capabilities
- **document-rendering** -- SHALL render, in preview and generate, every date-typed value in a
  single `dd-MMM-yyyy` (`13-Jul-2026`) form AND every enum value humanised (the same label the form
  list box shows -- e.g. `bank_transfer` -> `Bank Transfer`), formatting each from its raw value at
  render time while retaining the raw value for `showWhen`; a missing value renders the labelled
  placeholder.
- **template-document-projection** -- the projected form schema SHALL carry a human display **label**
  for every enum option (Initial Caps, `_`->space, acronyms upper-case, already-capitalised tokens
  preserved), derived from the option value; the stored/submitted value is unchanged.

## PII / security checklist

- **Introduces or moves PII?** No. Dates and enum labels are already-present agreement/form data;
  this changes only presentation. No Aadhaar / OTP / VID / secret.
- **Logging / redaction:** unchanged -- neither the HTML nor any value is logged.
- **Determinism:** the date format is locale-fixed and the label is a pure function of the value, so
  the reproducibility pin and the cacheable form schema stay stable; no new pin work.
- **Sandbox + dummy data only:** preserved. No dependency, schema, or migration change.
