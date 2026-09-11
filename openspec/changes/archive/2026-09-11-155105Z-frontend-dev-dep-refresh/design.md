## Context

Two independent defects hold the frontend build gate red for the whole repo, and the
fix for the first forces a third piece of work. They are one change because they share
a single goal — make `npm run build` and `npm run lint` pass again — and none of them
is a CR on its own.

**Current state, measured 2026-09-11:**

| Gate | Result |
|---|---|
| `npm run security:scan` | 2 findings — GHSA-82fw-gwwq-j7x9 (CVSS 5.9, CWE-22) against `vitest` 3.2.7 and `@vitest/mocker` 3.2.7, fixed in 4.1.11 |
| `npm run lint` | 28 problems — **2 errors** (`'process' is not defined`, `scripts/security-scan.mjs:37` and `:41`), 26 warnings |
| `npm test` | **green** — 27 files / 230 tests / 2.72s |
| `npm run build` | never reaches the compiler; dies on `security:scan`, the first link in the chain |
| `npm run terms:doc` | green today, **breaks on the bump** — see D6 |

Two constraints shape the approach. The frontend scan gate is **fail-on-any with no
CVSS threshold**, so there is no "medium, ignore it" path — either upgrade or file a
justified, time-boxed suppression. And the scanned surface is the **whole lockfile
including transitives**, which is a sharper instrument than "how many dependencies do
we declare" and is what D2 and D6 below are actually reasoning about.

## Goals / Non-Goals

**Goals:**

- `npm run security:scan` reports **0 findings**, with `frontend/osv-scanner.toml`
  still holding zero `[[IgnoredVulns]]` entries.
- `npm run lint` exits **0 with zero errors**, by *linting* `frontend/scripts/`
  rather than by excusing it.
- `npm run build` completes end to end.
- `npm run terms:doc` still works and still produces a **byte-identical**
  `docs/TERMS-OF-SERVICE.md`.
- The test suite stays green with no test rewritten to accommodate the runner —
  the pre-bump baseline (27 files / 230 tests) is the yardstick.

**Non-Goals:**

- The 26 surviving `vue/html-indent` / `vue/html-closing-bracket-newline` **warnings**
  in `.vue` sources. They do not fail the gate; fixing them means adjudicating the
  Prettier-vs-`eslint-plugin-vue` overlap across ~2000 lines of template.
- Any other dependency bump. `vite`, `eslint`, `typescript` stay put.
- Wiring these gates into CI — still local-only; CR-7's scope. Note this bounds what
  "blocks the build gate repo-wide" means: it is true of every developer's machine,
  not of any shared build, because there is no shared build running these gates yet.
- Restructuring test teardown (the ~47 hand-written `.mockReset()` calls). See Risks.
- Any runtime or product behaviour.

## Decisions

**D1 — Upgrade vitest to 4.1.11 rather than suppress the advisory.**
A suppression would need a reason and an `ignoreUntil`, and the honest reason would
be "we did not want to do a major bump" — which expires into the same work later,
with more drift. The upgrade is also cheap: vitest 4.1.11 declares
`peerDependencies.vite: ^6.0.0 || ^7.0.0 || ^8.0.0` and
`engines.node: ^20.0.0 || ^22.0.0 || >=24.0.0`, both already satisfied. *Verified
before proposing, because discovering at implementation time that vitest 4 demanded
Vite 7 would have turned this into a split.* Measured graph effect: **net −7
packages**, install-script surface unchanged.

*Alternative considered:* pin `@vitest/mocker` alone via `overrides`, keeping vitest
on 3.x. Rejected — `vitest` itself is named in the advisory, so the finding would
persist, and an `overrides` pin that disagrees with its parent is a standing source of
subtle breakage.

**D2 — Use the `globals` package at `^14.0.0`, and both add Node globals and
*remove* browser globals for `scripts/**`.**

*This reverses an earlier draft of D2, which hand-listed a few globals inline to
avoid declaring a dependency. Round-1 review showed that rationale was wrong on this
project's own terms, and that the inline approach did not deliver the property the
spec claims.* Two facts settle it:

