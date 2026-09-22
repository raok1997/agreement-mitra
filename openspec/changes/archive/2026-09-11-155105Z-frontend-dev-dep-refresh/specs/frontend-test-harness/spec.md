## MODIFIED Requirements

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
