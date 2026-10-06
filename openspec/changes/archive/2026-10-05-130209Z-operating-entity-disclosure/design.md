## Context

The operator is **KAVISAT TEK LABS LLP**. The name is final. The LLPIN and GSTIN are being issued, and the registered office is unconfirmed. Today nothing in the product names the operator (grounding sweep, 2026-10-05).

What constrains the design:

- **The frontend has no runtime config.** There are no `VITE_*` variables, no `.env` files and no config endpoint. The SPA is a static Vite build, deployed two ways. Both are live (user, 2026-10-05):
  - the compose `caddy` image (`deploy/Dockerfile.web`, build context = repo root);
  - Cloudflare Pages (`docs/DOMAIN-AND-EMAIL-SETUP.md` §3).
- **The landing page makes no network calls** (`LandingPage.vue:1-5`, `App.test.ts:284`). A runtime fetch of operator details is therefore out.
- **Terms clause bodies are plain text with "no interpolation"** (`termsOfService.ts:24`). That file is the one source of both `/terms` and the counsel copy `docs/TERMS-OF-SERVICE.md`. `termsOfService.test.ts` fails when they drift. `scripts/render-terms.mjs` loads the real `vite.config.ts`.
- **The backend has no `prod` profile.** The deploy runs `SPRING_PROFILES_ACTIVE=sandbox` with no sandbox yml, so production is effectively "no profile". The fail-closed precedent validates the *value* in a `@ConfigurationProperties` constructor and throws `IllegalStateException` (`AuthProperties`, `PaymentProperties`).
- **Deploy config is one gitignored env file per service** under `deploy/env/` (`docker-compose.prod.yml:9-12`). There is deliberately no shared `.env`, and the backend reads `env_file: ./env/backend.env`.
- **No invoice or receipt code exists.**
- **`seo-foundations` (active), task 2.6,** will put `legalName` and an address into the Organization JSON-LD in `index.html`, "reading entity facts from one config source". This change is that source. `index.html` is reachable only from the Vite config context (a `transformIndexHtml` hook or `%VITE_*%` replacement), never from a module that reads `import.meta.env`.

## Goals / Non-Goals

**Goals**
- Each operator fact has one home per runtime.
- Pending facts swap in through deploy env, with no source edit.
- No surface can show a fake identifier, and a GSTIN placeholder cannot reach any document.
- The landing page stays network-free, and the terms stay plain text.

**Non-Goals**
- Privacy, Refund/Cancellation and Contact pages. These go to the counsel paragraph.
- The JSON-LD itself (`seo-foundations` 2.6).
- Invoices and receipts.
- Rebuild-free swaps of frontend values.

## Decisions

### D1. The legal name is committed; only pending identifiers come from env

The name is final, and the terms state it in plain text. Making it env-overridable would let a deploy show one party in the footer and another in the contract. It lives in two places, one per runtime:

- **Backend:** the constant `OperatingEntity.LEGAL_NAME`. It is deliberately **not** a property: a bindable `operator.legal-name`, even one written as a literal in `application.yml`, can still be overridden by `OPERATOR_LEGAL_NAME` through Spring's relaxed binding (found by code review at apply). With no property to bind, such a variable is ignored.
- **Frontend:** `OPERATOR_LEGAL_NAME` in `src/content/operatorFacts.ts`. That module is import-free and free of `import.meta.env`, so `vite.config.ts` can load it (see D4, and the route `seo-foundations` 2.6 takes into `index.html`).

The two copies are pinned by tests:
- `operatorFacts.test.ts` extracts the Java string literal from `OperatingEntity.java` and asserts the names are equal, the same mechanism that pins the LLPIN pattern.
- The same test asserts that §1 contains the name.

It is one fact in two runtimes that cannot share a module, so it is held at one boundary by one test.

**Alternatives rejected:**
- A shared JSON file. The backend image context is `backend/` and the web image copies only `frontend/`.
- An env-overridable name, for the reason above.

### D2. Pending identifiers: blank default, honest rendering

Each pending identifier defaults to blank. Blank is the "dummy default", rendered honestly:

| Identifier | When blank |
|---|---|
| LLPIN | "LLPIN: being issued" |
| Registered office | Omitted. The terms' Operator details say "to be confirmed". |
| GSTIN (backend only) | Absent |

