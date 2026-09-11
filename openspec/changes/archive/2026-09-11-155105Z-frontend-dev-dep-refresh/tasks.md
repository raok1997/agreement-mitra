> **Test-task exemption applies**: this is a pure tooling/config change (a devDependency
> bump, an ESLint config block, and a rewrite of a docs generator's module loader) that
> adds no runnable backend or frontend behaviour, so no unit/integration test tasks are
> added. The existing 230-test suite is the regression net for the runner bump, and
> `npm run build` + `npm run lint` are the acceptance test — both are explicit tasks below.

## 1. Baseline

- [x] 1.1 Record the pre-change gate results in the flow journal: `npm run security:scan`
      (2 findings, GHSA-82fw-gwwq-j7x9 on `vitest`/`@vitest/mocker` 3.2.7),
      `npm run lint` (2 errors / 26 warnings), `npm test` (27 files / 230 tests / 2.72s
      green), `npm run terms:doc` (green). Record the **`osv-scanner` version and the UTC
      date** alongside them — the gate is local-only until CR-7, so a green result is only
      meaningful with the scanner build that produced it.

## 2. Dependency changes (one install for all of them)

**Both** dependency edits happen before the single install, so the lockfile is
regenerated once and every later gate scans the final graph. Declaring `globals` after
the install would leave `package.json` and `package-lock.json` out of sync (`npm ci`
would fail) while lint still passed against the already-hoisted transitive copy.

- [x] 2.1 In `frontend/package.json`: set `devDependencies.vitest` to `^4.1.11`, and add
      `"globals": "^14.0.0"`. The `^14` range is load-bearing — it dedupes onto the copy
      already hoisted at `node_modules/globals` (v14.0.0, via `@eslint/eslintrc`) for
      **zero new lockfile nodes**; `^17` would add one. If a future ESLint bump moves
      `@eslint/eslintrc` off the 14.x line, this pin starts adding the nested node it was
      chosen to avoid — re-check it then rather than assuming it still holds.
- [x] 2.2 Install once with `npm install --ignore-scripts` from `frontend/`. Confirm
      `npm ls vitest @vitest/mocker globals` resolves vitest/@vitest/mocker at 4.1.11+ and
      `globals` at the hoisted 14.0.0, and that `package-lock.json`'s root
      `devDependencies` now lists `globals`.
- [x] 2.3 **Provenance gate — a stop-the-line check, not a formality.** Before executing
      anything from the new tree: record the added/removed package set (expected **net
      −7**: +`obug`/`@standard-schema/spec`/`convert-source-map`,
      −`vite-node`/`tinypool`/`tinyspy`/`cac` among others), enumerate
      `preinstall`/`install`/`postinstall` across the tree (expected: exactly one,
      `esbuild` — `fsevents` has `build`/`clean`/`test`, which npm does not run for a
      registry dep), and run `npm audit signatures`. **Halt** on an unexpected install
      script or a signature failure. Note what this does and does not buy: it proves the
      registry served what it signed, and does **not** detect a malicious publish from a
      compromised maintainer account.
- [x] 2.4 Re-install normally (`npm install`, no flags) so every later gate verifies
      against the tree a real checkout produces. The inspection window in 2.2–2.3 is for
      looking before trusting; shipping the inspected state would mean testing a tree no
      developer has. **Verify rather than assume** — npm skips lifecycle scripts for
      packages it does not reinstall, so this step can be a no-op. Confirm the toolchain
      actually works with `npx esbuild --version` and `node -e "require('esbuild')"`.
      Note for the implementer: this is expected to pass either way, because the binary
      ships in the `@esbuild/<platform>` package (a normal dependency needing no script)
      and `generateBinPath()` resolves it at runtime; `esbuild`'s postinstall only
      hard-links it into `esbuild/bin/`. If `npx esbuild` fails while the `require` works,
      that is the missing hard link and is harmless to Vite, which uses the JS API.
- [x] 2.5 **Rewrite the terms generator off `vite-node`** (design D6). `vite-node` was
      never a declared dependency — it resolved only as a child of `vitest@3.2.7`, and
      4.1.11 drops it, so `npm run terms:doc` breaks at 2.2. Replace
      `frontend/scripts/render-terms.ts` with `frontend/scripts/render-terms.mjs`, and
      point the `terms:doc` npm script at `node scripts/render-terms.mjs`. Use exactly
      this shape — each option is here because omitting it caused a measured failure:

      ```js
      const root = join(dirname(fileURLToPath(import.meta.url)), "..");
      const server = await createServer({
        root,                                          // else MODULE_NOT_FOUND when run from the repo root
        appType: "custom",
        server: { middlewareMode: true, ws: false },   // ws:false — else an unauthenticated
                                                       // HMR socket binds :24678 on all interfaces
        optimizeDeps: { noDiscovery: true },           // else ~80 lines of esbuild race noise, still exit 0
        logLevel: "warn",
      });
      try {
        /* ssrLoadModule("/src/content/termsMarkdown.ts") + termsDocPath, writeFileSync */
      } finally {
        await server.close();                          // else the process never exits
      }
      ```

      **Do not** declare `vite-node`: the only `vite ^6`-compatible line is the abandoned
      3.2.4, and a naive `npm i -D vite-node` pulls 6.0.0 plus a nested Vite 8
      (+32 packages). **Do not** pass `configFile: false` — loading the real
      `vite.config.ts` keeps the generator's module resolution identical to the test's;
      both produce byte-identical output, so skipping it buys nothing and risks drift.
- [x] 2.6 Verify 2.5 directly — run `npm run terms:doc`, confirm it **exits 0, terminates
      promptly, prints no stack trace on stderr**, and leaves `docs/TERMS-OF-SERVICE.md`
      byte-identical (`git diff --exit-code docs/TERMS-OF-SERVICE.md`). All three
      conditions are load-bearing: the dep-scanner race produces correct bytes and exit 0
      *with* a screen of errors, and a missing `server.close()` produces correct bytes and
      never exits. Also run it once as `node frontend/scripts/render-terms.mjs` from the
      repo root to confirm cwd-independence. **The parity test is NOT the oracle here**:
      `src/content/termsOfService.test.ts:15-20` reads the committed file and compares it
      in-process to `renderTermsMarkdown()`, never invoking the script — it stays green
      with `terms:doc` completely broken.
- [x] 2.7 Run `npm test` and confirm all 27 files / 230 tests still pass with **no test
      file edited**. If vitest 4's mock-lifecycle changes break teardown, fix the
      teardown — do not change what a test asserts.
- [x] 2.8 Run `vue-tsc -p tsconfig.vitest.json --noEmit`. This is the **only** check that
      typechecks the suite against the new runner's types: `tsconfig.json` has no
      `references` and excludes `src/**/*.test.ts`, so `vue-tsc -b` in the build (4.3)
      never sees the test files or `tsconfig.vitest.json`.
- [x] 2.9 Even if 2.7 is green, read the two risk lists from design D6's Risks section —
      they are different files for different reasons. For **`mockReset` semantics**:
      `CaptureForm.test.ts` (22 sites, incl. the 22-bare-reset `beforeEach` at
      `:333-343`) and `App.test.ts` (11) — 33 of the 47 sites. For **`restoreAllMocks`
      narrowing / fake timers / prototype spies**: `signingProgress.test.ts`,
      `documentPreview.test.ts`, `AgreementStatus.test.ts` (the first two contain **zero**
      `.mockReset()` calls). Then re-run the suite shuffled
      (`npx vitest run --sequence.shuffle`) a few times. Note what this does and does not
      prove: `isolate` defaults true under `pool: forks`, so cross-file leakage is already
      structurally prevented — the shuffle exercises **within-file** ordering, which is
      exactly the exposure the `beforeEach` reset pattern creates.

## 3. Lint config

- [x] 3.1 Add a scoped block to `frontend/eslint.config.js` for
      `files: ["scripts/**/*.{js,mjs,cjs,ts}"]`, placed **last** in the exported array
      (flat config merges globals in order, so a later unscoped block would re-add the
      browser set). It must spread **browser-off first, then `globals.node`**:

      ```js
      globals: {
        ...Object.fromEntries(Object.keys(globals.browser).map((k) => [k, "off"])),
        ...globals.node,   // MUST be last
      }
      ```

      **The order is the correctness of this block.** The two sets overlap on 60 names
      (`console`, `fetch`, `URL`, `crypto`, `setTimeout`, …); only 703 are
      browser-exclusive. Node last re-defines the overlap and withholds only the 703.
      Reversed, browser-`"off"` wins on the overlap and strips `console` — trading the two
      `process` errors at `security-scan.mjs:37,41` for two `console` errors at `:33,35`.
      Comment the block with why browser globals are being withheld at all
      (`eslint-plugin-vue`'s unscoped `vue/base/setup` applies 763 browser globals
      repo-wide). Note the glob includes `.ts` for globals coverage, but `no-undef` does
      not run on `.ts` — do not read that extension as gate coverage.
- [x] 3.2 Run `npm run lint` and confirm **0 errors** and a zero exit status. The 26
      `vue/html-indent` / `vue/html-closing-bracket-newline` warnings are expected to
      survive and are out of scope.
- [x] 3.3 **Discriminating check — 3.2 alone is not sufficient.** "Zero errors" is equally
      satisfied by adding `scripts/` to `ignores` or turning off `no-undef`, the shortcuts
      the spec forbids. Run `npx eslint --print-config scripts/security-scan.mjs` and
      assert: output is a **JSON object, not the bare string `undefined`** (an ignored
      file prints `undefined` and exits 0 — that is what the excused case looks like);
      `process` **and `console` and `fetch`** are present in `languageOptions.globals`
      (the latter two are the canaries for a mis-ordered spread); `window`, `document`
      and `localStorage` are absent or `"off"`; and `no-undef` is still `"error"` (`2`).
      Repeat for `scripts/render-terms.mjs`.
- [x] 3.4 Confirm no regression in the application sources: `npx eslint --print-config
      src/main.ts` must still show `window`/`document` defined. The scripts block is
      scoped, but nothing else asserts the scoping actually held.

## 4. Full gate

- [x] 4.1 Run `npm run security:scan` on the now-final dependency graph and confirm
      **0 findings** — not merely the absence of GHSA-82fw-gwwq-j7x9. A new advisory
      arriving with the bump's transitives is a halt, not a suppression.
- [x] 4.2 Confirm `frontend/osv-scanner.toml` is unchanged, still carries zero
      `[[IgnoredVulns]]` entries, and that its header comment claiming "the graph is
      currently clean (0 known vulnerabilities)" is **true again** — it is false today,
      and 4.1 is what restores it rather than luck.
- [x] 4.3 Run `npm run build` from `frontend/` end to end and confirm it completes —
      `security:scan && test && vue-tsc -b && vite build` all pass. Only `vue-tsc -b` and
      `vite build` are genuinely new here; the scan and suite repeat 4.1 and 2.7.

## Coverage

Scenario dispositions for the `frontend-test-harness` delta. This CR has no runnable
behaviour, so its "tests" are the gate commands themselves — for a lint-config change,
ESLint **is** the oracle, run deterministically by a task rather than eyeballed. No
scenario here touches money, PII, or the document's legal validity, so no waiver
question arises (none is `WAIVED`).

Note that task 3.2 (`npm run lint` → 0 errors) is a **necessary but not sufficient**
oracle — it is satisfiable by exempting the very files under test — so the scenarios
that assert a *mechanism* map to the `--print-config` checks in 3.3/3.4 instead.

| Scenario | Disposition | Covering task |
|---|---|---|
| Lint script resolves and runs | COVERED | 3.2 — `npm run lint` must execute and report, not fail on a missing binary |
| Clean sources pass lint | COVERED | 3.2 — asserts **zero errors** and a zero exit; re-asserted end-to-end by 4.3 |
| Node tooling scripts may use Node globals | COVERED | 3.3 — asserts `process` (Node-exclusive) plus `console` and `fetch` (the overlap canaries) are defined for `security-scan.mjs`, whose two `no-undef` errors are this scenario's failing case |
| Browser-exclusive globals are not defined in Node tooling scripts | COVERED | 3.3 — asserts `window`/`document`/`localStorage` are absent or `"off"` for that path |
| Tooling scripts are actually inspected, not excused | COVERED | 3.3 — asserts the config resolves to a real object (not `undefined`) with `no-undef` still `"error"`, defeating both the `ignores` and the rule-disable shortcut |
| Application sources keep their browser globals | COVERED | 3.4 — `--print-config src/main.ts` still shows `window`/`document` defined |

6 scenarios — 6 COVERED, 0 GROUPED, 0 MANUAL, 0 WAIVED, **0 UNMAPPED**.

## 5. Register and docs

- [x] 5.1 Close the `frontend-dev-dep-refresh` row in the `## Follow-up register` table
      in `docs/ROADMAP.md` (marked "High — blocks the frontend build gate repo-wide";
      leaving it standing after this lands is stale).
- [x] 5.2 Add a register row for the deferred `.vue` stylistic lint warnings — the 26
      `vue/html-indent` / `vue/html-closing-bracket-newline` warnings arising from the
      Prettier vs `eslint-plugin-vue` overlap — raised by `frontend-dev-dep-refresh`,
      dated 2026-09-11, low priority (advisory only, does not fail the gate).
- [x] 5.3 Add a register row for **config-driven test teardown** — set `restoreMocks` /
      `clearMocks` in `vite.config.ts` and retire the 47 hand-written `.mockReset()`
      calls across 9 test files, so the next vitest major is cheap. Cheaper *after* this
      CR lands on v4 semantics, so it is sequenced work rather than unrelated cleanup.
      Medium priority. Not folded in: it would mean touching every one of those files.
- [x] 5.4 Add a register row for **`terms:doc` is gated by nothing** — this CR's own
      root-cause finding. No gate invokes the generator (`npm run build` does not), and
      the parity test passes with it completely broken, which is exactly how it broke
      silently this time. The document it produces is customer-facing legal text.
      Medium-high priority.
- [x] 5.5 Add a register row for the **`engines.node` vs vitest-4 mismatch** —
      `package.json` allows `>=20.19.0`, so Node 21/23 satisfies ours and violates the
      runner's `^20 || ^22 || >=24`. Affects every developer; narrowing `engines` is its
      own change. Low-medium priority.
- [x] 5.6 Update the generator's header comment — the existing one
      (`render-terms.ts:8-13`) documents the `vite-node` transitive-binary hazard this
      change removes. Replace it with why the generator boots Vite directly and why
      `ws: false` / `server.close()` / `root` are load-bearing, so nobody reintroduces
      `vite-node` or trims the options. Also fix the stale generator path in
      `src/content/termsDocPath.ts:3` (says `scripts/render-terms.ts`); note that
      `src/content/termsOfService.ts:7` already says `.mjs` — wrong today, correct after
      this CR — so nobody "fixes" it back.
