## Why

The frontend build gate is red for **every** change in the repo. `npm run build`
chains `security:scan && test && vue-tsc -b && vite build`, and the first link fails:
OSV-Scanner reports GHSA-82fw-gwwq-j7x9 against `vitest` and `@vitest/mocker` 3.2.7,
and the gate is fail-on-any by policy. Separately `npm run lint` exits with 2 errors
because `frontend/scripts/security-scan.mjs` — the scan shim itself — is linted
without Node globals, so `process` reads as undefined. Both were registered as the
`frontend-dev-dep-refresh` follow-up on 2026-09-10 at **High — blocks the frontend
build gate repo-wide**; until they land, no frontend change can demonstrate a green
build.

**What the advisory actually is.** GHSA-82fw-gwwq-j7x9 (CVSS 5.9) is **CWE-22, path
traversal / arbitrary local file read** via `@vitest/mocker`'s unauthenticated
HMR-socket redirect mock; the advisory names disclosure of an in-root `.env` as an
outcome. Exploitability in this repo is bounded — `vite.config.ts` sets no
`server.host`, tests run under jsdom rather than browser mode, and the vulnerable
`mockerPlugin`/`interceptorPlugin` exports are unused — but that is a reason to be
calm, **not** a reason to defer. Per this project's own policy, npm's `dev` flag is
not a risk downgrade, and the gate has no CVSS threshold.

## What Changes

- Bump `vitest` and its transitive `@vitest/mocker` from 3.2.7 to **4.1.11**, the
  advisory's fixed version. Verified compatible with the existing stack: vitest
  4.1.11 peers `vite: ^6 || ^7 || ^8` (we are on `^6.0.7`) and
  `node: ^20 || ^22 || >=24` (our `engines` floor is `>=20.19.0`), so **no Vite or
  Node bump rides along**. Measured effect on the graph: **net −7 packages**
  (+3 `obug`, `@standard-schema/spec`, `convert-source-map`; −10 including
  `vite-node`, `tinypool`, `tinyspy`, `cac`), with the install-script surface
  **unchanged** — enumerating `preinstall`/`install`/`postinstall` across the
  installed tree finds exactly one package with a lifecycle script npm runs,
  `esbuild`. (`fsevents` declares `build`/`clean`/`test`, which npm does **not** run
  for a registry dependency; an earlier draft of this proposal miscounted it as a
  second install-script package.) The suite is green at the
  pre-bump baseline (27 files / 230 tests / 2.72s), so any post-bump failure is
  attributable.
- **Rewrite `frontend/scripts/render-terms.ts` to load its TypeScript sources through
  Vite's own `ssrLoadModule` instead of the `vite-node` binary.** This is forced
  work, not scope creep: `npm run terms:doc` runs under `vite-node`, which resolves
  today *only* because `vitest@3.2.7` depends on it — and `vitest@4.1.11` drops that
  dependency entirely. The script's own header comment (lines 8–13) predicted exactly
  this. `vite` is already a direct dependency, so the rewrite adds **nothing** to the
  graph and ends the coupling permanently. The generator runs Vite with
  **`server: { ws: false }`** — see the security note below.
- Teach `frontend/eslint.config.js` that `frontend/scripts/**` are Node programs —
  Node globals defined, **and browser-exclusive globals explicitly undefined** for
  that path. The second half matters: `eslint-plugin-vue`'s `flat/recommended` block
  is unscoped and currently injects **763 browser globals into every file in the
  repo**, which is why `console` passes and `process` fails in the same file today.
  Without removing them, a build-gate script could reference `localStorage` or
  `document` and still lint clean. "Browser-exclusive" is precise and load-bearing:
  the browser and Node sets **overlap on 60 names** (`console`, `fetch`, `URL`,
  `crypto`, `setTimeout`, …), so only the **703** browser-minus-node names are
  withheld, and the ordering that achieves it is specified in design D2.
- Declare `globals` at `^14.0.0` as a devDependency. This **dedupes onto the copy
  already hoisted at `node_modules/globals` (v14.0.0)** via `@eslint/eslintrc`, so it
  adds **zero new nodes** to the scanned lockfile — the whole lockfile including
  transitives is already in scan scope. It is what makes the browser-globals removal
  above expressible without hand-listing 763 names.
