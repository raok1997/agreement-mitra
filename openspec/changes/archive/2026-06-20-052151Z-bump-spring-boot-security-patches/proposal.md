## Why

The CR-4 security-scan gate immediately surfaced **52 known CVEs** in the
backend dependency graph — all inherited from the outdated **Spring Boot 3.4.2
BOM** (26 in `tomcat-embed-core` alone, several 9.1–9.8 RCE-class). To land the
gate green they were **time-boxed-suppressed in `config/osv-scanner.toml` with
expiry 2026-07-20**. That date is the deadline: when it passes the OSV gate
fails again and the build breaks until the underlying versions are fixed. The
suppressions were always meant as a short bridge, not a fix — this CR is the
fix. Doing it now (before the expiry) closes real RCE-class vulnerabilities and
keeps the build green by *removing* the findings rather than renewing the mute.

## What Changes

> **Apply-time revision (2026-06-20):** the patch-only target (3.4.7) was
> abandoned after the re-scan proved it can't reach a clean graph — the
> spring-boot self-CVEs have **no 3.4.x fix at all** (only 3.5.14+) and 3.4.13
> still ships Tomcat 10.1.50 with three open 9.8/9.1 RCEs. Per the new
> "remediate, not renew" rule we do not mute fixable RCEs, so (with the user's
> agreement) the target moved to the latest **3.5.x minor**.

- Bump the Spring Boot Gradle plugin **and** managed BOM from **3.4.2 → 3.5.15**
  (latest 3.5.x). This pulls the patched Tomcat (10.1.55), Spring Framework
  (6.2.19), Jackson (2.21.4), logback (1.5.34), json-smart, postgresql, and
  assertj — clearing every Critical/High finding incl. all Tomcat RCEs and the
  spring-boot self-CVEs.
- Bump **Spring Modulith 1.3.4 → 1.4.12** (the 1.4.x line pairs with Spring Boot
  3.5.x).
- Bump the directly-pinned, non-BOM `io.minio:minio 8.5.14 → 8.6.0` (the fixed
  version for `GHSA-h7rh-xfpj-hpcm`; the fix landed in 8.6.0, not the 8.5.x line).
- Add a test-scope `org.bouncycastle:bcprov-jdk18on:1.84` pin (minio 8.6.0 pulls
  vulnerable 1.81 — `GHSA-c3fc-8qff-9hwx`; remediated by upgrade).
- Regenerate the dependency lockfile (`./gradlew dependencies --write-locks`) and
  re-run `osvScan` against the new graph.
- **Prune `config/osv-scanner.toml` from 52 entries to 5.** The 52 CR-4 entries
  are deleted (remediated by the bump). The 5 remaining are all bound to the
  **SpotBugs plugin's own tool classpath** (`spotbugs` configuration: log4j-core
  + commons-lang3), all **Medium**, not in any app/production/test-runtime or
  shipped artifact — re-justified with a fresh reason and a **short** new expiry
  (2026-09-20). Clearing them needs a SpotBugs-plugin upgrade → follow-up CR
  `bump-spotbugs-plugin`.
- No application/runtime-behavior change: no module, API, endpoint, signing-FSM,
  or webhook code is touched — this is a build/dependency-graph change only.

## Capabilities

### New Capabilities
<!-- none -->

### Modified Capabilities
- `backend-security-scanning`: adds one requirement — the **dependency-CVE
  suppression baseline is remediated, not perpetually renewed**: a fixable OSV
  suppression must be cleared by upgrading the offending dependency, not
  rubber-stamped with a later expiry; a suppression stays only for a finding with
  no fix in any release or one fixed only outside the project's accepted version
  policy. This CR is the first instance: it remediates the CR-4 Spring Boot 3.4.2
  baseline (52 → 5, the 5 being the escape-valve case). The gate's existing
  fail-on-any / fail-closed / justified-and-time-boxed requirements are unchanged.

## Impact

- **Build only**: `backend/build.gradle.kts` (Spring Boot 3.5.15 + Modulith
  1.4.12 + minio 8.6.0 + bcprov 1.84 test pin), `backend/gradle.lockfile`
  (regenerated), `backend/config/osv-scanner.toml` (52 → 5 entries). No `src/`
  change.
- **Blast radius**: a minor framework bump (3.4 → 3.5) shifts transitive versions
  and can change defaults/deprecations. Verified by a full `./gradlew check` —
  unit + Testcontainers integration tests, `ModularityTests`, the JaCoCo gate,
  and `securityScan` (OSV + SpotBugs/FindSecBugs) — all green with Docker up.
- **Result**: OSV down from 52 findings to 5, and from multiple 9.8 RCEs to
  **0 Critical / 0 High** (the 5 residual are Medium, SpotBugs-tool-classpath
  only).
- **Risk if deferred**: build breaks at the 2026-07-20 suppression expiry, and
  the RCE-class CVEs stay live in the dependency graph until then.

### PII / security review checklist
- **Introduces or moves Aadhaar / OTP / VID / PII or secrets?** None. This is a
  dependency-version bump and suppression-baseline cleanup; it adds, moves, and
  logs no data of any kind.
- **Redaction / securing of such data:** N/A — no data flow involved.
- **Sandbox + dummy data only preserved?** Yes — no runtime, fixture, or
  credential change; repo stays sandbox/dummy-data only.
- **Net security effect:** strictly positive — clears all 52 baseline CVEs
  (incl. multiple high-severity RCE) by upgrading to patched versions; the 5
  residual are Medium, non-shipping SpotBugs-tool-classpath findings.