1. **The scanned surface is the whole lockfile, not the declared set.** `globals` is
   already in it — hoisted at `node_modules/globals` **v14.0.0** via
   `@eslint/eslintrc`, plus a nested 13.24.0 under `eslint-plugin-vue`. Declaring
   `^14.0.0` dedupes onto the hoisted copy and adds **zero new nodes**. (A `^17` pin
   *would* add one; the range is load-bearing and is why the version is stated here.)
2. **Adding Node globals is only half the fix.** `eslint-plugin-vue`'s
   `flat/recommended` includes a `vue/base/setup` block with **no `files` key**, so it
   applies `globals.browser` — 763 names — to *every file in the repo*. Verified by
   enumerating the resolved config. That is the real reason `console.error` at
   `security-scan.mjs:33,35` passes while `process.exit` at `:37,41` fails: `console`
   is in the browser set and `process` is not. Flat-config `globals` **merge**, so a
   later block adds Node names on top and leaves `window`, `document`, `localStorage`
   and `fetch` defined inside a Node build script.

So the block sets browser names to `"off"` and spreads `globals.node` over the top.
Hand-listing 763 names to achieve that is not an option, which is what makes the
package the right call — on ergonomics, with zero scan cost, rather than on the
surface argument the earlier draft made.

**The spread order is the whole correctness of this block, and it is browser-off
first:**

```js
globals: {
  ...Object.fromEntries(Object.keys(globals.browser).map((k) => [k, "off"])),
  ...globals.node,   // MUST come last
}
```

The two sets **overlap on 60 names** — `console`, `fetch`, `URL`, `crypto`,
`setTimeout`, `structuredClone`, `TextEncoder`, `AbortController`, `Blob`,
`performance` and others — so only **703** names are browser-exclusive. Spreading
`globals.node` last re-defines all 60 and withholds only the 703. Reverse the order
and browser-`"off"` wins on the overlap, which silently strips `console` — turning
the two `process` errors at `security-scan.mjs:37,41` into two `console` errors at
`:33,35`. The CR would trade one red lint for another.

Verified empirically against the live config before writing this: with the block in
browser-first order, `--print-config scripts/security-scan.mjs` reports `process` and
`console` defined, `window`/`document`/`localStorage` `"off"`, `no-undef` still
`"error"`, and `npm run lint` drops to **0 errors / 26 warnings**.

The block must also sit **last** in the exported array — flat config merges
`languageOptions.globals` in order, so an unscoped block after it would re-add the
browser set.

**`no-undef` does not apply to `.ts`, and the requirement is scoped accordingly.**
`typescript-eslint`'s `eslint-recommended` turns `no-undef` **off** for TypeScript
files, on the grounds that the TypeScript compiler already reports unknown
identifiers. Measured: `--print-config scripts/render-terms.ts` → `no-undef: [0]`,
versus `[2]` for `scripts/security-scan.mjs`. A `languageOptions.globals` block does
not override that, because it sets no `rules` key.

So the globals block covers `.ts` under `scripts/` (harmless, and correct if the rule
is ever re-enabled), but the spec's `no-undef`-shaped clauses are deliberately
scoped to the **`.js`/`.mjs`/`.cjs`** scripts where the rule actually runs. Writing
`.ts` into those clauses — as an earlier draft did — makes them unsatisfiable, and
would put a requirement into the spec of record that the shipped config silently
violates. TypeScript tooling scripts get their undefined-identifier checking from
`vue-tsc`/`tsc`, not from this rule; the spec says that rather than implying a
coverage it does not have. This is also why the generator lands as `.mjs` rather than
`.ts` — it is the extension where the gate has teeth.

*Alternative considered:* add `scripts/` to ESLint's `ignores`. Rejected on
principle — `security-scan.mjs` *is* a build gate, and exempting the gate's own
implementation from linting is exactly the wrong direction. The same objection rules
out the cheaper cheat of `"no-undef": "off"` for that path; the spec delta states
both as explicit negative requirements, because "zero errors" alone is satisfied by
either.

**D3 — No `osv-scanner.toml` entry.**
The `frontend-security-scanning` spec's "clean graph ships an empty baseline"
scenario says the baseline stays comment-only when the graph is clean. D1 clears both
findings outright, so the file is untouched — and a task re-verifies that its
"0 known vulnerabilities" header comment is true again at the end, rather than
assuming it.

