## MODIFIED Requirements

### Requirement: The site footer discloses the operator without a network call
The landing page, the terms page and the policy pages (`/privacy`, `/refunds`, `/contact`) SHALL render one shared footer component, given the resolved operator entity. It SHALL contain:

- the sentence "AgreementMitra is a service of KAVISAT TEK LABS LLP.";
- the LLPIN, or the being-issued statement;
- the registered office, when configured;
- links to `/terms`, `/privacy`, `/refunds` and `/contact`;
- a `mailto:` link to `CONTACT_EMAIL`.

Every value SHALL come from build-time constants, and rendering the footer SHALL make no network request. The footer SHALL render values as escaped text, never as HTML.

The frontend SHALL read the operator variables only by naming each one. It SHALL NOT read `import.meta.env` as a whole object, and it SHALL NOT use raw `%VITE_*%` HTML replacement for them.

#### Scenario: Landing page footer
- **WHEN** the landing page renders
- **THEN** its footer contains the operator sentence and a `mailto:` link to `CONTACT_EMAIL`

#### Scenario: Terms page footer
- **WHEN** `/terms` renders
- **THEN** it contains the shared footer with the operator sentence, the `/terms` link and the `mailto:` link

#### Scenario: Footer links the policy pages
- **WHEN** the footer component mounts
- **THEN** it contains links with `href` `/terms`, `/privacy`, `/refunds` and `/contact`

#### Scenario: No request issued
- **GIVEN** the global `fetch` is spied
- **WHEN** the footer component mounts
- **THEN** `fetch` is never called

### Requirement: The terms of service name the operator and show its details outside the clause text
§1 of the terms of service SHALL state, in plain text with no interpolation, that the service is provided by KAVISAT TEK LABS LLP and that "we" and "us" mean that LLP. The LLPIN and the registered office SHALL NOT appear in any clause body or gap note of any policy document.

The `/terms` and `/privacy` pages and the generated `docs/TERMS-OF-SERVICE.md` and `docs/PRIVACY-POLICY.md` SHALL each carry an "Operator details" section after the clauses. The section SHALL give:

- the legal name;
- the LLPIN, or the being-issued statement;
- the registered office, or a to-be-confirmed statement;
- the support email.

The generated documents SHALL render that section from the committed defaults, never from the build environment. The section is deployment data and SHALL NOT form part of the text whose version a terms acceptance records.

#### Scenario: Page shows operator details
- **GIVEN** an operator entity with no LLPIN
- **WHEN** `/terms` renders with that entity
- **THEN** an "Operator details" section after the last clause names KAVISAT TEK LABS LLP and says the LLPIN is being issued

#### Scenario: Generated documents are environment-independent
- **GIVEN** `VITE_OPERATOR_LLPIN` is stubbed to `ACA-1234` and the policy modules are re-imported
- **WHEN** `renderLegalMarkdown()` runs for the terms of service and for the privacy policy
- **THEN** each output is identical to the output with the variable unset, and contains no `ACA-1234`

#### Scenario: Clause text holds no identifier
- **WHEN** the frontend test suite runs
- **THEN** no clause body or gap note of any policy document contains the text `LLPIN` or `GSTIN`

### Requirement: The operator's legal name is one committed value per runtime, pinned equal
The legal name of the operator SHALL be the committed value `KAVISAT TEK LABS LLP`, held in exactly two places:

- in the backend, as the constant `OperatingEntity.LEGAL_NAME`, not as a bindable property;
- in the frontend, as the legal-name constant in `src/content/operatorFacts.ts`.

A test SHALL fail when the two differ. A test SHALL fail when §1 of the terms of service does not contain the frontend constant verbatim. The legal name SHALL NOT be overridable from the environment, because the terms of service name the same party in plain text: an `operator.legal-name` property or an `OPERATOR_LEGAL_NAME` variable SHALL have no effect.

#### Scenario: Frontend and backend names agree
- **GIVEN** the backend `OperatingEntity.LEGAL_NAME` constant and the frontend legal-name constant
- **WHEN** the frontend test suite runs
- **THEN** it passes only if the two strings are identical

#### Scenario: Terms name the same party
- **GIVEN** the body of terms-of-service clause `who-we-are` (§1), looked up by id
- **WHEN** the frontend test suite runs
- **THEN** it passes only if that body contains the frontend legal-name constant verbatim

#### Scenario: The environment cannot rename the operator
- **GIVEN** `operator.legal-name` is set to another name in the environment
- **WHEN** the backend binds its configuration
- **THEN** binding succeeds and the legal name is still `KAVISAT TEK LABS LLP`

