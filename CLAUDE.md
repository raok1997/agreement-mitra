# AgreementMitra

Online rental-agreement platform for India. Core capability: Aadhaar eSign
(OTP-based) of rental agreements — generate agreement → render to PDF →
create signing request → signer authenticates with Aadhaar + OTP → signed
PDF + audit trail return to the app.

This file is context for Claude Code. Keep it short and current. Deep detail
lives in `docs/ARCHITECTURE.md`; per-feature intent lives in `openspec/`. The
team-shared roadmap (what's done / what's next) is `docs/ROADMAP.md`; vendor
specifics live in `docs/integrations/` (e.g. Leegality sandbox/pricing).

`docs/ROADMAP.md` also holds the **`## Follow-up register`** — the single list of
follow-ups spun out of a change. A CR that identifies work it deliberately does not
fold in records it there **before it archives**; a follow-up left only in the change's
`.flow-journal.md` moves into `openspec/changes/archive/` with it and is never read
again. Stage 7a of `openspec-flow` gates on this
(`flow-journal.mjs followups --change <name>`). Do not start a second backlog file,
and do not keep backlog content in agent memory — memory is per-user and does not
reach a teammate working on `main`.

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
  setters. Signing states (`SignatureStatus`): the active path is
  `PDF_GENERATED → STAMPED → SIGN_REQUESTED → SIGNED | FAILED | EXPIRED`, with
  `STAMP_FAILED` as a terminal branch off the stamp step
  (`PDF_GENERATED → STAMP_FAILED`). `DRAFT` is declared but **reserved — not yet
  used**; do not put it on the active path. Keep this line, the `Signing status
  FSM` line in `openspec/config.yaml`, and `SignatureStatus.java` in sync.
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
  `vue`'s **runtime** ships to the browser, and dev-tool install
  scripts/typosquatting are a real supply-chain risk). This **deliberately differs**
  from the backend's
  exclude-build-tooling scope. To accept a finding, add an `[[IgnoredVulns]]` entry
  to `frontend/osv-scanner.toml` with a `reason` AND an `ignoreUntil` expiry —
  justified and time-boxed, never permanent or wildcard. The baseline is currently
  **empty** (graph is clean). It **is** coupled to the build: `npm run build` chains
  `security:scan && test && vue-tsc -b && vite build`, so a passing build implies a
  passing scan (note `build` does **not** run eslint — `npm run lint` is separate).
  Still **not wired into CI** (local-only today, so a clean local run is not proof any
  shared build was gated); CR-7 promotes it. The same `osv-scanner` binary as the backend gate
  (`brew install osv-scanner`).
  - **`dev: false` is not "ships to the browser".** npm's `dev` flag tracks
    reachability from `dependencies`, not bundle membership. `vue` declares
    `@vue/compiler-sfc` (a *build-time* compiler), which pulls `postcss` → `nanoid`,
    so npm marks both non-dev — but Vite precompiles SFCs and tree-shakes the
    compiler out, so neither reaches `dist/`. Verified 2026-09-05 by grepping the
    production bundle for nanoid's `urlAlphabet` (absent) with the vue runtime
    present. So a high-CVSS `dev: false` finding in that subtree is **build-tooling
    risk, not shipped-surface risk** — still fixed (the gate is fail-on-any), but
    don't triage it as browser-reachable. Re-check only if `@vue/compiler-sfc` ever
    becomes browser-reachable; note that a `vue/dist/vue.esm-bundler` alias is *not*
    that trigger — it ships `@vue/compiler-dom`, whose deps are `@vue/compiler-core`,
    not postcss.
- **Not yet covered (follow-up CRs):** CI that runs these gates automatically
  (today they run only on local `./gradlew` / `npm run security:scan`). The backend
  `osv-scanner.toml` suppression baseline is currently **empty** — CR-8 remediated
  the Spring Boot 3.4.2 CVEs by bumping to 3.5.15, and CR-9 cleared the residual
  tool-classpath findings via the scan-scope policy above.
- **Boot 3.5.15 is NOT clean on its own.** As of 2026-09-05 four of its BOM-managed
  versions carry open advisories and are **overridden** in `build.gradle.kts`:
  `tomcat` 10.1.59, `postgresql` 42.7.12, `jackson-bom` 2.21.5, `log4j2` 2.25.5
  (a Boot bump could not fix these — 3.5.16 manages the identical versions). Those
  `extra[...]` overrides are load-bearing: **on the next Boot upgrade, drop one only
  after confirming the new BOM manages that artifact at or above the pinned version**,
  or the fixes silently regress. Full detail in `backend/config/osv-scanner.toml`.

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

**Never set `TESTCONTAINERS_RYUK_DISABLED=true`.** Ryuk is the reaper that removes
test containers when the test JVM dies *without* running its shutdown hook - an
OOM-kill, a Ctrl-C, a crash. The hook covers a clean exit only, so with the reaper
off every aborted run strands a Postgres + a MinIO container. They are invisible
(random names, no compose project) and they accumulate until the Docker engine
wedges - one incident reached 112 orphans / 4.4 GB of volumes, another 32 orphans
holding ~1.8 GiB of a 3.7 GiB Docker VM. **The build fails closed on this**: the
Ryuk guard in `build.gradle.kts` refuses to run `test` when the variable is set.
Deliberate one-off override: `./gradlew test -Pallow.ryuk.disabled=true`.

