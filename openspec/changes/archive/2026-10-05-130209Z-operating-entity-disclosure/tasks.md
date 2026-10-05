## 1. Backend operator source

- [x] 1.1 Add `in.agreementmitra.OperatingEntity`, a public non-record `@ConfigurationProperties(prefix = "operator")` class with a single constructor `(llpin, gstin)` and the constant `LEGAL_NAME` (design D1, D3).
  - The constructor treats null as blank, strips each field, and refuses:
    - a malformed LLPIN;
    - a GSTIN failing the structure, the `F` holder-type character (index 5), or the mod-36 check character.
  - The `IllegalStateException` names the property and never the value.
  - Accessors: `legalName()`, `Optional` `llpin()` and `gstin()`.
  - Register it from a root `OperatingEntityConfig` via `@EnableConfigurationProperties`.
- [x] 1.2 `application.yml`: add `operator.llpin: ${OPERATOR_LLPIN:}` and `gstin: ${OPERATOR_GSTIN:}`, with no `legal-name` key (D1).
  - The comment says the name is the `OperatingEntity.LEGAL_NAME` constant.
  - Add nothing to `application-local.yml` or the test config.
- [x] 1.3 Unit test `OperatingEntityTest`:
  - LLPIN: blank → empty; `ACA-1234` accepted; `LLPIN-PENDING` refused, with the message excluding the value.
  - GSTIN: blank → empty. A **published, known-valid firm GSTIN fixture** is accepted, so the check-digit algorithm is verified against an outside value rather than round-tripped. Refused: `XXXXXXXXXXXXXXX`, the fixture with its check character changed, and a valid-checksum GSTIN with holder type `P`.
  - `legalName()` is the committed constant.
- [x] 1.4 Integration test `OperatingEntityBindingTest`, using `ApplicationContextRunner` with `ConfigDataApplicationContextInitializer` and the user config `OperatingEntityConfig`:
  - Remove the system-environment property source first, so an exported `OPERATOR_*` in the shell cannot leak in.
  - With only the committed `application.yml` loaded, the legal name is `KAVISAT TEK LABS LLP` and LLPIN and GSTIN are empty.
  - With `operator.legal-name=SOMEONE ELSE LLP` added, the context starts and the legal name is unchanged.
  - With `operator.gstin=XXXXXXXXXXXXXXX` added, the context fails. The cause chain names `operator.gstin`, and no message anywhere in the chain contains the value.
  - `BindFailureAnalyzer` prints no `Value:` line for a constructor-bound failure, because `ValueObjectBinder` clears the property before instantiating. Note that in a comment.
  - `ModularityTests` stays green.

## 2. Signed-agreement email

- [x] 2.1 `DeliveryMessages.withAttachment` and `notificationOnly` take an `operatorLine` and append it as the final paragraph (D7).
  - Update the class Javadoc: the line identifies the sender, not the parties.
  - `SignedDocumentDeliveryService` gets `OperatingEntity` by constructor injection and builds the line once.
  - Update the hand-built service in `SignedDocumentDeliveryServiceTest` (`:91`) to pass an `OperatingEntity`.
  - `SigningModuleSliceTest` is a standalone `@ApplicationModuleTest`, which does not scan the root package. Add `@Import(OperatingEntityConfig.class)` to it, and run it with `ModularityTests`.
- [x] 2.2 Unit test `DeliveryMessagesTest`:
  - both messages end with the exact line, after a blank line;
  - no `LLPIN` text when the LLPIN is absent;
  - `(LLPIN ACA-1234).` when it is present;
  - never a GSTIN, even when one is configured on the entity.
- [x] 2.3 Integration test: `SignedDeliveryIntegrationTest` asserts that the sent body ends with "AgreementMitra is a service of KAVISAT TEK LABS LLP.\n", as the spec states.
  - The context must not inherit an exported `OPERATOR_LLPIN`: pin it blank with `@TestPropertySource(properties = "operator.llpin=")`. Re-check its existing body assertions (e.g. `:374`) against the new ending.

## 3. Frontend operator source

- [x] 3.1 Add `src/content/operatorFacts.ts`. It has no imports and no `import.meta.env`, so it can be loaded from config. It holds:
  - `OPERATOR_LEGAL_NAME`;
  - the LLPIN pattern and the registered-office rule;
  - `assertOperatorEnv(env)`, which trims each `VITE_OPERATOR_*` value and throws, naming the variable, on a malformed non-blank value.
