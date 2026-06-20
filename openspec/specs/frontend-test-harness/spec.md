# Frontend Test Harness

## Purpose

The frontend unit/component base of the test pyramid that makes "tests from the first
story" possible on the Vue 3 SPA: a Vitest + @vue/test-utils runner in a jsdom
environment (wired to `npm test` one-shot and a watch script), a repaired ESLint flat
config so `npm run lint` actually runs, and two seed tests that prove the harness boots
green — an API-client unit test (`fetch` mocked) and a SignDemo component test (the API
client mocked). Scope is deliberately the pyramid base only: no E2E/Playwright and no
cross-stack contract tests (the network is mocked), both deferred as future CRs.

## Requirements

### Requirement: Frontend unit/component test runner

The frontend SHALL provide a Vitest-based test runner configured for the Vue 3 SPA,
running in a jsdom environment so components can be mounted and asserted on in-process
without a real browser. The runner SHALL be invokable via `npm test` as a one-shot
(non-watch) run suitable for CI, and SHALL support a watch mode for local development.

#### Scenario: One-shot test run via npm test

- **WHEN** a developer runs `npm test` in `frontend/`
- **THEN** Vitest executes all test files once in the jsdom environment and exits with a
  non-zero status if any test fails, zero if all pass

#### Scenario: Watch mode for local development

- **WHEN** a developer runs the watch script (e.g. `npm run test:watch`)
- **THEN** Vitest runs the suite and stays resident, re-running affected tests on file
  changes

#### Scenario: Component mounting is supported

- **WHEN** a test mounts a Vue single-file component using @vue/test-utils
- **THEN** the component renders into the jsdom DOM and its rendered output and
  interactions are assertable, with no browser process required

### Requirement: Working ESLint lint script

The frontend SHALL have a functioning `npm run lint` script: ESLint and its required
plugins MUST be present in `devDependencies` and a flat ESLint config MUST exist so the
command resolves and lints `.ts` and `.vue` sources instead of failing on a missing
binary.

#### Scenario: Lint script resolves and runs

- **WHEN** a developer runs `npm run lint` in `frontend/`
- **THEN** ESLint executes against the `.ts` and `.vue` sources and reports results,
  rather than erroring that `eslint` cannot be found

#### Scenario: Clean sources pass lint

- **WHEN** `npm run lint` runs against the current committed sources
- **THEN** it completes without reporting lint errors (the seed tests and config
  included)

### Requirement: API client unit test

The harness SHALL include a unit test for the API client `requestSignature` function
that exercises its HTTP contract with `fetch` mocked — no real network call.

#### Scenario: Posts to the correct endpoint

- **WHEN** `requestSignature` is called with an agreement id and `fetch` is mocked
- **THEN** it issues a `POST` to `/api/signing/<agreementId>/request`

#### Scenario: Parses the JSON response body

- **WHEN** the mocked `fetch` resolves with an ok response whose JSON body is a
  `SignSession`
- **THEN** `requestSignature` resolves to that parsed `SignSession`

#### Scenario: Throws on a non-ok response

- **WHEN** the mocked `fetch` resolves with a non-ok response (e.g. status 500)
- **THEN** `requestSignature` rejects with an error rather than returning a value

### Requirement: SignDemo component test

The harness SHALL include a component test for the SignDemo view, mounted in jsdom with
the API client module mocked, asserting the component's user-facing wiring and states.

#### Scenario: Clicking the button invokes the API client

- **WHEN** the component is mounted and the "Request signature" button is clicked
- **THEN** the mocked `requestSignature` is called with the entered agreement id

#### Scenario: Loading state disables the button and updates its label

- **WHEN** a signing request is in flight
- **THEN** the button is disabled and its label reads "Requesting..."

#### Scenario: Resolved session renders the signing URL

- **WHEN** the mocked `requestSignature` resolves with a session containing a signing URL
- **THEN** the component renders that signing URL as a link

#### Scenario: Rejected request renders the error text

- **WHEN** the mocked `requestSignature` rejects with an error
- **THEN** the component renders the error message text and does not render a signing URL
