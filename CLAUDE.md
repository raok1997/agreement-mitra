# AgreementMitra

Online rental-agreement platform for India. Core capability: Aadhaar eSign
(OTP-based) of rental agreements — generate agreement → render to PDF →
create signing request → signer authenticates with Aadhaar + OTP → signed
PDF + audit trail return to the app.

This file is context for Claude Code. Keep it short and current. Deep detail
lives in `docs/ARCHITECTURE.md`; per-feature intent lives in `openspec/`.

## Architecture (decided — do not relitigate without a proposal)

- **Backend**: Java 21 + Spring Boot 3.x, structured as a **modular monolith**
  using Spring Modulith. One deployable. Modules have hard boundaries and talk
  through public interfaces / events only — never reach into another module's
  internal packages.
- **Frontend**: Vue 3 + Vite + TypeScript + Tailwind. SPA that talks to the
  backend over a JSON API.
- **Data**: PostgreSQL for state + audit trail. Object storage (MinIO locally,
  S3-compatible in prod) for PDF blobs — never store PDF bytes in Postgres.
  Schema is **Flyway-managed** (single source of truth): forward-only migrations in
  `backend/src/main/resources/db/migration` named `V<n>__<desc>.sql`, applied on
  startup; JPA stays `ddl-auto: validate` in every env (local/test/prod) and
  `flyway.clean` is disabled. Each feature CR ships its own migration; never edit an
  applied one. Regenerate the Gradle lockfile after a dep change
  (`./gradlew dependencies --write-locks`).
- **eSign**: integrated through an `EsignProvider` interface. First adapter is
  Leegality (sandbox). Keep all vendor specifics behind the interface so Digio
  (likely production choice for the KYC bundle) is a one-adapter swap.

### Modules (`in.agreementmitra.*`)
- `signing` — agreements, signing requests, status state machine, webhook
  intake, `EsignProvider` + vendor adapters. The heart of the app.
- `documents` — template → PDF rendering (headless Chromium via Playwright).
- `identity` — KYC / DigiLocker (future feature; stub for now).
- `rules` — multi-state legal-logic rules engine (future; Drools, JVM-native).

## Conventions

- Java: prefer records for DTOs/value objects; constructor injection (no field
  `@Autowired`); package-private by default, `public` only on the module API.
- One aggregate's state transitions go through its state machine, not ad-hoc
  setters. Signing states: `DRAFT → PDF_GENERATED → SIGN_REQUESTED →
  SIGNED | FAILED | EXPIRED`.
- eSign is **asynchronous**: never block a request thread waiting on a
  signature. Create the request, return the signing URL, let the webhook drive
  completion. A scheduled reconciliation job is the fallback for missed hooks.
- Tests: every module change must keep `ModularityTests` green (it verifies
  module boundaries). Write a slice/integration test for new endpoints.
- Frontend: composition API + `<script setup>`; Tailwind utilities for layout
  (responsive is a CSS concern, not a JS one); keep API calls in `src/api/`.

## Security & data handling (non-negotiable)

- This is identity/legal infra. **Sandbox + dummy data only** in this repo.
- **Never log** Aadhaar numbers, OTPs, virtual IDs, or full signer PII. Redact
  before logging. Never echo a webhook payload to logs verbatim.
- Secrets come from env vars only. Never commit `.env`, keys, or credentials.
- Treat inbound webhooks as untrusted: verify signature/HMAC before acting.

## Testing & Scanning

Every change that adds or modifies runnable behavior ships with tests, balanced
as a **pyramid** — broad base, narrow top:

- **Unit (many, fast):** pure logic in isolation — state-machine transitions, DTO
  mapping, redaction helpers, validators. No Spring context, no I/O. The default;
  most coverage lives here.
- **Integration (fewer):** real wiring against real infra — Testcontainers
  (Postgres + MinIO/S3), Spring Modulith slice/module tests, the `EsignProvider`
  adapter against a sandbox/stub. Keep `ModularityTests` green here.
- **End-to-end (few):** a thin top — full signing happy-path through the API.
  Slow and brittle; reserve for the critical flow, don't multiply.

The OpenSpec `tasks:` rule enforces this: a behavioral change must list both a
unit and an integration test task (pure config/docs/harness changes are exempt,
recorded with a one-line note atop `tasks.md`).

**Scanning** (required build gates):

- **Backend dependency-vulnerability scan** — `OSV-Scanner` over the locked
  Gradle graph (`backend/gradle.lockfile`). Runs as part of `./gradlew check`
  (via the `securityScan` task). **Fails on any unsuppressed finding** (OSV has
  no CVSS threshold); the gate is **fail-closed** — a missing `osv-scanner`
  binary breaks the build. Install it: `brew install osv-scanner` (or see
  google.github.io/osv-scanner/installation). To accept a finding, add an
  `[[IgnoredVulns]]` entry to `backend/config/osv-scanner.toml` with a `reason`
  AND an `ignoreUntil` expiry — suppressions are justified and time-boxed, never
  permanent or wildcard. Regenerate the lockfile after dep changes:
  `./gradlew dependencies --write-locks`.
  - **Scan scope (policy):** the gate locks + scans only the **shipping + test**
    configurations (compile/runtime/productionRuntime + testCompile/testRuntime).
    **Build/dev/tool classpaths are excluded by design** — `spotbugs`/
    `spotbugsPlugins`, `jacocoAnt`/`jacocoAgent`, `developmentOnly`, and the
    annotation-processor configs. They ship to no one and run only on the build
    host, so they are out of the product risk surface (the per-config activation
    lives in `build.gradle.kts`, replacing `lockAllConfigurations()`). Trade-off:
    a CVE living *only* in a build tool won't be caught here — lower risk, but not
    zero (the toolchain runs on the build host). **Revisit before any production /
    real-PII deployment** (this is legal/identity infra); compensating control is
    a periodic full-lockfile `osv-scanner` audit or an eventual SpotBugs/JaCoCo
    plugin bump.