- [x] 3.2 Add `src/content/operatingEntity.ts` with `resolveOperatingEntity(env)` (trim; blank → `null`), `OPERATING_ENTITY` and `OPERATING_ENTITY_DEFAULTS`.
  - `OPERATING_ENTITY` names each `import.meta.env.VITE_OPERATOR_*` key explicitly and never passes `import.meta.env` whole (D4). Type the two `VITE_OPERATOR_*` variables in `vite-env.d.ts`.
- [x] 3.3 Convert `vite.config.ts` to the function form (D4).
  - Derive the frontend directory from the config's own `import.meta.url` and set `envDir` to it.
  - Call `loadEnv(mode, thatDir, "VITE_OPERATOR_")` and pass the result to `assertOperatorEnv`.
- [x] 3.4 ~~Add `readYamlLiteral` to `test-support/backendConfig.ts`.~~ Dropped at apply: the backend name became the Java constant `LEGAL_NAME` (D1), so 3.5 reads it the way it reads the LLPIN pattern and no YAML helper is needed.
- [x] 3.5 Unit test `operatorFacts.test.ts`, extracting Java string literals from `OperatingEntity.java` and un-escaping `\\`:
  - `OPERATOR_LEGAL_NAME` equals `LEGAL_NAME`;
  - the TS LLPIN regex `.source` equals `LLPIN_REGEX`;
  - `assertOperatorEnv` throws for `TBD`, and for an office containing `<`, `"`, `\`, `;`, `$`, a tab, a newline, U+2028 or a non-ASCII letter, naming the variable;
  - `assertOperatorEnv` passes for blank values, ` ACA-1234 ` (trimmed) and a realistic address with `#` and `/`;
  - `resolveOperatingEntity` maps blank to `null` and trims;
  - `OPERATING_ENTITY_DEFAULTS` has `null` LLPIN and office.
- [x] 3.6 Integration test `viteConfig.test.ts`, under `// @vitest-environment node`: import `vite.config.ts` and invoke its exported function with a full `ConfigEnv` (`command: "build"`, `mode: "production"`, `isSsrBuild: false`, `isPreview: false`).
  - Delete `process.env.VITE_OPERATOR_LLPIN` in `afterEach`.
  - With `process.env.VITE_OPERATOR_LLPIN = "TBD"` it throws, naming the variable.
  - With it unset it returns a config.

## 4. Site footer

- [x] 4.1 Add `src/components/SiteFooter.vue` with a required `entity` prop.
  - It carries the existing landing footer markup and classes, the `/terms` and `mailto:` links, the operator sentence, the LLPIN line (value or "being issued"), and the office line when set.
  - It keeps the `<footer>` element (`LandingPage.test.ts:221` queries `footer a[href^="mailto:"]`).
  - It uses `{{ }}` only, no `v-html`.
  - Replace the inline footer in `LandingPage.vue` and mount the footer in `TermsOfService.vue`, each passing `OPERATING_ENTITY` (D5).
- [x] 4.2 Unit test `SiteFooter.test.ts`, with explicit props and no env dependence:
  - the operator sentence;
  - "being issued" for `llpin: null`;
  - `LLPIN: ACA-1234` for a set LLPIN;
  - the office line omitted when `null`, present when set;
  - the `mailto:` href is `CONTACT_EMAIL`;
  - the `/terms` link is present;
  - a `fetch` spy is never called;
  - the source contains no `v-html`.
- [x] 4.3 Integration test: extend `App.test.ts` so `/` and `/terms` each render a `footer` element whose own text contains the operator sentence. Scope the assertion to the footer, because on `/terms` the name also appears in §1 and in Operator details. The assertions are env-independent: the name only, not the LLPIN state. The existing no-API assertion at `/` still holds.

## 5. Terms of service

- [x] 5.1 Add the literal operator sentence to §1 of `termsOfService.ts` and redefine "we"/"us" as the LLP (D6). Bump `TERMS_LAST_UPDATED`.
- [x] 5.2 Render an "Operator details" section after the clauses, and regenerate `docs/TERMS-OF-SERVICE.md` with `npm run terms:doc`.
  - `TermsOfService.vue` renders it from an `entity` prop defaulting to `OPERATING_ENTITY`, with no `v-html`.
  - `renderTermsMarkdown()` renders it from `OPERATING_ENTITY_DEFAULTS`, with a line saying the identifiers come from deployment configuration.