**D4 — Bundle these fixes in one CR.**
None is an aggregate, an endpoint, or a state-machine change, so the CR scope
heuristics are not strained. D6 in particular is not optional scope: it is breakage
*caused by* D1, and shipping D1 without it would knowingly leave `npm run terms:doc`
broken.

**D5 — The build and lint gates are the acceptance test; no new unit test ships.**
A pure tooling/config change with no runtime behaviour to assert on — the
config/docs/harness exemption in the project's `tasks:` rule, recorded atop
`tasks.md`. The *existing* 230 tests are the regression net for the runner bump.
One caveat, stated because round-1 review caught it: "the suite is green" is a weak
oracle for a teardown-semantics change (see Risks), so the tasks add a discriminating
check rather than relying on the green tick alone.

**D6 — Replace the `vite-node` binary with Vite's own `ssrLoadModule`.**

`npm run terms:doc` runs `vite-node scripts/render-terms.ts`. `vite-node` is **not a
declared dependency** — it resolves only as a child of `vitest@3.2.7`
(`npm ls vite-node` confirms), and `vitest@4.1.11` has no `vite-node` dependency at
all. The script's own header (lines 8–13) predicted this exact failure. So the bump
breaks the generator for the repo's customer-facing legal text, silently — nothing in
`npm run build` invokes it.

The chosen fix boots Vite programmatically in middleware mode and `ssrLoadModule`s the
TypeScript sources directly. **Validated before committing to it**: the spike loaded
`renderTermsMarkdown` and `TERMS_DOC_PATH`, produced 15216 characters (15218 bytes — the two differ because the text contains multi-byte characters), compared
**byte-identical** to the committed `docs/TERMS-OF-SERVICE.md`, and exited 0 — from
the repo root as well as from `frontend/`.

Four options are load-bearing, and each is here because omitting it caused a real,
measured failure rather than a theoretical one:

```js
const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const server = await createServer({
  root,                                          // cwd-independence
  appType: "custom",
  server: { middlewareMode: true, ws: false },   // no HMR socket
  optimizeDeps: { noDiscovery: true },           // no dep-scanner race
  logLevel: "warn",
});
try {
  /* ssrLoadModule + writeFileSync */
} finally {
  await server.close();                          // or the process never exits
}
```

- **`ws: false` — security.** In middleware mode `httpServer` is null, so Vite creates
  its *own* standalone WebSocket server on port 24678 bound to **all interfaces**,
  unauthenticated. Measured: default config → 1 listener on 24678; `ws: false` → 0
  listeners, byte-identical output (15216 characters / 15218 bytes). Opening an unauthenticated HMR socket
  inside the CR that exists to close an unauthenticated-HMR-socket advisory would be
  indefensible when the fix is one key and costs nothing. (Bounding, for honesty: the
  window is seconds, `server.fs.strict` is on and `fs.allow` resolves to `frontend/`,
  so this was never a re-creation of GHSA-82fw-gwwq-j7x9 — it is simply an
  unnecessary listener.)
- **`await server.close()` in a `finally` — correctness.** Without it the script
  **hangs indefinitely**; the Vite server keeps the event loop alive. A version
  written to an earlier draft of this design, which omitted it, produced the correct
  bytes and then never exited. `npm run terms:doc` would appear to hang forever.
- **`root` from `import.meta.url` — a property the old script had.** With no `root`,
  Vite resolves it from `process.cwd()`, so the generator works under
  `npm run terms:doc` and fails with `MODULE_NOT_FOUND` when invoked as
  `node frontend/scripts/render-terms.mjs`. The `vite-node` version was
  cwd-independent (`render-terms.ts:21`); losing that would be a silent regression in
  a change whose non-goal is behaviour change. It also makes `TERMS_DOC_PATH`
  (`"../docs/TERMS-OF-SERVICE.md"`, documented as relative to `frontend/`) resolve
  correctly.