If container-backed tests appear to *skip*, the cause is socket resolution, not the
reaper - use `./run-tests.sh`, which resolves the socket per Docker context and
leaves Windows/Docker Desktop npipe alone. To clear orphans an earlier run already
left: `./scripts/sweep-test-containers.sh` (dry-run by default, `--force` to remove;
selects on the `org.testcontainers` label and skips compose-managed containers, so it
cannot touch the local dev stack).

Container-backed tests that *fail* (rather than skip) with `Connection refused` on a
Testcontainers-mapped port are a different problem: **an asynchronous host port
forwarder**. Rancher Desktop's experimental `sshPortForwarder` publishes the mapped
port 0.3-1.8s *after* the container reports ready, and Postgres' stock wait strategy is
log-based - it only proves the service is up *inside* the container. Testcontainers
returns, Flyway dials `localhost:<mapped>`, and the port is not bound yet. This is
environmental, not a code bug, and it fails ~200+ tests at once via cascading
`ApplicationContext failure threshold exceeded`. `HarnessTestConfig` handles it by
pairing the log wait with a host-port TCP check (see the javadoc there - the port check
is necessary but NOT sufficient on its own, since the forwarder accepts before the
service behind it is ready, so the two strategies must stay paired). MinIO needs no such
override: its default `Wait.forHttp` already dials the mapped port from the host.

A third variant is a **startup `TimeoutException` on `postgresContainer` only** (never MinIO)
that reshuffles between runs and passes on an isolated re-run. Cause: `WaitAllStrategy`'s
default budget is 30s — half the 60s a bare Postgres wait gets — and in the default
`WITH_OUTER_TIMEOUT` mode `withStrategy()` stamps the *current* outer timeout onto each child
as it is added. So `withStartupTimeout()` must be called **before** `withStrategy()`, or the
children keep 30s, which is not enough under a full suite's container contention. Full
reasoning in `HarnessTestConfig`.

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
3. Apply (`/opsx:apply`), then archive with `openspec archive -y <slug>` when done.
Project context for OpenSpec lives in `openspec/config.yaml` (the `context:`
section), included automatically in every OpenSpec request. Keep its `Signing
status FSM` line in sync with `SignatureStatus.java` — it is injected into every
artifact the CLI helps generate, so drift there mis-specs future changes.

### Archiving folds the spec of record — always use the CLI

`openspec archive` parses each delta, rebuilds the target spec, validates it, and
**aborts without writing** if anything does not hold. Never hand-merge deltas into
`openspec/specs/`, never delegate that merge to a subagent, and never pass
`--skip-specs` to a change that has delta specs. Doing so once left six capabilities
archived but never folded into the baseline (`openspec/BASELINE-FOLD-GAP.md`) and let
a requirement missing its `SHALL` sit in the spec of record for two months.

There is deliberately **no `openspec-archive-change` skill and no `/opsx:archive`
command** — both were deleted because they hand-rolled a `mv` around the CLI. If
`openspec update` regenerates them, delete them again.

### Which skills survive `openspec update`

`.claude/skills/` holds two tiers, and the tier decides where logic may live:

- **Ours (durable).** `openspec-flow`, `review-spec`, `openspec-validate` — none are in
  the CLI's `WORKFLOW_TO_SKILL_DIR`, so `openspec update` never regenerates or deletes
  them. **All policy belongs here.**
- **Vendor's (regenerated).** `openspec-explore`, `openspec-propose`,
  `openspec-apply-change` and the `opsx:` commands for them are written by
  `openspec init/update` via unconditional overwrite. **Treat them as read-only** — an
  edit there survives only until the next openspec release flips the version stamp.

Do not install the upstream skills we omit. `openspec-sync-specs` in particular is
explicitly "agent-driven … you will read delta specs and directly edit main specs" —
that is the improvised merge the CLI exists to replace.

### Reviewing the skills — read `DECISIONS.md` first

`.claude/skills/DECISIONS.md` is the adjudication register for the skills and `opsx:`
commands. Prose instruction files have no test suite, so review is their only quality
gate — an unbounded one, and three rounds in three days each surfaced fresh Criticals
that were mostly second-order consequences of the previous round's own fixes. Before
reviewing any skill: read the register, review **the diff since the last commit rather
than the whole file**, and do not re-raise an entry marked `accepted`/`deferred` without
new evidence. When you fix something, land its blast radius in the same round (grep for
references in the repo, `README.md`, this file, the auto-memory, and
`~/.config/openspec/config.json`). Cap tooling review at 2 rounds, then commit.

## Gotchas

- The webhook listener needs a **public URL** in local dev — front it with a
  cloudflared/ngrok tunnel or the aggregator's callback never arrives.
- PDF rendering for vernacular/Indic scripts must use Chromium (Playwright),
  not a pure-Java PDF lib — only Chromium shapes complex scripts correctly.
  Bundle Noto fonts. (This is why `documents` is its own module.)
