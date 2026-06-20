# Backend Security Scanning

## Purpose

The backend's security build gates: a dependency-vulnerability scan
(OSV-Scanner) and SAST (SpotBugs + FindSecBugs), run as part of `./gradlew
check` via a `securityScan` task. Defines what each gate fails on, the
fail-closed posture, and the checked-in, justified, time-boxed suppression
workflow that records accepted risk.

## Requirements

### Requirement: Backend dependency-vulnerability scan gate

The build SHALL scan the backend's resolved Gradle dependency graph (compile +
runtime) for known vulnerabilities using **OSV-Scanner**. The scan SHALL
**report all findings** and SHALL **fail on any unsuppressed finding**. A finding
is accepted (and excluded from the fail decision) only by an explicit,
checked-in suppression carrying a reason and an expiry date — the suppression
file is the team's recorded risk-acceptance log. (OSV-Scanner has no native
CVSS-severity threshold; its idiomatic model is fail-on-any plus curated per-ID
ignores, which is stricter than a CVSS cutoff and maps directly to the
suppression requirement below.)

#### Scenario: Unsuppressed vulnerability fails the scan
- **WHEN** the dependency scan runs and a resolved backend dependency has a
  vulnerability not covered by a suppression
- **THEN** the scan task fails with a non-zero exit and names the offending
  dependency and advisory

#### Scenario: Suppressed finding does not fail
- **WHEN** the only findings are ones listed in the checked-in suppression file
  with a reason and an unexpired date
- **THEN** the findings are still reported AND the scan task succeeds

#### Scenario: Clean graph passes
- **WHEN** the dependency scan runs and finds no vulnerabilities
- **THEN** the scan task succeeds

#### Scenario: Missing scanner fails closed
- **WHEN** the dependency scan task runs but the OSV-Scanner binary is not
  available on the build host
- **THEN** the task fails with an actionable message telling the developer to
  install OSV-Scanner — it does NOT silently pass (the gate is fail-closed, not
  skip-on-absence)

### Requirement: Backend SAST gate

The build SHALL run static application security testing (SAST) over the
backend's own compiled code using SpotBugs with the FindSecBugs plugin. The
fail decision SHALL be scoped to **FindSecBugs security-category findings at
high confidence** (via an include filter that selects the security bug
patterns), so that non-security SpotBugs categories (style, performance) do not
fail the build. All findings SHALL be reported.

#### Scenario: High-confidence security defect fails the scan
- **WHEN** SAST runs and FindSecBugs reports an unsuppressed high-confidence
  security finding in backend code
- **THEN** the SAST task fails with a non-zero exit and identifies the finding
  (class, location, bug pattern)

#### Scenario: Non-security finding does not fail
- **WHEN** SpotBugs reports only non-security findings (e.g. style or
  performance), or security findings below high confidence
- **THEN** they may be reported but the SAST task succeeds

#### Scenario: Clean code passes
- **WHEN** SAST runs and no unsuppressed high-confidence security finding exists
- **THEN** the SAST task succeeds

### Requirement: Security scans run as part of the build check gate

The dependency-vulnerability scan and SAST SHALL be reachable through a
dedicated `securityScan` aggregate Gradle task, and that task SHALL be wired
into the default `check` task so the gates run as part of the standard build.
The task graph SHALL ensure backend code is compiled before SAST analyzes it and
the dependency graph is resolved before the dependency scan runs.

#### Scenario: check runs the gates
- **WHEN** a developer runs `./gradlew check`
- **THEN** both the dependency-vulnerability scan and SAST run and apply their
  fail-on-severity thresholds as part of that build

#### Scenario: Dedicated task also runs the gates
- **WHEN** a developer runs the `securityScan` Gradle task directly
- **THEN** both gates run against compiled classes and the resolved dependency
  graph

### Requirement: Suppressions are explicit, checked in, justified, and time-boxed

The build SHALL support a checked-in suppression/baseline mechanism so a known,
accepted finding (e.g. a transitive CVE with no fix, or a false positive) can be
excluded from the fail decision. Each suppression SHALL carry a human-readable
reason AND an expiry date, and suppressions SHALL be version-controlled so they
are reviewable in diffs.

#### Scenario: Suppressed finding does not fail the build
- **WHEN** a finding that would otherwise fail a scan is listed in the checked-in
  suppression file with a reason and an unexpired date
- **THEN** the scan task succeeds despite that finding

#### Scenario: A new, unsuppressed finding still fails
- **WHEN** a finding appears that is not covered by any suppression entry
- **THEN** the scan task fails (suppression is opt-in per finding, not a blanket
  mute)

#### Scenario: Wildcard / permanent suppression is disallowed by convention
- **WHEN** a suppression entry omits an expiry date or uses an open-ended
  package wildcard
- **THEN** it is treated as out-of-policy in review (the mechanism requires a
  scoped, dated entry), preventing the gate from being permanently neutered

### Requirement: Dependency-CVE suppression baseline is remediated, not perpetually renewed

A dependency-vulnerability (OSV) suppression for a finding fixable within the project's accepted version policy SHALL be cleared by upgrading the offending dependency before the suppression's expiry, and SHALL NOT be kept alive by extending the expiry date on the same vulnerable version. This requirement governs the dependency-scan suppression file (`osv-scanner.toml`); SAST (SpotBugs/FindSecBugs) suppressions are out of its scope, since those are resolved by code fixes or false-positive review, not dependency upgrades. A suppression MAY remain — re-justified with a fresh reason and a new, **short** expiry — only for a finding that is either (a) not yet fixed in any release, or (b) fixed only by an upgrade outside the project's accepted version policy (e.g. a higher minor/major the project has deliberately not adopted). The effect is a shrinking risk-acceptance log of genuinely-unremediable findings, not a permanent mute that hides remediable vulnerabilities behind rolling dates.

#### Scenario: A fixable suppressed finding is remediated by upgrade
- **WHEN** a checked-in OSV suppression covers a finding for which a fixed
  dependency version exists within the project's accepted version policy
- **THEN** the dependency is upgraded so the finding no longer appears in the
  scan AND the suppression entry is deleted from `osv-scanner.toml` — rather
  than the entry's expiry being pushed to a later date on the vulnerable version

#### Scenario: A finding with no policy-compatible fix keeps a short-expiry suppression
- **WHEN** after the upgrade the re-run scan still reports a finding that has no
  fix in any release, or whose only fix requires an upgrade outside the
  project's accepted version policy
- **THEN** that finding MAY keep a suppression entry, rewritten with a fresh
  human-readable reason (stating which case applies) and a new, short expiry
  date — and the situation is called out rather than silently rolled forward

#### Scenario: Suppression file shrinks as the baseline is remediated
- **WHEN** a remediation change upgrades dependencies to clear a pre-existing
  suppression baseline
- **THEN** `osv-scanner.toml` contains strictly fewer entries than before
  (every cleared finding's entry removed), each deletion corresponds to the
  named dependency version actually changing in the lockfile, and the remaining
  entries are each still-unremediable findings with current justification
