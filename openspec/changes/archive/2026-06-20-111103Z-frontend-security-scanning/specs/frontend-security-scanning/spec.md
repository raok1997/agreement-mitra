## ADDED Requirements

### Requirement: Frontend dependency-vulnerability scan gate

The frontend build tooling SHALL scan the frontend's locked npm dependency graph
(`frontend/package-lock.json`) for known vulnerabilities using **OSV-Scanner** —
the same scanner the backend gate uses, for one tool and one mental model across
both stacks. The scanned graph SHALL cover the **entire lockfile — both
`dependencies` and `devDependencies`, including transitive dependencies** (npm
devDependencies are a primary supply-chain risk surface and execute on
developer/build hosts; this deliberately differs from the backend gate, which
excludes build-tool classpaths). The scan SHALL **report all findings** and SHALL
**fail on any unsuppressed finding**. (OSV-Scanner has no native CVSS-severity
threshold; its idiomatic model is fail-on-any plus curated per-ID ignores, which
is stricter than a CVSS cutoff and matches the backend gate.) A finding is
accepted (and excluded from the fail decision) only by an explicit, checked-in
suppression carrying a reason and an expiry date. The gate SHALL be invocable as a
frontend tooling command (an npm script).

#### Scenario: Unsuppressed vulnerability fails the scan
- **WHEN** the scan runs and a dependency in `package-lock.json` has a
  vulnerability not covered by a suppression
- **THEN** the scan exits non-zero and names the offending package and advisory

#### Scenario: Transitive and dev dependencies are in scope
- **WHEN** a vulnerability exists in a transitive dependency or in a
  `devDependency` (not a direct production dependency)
- **THEN** the scan still reports it and fails on it (the whole lockfile is in
  scope, not just direct production deps)

#### Scenario: Clean graph passes
- **WHEN** the scan runs and finds no vulnerabilities
- **THEN** the scan succeeds with a zero exit

#### Scenario: Suppressed finding does not fail
- **WHEN** the only findings are ones listed in the checked-in suppression file
  with a reason and an unexpired date
- **THEN** the findings are still reported AND the scan succeeds

### Requirement: Frontend scan is fail-closed

The gate SHALL be fail-closed: if the OSV-Scanner binary is unavailable on the
build host, the scan SHALL fail with an actionable message rather than silently
passing or skipping. A missing scanner is treated as an unmet gate, not an absent
one.

#### Scenario: Missing scanner fails closed
- **WHEN** the scan command runs but the `osv-scanner` binary is not on the build
  host
- **THEN** the command exits non-zero with a message telling the developer to
  install OSV-Scanner — it does NOT silently pass

### Requirement: Frontend scan suppressions are justified and time-boxed

Accepted vulnerabilities SHALL be recorded only in a checked-in OSV-Scanner
configuration file (`frontend/osv-scanner.toml`). Each suppression SHALL carry a
human-readable **reason** and an **expiry** (`ignoreUntil`); suppressions SHALL
NOT be permanent and SHALL NOT use wildcards. The suppression file is the team's
recorded, time-boxed risk-acceptance log. When the dependency graph is clean, the
baseline SHALL be empty (header/policy comment only, zero ignore entries).

#### Scenario: Suppression requires reason and expiry
- **WHEN** a vulnerability is accepted to keep the build green
- **THEN** it is recorded in `frontend/osv-scanner.toml` with a reason and an
  `ignoreUntil` expiry date, never as a permanent or wildcard entry

#### Scenario: Expired suppression stops masking the finding
- **WHEN** a suppression's `ignoreUntil` date has passed and the underlying
  vulnerability is still present
- **THEN** the finding once again fails the scan (the suppression no longer
  excludes it)

#### Scenario: Clean graph ships an empty baseline
- **WHEN** the frontend dependency graph has no known vulnerabilities
- **THEN** `frontend/osv-scanner.toml` contains the policy header comment and no
  `[[IgnoredVulns]]` entries
