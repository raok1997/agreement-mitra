## Context

The backend gained a dependency-vuln scan gate in CR-4 (`backend-security-scanning`):
OSV-Scanner over the locked Gradle graph, wired into `./gradlew check`,
fail-on-any-unsuppressed-finding, fail-closed on a missing binary, with a
checked-in `osv-scanner.toml` suppression baseline (reason + `ignoreUntil`).
The frontend has **no equivalent**, leaving CLAUDE.md's "backend + frontend"
dependency-scan promise half-kept.

Frontend toolchain today: npm + `package-lock.json` (v3 lockfile), Vite/Vue/TS,
Vitest for tests, ESLint for lint. No CI exists yet (CR-7). Current graph is
clean — `npm audit` reports 0 vulnerabilities.

Scanner choice and threshold were settled with the user before drafting:
**OSV-Scanner** (not `npm audit`) and **fail-on-any** (not a severity cutoff), so
the frontend gate is behaviourally identical to the backend's.

## Goals / Non-Goals

**Goals:**
- A runnable frontend gate that scans `package-lock.json` for known CVEs.
- Behavioural parity with the backend gate: fail-on-any-unsuppressed,
  fail-closed on missing binary, suppress only via reason + expiry.
- One scanner/mental-model across both stacks (reuse the `osv-scanner` binary).
- A checked-in suppression baseline (`frontend/osv-scanner.toml`), empty today.

**Non-Goals:**
- Always-on CI enforcement — that is CR-7 (`ci-pipeline`). This CR delivers a
  local runnable gate only, exactly as the backend gate started.
- A coverage/SAST equivalent for the frontend (no frontend SAST in scope).
- Scanning anything beyond the npm lockfile (no source SAST, no license scan).
- Changing any application code under `src/`.

## Decisions

**1. OSV-Scanner over `npm audit`.**
`npm audit` is native and has severity tiers, but: (a) it diverges from the
backend's tooling, doubling the mental model; (b) its suppression story is weak —
no first-class ignore-with-expiry, only `overrides`/wrapper hacks; (c) it is
npm-registry-advisory-only. OSV-Scanner reads `package-lock.json` natively, shares
the backend's `osv-scanner.toml` `[[IgnoredVulns]]` (reason + `ignoreUntil`)
model, and the binary is already a documented prerequisite. Trade-off accepted:
OSV has no CVSS threshold, so the gate is fail-on-any — which is what we want for
legal/identity infra and matches the backend.

**2. Fail-on-any-unsuppressed, fail-closed.**
Identical to the backend. Any finding not covered by an unexpired checked-in
suppression fails the scan with a non-zero exit. A missing `osv-scanner` binary
**fails** (with an install hint), it does not skip — the gate is fail-closed, not
skip-on-absence.

**3. Home = an npm script (`security:scan`) backed by a Node shim, run from
`frontend/`.** The script invokes a small **`scripts/security-scan.mjs`** (Node,
not a shell one-liner) that runs OSV-Scanner against `package-lock.json` with the
checked-in config. We do **not** wire it into `npm run build` or a pre-commit hook
in this CR — the backend gate is a runnable task (`./gradlew securityScan`) that
CR-7 will promote to CI, and the frontend mirrors that. Wiring it into `build`
now would be a divergence and would slow every local build before CI exists to
make it consistent.
- *Exact invocation (pinned):* the shim runs, from the `frontend/` directory,
  `osv-scanner scan --config=osv-scanner.toml --lockfile=package-lock.json` —
  the same explicit `scan --config= --lockfile=` form the backend uses (so config
  precedence is deterministic, not left to adjacent-file auto-discovery). OSV-Scanner
  parses `package-lock.json` natively (v3 lockfile = fully flattened transitive
  tree, so transitive CVEs are covered).
- *Fail-closed mechanism (cross-platform):* a **shell one-liner guard would not
  fire under `cmd.exe`** on Windows, breaking the fail-closed-with-message
  contract. So the shim — portable across the platforms the declared
  `engines.node >=20.19.0` supports — first checks `osv-scanner` is resolvable on
  PATH; if not, it prints the actionable `brew install osv-scanner` (or
  installation-docs) hint and exits non-zero. OSV-Scanner then exits non-zero
  naturally on any unsuppressed finding; the shim propagates that exit code.

