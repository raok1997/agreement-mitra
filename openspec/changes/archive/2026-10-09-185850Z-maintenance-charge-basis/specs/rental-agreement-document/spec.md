## ADDED Requirements

### Requirement: The user chooses how maintenance charges are handled

The rental Charges & Utilities section SHALL capture maintenance as one optional enum, `maintenanceMode`, labelled "How is maintenance handled?", with exactly four options: `included_in_rent`, `fixed_amount`, `as_billed_by_society`, `paid_by_owner`.

**Default.** The field SHALL default to `as_billed_by_society`, so the section stays defaulted under the parity contract.

**One clause per option.** Each option SHALL render exactly one maintenance clause, selected by a `showWhen` on the option value. No other option's maintenance clause SHALL appear:

| Option | Clause |
|---|---|
| `included_in_rent` | The monthly rent includes the society and building maintenance charges, which the Owner shall pay to the society directly. |
| `fixed_amount` | In addition to the rent, the Tenant shall pay the Owner a maintenance charge of INR {{maintenanceAmount}} per month, together with the rent. |
| `as_billed_by_society` | The Tenant shall pay the society and building maintenance charges directly to the society, as billed by it during the tenancy. |
| `paid_by_owner` | The society and building maintenance charges shall be borne by the Owner, who shall pay them to the society directly. |

**Fixed amount.**

- The Fixed clause and its revision clause SHALL render only when `maintenanceMode` is `fixed_amount` **and** `maintenanceAmount` is greater than zero.
- The revision clause says the amount is revised to match a revision of the society's charges, from the month after the Owner gives the Tenant written notice with a copy of the society's demand.
- `maintenanceAmount` SHALL be labelled "Maintenance amount (INR / month) – only if Fixed".
- Under any other option, `maintenanceAmount` SHALL have no effect on the document.

**Society levies.** Whenever the section is active and `propertyType` is `apartment`, `gated_community` or `villa`, whatever the option, the section SHALL render a clause. It states that any one-time or capital levy raised by the society, including sinking fund, corpus fund, major-repair and non-occupancy charges, is borne by the Owner, even where the society bills it to the Tenant. For `independent_house` and `pg_room` the clause SHALL NOT render.

**Removed.** The section SHALL no longer declare `maintenanceBorneBy`, `maintenanceClause` or `maintenanceAmountClause`. The rental base layer's `meta.version` SHALL be bumped.

**Capture form.** The capture form SHALL refuse to save the Charges & Utilities section while `maintenanceMode` is `fixed_amount` and `maintenanceAmount` is blank or not greater than zero. It SHALL show the error on the amount field. This is the same cross-field mechanism that refuses an end date before the start date.

#### Scenario: Each mode renders its own clause and only that one

- **GIVEN** the rental effective template for `(TG, residential)` with Charges & Utilities active and `maintenanceAmount = 3500`
- **WHEN** the document is compiled once for each of the four `maintenanceMode` options
- **THEN** each compile contains that option's maintenance clause
- **AND** none of the other three options' maintenance clauses

#### Scenario: A fixed amount states who pays whom, on top of the rent

- **WHEN** the document is compiled with `maintenanceMode = fixed_amount` and `maintenanceAmount = 3500`
- **THEN** it contains "In addition to the rent, the Tenant shall pay the Owner a maintenance charge of INR 3500 per month, together with the rent."
- **AND** it contains the revision clause
- **AND** it does not contain "payable amount to"

#### Scenario: An amount typed under another mode is not printed

- **WHEN** the document is compiled with `maintenanceMode = included_in_rent` and `maintenanceAmount = 3500`
- **THEN** the compiled Charges & Utilities section contains no "3500"

#### Scenario: Society levies are always the Owner's

- **WHEN** the document is compiled with Charges & Utilities active and `propertyType = apartment`, for any `maintenanceMode`
- **THEN** it contains the society-levy clause naming sinking fund, corpus fund, major-repair and non-occupancy charges as borne by the Owner, even where billed to the Tenant

#### Scenario: No society-levy clause for an independent house

- **WHEN** the document is compiled with Charges & Utilities active and `propertyType = independent_house`
- **THEN** it does not contain the society-levy clause

#### Scenario: A fixed mode with a zero amount prints no amount

- **WHEN** the document is compiled with `maintenanceMode = fixed_amount` and `maintenanceAmount = 0`
- **THEN** neither the Fixed clause nor the revision clause appears

#### Scenario: The form will not save Fixed without an amount

- **GIVEN** the Charges & Utilities section open in the capture form
- **WHEN** the user chooses Fixed Amount and leaves the amount blank, or enters 0
- **THEN** the amount field shows an error and the section cannot be saved
- **AND** entering an amount, or choosing another option, clears the error

#### Scenario: A stored draft carrying the removed key still validates

- **GIVEN** stored capture data that includes `maintenanceBorneBy = owner`
- **WHEN** the data is validated against the new template
- **THEN** validation succeeds and the key is dropped from the coerced values

#### Scenario: The defaulted mode keeps parity

- **WHEN** the parity guards run over the rental layer set
- **THEN** they pass, because `maintenanceMode` is optional with a default and `maintenanceAmount` is optional

## MODIFIED Requirements

### Requirement: Section render kinds drive the document layout

Each rental section SHALL declare a `render` kind so the compiler dispatches its body: `Owner` and
`Tenant` -> `parties`; `Schedule of Property`, `Term`, and `Financial` -> `keyvalue` (the compiler
composes `Term` and `Financial` into the "Terms of Tenancy" table); the witnesseth section -> `clauses`;
the optional `Charges & Utilities` section -> `clauses`; the optional fixtures/inventory annexure ->
`annexure`. The render kind SHALL be declared in the template content, never hardcoded in the compiler
or the frontend.

A `clauses` section that lists fields, as `Charges & Utilities` does, SHALL still offer those fields
in the capture form. In the document it SHALL render only its included clauses, so no field label,
value, or `[ label ]` placeholder from that section appears in the deed.

#### Scenario: Render kinds are declared per section

- **WHEN** the rental set is resolved for `(IN, residential)`
- **THEN** the `Owner`/`Tenant` sections declare render kind `parties`, `Schedule of Property`/`Term`/
  `Financial` declare `keyvalue`, the `Now This Agreement Witnesseth` and `Charges & Utilities`
  sections declare `clauses`, and the optional `Annexure` section declares `annexure`

#### Scenario: Term and Financial compose the Terms of Tenancy table

- **WHEN** the resolved set is compiled
- **THEN** the `Term` and `Financial` key/value sections render as the "Terms of Tenancy" table content

#### Scenario: Charges & Utilities prints clauses and no blanks

- **WHEN** the set is compiled with `Charges & Utilities` active, no `maintenanceAmount` and no
  `latePaymentPenalty`
- **THEN** the section renders as a numbered clause list with no key/value table
- **AND** the document contains no `[ Maintenance amount (INR / month) ]`,
  `[ Late-payment penalty (INR) ]` or "Grace period" text

#### Scenario: Charges & Utilities still asks its fields

- **WHEN** the form schema is projected for `(TG, residential)`
- **THEN** the `Charges & Utilities` section lists `maintenanceMode`, `maintenanceAmount`,
  `utilitiesBorneBy`, `latePaymentPenalty` and `gracePeriodDays` as form fields