- Close the `frontend-dev-dep-refresh` row in the `## Follow-up register` of
  `docs/ROADMAP.md`.

**Acceptance bar is zero lint _errors_, not zero problems.** 26
`vue/html-indent` / `vue/html-closing-bracket-newline` **warnings** survive in the
`.vue` sources. They do not fail `eslint .` (which exits non-zero on errors only),
they are the same Prettier-vs-`eslint-plugin-vue` stylistic conflict the config's
existing off-block partly addresses, and they are **out of scope** — recorded as a
separate follow-up rather than folded in.

Not breaking: no runtime behaviour, no API, no persisted state changes. The generated
`docs/TERMS-OF-SERVICE.md` must come out **byte-identical**.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `frontend-test-harness`: the **Working ESLint lint script** requirement scopes
  linting to `.ts` and `.vue` sources. The repo's Node tooling scripts
  (`frontend/scripts/`) are linted in practice but sit outside that stated scope, and
  are the reason the "clean sources pass lint" scenario is currently violated. Widen
  the requirement to cover Node tooling scripts with Node globals available and
  browser globals withheld, and state the pass bar as zero **errors**.

`frontend-security-scanning` is deliberately **not** modified: its existing
"fail on any unsuppressed finding" requirement is what makes this bump mandatory.
Clearing a finding by upgrading — rather than suppressing it — is conformance with
that requirement, not a change to it. Consistent with its "clean graph ships an
empty baseline" scenario, `frontend/osv-scanner.toml` stays empty (no
`[[IgnoredVulns]]` entry is added for GHSA-82fw-gwwq-j7x9).

## Impact

- `frontend/package.json` — `vitest` bumped, `globals` added; `terms:doc` script
  retargeted at the rewritten generator.
- `frontend/package-lock.json` — regenerated.
- `frontend/scripts/render-terms.ts` → rewritten as a Vite-native generator (and
  renamed to `.mjs`, since it no longer needs a TypeScript-aware runner to launch).
- `frontend/eslint.config.js` — one scoped config block.
- `docs/ROADMAP.md` — follow-up register row closed; one new row for the deferred
  `.vue` stylistic warnings.
- No backend, no database migration, no module boundary touched.

**Signing-status FSM**: untouched. This change contains no runtime code — it moves
no `SignatureStatus` transition and adds no path through the signing flow.

**PII/security review checklist**: **no PII flow.** The change adds, moves, and reads
no Aadhaar number, OTP, virtual ID, KYC data, signer PII, or secret — it edits a
devDependency version, a lockfile, an ESLint config block, a docs generator, and a
roadmap table. No new outbound flow, no logging change, nothing to redact. Sandbox +
dummy-data-only posture is unaffected.

**Legal-text pipeline.** The change rewrites the generator for
`docs/TERMS-OF-SERVICE.md` — a customer-facing legal instrument governed by this
project's "update the ToS in the CR that changes it" rule. The change alters **how the
document is produced, not one word of what it says**, and the control for that is
**byte-identity**, verified by running the generator and requiring an empty
`git diff` (task 2.5). No clause text is touched.

**Build-host execution surface.** Two items, neither a PII flow but both worth naming:

- **The new generator boots a Vite server in-process.** By default that binds an
  unauthenticated HMR WebSocket on port 24678 across **all** interfaces — measured,
  not inferred. In a CR motivated by an unauthenticated-HMR-socket advisory that would
  be a poor trade, so the generator sets `server: { ws: false }`, which binds no port
  and produces byte-identical output. Verified both ways.
- **`npm install` runs third-party code** on a machine holding sandbox credentials.
  This is inspected rather than claimed: the install is done once with
  `--ignore-scripts`, the added package set and lifecycle scripts are enumerated, and
  `npm audit signatures` is run **before** the new tree is first executed, after which
  a normal install restores the tree every other developer has. Be precise about what
  that buys: registry signatures prove the registry served what it signed and do
  **not** detect a malicious publish from a compromised maintainer account, which is
  the dominant npm compromise mode. It is a cheap integrity check at a known-risky
  moment, not a defence against account takeover, and the tasks say so.

Net security effect is positive: it removes the advisory from the dependency graph,
shrinks the graph by 7 packages, drops a transitive binary the repo was silently
depending on, and restores the lint gate that guards the scan shim itself.
