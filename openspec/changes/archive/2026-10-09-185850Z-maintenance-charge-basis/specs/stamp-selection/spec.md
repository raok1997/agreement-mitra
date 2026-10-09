## MODIFIED Requirements

### Requirement: The customer is shown the saved key terms before paying

The stamp-quote step SHALL show, above the stamp quote, a summary of the agreement's key terms as the server has them stored when the step opens: the property address, monthly rent, security deposit, start date, end date, the term in months as stored, and each party's name with their role.

When the stored agreement's pinned template type is `residential` and its active sections include Charges & Utilities, the summary SHALL also show a maintenance line, read from the stored `maintenanceMode` and `maintenanceAmount`:

| Stored mode | The line shows |
|---|---|
| `fixed_amount` | The amount per month, paid with the rent, and the monthly total of rent plus maintenance |
| `included_in_rent` | That maintenance is included in the rent |
| `as_billed_by_society` | That the tenant pays the society as billed |
| `paid_by_owner` | That the owner pays it |

**Absent mode.** A stored agreement with the section active but no stored `maintenanceMode` SHALL be summarised as `as_billed_by_society`, the template default the deed also applies. An agreement that instead stores the pre-v8 `maintenanceBorneBy` is pinned to a deed that states that choice, and SHALL show no maintenance line.

**Reading the amount.** The stored amount SHALL be read only when it is a plain decimal of rupees with at most two decimal places (`^\d+(\.\d{1,2})?$`) and greater than zero. It SHALL be displayed exactly, paise included, through the same rupee formatter as the rent. The total SHALL be summed in whole paise, with the rent converted the same way. Under `fixed_amount` with an amount that is greater than zero but cannot be read so (such as `1e3`), the line SHALL still state that a fixed monthly maintenance charge is payable to the owner with the rent, and SHALL show no figure and no total. Under `fixed_amount` with an amount that is blank, zero or negative, the deed carries neither Fixed clause, so no maintenance line SHALL be shown. The line SHALL never state a maintenance term the deed does not carry.

No maintenance line SHALL be shown when the stored agreement's type is not `residential` (a commercial agreement reuses the section title), when it has no active Charges & Utilities section, or when the stored mode is outside the four values above.

The total SHALL be computed for display only and SHALL NOT be stored or written into the deed.

The summary SHALL be read from the stored agreement for the step's agreement id each time the step
opens. It SHALL NOT be read from the capture form's on-screen values, from the browser-stored draft or
from a copy held by the screen behind the step. The summary SHALL display only the fields listed above.
No other party detail (email, mobile number, address, father's name) and no other captured value
SHALL be displayed in it.

The summary SHALL load independently of the quote. While the stored terms are loading, the step SHALL
say so, and the action to continue to payment SHALL be disabled. If the stored agreement cannot be
read, the step SHALL show an error in place of the summary and SHALL offer no stamp option and no way
to continue to payment. The step's Back action SHALL stay available in every state.

The summary SHALL tell the customer to go back before paying if anything in it is wrong. It SHALL NOT
offer a way to change the terms from the step itself.

#### Scenario: The summary shows the stored terms, not the on-screen ones

- **GIVEN** an agreement stored with a monthly rent of ₹25,000
- **AND** the capture form's working values show a monthly rent of ₹24,000
- **WHEN** the customer reaches the stamp-quote step for that agreement
- **THEN** the summary shows a monthly rent of ₹25,000
- **AND** it shows the stored security deposit, start and end dates, term in months, property address
  and each party's name and role

#### Scenario: A fixed maintenance shows the amount and the monthly total

- **GIVEN** an agreement stored with a monthly rent of ₹25,000, Charges & Utilities active,
  `maintenanceMode = fixed_amount` and `maintenanceAmount = 3500`
- **WHEN** the customer reaches the stamp-quote step
- **THEN** the summary shows maintenance of ₹3,500 per month, paid with the rent
- **AND** it shows a monthly total of ₹28,500

#### Scenario: Paise are shown exactly

- **GIVEN** an agreement stored with a monthly rent of `25000.00`, Charges & Utilities active,
  `maintenanceMode = fixed_amount` and `maintenanceAmount = 3500.50`
- **WHEN** the customer reaches the stamp-quote step
- **THEN** the summary shows maintenance of ₹3,500.50 and a monthly total of ₹28,500.50

#### Scenario: Other maintenance modes show their arrangement without a total

- **GIVEN** an agreement stored with Charges & Utilities active and `maintenanceMode = included_in_rent`
- **WHEN** the customer reaches the stamp-quote step
- **THEN** the summary says maintenance is included in the rent
- **AND** it shows no monthly total

#### Scenario: No maintenance line without the section or with an unknown mode

- **WHEN** the customer reaches the stamp-quote step for a stored agreement that is commercial, or
  where Charges & Utilities is not active, or the stored mode is not one of the four values
- **THEN** the summary shows no maintenance line

#### Scenario: An absent mode is summarised as the default

- **GIVEN** an agreement stored with Charges & Utilities active and no `maintenanceMode`
- **WHEN** the customer reaches the stamp-quote step
- **THEN** the summary says the tenant pays the society as billed

#### Scenario: An unreadable fixed amount still states the arrangement

- **GIVEN** an agreement stored with Charges & Utilities active, `maintenanceMode = fixed_amount` and
  `maintenanceAmount = 1e3`
- **WHEN** the customer reaches the stamp-quote step
- **THEN** the summary says a fixed monthly maintenance charge is payable to the owner with the rent
- **AND** it shows no figure and no monthly total

#### Scenario: A fixed mode the deed does not state shows no line

- **GIVEN** an agreement stored with Charges & Utilities active, `maintenanceMode = fixed_amount` and
  `maintenanceAmount` blank or `0`
- **WHEN** the customer reaches the stamp-quote step
- **THEN** the summary shows no maintenance line

#### Scenario: An agreement from before the maintenance mode shows no line

- **GIVEN** an agreement stored with Charges & Utilities active, no `maintenanceMode` and
  `maintenanceBorneBy = owner`
- **WHEN** the customer reaches the stamp-quote step
- **THEN** the summary shows no maintenance line

#### Scenario: Only the listed fields are shown

- **WHEN** the summary is shown for an agreement whose parties have an email, mobile number, address
  and father's name on record
- **THEN** none of those details appear anywhere on the step

#### Scenario: Payment waits for the summary

- **GIVEN** the stamp quote has loaded but the stored agreement has not yet been read
- **WHEN** the customer looks at the step
- **THEN** the step says the saved terms are loading
- **AND** the continue-to-payment action is disabled
- **AND** when the stored agreement is then read, the summary appears and the action becomes enabled

#### Scenario: Unreadable terms block payment

- **WHEN** reading the stored agreement fails
- **THEN** the step shows an error in place of the summary
- **AND** it shows no stamp option and no continue-to-payment action
- **AND** the Back action is still available

#### Scenario: A wrong term sends the customer back, not into an edit

- **WHEN** the summary is shown
- **THEN** it asks the customer to go back before paying if anything is wrong
- **AND** the step offers no control that changes the agreement's terms