- [x] 5.3 Unit tests in `termsOfService.test.ts`:
  - §1 contains `OPERATOR_LEGAL_NAME`;
  - no clause body or gap contains `LLPIN` or `GSTIN`;
  - environment independence: `vi.stubEnv("VITE_OPERATOR_LLPIN", "ACA-1234")`, then `vi.resetModules()`, then a dynamic `import()` of `termsMarkdown.ts`. The output must be identical to the unstubbed output and contain no `ACA-1234`. Unstub afterwards.
- [x] 5.4 Integration test in `TermsOfService.test.ts`:
  - mount with an explicit `entity` whose `llpin` is `null`;
  - the "Operator details" section, found by `data-testid="terms-operator-details"`, comes after the last clause, names the LLP, says "being issued" and contains the support email;
  - the source contains no `v-html`;
  - the existing markdown sync test passes against the regenerated file.

## 6. Deploy wiring and docs

- [x] 6.1 Backend env and web build env (D8):
  - `deploy/env/backend.env.example` gains `OPERATOR_LLPIN=` and `OPERATOR_GSTIN=`, blank and commented.
  - Add `deploy/env/web-build.env.example`, holding only the two `VITE_OPERATOR_*` keys. Its header says the values are baked into a public bundle, must never hold a secret, and that the address must be double-quoted.
  - `provision.sh secrets` touches `web-build.env`.
  - In the `spa` stage of `Dockerfile.web`, after `COPY frontend/ ./`, add a `RUN --mount=type=bind,source=deploy,target=/deploy` step (`deploy/`, not `deploy/env`: with `web-build.env` absent the allowlisted context has no `deploy/env/` directory, but always has `deploy/Caddyfile`). It does three things:
    - when `web-build.env` is present, copies it to `.env.production.local`;
    - when it is absent, continues with no values;
    - fails when the file has any key other than the two `VITE_OPERATOR_*` keys, or any `$`.
  - Add `deploy/Dockerfile.web.dockerignore` as an allowlist (D8). Delete the inert `frontend/.dockerignore`.
  - Amend the compose CONFIG MODEL comment to name `web-build.env`.
- [x] 6.2 Docs:
  - `docs/DOMAIN-AND-EMAIL-SETUP.md` §3 lists the two Pages variables.
  - `docs/DEPLOYMENT.md`: add a `web-build.env` row to the env-file table (`:217-219`) and check the note at `:330`.
  - `docs/ROADMAP.md`: "Ops / config" gains the swap-in step naming all three places. The counsel paragraph gains the Privacy, Refund and Contact pages and the §1 "LLP named while LLPIN pending" question. The `operating-entity-disclosure` register row is deleted at archive.
- [x] 6.3 Run the gates (backend `check`, frontend build + lint, the `TBD` build failure and the absent-`web-build.env` image build were run at apply; the empty / valid / extra-key image builds were handed to the manual-test gate, which the user passed 2026-10-05):
  - `./run-tests.sh check` (backend);
  - `npm run build` and `npm run lint` (frontend);
  - `VITE_OPERATOR_LLPIN=TBD npx vite build`, which must exit non-zero;
  - `docker compose -f deploy/docker-compose.prod.yml build caddy`, which must succeed both with `web-build.env` absent and with it empty;
  - the same build with a `web-build.env` containing a third key, which must fail.

## Coverage

| Scenario | Disposition | By |
|---|---|---|
| Frontend and backend names agree | COVERED | 3.5 |
| Terms name the same party | COVERED | 5.3 |
| The environment cannot rename the operator | COVERED | 1.4 |
| No value configured | COVERED | 4.2 |
| Real value configured | COVERED | 4.2 (prop), 3.5 (`resolveOperatingEntity`) |
| Registered office not yet configured | COVERED | 4.2 |
| Malformed LLPIN at backend startup | COVERED | 1.3 |
| Placeholder or wrong GSTIN at backend startup | COVERED | 1.3 (all three values), 1.4 (context refuses) |
| Valid GSTIN accepted | COVERED | 1.3 |
| Malformed value at frontend build | COVERED | 3.5 (`assertOperatorEnv`), 3.6 (config wiring) |
| No GSTIN configured | COVERED | 1.4 |
| Repository carries no default GSTIN | COVERED | 1.4 |
| Landing page footer | COVERED | 4.3 |
| Terms page footer | COVERED | 4.3 |
| No request issued | COVERED | 4.2 |
| Page shows operator details | COVERED | 5.4 |
| Generated document is environment-independent | COVERED | 5.3 |
| Clause text holds no identifier | COVERED | 5.3 |
| Attachment message without LLPIN | COVERED | 2.2, 2.3 |
| Oversize notification with LLPIN | COVERED | 2.2 |
