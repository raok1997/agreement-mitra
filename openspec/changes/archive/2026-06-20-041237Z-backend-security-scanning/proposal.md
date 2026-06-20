## Why

CLAUDE.md names two required build gates that don't exist yet: a
dependency-vulnerability scan and SAST over the backend. Today nothing flags a
vulnerable transitive dependency or a security-class defect in our own code —
unacceptable for identity/legal infrastructure handling Aadhaar eSign. This CR
stands up the **backend** half of that scanning posture now, while the codebase
is small, so every later feature inherits the gate.

## What Changes

- Add a **dependency-vulnerability scan** of backend (Gradle) dependencies via
  **OSV-Scanner** (chosen at review — no NVD key, no warm cache). It **reports
  all findings** and **fails on any unsuppressed finding** (OSV-Scanner has no
  native CVSS threshold; its `osv-scanner.toml` ignore list — reason + expiry —
  is the risk-acceptance log). It **fails closed** if the scanner binary is
  missing (no silent pass). Runs against a locked, reproducible dependency graph.
- Add **SAST** over the backend via **SpotBugs + FindSecBugs**, failing only on
  **high-confidence, security-category findings** (via an include filter — not a
  blanket report level). Design must confirm FindSecBugs runs clean on Java 21 /
  Spring Boot 3 bytecode (its releases lag the JDK).
- Both gates hang off a **`securityScan` aggregate Gradle task wired into
  `check`** (changed at review — an opt-in task with no CI is near-zero
  enforcement). The task graph compiles before SAST and resolves the locked
  graph before the dependency scan.
- Ship a **checked-in, justified, time-boxed suppression/baseline file** per
  scanner under `backend/config/`, so day-one transitive CVEs and stub-code
  findings are acknowledged explicitly (with reason + expiry) rather than
  silently blocking or permanently muted.
- Document the task and suppression workflow in CLAUDE.md's Scanning section.

Out of scope (tracked as separate follow-up CRs): **frontend** dependency
scanning (CR-6) and **CI wiring** that runs these gates automatically (CR-7).

## Capabilities

### New Capabilities
- `backend-security-scanning`: the backend dependency-vulnerability scan and
  SAST build gates — what they cover, their fail-on-severity thresholds, the
  `securityScan` task wired into `check`, and the suppression/baseline workflow.

### Modified Capabilities
<!-- None. dev-policy documents the *posture*; this CR implements the backend gate as its own capability without changing dev-policy's requirements. -->

## Impact

- **Build**: `backend/build.gradle.kts` (SpotBugs plugin + `securityScan` task
  wired into `check`); Gradle dependency locking enabled (`gradle.lockfile`);
  new suppression/baseline files under `backend/config/`.
- **Docs**: CLAUDE.md Scanning section (how to run, install OSV-Scanner, suppress).
- **Runtime code**: none — pure build-tooling/config; no app behavior changes.
- **Dependencies**: adds build-time scanner tooling only; no new runtime deps.

## PII/Security review checklist

- **New/moved PII flow (Aadhaar/OTP/VID/PII)?** None — this CR adds build-time
  scanners and config only; it touches no request path, log, or data store.
- **Secrets?** None committed. OSV-Scanner needs no API key (a deliberate part of
  why it was chosen over OWASP Dependency-Check). Suppression files contain CVE
  IDs and reasons, not secrets.
- **Sandbox + dummy data only:** preserved — no runtime or data change.
- **Signing-status FSM:** untouched (no DRAFT→…→SIGNED transition affected).
