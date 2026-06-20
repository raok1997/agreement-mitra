# Test exemption: pure build-tooling/config change — adds an OSV-Scanner npm gate + an empty suppression baseline + docs, with zero runnable app behavior (no `src/` changes) — so per dev-policy it is exempt from the unit+integration test-task requirement. Verification is by running the gate itself (see Group 3).

## 1. Suppression baseline

- [x] 1.1 Add `frontend/osv-scanner.toml` with a **frontend-specific** policy header comment (every ignore needs a `reason` + `ignoreUntil` expiry; no permanent/wildcard entries; remediate-don't-renew) and zero `[[IgnoredVulns]]` entries — the graph is clean today. Do NOT copy the backend's Gradle/SpotBugs/scan-scope remediation history.

## 2. Wire the frontend dependency-vulnerability gate (OSV-Scanner)

- [x] 2.1 Add `frontend/scripts/security-scan.mjs` (a Node shim) and a `security:scan` npm script that runs it. The shim runs, from `frontend/`, the pinned command `osv-scanner scan --config=osv-scanner.toml --lockfile=package-lock.json` (explicit `--config`, the whole dev+prod lockfile per the scope decision)
- [x] 2.2 Confirm the fail decision: report ALL findings, fail (non-zero exit) on ANY unsuppressed finding — OSV-Scanner's native behavior; the toml ignore list is the risk-acceptance log. The shim propagates OSV-Scanner's non-zero exit
- [x] 2.3 Make the shim FAIL-CLOSED and cross-platform: before invoking, check `osv-scanner` is resolvable on PATH; if absent, print an actionable "install OSV-Scanner (`brew install osv-scanner`)" message and exit non-zero. A Node check (not a shell one-liner) so it also fires under Windows `cmd.exe`

## 3. Verify the gate

- [x] 3.1 Clean-graph pass: `npm run security:scan` exits 0 against the current (0-vuln) lockfile
- [x] 3.2 Fail-on-finding (non-destructive): run the scanner against a **throwaway fixture lockfile** (via `--lockfile=<fixture>`) seeding a known-CVE dependency reached **transitively** (proves transitive depth, not just a direct pin); confirm non-zero exit naming the advisory. Do NOT mutate the real `package-lock.json`/`node_modules`. If no transitive fixture is readily constructible, fall back to a direct-pin known-CVE in the fixture rather than skipping this check
- [x] 3.3 Fail-closed: simulate a missing `osv-scanner` (PATH stripped) and confirm the script exits non-zero AND the output contains the install hint (`brew install osv-scanner`), not just a bare failure

## 4. Document

- [x] 4.1 Update CLAUDE.md Testing & Scanning: add the frontend dependency-scan gate (`npm run security:scan`, OSV-Scanner, fail-on-any, fail-closed, `frontend/osv-scanner.toml`) and remove "frontend dependency scanning" from the "Not yet covered" follow-up list (leaving CI as the remaining follow-up)
