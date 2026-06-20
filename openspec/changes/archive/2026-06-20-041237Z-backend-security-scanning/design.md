## Context

CLAUDE.md commits to two required build gates — a dependency-vulnerability scan
and SAST over the backend — that aren't implemented. The backend is
Gradle-built (Kotlin DSL, [build.gradle.kts](backend/build.gradle.kts)) on Java
21 / Spring Boot 3.4, currently stub-heavy. There is **no CI** yet (no
`.github/workflows`), so for now every gate runs only via local `./gradlew`.

This CR implements the **backend** half. Frontend dependency scanning (CR-6) and
CI wiring (CR-7) are deliberately separate follow-ups.

## Goals / Non-Goals

**Goals:**
- A dependency-vulnerability gate (OSV-Scanner) failing on CVSS ≥ 7.0 findings,
  reporting all findings, and failing closed when the scanner is absent.
- A SAST gate (SpotBugs + FindSecBugs) failing on high-confidence,
  security-category findings only.
- A `securityScan` aggregate task wired into `check`, with a correct task graph.
- Checked-in, justified, **time-boxed** suppression/baseline files.
- Documented run + suppress workflow in CLAUDE.md.

**Non-Goals:**
- Frontend dependency scanning (CR-6).
- CI/automation (CR-7) — this CR makes the gate real locally; CR-7 promotes it.
- Ratcheting thresholds below CVSS 7.0 or to medium-confidence SAST (later).
- Container/IaC/secret scanning.
- Custom rules to enforce the never-log-Aadhaar / verify-HMAC invariants — stock
  FindSecBugs will NOT catch those (see R6); a PII-lint is a possible later CR.
- A CycloneDX SBOM artifact — low-cost and OSV-friendly, noted for CR-7 rather
  than added here to keep this CR's slice tight.

## Decisions

### D1 — Dependency scanner: OSV-Scanner (resolved at review)

Chosen over OWASP Dependency-Check. Rationale: OSV-Scanner needs **no NVD API
key** and **no warm multi-hundred-MB cache**, which fits this CI-less,
run-it-locally world far better; OWASP Dependency-Check now requires
`NVD_API_KEY` and a slow-to-warm cache, painful without CI caching. We can
revisit Dependency-Check once CR-7 gives us a cached CI home.

Wiring: OSV-Scanner is an external binary invoked from a Gradle task over the
resolved dependency list. To give it a stable input, **enable Gradle dependency
locking** (`gradle.lockfile`) so the scan runs against a pinned, reproducible
graph rather than ad-hoc resolution.