**4. Empty suppression baseline with a frontend-specific header.** Graph is clean
today, so `frontend/osv-scanner.toml` ships with **zero `[[IgnoredVulns]]`
entries**. Its header comment is written **fresh for the frontend** — it states
only the npm-relevant policy (every ignore needs a `reason` + `ignoreUntil`
expiry; no permanent/wildcard entries; remediate-don't-renew) and does **not**
copy the backend's Gradle/SpotBugs/log4j scan-scope remediation history, which is
meaningless here. First real finding gets a justified, time-boxed entry.

**5. Config lives at `frontend/osv-scanner.toml`.** Keeps frontend tooling
self-contained under `frontend/` (where `package.json` runs), parallel to the
backend's `backend/config/osv-scanner.toml`. (Frontend has no `config/`
convention; root-of-frontend is the natural spot next to `package-lock.json`.)

**6. Scan the WHOLE lockfile (devDependencies + production), deliberately
diverging from the backend's exclude-build-tooling scope.** The backend (CR-9)
excludes build-tool classpaths (SpotBugs/JaCoCo) because they "ship to no one and
run only on the build host." The frontend's literal analog is devDependencies
(Vite, ESLint, Vitest, vue-tsc) — and this graph is ~95% devDeps, with only `vue`
shipping to the browser, so mirroring the backend would leave the gate covering
almost nothing. We scan everything instead, because **npm devDependencies are the
#1 supply-chain risk surface** (lifecycle install scripts, typosquatting) and they
execute on developer/build hosts. This is the strictest reading and aligns with
the fail-on-any choice and the legal/identity-infra posture. Trade-off: more noise
from dev-tool transitives, absorbed by the same time-boxed suppression workflow
the backend uses. (User-confirmed at review; recorded here because it knowingly
departs from a documented backend decision.)

## Risks / Trade-offs

- **[Fail-on-any is noisy in a fast-moving npm graph]** → npm transitive churn can
  surface advisories often. Mitigation: the suppression workflow (reason + short
  `ignoreUntil`) is the pressure valve; same discipline already in use on the
  backend. For legal/identity infra, erring strict is the right default.
- **[Local-only gate = weak enforcement until CR-7]** → a developer can skip
  `npm run security:scan`. Mitigation: documented, and CR-7 promotes it to CI
  (the backend gate carries the same caveat). Not solved here by design.
- **[External binary dependency]** → frontend devs who never touch the backend now
  also need `osv-scanner` installed. Mitigation: the fail-closed message names the
  install command; it's the same binary the repo already requires.
- **[Lockfile staleness is a real gate-evasion path, accepted eyes-open]** → the
  scan only sees what's in `package-lock.json`; if it is out of sync with
  `package.json` or the installed tree, the gate can pass on deps that aren't what
  actually ships. The backend couples to a build-regenerated locked graph; this
  frontend gate asserts no freshness. We accept this **explicitly** (not as an
  afterthought): it is a local-only gate today, and lockfile hygiene (`npm ci`
  discipline) is owned elsewhere. CR-7 (CI) is where a freshness check
  (`npm ci`/lockfile-vs-manifest consistency) naturally belongs.
- **[No build coupling = a clean run is not proof the artifact was scanned]** →
  unlike the backend gate (coupled to `check`), `security:scan` is decoupled from
  `npm run build` by design, so a green local run says only "the lockfile was
  scanned at that moment," not "this build was gated." Mitigation: documented;
  CR-7 makes the scan an always-on CI step.

## Migration Plan

Additive only — new config file + new npm script + a doc paragraph. No rollback
complexity: removing the script and toml reverts cleanly. No runtime/app impact.

## Open Questions

None — scanner (OSV-Scanner), threshold (fail-on-any), fail-closed mechanism
(Node shim), exact invocation, and scan scope (whole lockfile) are all decided
above.
