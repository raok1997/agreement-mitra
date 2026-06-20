<!-- Test-task exemption: this is a pure test/build-harness + lint-config change. It adds
no runnable application behavior — the seed tests below ARE the test deliverable, not
behavior needing its own separate unit+integration coverage. (Mirrors the archived
backend-test-harness CR's exemption.) -->

## 1. Test runner setup

- [x] 1.1 Add `vitest` (3.x — pairs with Vite 6), `@vue/test-utils`, and `jsdom` to `frontend/package.json` devDependencies (run install to update `package-lock.json`)
- [x] 1.2 Configure Vitest for jsdom + globals — extend `vite.config.ts` with a `test` block (jsdom environment, `globals: true`), falling back to a standalone `vitest.config.ts` only if the Vite/Vitest type merge is awkward
- [x] 1.3 Keep test files out of the production type-check: add `"exclude": ["src/**/*.test.ts"]` to `tsconfig.json` (the `vue-tsc -b` build config), and add a separate `tsconfig.vitest.json` extending the base that `include`s the test files and adds `vitest/globals` to `types` (for IDE support — Vitest itself transpiles, doesn't type-check). Do NOT add `vitest/globals` to the prod `tsconfig.json`
- [x] 1.4 Wire `npm test` → `vitest run` (one-shot) and add a `test:watch` → `vitest` script in `package.json`

## 2. Fix the lint setup

- [x] 2.1 Add `eslint`, `eslint-plugin-vue`, and `typescript-eslint` to devDependencies (current compatible majors: ESLint 9 / typescript-eslint 8 / eslint-plugin-vue 9)
- [x] 2.2 Add a flat `frontend/eslint.config.js` composing `typescript-eslint` recommended + `eslint-plugin-vue` flat recommended. Wire `.vue` parsing explicitly: vue files use `vue-eslint-parser` with `parserOptions.parser: tseslint.parser` so `<script setup lang="ts">` parses
- [x] 2.3 Change the `lint` script to `eslint .` (drop `--ext`; flat config matches files via `files` globs)
- [x] 2.4 Make `npm run lint` clean on committed sources: fix any rule violations eslint-plugin-vue flags in the existing `.vue` files (`App.vue`, `SignDemo.vue`); only disable a specific rule in the config — with a one-line rationale — where the rule is genuinely undesired (no blanket disables)

## 3. Seed unit test — API client

- [x] 3.1 Add `src/api/client.test.ts`: mock global `fetch`; assert `requestSignature` issues `POST /api/signing/<id>/request`, resolves to the parsed `SignSession`, and rejects on a non-ok response

## 4. Seed component test — SignDemo

- [x] 4.1 Add `src/views/SignDemo.test.ts`: `vi.mock('../api/client')`; mount with @vue/test-utils
- [x] 4.2 Assert clicking "Request signature" calls `requestSignature` with the **edited** agreement id (set the input v-model to a non-default value first, so the test proves the binding, not just the `demo-agreement-1` default)
- [x] 4.3 Assert in-flight state disables the button and shows "Requesting...": the mock returns a **deferred promise the test holds unresolved** (`let resolveIt; mockReturnValue(new Promise(r => { resolveIt = r }))` — NOT `mockResolvedValue`), `await trigger('click')` + `await nextTick()`, assert disabled + label, then `resolveIt(session)`
- [x] 4.4 Assert a resolved session renders the signing URL as a link
- [x] 4.5 Assert a rejected request renders the error text and renders no signing URL

## 5. Verify the harness

- [x] 5.1 Run `npm test` — all seed tests pass, process exits clean
- [x] 5.2 Run `npm run lint` — resolves and reports no errors on committed sources
- [x] 5.3 Confirm `npm run build` (`vue-tsc -b`) still passes with the new config + co-located test files present (verifies the `tsconfig` exclude actually keeps tests out of the prod type-check)
- [x] 5.4 Confirm the updated `package-lock.json` is part of the change (reproducible installs / supply-chain hygiene — the lockfile update must land with the devDependency additions)