**Fail decision — fail-on-any, not CVSS threshold (changed during apply).**
OSV-Scanner 2.4.0 has **no native CVSS-severity threshold** (verified against the
installed binary's `scan --help`): it reports all findings and exits non-zero on
*any* vulnerability, with the only gating lever being `osv-scanner.toml`
`[[IgnoredVulns]]` entries (`id` + `reason` + `ignoreUntil` expiry). Rather than
post-process JSON and re-implement CVSS-vector→score math (brittle; OSV stores
vectors, not numeric scores), we adopt OSV-Scanner's idiomatic model: **fail on
any unsuppressed finding**, and use the checked-in `osv-scanner.toml` as the
risk-acceptance log (reason + expiry per accepted finding). This is stricter than
a CVSS≥7.0 cutoff and folds the severity decision into a human-reviewed,
time-boxed suppression list — which also satisfies D4 with the tool's own
mechanism. (Alternative C, OWASP Dependency-Check with `failBuildOnCVSS = 7.0`,
was reconsidered and rejected to preserve the no-NVD-key/no-cache rationale.)

**Fail-closed (from review):** if the OSV-Scanner binary is missing, the task
**fails** with an install message — it does not skip. This is a security gate,
not a test; "no scanner = green build" is unacceptable. (Contrast the
Testcontainers skip-without-Docker pattern, which is fine for tests but wrong
for a control.) Pin the OSV-Scanner version in docs and verify the binary
(checksum / official source) since it joins the trusted build base.

### D2 — SAST: SpotBugs + FindSecBugs, security-category + high-confidence only

Use the `com.github.spotbugs` Gradle plugin with FindSecBugs on its
`spotbugsPlugins` configuration. **The fail decision is not a single knob:**
`reportLevel`/confidence spans all SpotBugs categories. To fail on
*security-category, high-confidence* findings only (review Major #3), use a
SpotBugs **include filter** that selects the FindSecBugs security bug patterns
and set confidence to high, so style/performance bugs don't fail the build. All
findings are still reported.

Candidate version pins to verify (review Minor #3, retires risk R1 at apply):
SpotBugs Gradle plugin 6.x, FindSecBugs 1.13.0, SpotBugs core compatible with
Java 21. **Verification task in implementation:** confirm these analyze Java 21
/ Spring Boot 3 bytecode without erroring (FindSecBugs releases historically lag
the JDK). Alternative considered: PMD/SonarQube — heavier (Sonar wants a server),
weaker security ruleset; rejected.

### D3 — `securityScan` aggregate task, wired into `check` (changed at review)

Originally proposed as opt-in (not in `check`). Reviewers unanimously flagged
that an opt-in task with no CI is near-zero enforcement — a gate that runs only
on developer memory is documentation, not a control. **Decision (user
confirmed): wire `securityScan` into `check` now.** The codebase is stub-heavy
and scans are fast, the baseline absorbs day-one noise, and CR-7 later just
promotes the same task into CI. Cost accepted: slightly slower local `check`.

**Task graph (review Major #5):** SpotBugs analyzes bytecode, so its task depends
on `compileJava`; the dependency scan depends on the resolved/locked dependency
graph. `securityScan` depends on both; `check` depends on `securityScan`. The
SpotBugs plugin attaches its own tasks to `check` by default — we route them
through `securityScan` deliberately rather than relying on that default, keeping
the graph explicit.

### D4 — Suppressions: checked-in, per-scanner, justified, time-boxed

Keep suppression/baseline files under `backend/config/` (declared here as the
home for build-tooling config so future CRs follow suit) — an OSV ignore file
for dependencies and a SpotBugs exclude filter for SAST. Each entry carries a
**reason and an expiry date** (review Minor #2): reason-only suppressions can rot
into permanent mutes, and with no CI no one is forced to review the diff, so
expiry forces periodic re-justification. Wildcard/open-ended entries are
out-of-policy. Rationale: stub code + transitive CVEs produce day-one findings;
the baseline absorbs them explicitly (reviewable in diff) so the gate starts
green and red-lines only *new* issues.

## Risks / Trade-offs

- **R1 — FindSecBugs may lag Java 21.** → Pin verified versions (D2); if it can't
  analyze cleanly, halt and surface options rather than ship a broken gate.
- **R2 — Slower local `check`.** → Accepted; scans are fast on a small codebase
  and real enforcement is worth it. CR-7 moves the cost to CI.
- **R3 — Day-one findings could block immediately.** → Time-boxed
  suppression/baseline (D4) starts the gate green, red-lining only new issues.
- **R4 — OSV-Scanner is an external binary.** → Fail-closed with an actionable
  install message (D1); pin + verify the binary; document install in CLAUDE.md.
- **R5 — Suppression files can rot into blanket mutes.** → Reason **and** expiry
  per entry; wildcards out-of-policy; reviewable in diffs (D4).
- **R6 — Stock SAST does NOT cover this codebase's actual top risks**
  (never-log-Aadhaar, verify-HMAC). → Stated as a Non-Goal so no one over-trusts
  the gate; a PII-lint / custom rules are a candidate future CR.

## Open Questions

- None blocking. Dependency scanner (OSV-Scanner) and enforcement posture (wired
  into `check`, fail-closed) resolved at review.
