## ADDED Requirements

### Requirement: The customer is shown the saved key terms before paying

The stamp-quote step SHALL show, above the stamp quote, a summary of the agreement's key terms as the server has them stored when the step opens: the property address, monthly rent, security deposit, start date, end date, the term in months as stored, and each party's name with their role.

The summary SHALL be read from the stored agreement for the step's agreement id each time the step
opens. It SHALL NOT be read from the capture form's on-screen values, from the browser-stored draft or
from a copy held by the screen behind the step. The summary SHALL show only the fields listed above.
No other party detail (email, mobile number, address, father's name) and no other captured value
SHALL appear in it.

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
