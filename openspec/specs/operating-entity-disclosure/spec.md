# operating-entity-disclosure Specification

## Purpose
TBD - created by archiving change operating-entity-disclosure. Update Purpose after archive.
## Requirements
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

### Requirement: Pending identifiers default to blank and blank means not yet issued
Each pending identifier SHALL be read from deployment configuration and SHALL default to blank:

- the LLPIN: `OPERATOR_LLPIN` on the backend, `VITE_OPERATOR_LLPIN` at frontend build time;
- the registered office: `VITE_OPERATOR_REGISTERED_OFFICE`, frontend only.

A blank value SHALL mean "not yet issued". It SHALL NOT be replaced by a realistic-looking dummy on any surface. Replacing a default with a real value SHALL require only setting the variable and rebuilding or restarting, never a source edit.

#### Scenario: No value configured
- **GIVEN** an operator entity with no LLPIN
- **WHEN** a surface that discloses the LLPIN renders
- **THEN** it states that the LLPIN is being issued and shows no LLPIN-shaped value

#### Scenario: Real value configured
- **GIVEN** `VITE_OPERATOR_LLPIN` is `ACA-1234` when the operator entity is resolved
- **WHEN** the site footer renders that entity
- **THEN** it shows `LLPIN: ACA-1234`

#### Scenario: Registered office not yet configured
- **GIVEN** an operator entity with no registered office
- **WHEN** the site footer renders
- **THEN** it omits the registered office line rather than printing a placeholder address

### Requirement: A malformed identifier fails closed
A non-blank identifier SHALL be checked after trimming:

- **LLPIN:** three uppercase letters, a hyphen and four digits.
- **Registered office:** at most 200 characters, drawn only from ASCII letters, digits, the U+0020 space and `, . - / # ( ) & '`.
- **GSTIN:** fifteen characters in the GSTIN structure, with `F` (firm/LLP) as its holder-type character and a valid check character.

When a non-blank value fails its check:

- the backend SHALL refuse to start, naming the property and not echoing the value;
- for a `VITE_OPERATOR_*` variable, loading the frontend build configuration SHALL fail, naming the variable.

#### Scenario: Malformed LLPIN at backend startup
- **GIVEN** `OPERATOR_LLPIN` is `LLPIN-PENDING`
- **WHEN** the backend binds its configuration
- **THEN** binding fails with an error naming `operator.llpin` that does not contain `LLPIN-PENDING`

#### Scenario: Placeholder or wrong GSTIN at backend startup
- **GIVEN** `OPERATOR_GSTIN` takes one of these values:
  - `XXXXXXXXXXXXXXX`;
  - a well-formed GSTIN with a wrong check character;
  - a GSTIN whose holder-type character is `P`.
- **WHEN** the backend binds its configuration
- **THEN** binding fails with an error naming `operator.gstin`

#### Scenario: Valid GSTIN accepted
- **GIVEN** `OPERATOR_GSTIN` is a firm GSTIN whose check character is valid
- **WHEN** the backend binds its configuration
- **THEN** binding succeeds and the GSTIN accessor returns that value

#### Scenario: Malformed value at frontend build
- **GIVEN** `VITE_OPERATOR_LLPIN` is `TBD`, or `VITE_OPERATOR_REGISTERED_OFFICE` contains `<`
- **WHEN** the Vite configuration is loaded for a production build
- **THEN** it throws a message naming the offending variable

### Requirement: The GSTIN is reachable only through a guarded optional accessor
The backend SHALL expose the GSTIN only as an `Optional<String>` on the operator type, empty when the GSTIN is blank. The repository SHALL contain no non-blank default for the GSTIN.

Any tax invoice, payment receipt or other document that prints a GSTIN SHALL obtain it from this accessor. When the accessor is empty, that document SHALL do one of two things:

- omit the GSTIN line;
- refuse to issue, where the document type legally requires a GSTIN.

It SHALL NOT print a placeholder. The frontend SHALL NOT display a GSTIN.

#### Scenario: No GSTIN configured
- **GIVEN** `OPERATOR_GSTIN` is unset
- **WHEN** code asks the operator type for the GSTIN
- **THEN** it receives an empty `Optional`

#### Scenario: Repository carries no default GSTIN
- **GIVEN** the committed `application.yml` loaded as configuration data, with no environment override
- **WHEN** the operator properties are bound
- **THEN** the legal name is `KAVISAT TEK LABS LLP` and the GSTIN and LLPIN accessors are empty

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

### Requirement: The signed-agreement email names the operator
Both signed-agreement delivery messages SHALL end, after a blank line, with the operator line:

- "AgreementMitra is a service of <legal name>." when no LLPIN is configured;
- "AgreementMitra is a service of <legal name> (LLPIN <llpin>)." when one is.

The line SHALL NOT include the GSTIN. It SHALL add no party, property or financial detail to the body.

#### Scenario: Attachment message without LLPIN
- **GIVEN** the legal name is KAVISAT TEK LABS LLP and no LLPIN is configured
- **WHEN** the delivery message with attachment is composed
- **THEN** its body ends with "\n\nAgreementMitra is a service of KAVISAT TEK LABS LLP.\n" and contains no `LLPIN`

#### Scenario: Oversize notification with LLPIN
- **GIVEN** the LLPIN is `ACA-1234`
- **WHEN** the notification-only message is composed
- **THEN** its body ends with "AgreementMitra is a service of KAVISAT TEK LABS LLP (LLPIN ACA-1234).\n"