- **`optimizeDeps: { noDiscovery: true }` — suppresses a misleading error.** Without
  it the dependency scanner races `server.close()` and prints ~80 lines of esbuild
  stack trace **while still exiting 0** with correct output. An earlier draft claimed
  `configFile: false` was also required for this; that is wrong — `noDiscovery` alone
  suppresses the race. Because the failure is noisy-but-green, task 2.6 asserts clean
  stderr rather than exit status alone.

**The real `vite.config.ts` is loaded** (no `configFile: false`). An earlier draft
skipped it to keep the boot minimal, but that would resolve modules under different
rules than `termsOfService.test.ts` uses — the divergence would surface as a stale
legal document rather than an error. Verified: both modes produce the identical
output, so loading the real config costs nothing and removes the divergence.

*Alternatives considered and rejected:*

- **Declare `vite-node` as a devDependency.** The only line compatible with `vite ^6`
  is **3.2.4**, the terminal release of the branch vitest 4 just abandoned — there is
  no stable 4.x (`dist-tags`: `beta 4.0.0-beta.19`, `latest 6.0.0`), 5.x hard-depends
  on `vite ^7.3.1` and 6.0.0 on `vite ^8.0.0`. So the naive `npm i -D vite-node`
  installs 6.0.0 and nests **a second full Vite 8 toolchain, +32 packages**, in the
  change whose premise is that Vite stays put. Pinning 3.2.4 avoids that but makes us
  the owner of a dead branch: under a fail-on-any gate, the first advisory ever filed
  against it forces either a suppression against the empty baseline this CR restores,
  or the Vite major D1 avoided. Rejected on both counts.
- **`npx vite-node` on demand.** Same resolution problem, plus a network fetch at run
  time and no lockfile pinning at all.
- **`node --experimental-strip-types`.** Unflagged only from Node 22.18; our `engines`
  floor is `>=20.19.0`, so it would break for developers we currently support.
- **Retire `terms:doc` and regenerate by hand.** The parity test would still catch
  staleness, but it makes updating a legal document a manual transcription step —
  precisely the failure `docs/LEGAL-POSTURE.md` exists to prevent.

Because the generator no longer needs a TypeScript-aware runner to *launch*, it
becomes `render-terms.mjs`; the TypeScript it imports is still TypeScript, loaded
through Vite.

## Risks / Trade-offs

- **A vitest 4 behavioural change breaks existing tests — concentrated in mock
  lifecycle.** v4 changed `mockReset` to restore the original implementation, and
  narrowed `vi.restoreAllMocks` to spies created via `vi.spyOn`. The exposed surface
  is **47 `.mockReset()` call sites across 9 test files** — a count the earlier draft
  missed, because it counted `vi.*` calls and `.mockReset()` is a method on the mock,
  invisible to that grep. Chained sites that immediately re-stub are self-healing;
  **bare** `mockReset()` calls are where the semantics change lands.

  These are **two different risks with two different reading lists**, and conflating
  them (as an earlier draft did) points the implementer at the wrong files:

  - **`mockReset` semantics** — measured distribution: `CaptureForm.test.ts` **22**,
    `App.test.ts` **11**, `AgreementStatus.test.ts` 4, `StaffConsoleRoute` 3,
    `StaffConsole` 2, `TemplatePicker` 2, `RecoverAgreement` / `AuthCallback` /
    `staffQueue` 1 each. The highest-risk pattern in the repo is
    `CaptureForm.test.ts:333-343` — 22 bare `mockReset()` in a `beforeEach` with no
    re-stub, exactly the case the v4 change alters. `CaptureForm.test.ts` and
    `App.test.ts` are 33 of the 47 sites and are the list to read.
  - **`restoreAllMocks` narrowing + fake timers + prototype spies** —
    `signingProgress.test.ts` (`restoreAllMocks` + `useFakeTimers` + an
    `HTMLAnchorElement.prototype` spy), `documentPreview.test.ts` (`restoreAllMocks` +
    two `console` spies), `AgreementStatus.test.ts` (`vi.hoisted` + bare `mockReset()`
    + `restoreAllMocks`). Note `signingProgress` and `documentPreview` contain **zero**
    `.mockReset()` calls — they matter for the restore path only.

  → **Mitigation:** the pre-bump baseline is recorded, so a failure is attributable to
  the bump. A failure here is a *test-harness* fix inside this CR's slice — adjusting
  teardown is in scope; rewriting what a test asserts is not, and would be a halt.
  Read both lists even if the suite is green.

