## ADDED Requirements

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
