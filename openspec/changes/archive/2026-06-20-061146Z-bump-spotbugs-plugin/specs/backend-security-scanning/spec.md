## MODIFIED Requirements

### Requirement: Backend dependency-vulnerability scan gate

The build SHALL scan the backend's resolved Gradle dependency graph for known
vulnerabilities using **OSV-Scanner**. The scanned graph SHALL cover the
**shipping and test** configurations (the dependencies compiled into, run by, or
linked against the deployed artifact and its tests) and SHALL **exclude
build-tool classpaths** — the configurations that exist only to run build/analysis
plugins on the build host (e.g. the SpotBugs plugin's `spotbugs` /
`spotbugsPlugins` configurations). Build-tool classpaths ship to no one and
execute only on the build machine, so they are out of the product risk surface
and excluded by design. Build-tool/build-host configurations include — but are not
limited to — the SpotBugs plugin's `spotbugs` / `spotbugsPlugins`, the JaCoCo
coverage runner's `jacocoAnt` / `jacocoAgent`, annotation-processor configurations,
and dev-only configurations such as `developmentOnly`. This exclusion is realized by
locking only the in-scope (shipping + test) configurations into the lockfile the
scanner reads, and it SHALL be documented as deliberate policy rather than left
implicit. The scan SHALL **report all
findings** in the scanned scope and SHALL **fail on any unsuppressed finding**. A
finding is accepted (and excluded from the fail decision) only by an explicit,
checked-in suppression carrying a reason and an expiry date — the suppression file
is the team's recorded risk-acceptance log. (OSV-Scanner has no native
CVSS-severity threshold; its idiomatic model is fail-on-any plus curated per-ID
ignores, which is stricter than a CVSS cutoff and maps directly to the
suppression requirement below.)

#### Scenario: Unsuppressed vulnerability fails the scan
- **WHEN** the dependency scan runs and a resolved backend dependency in a
  shipping or test configuration has a vulnerability not covered by a suppression
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

#### Scenario: Build-tool-classpath vulnerability does not fail the scan
- **WHEN** a vulnerability exists only in a build-tool classpath (e.g. a
  transitive of the SpotBugs plugin in the `spotbugs` / `spotbugsPlugins`
  configuration) and not in any shipping or test configuration
- **THEN** that configuration is absent from the lockfile the scanner reads, the
  finding does NOT appear in the scan, and the scan task is not failed by it —
  no suppression entry is required to keep the build green

### Requirement: Dependency-CVE suppression baseline is remediated, not perpetually renewed

A dependency-vulnerability (OSV) suppression for a finding fixable within the project's accepted version policy SHALL be cleared by upgrading the offending dependency before the suppression's expiry, and SHALL NOT be kept alive by extending the expiry date on the same vulnerable version. A suppression for a finding that exists ONLY on a configuration the documented scan scope excludes (a build-tool/build-host classpath, per the scope requirement above) SHALL instead be cleared by removing that configuration from the scanned scope — after which the finding is no longer in the gate's scope and needs no suppression. This requirement governs the dependency-scan suppression file (`osv-scanner.toml`); SAST (SpotBugs/FindSecBugs) suppressions are out of its scope, since those are resolved by code fixes or false-positive review, not dependency upgrades. A suppression MAY remain — re-justified with a fresh reason and a new, **short** expiry — only for an in-scope finding that is either (a) not yet fixed in any release, or (b) fixed only by an upgrade outside the project's accepted version policy (e.g. a higher minor/major the project has deliberately not adopted). The effect is a shrinking risk-acceptance log of genuinely-unremediable in-scope findings, not a permanent mute that hides remediable vulnerabilities behind rolling dates.

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

#### Scenario: A tool-only suppressed finding is cleared by removing it from scan scope
- **WHEN** a checked-in OSV suppression covers a finding that exists only on a
  build-tool/build-host configuration excluded by the documented scan scope
- **THEN** the suppression entry is deleted from `osv-scanner.toml` because the
  configuration is no longer locked into the scanned lockfile — and this
  scope-removal deletion does NOT require the named dependency's version to change
  (the finding simply leaves the gate's scope), and is recorded as a deliberate
  scope decision rather than as an upgrade remediation

#### Scenario: Suppression file shrinks as the baseline is remediated
- **WHEN** a remediation change upgrades dependencies or narrows the documented
  scan scope to clear a pre-existing suppression baseline
- **THEN** `osv-scanner.toml` contains strictly fewer entries than before
  (every cleared finding's entry removed), each deletion corresponds either to the
  named dependency version actually changing in the lockfile OR to the finding's
  configuration leaving the scanned scope, and the remaining entries are each
  still-unremediable in-scope findings with current justification
