## Why

AgreementMitra is a brand, not a legal person. The service is operated by **KAVISAT TEK LABS LLP**, and nothing in the product says so today. Three outside requirements make that a blocker for the first paid release:

- **Payment-gateway onboarding.** The gateway checks that the legal name on the website matches the settlement account.
- **Consumer-protection e-commerce rules.** A seller must display its legal name, registered address and contact details.
- **GST law.** Once registered, every tax invoice must carry the legal name and GSTIN.

The legal name is final. The LLPIN and GSTIN are still being issued, and the registered office may change with them. So every surface is built against **one configuration source** with safe defaults, and the real values replace those defaults without a code change. A placeholder GSTIN must never reach a tax invoice.

## What Changes

- **One operator source per runtime, with the legal name fixed in both.**
  - **Legal name.** "KAVISAT TEK LABS LLP" is a committed value. The backend holds it as the constant `OperatingEntity.LEGAL_NAME`, not a bindable property, so no env var can rename it. The frontend holds it as a constant in the import-free `src/content/operatorFacts.ts`, which `vite.config.ts` can load. A test pins the two copies equal. It is not env-overridable, because the terms of service name the same party in plain text.
  - **Pending identifiers.** The LLPIN (both runtimes), the registered office (frontend) and the GSTIN (backend) come from deployment env and default to blank. Blank means "not yet issued", and every surface handles that state.
- **Placeholder GSTIN is fail-closed.** On the backend the GSTIN is exposed only as an `Optional`: blank means absent. A non-blank value that fails the GSTIN format and checksum stops the application from starting. There is no dummy GSTIN anywhere to print. The frontend never shows a GSTIN; it belongs on invoices, which are backend output.
- **Malformed LLPIN fails closed too.** A non-blank LLPIN that fails its format stops the backend from starting and fails the frontend build. So does a registered office containing anything outside a plain-address character set. A blank one renders as "LLPIN: being issued", an honest statement, never a realistic-looking dummy.
- **Site footer discloses the operator.** A shared `SiteFooter` component replaces the landing page's inline footer and also appears on `/terms`. It says "AgreementMitra is a service of KAVISAT TEK LABS LLP" and gives the LLPIN, the registered office (when set) and the support email. It reads build-time values only, so the landing page still makes no network call.
- **Terms of service name the contracting party without interpolation.**
  - §1 states the legal name literally. `termsOfService.ts` stays plain text, and a test pins that literal to the operator constant.
  - The identifiers that are not yet issued stay out of the clause text. They render in an **Operator details** block after the clauses, both on `/terms` and in the generated `docs/TERMS-OF-SERVICE.md`.
  - The generated document renders that block from the committed defaults, so the sync test never depends on whoever's env runs it.
  - `TERMS_LAST_UPDATED` is bumped.
- **Signed-agreement email names the operator.** Both delivery messages end with one line naming the operator.
- **Future invoices must use the guarded value.** A requirement binds any future tax invoice or payment receipt to the guarded GSTIN accessor. No invoice is built here.
- **Deploy wiring.**
  - The existing per-service env-file model is kept: the backend values go in `deploy/env/backend.env`.
  - A new public-only `deploy/env/web-build.env` is baked into the `caddy` image's Vite build.
  - On Cloudflare Pages, the same `VITE_*` variables are set in the dashboard.
  - A per-Dockerfile ignore keeps env files and host `node_modules` out of the repo-root build context.
  - All three places are documented, and the ROADMAP "Ops / config" list gains the swap-in step.

**Out of scope.**
- **Privacy Policy, Refund/Cancellation and Contact pages.** Each needs new legal text; they are added to the existing counsel row.
- **The Organization JSON-LD in `index.html`.** It is owned by `seo-foundations` task 2.6, which consumes this change's source.
- **Tax invoices and payment receipts themselves.**
- **Runtime (rebuild-free) injection of frontend values.** The user accepted a rebuild on value change.

## Capabilities

### New Capabilities
- `operating-entity-disclosure`: what the operator source holds and how it fails closed, plus where the legal entity is disclosed. The surfaces are the site footer, the terms of service operator details, and the signed-agreement email. It also covers the rule binding future invoices to the guarded GSTIN.

### Modified Capabilities
_None._ The landing page spec's footer rule (a `mailto:` link to `CONTACT_EMAIL`) still holds unchanged. The operator line is not an availability claim under its "status board" requirement. The delivery spec puts no constraint on message body text.

## Impact

- **Backend**
  - New `in.agreementmitra.OperatingEntity` and its `@ConfigurationProperties` record, in the root package alongside the other shared types.
  - `application.yml` gains `operator.*`.
  - `signing/delivery/DeliveryMessages` uses the new type.
- **Frontend**
  - New `src/content/operatorFacts.ts`, `src/content/operatingEntity.ts` and `src/components/SiteFooter.vue`.
  - Changed: `LandingPage.vue` footer, `TermsOfService.vue`, `termsOfService.ts` §1, `termsMarkdown.ts`, the regenerated `docs/TERMS-OF-SERVICE.md`, a build-time check in `vite.config.ts`, and `vite-env.d.ts` typings.
- **Deploy and docs**
  - `deploy/Dockerfile.web` and a new `Dockerfile.web.dockerignore`, plus `deploy/env/*.example`, `provision.sh` and the compose config comment.
  - `docs/DOMAIN-AND-EMAIL-SETUP.md` (Pages variables) and `docs/ROADMAP.md`.
- **Signing FSM:** none. No state or transition is touched.
- **PII / security:** none. The operator's identifiers are public statutory facts about the company, not personal data or secrets. No Aadhaar, OTP, VID or signer PII is introduced or moved, and sandbox + dummy data only is preserved. The values are set via env like other config but are not secret.
