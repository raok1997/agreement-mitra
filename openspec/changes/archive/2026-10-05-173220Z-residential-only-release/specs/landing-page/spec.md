## MODIFIED Requirements

### Requirement: The FAQ is consistent with the rules engine and with its structured data
The home page FAQ SHALL NOT state a registration threshold as applying in every state. It SHALL NOT use "the twelve-month rule" as a universal statement, and SHALL NOT answer the e-signature question with an unconditional "Yes". It SHALL include a question on what the service costs and a question on what happens if something goes wrong. It SHALL NOT offer commercial agreements while the picker withholds them. The visible FAQ SHALL equal the FAQPage JSON-LD in `index.html`, question for question and answer for answer, in the same order.

#### Scenario: The 11-month answer is state-relative
- **GIVEN** the FAQ question "Why are most rental agreements in India for 11 months?"
- **WHEN** its answer is read
- **THEN** it contains "term longer than a year", "may require registration for shorter leases" and "Before you pay, we show whether your agreement may need registering"
- **AND** none of "does not need to be registered", "simply does not need", "twelve-month rule" and "below that threshold" appears anywhere in the FAQ

#### Scenario: Cost and something-goes-wrong questions exist
- **GIVEN** the FAQ
- **WHEN** its questions are listed
- **THEN** "What does it cost?" and "What happens if something goes wrong?" are present, and "What does stamp duty cost?" is not

#### Scenario: The e-signature answer is not an unconditional yes
- **GIVEN** the FAQ question "Is an Aadhaar OTP signature legally valid?"
- **WHEN** its answer is read
- **THEN** it does not begin with "Yes"

#### Scenario: The cities answer offers residential agreements only
- **GIVEN** the FAQ question "Which cities do you serve?"
- **WHEN** its answer is read
- **THEN** it names Telangana and Karnataka and says "residential"
- **AND** "commercial" appears nowhere in the FAQ

#### Scenario: Visible FAQ equals the JSON-LD
- **GIVEN** the FAQ rendered under `[data-testid="faq-q"]` and `[data-testid="faq-a"]`
- **WHEN** both are compared with the `FAQPage` node in `index.html`, whitespace collapsed
- **THEN** the questions and answers are equal in content and order