Production is the live founding-team beta. A realistic dummy such as `AAA-0000` would be a false statutory statement on a public page, and it is exactly the kind of value the GSTIN rule exists to keep off a document.

**The registered office is validated even though it is free text.** It reaches HTML today and, via `seo-foundations`, a JSON-LD `<script>` later. It is therefore constrained to at most 200 characters from ASCII letters, digits, U+0020 space and `, . - / # ( ) & '`. That excludes `<`, `>`, `"`, `\`, `;`, `$`, tabs, newlines and non-ASCII. The frontend gate refuses anything else (D4).

**Consumers still escape for their own context.** Vue's `{{ }}` escapes for HTML; a JSON-LD consumer JSON-encodes and escapes `<`. A consumer must read the value through `operatingEntity.ts`, never through Vite's raw `%VITE_*%` HTML replacement, which would decode `&` sequences.

**The office stays one string.** That is sufficient for `seo-foundations` 2.6, because schema.org `Organization.address` accepts Text as well as `PostalAddress`. A structured address would add more variables to all three swap-in places, for no requirement that needs it today. This is recorded here so 2.6 does not reopen it.

The backend does not bind the registered office. No backend surface uses it, so binding it there would be speculative.

### D3. Backend: fail closed on the value, not the profile

`OperatingEntity` is a public, non-record `@ConfigurationProperties(prefix = "operator")` class in the root package `in.agreementmitra`, beside `AbuseLimitsProperties`. Root-package types are already shared by modules; for example, `ConflictException` is used in `signing.payment`. A root `OperatingEntityConfig` registers it with `@EnableConfigurationProperties`.

Its single constructor `(String llpin, String gstin)` treats null as blank and strips every field; the legal name is the constant (D1). It then refuses to start when:

- **a non-blank LLPIN** does not match `^[A-Z]{3}-\d{4}$`;
- **a non-blank GSTIN** fails any of these:
  - the structure `^\d{2}[A-Z]{5}\d{4}[A-Z][1-9A-Z]Z[0-9A-Z]$`;
  - a check that PAN character 4 (GSTIN index 5) is `F`, the firm/LLP holder type. This catches a founder's personal (`P`) GSTIN pasted by mistake, which a checksum cannot;
  - the mod-36 check character. Over `0-9A-Z`, take the first 14 characters with factors alternating 1, 2. Sum `p / 36 + p % 36` for each product `p`. The check character is `(36 - sum % 36) % 36`.

Errors name the property (`operator.gstin`) and never the value.

Accessors:
- `legalName(): String`
- `llpin(): Optional<String>`
- `gstin(): Optional<String>`

There is **no raw GSTIN accessor**. The class is not a record, so no component accessor leaks a string. The rule against dummy GSTINs is therefore structural:
- there is no default to print;
- a typed placeholder (`XXXXXXXXXXXXXXX`, a checksum-invalid number, a non-`F` holder) stops the app;
- callers must handle empty.

**Accepted trade-off:** a malformed GSTIN stops the live backend even though nothing reads the GSTIN until the invoice CR. This matches the `AuthProperties` and `PaymentProperties` precedent. A typo is caught at the deploy that introduces it, not at the first invoice.

**Alternative rejected:** a `prod` profile that refuses dummies. No such profile exists (the deploy runs `sandbox`), and inventing one is a second mechanism to keep in sync.

### D4. Frontend: build-time constants and a config-time format gate

**`src/content/operatorFacts.ts`** has no imports and no `import.meta.env`, so the Node config context can load it. It holds:
- `OPERATOR_LEGAL_NAME`;
- the LLPIN pattern and the registered-office rule;
- `assertOperatorEnv(env)`, which trims each `VITE_OPERATOR_*` value and throws, naming the variable, when a non-blank one fails its rule.

**`src/content/operatingEntity.ts`** exports:
- `resolveOperatingEntity(env)`, which trims and maps blank to `null`, giving `{ legalName, llpin, registeredOffice }`;
- `OPERATING_ENTITY`, built from an object that names `import.meta.env.VITE_OPERATOR_LLPIN` and `import.meta.env.VITE_OPERATOR_REGISTERED_OFFICE` explicitly. It is never built from `import.meta.env` as a whole: Vite inlines a whole-object reference as every `VITE_*` value it can see, which would put any future key, or a stray secret in a build env, into the public bundle;
- `OPERATING_ENTITY_DEFAULTS = resolveOperatingEntity({})`.

**`vite.config.ts`** becomes the function form.
- It derives the frontend directory from its own `import.meta.url`, never from `process.cwd()`, and sets `envDir` to that directory.
- It calls `loadEnv(mode, thatDir, "VITE_OPERATOR_")`, which reads `.env*` files plus `process.env`, and passes the result to `assertOperatorEnv`.
- The gate therefore validates exactly the files Vite bakes from, even when `render-terms.mjs` runs from the repo root (its header says independence from the working directory is load-bearing).
- This runs on every command: build, dev, Vitest and `render-terms.mjs`.
- The function form is unit-tested by importing the config and invoking it with a stubbed `process.env`, so the wiring has an automated test, not only a manual build.
- `vite.config.ts` is outside `tsconfig.json`'s `include`, so `vue-tsc` does not check it. That is the status quo, and the logic it calls lives in the type-checked `src/` module.

There is no GSTIN on the frontend (spec), so there is no frontend checksum. The algorithm lives only in Java.

The LLPIN pattern exists in both runtimes. `operatorFacts.test.ts` extracts the Java string literal from `OperatingEntity.java`, un-escapes `\\` to `\`, and asserts it equals the TS regex `.source`.

Values are baked at build time, so rendering makes no request. The landing constraint holds by construction.

**Alternative rejected:** Caddy templating values into `index.html` at serve time. It saves a rebuild, which the user accepted, but costs:
- escaping work;
- a second path for Pages, which serves static files only;
- friction with `spa-content-security-policy`.

### D5. `SiteFooter.vue` takes the entity as a prop

`src/components/SiteFooter.vue` carries the existing landing footer markup:
- the classes;
- the `/terms` link;
- the `mailto:` `CONTACT_EMAIL` link;
- plus the operator sentence and the identifier lines.

It takes `entity` as a required prop, and the views pass `OPERATING_ENTITY`. The component itself reads no env. Its tests are deterministic whatever is in the developer's `.env.local` or shell, and need no module reset.

It is mounted by `LandingPage.vue` and `TermsOfService.vue`. On `/terms` the footer's terms link points at the page itself. That is harmless and kept, so the footer stays one shape.

The `landing-page` spec's footer, mailto and test-hook rules are preserved. The operator line is not an availability claim.

### D6. Terms: literal name in §1, identifiers in a rendered "Operator details" block

§1 gains a plain sentence: AgreementMitra is a service provided by KAVISAT TEK LABS LLP, and in these terms "we" and "us" mean that LLP. There is no interpolation. Equality with the constant is enforced by a test (D1).

The LLPIN and office stay out of every clause, so the accepted terms text does not change when they are issued. The terms-acceptance checkpoint queued for later records which version was accepted, and the Operator details block is **not** part of that version. It is deployment data, and it may legitimately differ between builds.

The "Operator details" block comes after the clauses:
- `TermsOfService.vue` renders it from an `entity` prop that defaults to `OPERATING_ENTITY`.
- `renderTermsMarkdown()` renders it from `OPERATING_ENTITY_DEFAULTS`, so the generated document is deterministic.

The env-independence test stubs `VITE_OPERATOR_LLPIN`, calls `vi.resetModules()`, and dynamically re-imports `termsMarkdown.ts`. Without that re-import a regression to `OPERATING_ENTITY` would stay green.

The markdown section says the identifiers come from deployment configuration. `TERMS_LAST_UPDATED` is bumped and `npm run terms:doc` regenerates the file.

**Alternative rejected:** naming the party by role only. With the name final, a contract should name its party.

### D7. Email operator line

`SignedDocumentDeliveryService` builds `operatorLine` once from the injected `OperatingEntity`:
- `AgreementMitra is a service of KAVISAT TEK LABS LLP.`
- or, once an LLPIN is set, `AgreementMitra is a service of KAVISAT TEK LABS LLP (LLPIN ACA-1234).`

The GSTIN is never included.

`DeliveryMessages.withAttachment` and `notificationOnly` take the line and append it as the last paragraph, after a blank line. `DeliveryMessages` stays a pure static composer. Its class Javadoc ("nothing else identifying") is amended: the operator line identifies the sender, not the parties.

### D8. Deploy wiring keeps the per-service env-file model

- **Backend:** `OPERATOR_LLPIN` and `OPERATOR_GSTIN` go in `deploy/env/backend.env`, the existing convention, and are listed blank in `backend.env.example`. No compose `environment:` entry is added, so nothing can override `env_file` with a blank.
- **Web image:** a new gitignored per-service file, `deploy/env/web-build.env`, holds only `VITE_OPERATOR_LLPIN` and `VITE_OPERATOR_REGISTERED_OFFICE`.
  - A template is added, and `provision.sh` creates the file empty.
  - `Dockerfile.web` copies it into the `spa` stage as `.env.production.local`, which Vite reads in a production build.
  - Its header states that it is baked into a public bundle and must never hold a secret.
  - It uses `env_file`-style literal `KEY=value`, and the template says to double-quote the address.
- **The env file is optional.** The `spa` stage reads `web-build.env` through a `RUN --mount=type=bind` of `deploy/` (always present in the context via the Caddyfile, whereas `deploy/env/` is absent when the file is), so an absent file means no values: the honest "being issued" state, the same as an unset Pages variable. A fresh clone therefore still builds.
  - The same step fails the build when the file holds any key other than the two `VITE_OPERATOR_*` keys, or any `$`. Vite's dotenv-expand would otherwise expand `$` silently, even inside quotes, before the gate sees the value.
  - The template warns that an unquoted ` #` truncates the value.
