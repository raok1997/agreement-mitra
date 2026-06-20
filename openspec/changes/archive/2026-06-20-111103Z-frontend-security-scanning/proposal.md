## Why

CLAUDE.md promises a **backend + frontend** dependency-vulnerability scan, but
only the backend gate exists (CR-4 / `backend-security-scanning`). The frontend's
npm dependency graph (`frontend/package-lock.json`) ships unscanned — a known-CVE
package could land in the SPA with nothing to stop it. This CR closes that gap,
mirroring the backend gate's shape so both stacks enforce the same posture.

## What Changes

- Add a **frontend dependency-vulnerability scan gate** using **OSV-Scanner**
  (the same binary the backend gate already requires) against
  `frontend/package-lock.json`. The scan covers the **whole lockfile — dev +
  production, including transitives** (npm devDependencies are a primary
  supply-chain surface; this deliberately differs from the backend's
  exclude-build-tooling scope).
- Gate is **fail-on-any-unsuppressed-finding** and **fail-closed**: a missing
  `osv-scanner` binary fails the scan with an actionable install message, it does
  not skip.
- Expose it as a runnable **npm script** (`npm run security:scan`) backed by a
  small cross-platform Node shim (`frontend/scripts/security-scan.mjs`) so it has
  a home in the frontend toolchain today; it is **not** coupled to `npm run build`
  (a clean local run is not proof the build was gated). Wiring it into always-on
  CI is deferred to CR-7 (`ci-pipeline`), same as the backend gate.
- Add a checked-in **`frontend/osv-scanner.toml`** suppression baseline. The
  graph is currently clean (`npm audit` → 0 vulnerabilities), so the baseline
  ships **empty**. Accepting any future finding requires an `[[IgnoredVulns]]`
  entry with a `reason` AND an `ignoreUntil` expiry — never permanent, never a
  wildcard (identical policy to the backend).
- Document the frontend gate in CLAUDE.md's Testing & Scanning section.

This is a **build/tooling change** (scanner config + an npm script), not runnable
application behavior — so per the OpenSpec `tasks:` rule it is **exempt from the
unit + integration test requirement**, recorded as a one-line note atop `tasks.md`.
Verification is the gate's own pass/fail behavior (clean graph passes; a seeded
finding fails; missing binary fails closed).

## Capabilities

### New Capabilities
- `frontend-security-scanning`: the frontend's dependency-vulnerability build
  gate — what it scans (`package-lock.json` via OSV-Scanner), what it fails on
  (any unsuppressed finding), its fail-closed posture, and the checked-in,
  justified, time-boxed suppression workflow.

### Modified Capabilities
<!-- None. The backend-security-scanning capability is untouched; this is a new,
     parallel frontend capability. -->

## Impact

- **New files**: `frontend/osv-scanner.toml` (empty suppression baseline),
  `frontend/scripts/security-scan.mjs` (the cross-platform scan shim).
- **Modified**: `frontend/package.json` (`security:scan` script), `CLAUDE.md`
  (Testing & Scanning — frontend gate + "not yet covered" list trimmed).
- **Dependencies**: no new npm deps. Relies on the external `osv-scanner` binary
  already required by the backend gate (`brew install osv-scanner`).
- **No application code touched** — zero changes to `src/`, no signing/eSign
  surface, no module boundaries.
- **CI**: still local-only (`npm run security:scan`); promotion to always-on CI is
  CR-7's job.