- **A green suite does not prove test isolation survived.** A teardown change that
  degrades isolation stays green until some unrelated future test trips on it. There
  is no shuffle, no per-file isolation assertion, and `vite.config.ts` sets neither
  `clearMocks` nor `restoreMocks`. → **Mitigation:** a shuffled re-run as an explicit
  task. The structural fix — config-driven teardown instead of 47 hand-written resets
  — is deliberately **not** in this CR; it goes to the follow-up register, because it
  would mean touching every one of those files.

- **The bump pulls in new transitives carrying their own advisories.** → **Mitigation:**
  `security:scan` is re-run after the install is final and must report **0**, not
  merely "no longer GHSA-82fw-gwwq-j7x9". A new finding is a halt, not a suppression.

- **`npm install` executes third-party code on a machine holding sandbox credentials,
  and OSV reports known advisories, not hijacks.** The added set
  (`obug`, `@standard-schema/spec`, `convert-source-map`) declares no install scripts;
  exactly one package in the whole tree does (`esbuild`). → **Mitigation:** install
  once with `--ignore-scripts`, enumerate the added/removed set and its lifecycle
  scripts, run `npm audit signatures`, **then re-install normally** so every later
  gate runs against the tree a real checkout produces. Be honest about the ceiling:
  `--ignore-scripts` is a one-off inspection window on one machine, not a control —
  nothing in `.npmrc` enforces it and the next plain `npm install` runs every
  lifecycle script. And `npm audit signatures` proves the registry served what it
  signed; it does **not** detect a malicious publish from a compromised maintainer
  account, which is the dominant npm compromise mode, and only a minority of this
  graph carries provenance attestations at all. The real enforcement is a CI-side
  control, which is CR-7's territory.

- **`vue-tsc -b` fails on new vitest types — and the obvious mitigation does not
  run.** `tsconfig.vitest.json` pulls `types: ["vitest/globals"]`, but `tsconfig.json`
  has **no `references`** and excludes `src/**/*.test.ts`, and nothing points at
  `tsconfig.vitest.json` — so `vue-tsc -b` typechecks neither the test files nor that
  config. An earlier draft mitigated this risk with "`npm run build` runs `vue-tsc -b`
  and will surface it", which is inert: the check can never fire. → **Mitigation:**
  run `vue-tsc -p tsconfig.vitest.json --noEmit` as an explicit task, which is the
  only thing that actually typechecks the suite against the new runner's types. A
  stated mitigation that cannot run is worse than an acknowledged gap.

- **`engines.node` (`>=20.19.0`) is looser than vitest 4's matrix**
  (`^20 || ^22 || >=24`), so Node 21 or 23 satisfies ours and violates the runner's.
  → Accepted, not fixed here: narrowing `engines` affects every developer and belongs
  in its own change. Noted so it is not rediscovered as a surprise.

- **Leaving 26 warnings behind normalises a noisy lint run.** → Accepted deliberately,
  and made explicit in the spec delta (the pass bar is zero *errors*). The deferred
  cleanup goes to the follow-up register so it is visible rather than tacit.

- **`npm run lint` is in no gate.** `build` does not chain it, so the fix this CR
  lands is protected by habit alone until CR-7. → Noted rather than fixed; the spec
  delta's framing ("tooling that gates the build is not exempt from the gate that
  checks it") is an intent this repo cannot yet enforce automatically.

## Migration Plan

None — no deployed artefact, schema, or API changes. Rollback is `git revert` of the
`package.json` / `package-lock.json` / `eslint.config.js` / `scripts/` diff; the
previous state is a red build gate, not a broken product.

## Open Questions

None blocking. The two unknowns that mattered — whether the Vite-native generator
reproduces the document byte-for-byte, and whether vitest 4's mock-lifecycle changes
touch this suite — are resolved by the D6 spike (yes) and by running the tests, whose
handling is specified under Risks.