- **`deploy/Dockerfile.web.dockerignore`:** BuildKit's per-Dockerfile ignore file, written as an **allowlist**. It ignores `*`, then re-includes `frontend/`, `deploy/Caddyfile` and `deploy/env/web-build.env`, then re-excludes `frontend/node_modules`, `dist`, `.vite` and `.env*`.
  - Keys, certificates, `.git` and `backend/` therefore never reach the build context.
  - The legacy builder (`DOCKER_BUILDKIT=0`) ignores this file, but `provision.sh` installs buildx.
  - `frontend/.dockerignore` is inert for a repo-root context and is deleted.

  This also repairs the inert ignore. On a checkout that has a host `node_modules`, `COPY frontend/ ./` could overwrite the `npm ci` install with it.
- **Cloudflare Pages:** the two `VITE_OPERATOR_*` variables are set in the dashboard, documented in `docs/DOMAIN-AND-EMAIL-SETUP.md` §3.
- **Swap-in:** the ROADMAP "Ops / config" line names all three places.

**Alternative rejected:** compose `environment:` interpolated from a shared `deploy/.env`. It overrides `env_file` with blanks, and it breaks the documented no-shared-`.env` model. All three reviewers flagged it in round 1.

## Risks / Trade-offs

- **[Three places hold the LLPIN: `backend.env`, `web-build.env`, Pages]** → If one is missed, that surface says "being issued". It never shows a wrong-format value. The ops line lists all three.
- **[The counsel document shows defaults, not deployed values]** → Intended (D6), and stated in the rendered section.
- **[A valid but wrong GSTIN, e.g. another firm's]** → Format, holder-type and checksum checks cannot catch it. The human swap-in step is the control. The invoice CR should surface the GSTIN for review.
- **[The config gate also runs under Vitest and dev]** → A malformed local `.env` fails tests. This is fail-closed and intended.
- **[A malformed GSTIN stops the backend before anything uses it]** → Accepted (D3).
- **[The landing spec's text-ownership rules count footer text]** → The operator sentence repeats no owned message. The existing ownership tests confirm this.

## Migration Plan

1. Deploy with no new values. Every surface shows the name and "being issued", and the GSTIN is absent. `provision.sh` creates an empty `web-build.env` (an existing server runs `touch deploy/env/web-build.env` once).
2. When the LLPIN or office is issued:
   - set it in `backend.env` (LLPIN only), `web-build.env` and Pages;
   - rebuild `caddy` and Pages;
   - restart the backend.
3. When the GSTIN is issued, set `OPERATOR_GSTIN` in `backend.env` and restart.

Rollback is blanking the values.

## Open Questions

None blocking. Whether §1 may name an LLP whose LLPIN is still pending is appended to the counsel paragraph in ROADMAP.