- **Backend SAST** — SpotBugs + FindSecBugs, also via `securityScan` in `check`.
  Fails only on **FindSecBugs SECURITY-category findings at HIGH confidence**
  (scoped by `backend/config/spotbugs-include.xml`); suppressions go in
  `backend/config/spotbugs-exclude.xml` with a reason + expiry comment. Stock
  FindSecBugs does **not** catch our own invariants (never-log-Aadhaar,
  verify-HMAC) — those need custom rules / a PII-lint (candidate future CR).
- **Run all gates:** `./gradlew securityScan` (or just `./gradlew check`).

- **Frontend dependency-vulnerability scan** — `OSV-Scanner` over the npm
  lockfile (`frontend/package-lock.json`), run via `npm run security:scan` (from
  `frontend/`; a cross-platform Node shim at `frontend/scripts/security-scan.mjs`).
  **Fails on any unsuppressed finding** (OSV has no CVSS threshold); the gate is
  **fail-closed** — a missing `osv-scanner` binary fails with an install hint, it
  does not skip. **Scan scope:** the **whole lockfile — dev + production deps,
  including transitives** (npm devDependencies are the dominant surface here; only
  `vue` ships to the browser, and dev-tool install scripts/typosquatting are a real
  supply-chain risk). This **deliberately differs** from the backend's
  exclude-build-tooling scope. To accept a finding, add an `[[IgnoredVulns]]` entry
  to `frontend/osv-scanner.toml` with a `reason` AND an `ignoreUntil` expiry —
  justified and time-boxed, never permanent or wildcard. The baseline is currently
  **empty** (graph is clean). **Not yet wired into CI** (local-only today; not
  coupled to `npm run build` — a clean local run is not proof the build was gated);
  CR-7 promotes it. The same `osv-scanner` binary as the backend gate
  (`brew install osv-scanner`).
- **Not yet covered (follow-up CRs):** CI that runs these gates automatically
  (today they run only on local `./gradlew` / `npm run security:scan`). The backend
  `osv-scanner.toml` suppression baseline is currently **empty** — CR-8 remediated
  the Spring Boot 3.4.2 CVEs by bumping to 3.5.15, and CR-9 cleared the residual
  tool-classpath findings via the scan-scope policy above.

The local PII/secret edit guard (`.claude/hooks/pii-secret-guard.sh`) is
**defense-in-depth — a reminder, not the authoritative control**: it can be
evaded by encoded/split/downloaded secrets. A server-side pre-commit / CI secret
scanner is the real enforcement.

## Commands

Backend (from `backend/`):
- `./gradlew bootRun` — run the API (needs Postgres + MinIO; see below). Requires
  `S3_ACCESS_KEY`/`S3_SECRET_KEY` in the env (default empty → MinioClient fails to start).
- `./start_local.sh` — run the API against the compose infra without remembering env
  vars: supplies the compose MinIO creds (defaults only; a real env wins) + the `local`
  profile, then `bootRun`. Run `docker compose up -d` first.
- `./gradlew test` — run tests (includes module-boundary verification)
- `./gradlew check` — tests + JaCoCo coverage gate
- `./gradlew spotlessApply` — format Java

Integration tests use Testcontainers and need a **running Docker daemon**. Without
Docker they skip cleanly (they do not fail the build); unit tests run regardless.
Use `./run-tests.sh` (from `backend/`) to run them without remembering the
Docker/Testcontainers env — it auto-detects the Docker socket and runs `check`
(pass a task to override, e.g. `./run-tests.sh test`).

Frontend (from `frontend/`):
- `npm run dev` — Vite dev server
- `npm run build` — production build
- `npm run lint` / `npm run format`

Local infra (from repo root):
- `docker compose up -d` — Postgres + MinIO

## Working with OpenSpec

Features are built spec-first. Before implementing anything non-trivial:
1. Propose a change (`/opsx:propose <slug>`), which writes
   `openspec/changes/<slug>/` (proposal, specs, design, tasks).
2. Review the proposal and spec deltas with me before code lands.
3. Apply (`/opsx:apply`), then archive (`/opsx:archive`) when done.
Project context for OpenSpec lives in `openspec/config.yaml` (the `context:`
section), included automatically in every OpenSpec request.

## Gotchas

- The webhook listener needs a **public URL** in local dev — front it with a
  cloudflared/ngrok tunnel or the aggregator's callback never arrives.
- PDF rendering for vernacular/Indic scripts must use Chromium (Playwright),
  not a pure-Java PDF lib — only Chromium shapes complex scripts correctly.
  Bundle Noto fonts. (This is why `documents` is its own module.)
