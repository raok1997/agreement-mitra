## Why

The frontend SPA has **no test framework at all** — no Vitest, no Vue Test Utils — and
its `npm run lint` script is broken: it invokes `eslint` but `eslint` is not in
`devDependencies`, so the script errors out. The tightened dev-policy expects every
later frontend story to ship unit/component tests from day one; that is impossible
until the harness exists. This CR mirrors the backend-test-harness CR on the frontend:
it builds the **unit/component base of the test pyramid** so the next feature CR
inherits "tests from the first story," and repairs lint so the existing quality gate
actually runs.

## What Changes

- Add **Vitest** + **@vue/test-utils** + **jsdom** as `devDependencies`, plus a Vitest
  config (jsdom environment, `globals: true`) so components can be mounted and asserted
  on in-process, no browser.
- **Fix the broken lint script**: add `eslint` + `eslint-plugin-vue` +
  `typescript-eslint` as `devDependencies` and a flat config (`eslint.config.js`) so
  `npm run lint` runs cleanly over `.ts`/`.vue`.
- Wire **`npm test` → `vitest run`** (one-shot, CI-friendly) and a `test:watch` script
  for local development.
- Add a **first unit test** on [client.ts](../../../frontend/src/api/client.ts):
  `requestSignature` posts to the right URL with `POST`, parses the JSON body, and
  throws on a non-`ok` response — `fetch` mocked, no network.
- Add a **first component test** on [SignDemo.vue](../../../frontend/src/views/SignDemo.vue):
  mounted in jsdom with `../api/client` mocked — clicking the button calls
  `requestSignature`; while loading the button is disabled and reads "Requesting…"; a
  resolved session renders the signing URL; a rejected promise renders the error text.
- **Out of scope (deliberate):** no **E2E / Playwright** (no real browser driving a
  running SPA + backend) and no **cross-stack contract test** (the network is mocked, so
  these tests cannot catch frontend/backend `SignSession` shape drift). Both are the
  brittle top of the pyramid and are deferred; the contract-drift gap is noted as a
  known future CR candidate.

## Capabilities

### New Capabilities
- `frontend-test-harness`: the frontend unit/component test base — Vitest +
  @vue/test-utils + jsdom with a working config, a repaired ESLint setup so `npm run
  lint` runs, an `npm test` entry point, and two seed tests (an API-client unit test and
  a SignDemo component test) that prove the harness boots green.

### Modified Capabilities
<!-- None. dev-policy is a separate capability and its requirements are unchanged;
     this CR satisfies that policy on the frontend rather than altering it. -->

## Impact

- **Frontend build**: `frontend/package.json` — new `devDependencies` (vitest,
  @vue/test-utils, jsdom, eslint, eslint-plugin-vue, typescript-eslint); new `test` /
  `test:watch` scripts; `lint` script now resolvable.
- **Config**: new `frontend/vitest.config.ts` (or `test` block in `vite.config.ts`) and
  `frontend/eslint.config.js` (flat config). `package-lock.json` updates.
- **Tests**: new `src/api/client.test.ts` and `src/views/SignDemo.test.ts` (co-located
  with sources). No production source files change.
- **No runtime behavior touched.** No `src/main` SPA logic, no API surface, no backend.
  Pure tooling + test addition.
- **Local dev**: `npm test` and `npm run lint` become usable; runs in-process (jsdom),
  no Docker, no browser, no running backend required.
- **Supply chain**: this CR materially widens the frontend `devDependencies` tree
  (ESLint + plugins, Vitest, @vue/test-utils, jsdom). The planned dependency-vulnerability
  scan (CR-4 of the roadmap) MUST scan `devDependencies`, not just runtime deps — they are
  the larger surface here. Noted for that CR; not implemented in this one.

## PII / security review checklist

- **Introduces or moves Aadhaar / OTP / VID / PII or secrets?** None. This is test/build
  tooling only; it adds no runtime data flow and no production code path. Test fixtures
  use the existing dummy `demo-agreement-1` id and a fake signing URL.
- **Redaction / securing:** N/A — no PII or secret data is handled.
- **Sandbox + dummy data only:** preserved. Tests mock `fetch`/the API client; no real
  endpoint, credential, or signer data is touched or committed.
- **Signing-status FSM transitions touched:** none — no signing behavior is added or
  changed; the component test asserts UI wiring against a mocked client only.
