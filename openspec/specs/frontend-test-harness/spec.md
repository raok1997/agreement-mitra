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
command resolves and lints the frontend's own sources instead of failing on a missing
binary. The linted scope SHALL cover `.ts` and `.vue` application sources **and the
Node tooling scripts under `frontend/scripts/`** — including the OSV-Scanner shim that
backs the dependency-scan gate. Tooling that gates the build is not exempt from the
gate that checks it.

Those scripts run under Node, not in a browser, and the config SHALL reflect that in
**both** directions for that path: the Node global set (`process`, `console`, `Buffer`,
`__dirname` and the rest) SHALL resolve as defined, and **browser-exclusive** globals
(`window`, `document`, `localStorage` and the rest of the browser set that is *not*
also a Node global) SHALL NOT resolve as defined. Defining Node globals alone is
insufficient — a Vue-plugin preset applies browser globals repo-wide, so a script
referencing `localStorage` would otherwise lint clean inside a Node program.

**"Browser-exclusive" is deliberate and MUST NOT be read as "every name in the browser
set".** The two sets overlap substantially (`console`, `fetch`, `URL`, `crypto`,
`setTimeout` and others are in both), and those names are genuinely available in Node.
Withholding them would break the very scripts this requirement exists to lint, so the
Node set SHALL take precedence wherever the two overlap.

The `no-undef` clauses above apply to the `.js`, `.mjs` and `.cjs` tooling scripts,
where that rule runs. They do **not** bind `.ts` tooling scripts: the TypeScript ESLint
preset disables `no-undef` for TypeScript on the grounds that the compiler already
reports unknown identifiers, and that remains the checking mechanism for those files.
A requirement written to cover `.ts` here would be one the shipped config silently
violates.

The pass bar for this requirement is **zero ESLint errors**. ESLint warnings are
advisory and do not fail `eslint .`; a source that reports only warnings satisfies
"clean" here.

This requirement SHALL NOT be satisfied by exempting those scripts from linting —
neither by listing `frontend/scripts/` in ESLint's `ignores`, nor by disabling
`no-undef` for that path. Both would produce zero errors while destroying the property
the requirement exists to establish.

#### Scenario: Lint script resolves and runs

- **WHEN** a developer runs `npm run lint` in `frontend/`
- **THEN** ESLint executes against the `.ts` and `.vue` sources and the
  `frontend/scripts/` Node tooling, and reports results, rather than erroring that
  `eslint` cannot be found

#### Scenario: Clean sources pass lint

- **WHEN** `npm run lint` runs against the current committed sources
- **THEN** it completes with zero errors and a zero exit status (the seed tests,
  config, and tooling scripts included), even if advisory warnings are reported

#### Scenario: Node tooling scripts may use Node globals

- **WHEN** a `.js`/`.mjs`/`.cjs` script under `frontend/scripts/` references a Node
  global such as `process`, `Buffer`, or one shared with the browser set such as
  `console` or `fetch`
- **THEN** ESLint resolves it as a defined global and reports no `no-undef` error for it

#### Scenario: Browser-exclusive globals are not defined in Node tooling scripts

- **WHEN** a `.js`/`.mjs`/`.cjs` script under `frontend/scripts/` references a
  browser-exclusive global such as `localStorage`, `window` or `document`
- **THEN** ESLint reports it as `no-undef`, because those names are withheld from that
  path

#### Scenario: Tooling scripts are actually inspected, not excused

- **WHEN** the linted file set is inspected (e.g. `npx eslint --print-config` for a
  file under `frontend/scripts/`, or ESLint's own report of files examined)
- **THEN** that file resolves to a real configuration object with `no-undef` enabled —
  not skipped via `ignores` (which makes `--print-config` report no configuration at
  all) and not passing because the rule was turned off

#### Scenario: Application sources keep their browser globals

- **WHEN** a file under `frontend/src/` references a browser global such as `window`
  or `document`
- **THEN** it still resolves as defined — the Node-scoped configuration applies only to
  `frontend/scripts/` and does not regress the application sources

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

