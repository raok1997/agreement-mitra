## Context

The Vue 3 + Vite + TypeScript SPA in `frontend/` ships with zero test tooling and a
broken `lint` script (`eslint` invoked but absent from `devDependencies`). The
dev-policy expects unit/component tests from the first frontend feature story; this CR
builds that base, mirroring the already-archived `backend-test-harness` CR on the
frontend side. The codebase is small and current — two source modules of interest:
[client.ts](../../../frontend/src/api/client.ts) (`requestSignature`) and
[SignDemo.vue](../../../frontend/src/views/SignDemo.vue) (the demo signing view).

## Goals / Non-Goals

**Goals:**
- A working Vitest + @vue/test-utils + jsdom runner, wired to `npm test` (one-shot) and a
  watch script.
- Repair `npm run lint`: ESLint + plugins in `devDependencies` and a flat config.
- Two seed tests proving the harness: an API-client unit test (fetch mocked) and a
  SignDemo component test (API client mocked).

**Non-Goals:**
- No **E2E / Playwright** — no real browser driving a running SPA + backend.
- No **cross-stack contract test** — the network is mocked here, so frontend/backend
  `SignSession` shape drift is explicitly not covered (noted as a future CR candidate).
- No production source changes; no backend changes; no Docker dependency.
- No coverage gate yet (the frontend analogue of JaCoCo) — deferred; this CR establishes
  the runner first, a threshold can ratchet in later.

## Decisions

**Vitest over Jest.** The project already uses Vite; Vitest reuses the same
config/transform pipeline and resolves `.vue`/TS out of the box via
`@vitejs/plugin-vue` (already a devDependency). Jest would need separate Babel/ts-jest
and Vue-SFC transform wiring — redundant in a Vite project.

**jsdom over happy-dom.** jsdom is the mature default and is sufficient for these
component assertions; happy-dom is faster but less complete. No need for the speed
trade-off at this scale.

**Config location: a `test` block in `vite.config.ts` (single source) vs. a separate
`vitest.config.ts`.** Prefer extending the existing `vite.config.ts` with a `test`
block (using the `vitest/config` `defineConfig`) so plugin/resolve settings aren't
duplicated. A standalone `vitest.config.ts` is the fallback only if the Vite/Vitest
type merge proves awkward. Either satisfies the spec; this is an implementation detail.
Pin Vitest to the **3.x** line — the current major paired with Vite 6 — to avoid a peer
mismatch; `@vue/test-utils` 2.x and `jsdom` current track this fine.

**Test files must NOT enter the production type-check.** The production build is
`vue-tsc -b`, driven by `tsconfig.json` whose `include` is `src/**` with
`noUnusedLocals`/`noUnusedParameters: true`. Co-located `*.test.ts` files (next to their
sources, per the placement decision below) would otherwise be pulled into the prod
type-check — failing the build on unresolved test globals and unused-var noise. So:
- Add `"exclude": ["src/**/*.test.ts"]` to the production `tsconfig.json` so `vue-tsc -b`
  ignores tests. The build only type-checks shipped source.
- Put test-only types in a **separate `tsconfig.vitest.json`** that extends the base,
  `include`s the test files, and adds `vitest/globals` to `types` — for editor/IDE
  support. **Do not** add `vitest/globals` to the production `tsconfig.json` types; that
  leaks test types into the shipped build.
- Vitest itself transpiles (esbuild) rather than type-checks, so the runner is unaffected
  by this split; the split exists purely to keep `vue-tsc -b` clean while giving the IDE
  test-global types.

**`globals: true`.** Enables `describe/it/expect/vi` without per-file imports, matching
common Vue testing ergonomics; the `vitest/globals` types live in `tsconfig.vitest.json`
(above), not the prod config.

**Flat ESLint config (`eslint.config.js`).** ESLint 9's flat config is current and
avoids legacy `.eslintrc` plus `--ext` quirks. Compose `typescript-eslint` +
`eslint-plugin-vue`'s recommended configs. Keep the existing `lint` script name; drop
the now-unnecessary `--ext` flag since flat config matches files via `files` globs.
Two composition traps to handle explicitly:
- **`.vue` + `<script lang="ts">` parsing.** `typescript-eslint` recommended targets
  `.ts` only. For `.vue` files, eslint-plugin-vue's flat `recommended` sets
  `vue-eslint-parser` as the top parser; it must be configured with
  `parserOptions.parser: tseslint.parser` so the TS in `<script setup lang="ts">` parses.
  Order matters: spread the vue flat config, then ensure the TS parser is wired for
  `.vue`.
- **"Clean sources pass lint" is a build-time gate, not a given.** eslint-plugin-vue's
  recommended rules (attribute order, self-closing, etc.) may flag the existing
  `SignDemo.vue`/`App.vue`. Resolve by fixing the flagged source (preferred — it's
  trivial) or, only where a rule is genuinely undesired, disabling that specific rule in
  the config with a one-line rationale. Do not blanket-disable.

**Mocking strategy — mock the boundary, not the transport, in the component test.** The
component test mocks `../api/client` (`vi.mock`) so it asserts UI wiring against a
controlled `requestSignature`; the client unit test separately mocks `fetch` (global
stub via `vi.stubGlobal` / `vi.fn`) to assert the HTTP contract. This keeps the two
concerns — rendering vs. transport — in separate tests, the standard pyramid split.

**Test file placement: co-located `*.test.ts` next to sources.** `client.test.ts` beside
`client.ts`, `SignDemo.test.ts` beside `SignDemo.vue`. Matches the small-project norm and
keeps the test visible next to what it covers.

## Risks / Trade-offs

- **[Mocked network hides contract drift]** → Accepted and explicit: a backend that
  changes the `SignSession` JSON shape won't fail these tests. Mitigation: documented as
  a Non-Goal and a future contract-test/MSW CR candidate; not silently ignored.
- **[ESLint flat-config + typescript-eslint + eslint-plugin-vue version mismatch]** → Pin
  to current mutually-compatible majors (ESLint 9, typescript-eslint 8, eslint-plugin-vue
  9). Mitigation: the "clean sources pass lint" scenario is the build-time check that the
  config actually composes.
- **[jsdom async timing in the loading-state assertion]** → Asserting the transient
  "Requesting..." state needs control over promise resolution. Mitigation: the mocked
  `requestSignature` must return a **deferred promise the test holds unresolved** —
  `let resolveIt; mockReturnValue(new Promise(r => { resolveIt = r }))` — NOT
  `mockResolvedValue` (which would let the request settle and flip `loading` back to
  false before the assertion runs). In `startSigning`, `loading.value = true` is set
  synchronously before the first `await`, so after `await trigger('click')` +
  `await nextTick()` the button is observably disabled with label "Requesting..."; then
  call `resolveIt(session)` and `await` to assert the resolved state. This sequencing is
  the difference between the test passing and silently asserting the wrong frame.
- **[Spotless format-hook churn]** → The PostToolUse format hook runs on every Edit/Write;
  it targets Java (backend) so frontend edits shouldn't trigger backend reformat, but
  watch for unrelated churn as seen in prior CRs. No action beyond awareness.

## Migration Plan

Additive only — new devDependencies, config, and test files. No rollback concern: if the
harness is unwanted it is removed by reverting the `package.json`/config additions. No
runtime or deployment impact (test/lint tooling is dev-time only).

## Open Questions

- Frontend coverage gate (vitest `--coverage` threshold) — deferred to a later CR, same
  ratchet philosophy as the backend JaCoCo floor. Not blocking.
