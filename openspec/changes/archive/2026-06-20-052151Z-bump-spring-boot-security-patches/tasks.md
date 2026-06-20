# Test-task exemption: pure build/dependency change — bumps versions, regenerates
# the lockfile, prunes the OSV suppression baseline. Adds NO runnable backend
# behavior, so the unit+integration test-task rule does not apply. Verification is
# the existing full `./gradlew check` (tests + ModularityTests + JaCoCo +
# securityScan) run as a regression gate against the bumped graph.
#
# APPLY-TIME PIVOT (2026-06-20): the patch-only 3.4.7 target was abandoned — the
# re-scan showed 3.4.x cannot clear the spring-boot self-CVEs (no 3.4.x fix) or
# the Tomcat 9.8 RCEs (need 10.1.55, only on 3.5.x). Target moved (user-agreed) to
# Spring Boot 3.5.15 + Spring Modulith 1.4.12. Result: 52 → 5 findings, 0 Crit/0 High.

## 1. Bump dependency versions

- [x] 1.1 In `backend/build.gradle.kts`, bump the `org.springframework.boot`
      plugin `3.4.2 → 3.5.15` (moves the Boot-managed BOM with it).
- [x] 1.2 Bump Spring Modulith `1.3.4 → 1.4.12` (`springModulithVersion` extra) —
      the 1.4.x line pairs with Spring Boot 3.5.x.
- [x] 1.3 Replace the directly-pinned `io.minio:minio:8.5.14` with an explicit
      `8.6.0` (the `GHSA-h7rh-xfpj-hpcm` fix; landed in 8.6.0, not the 8.5.x line).
- [x] 1.4 Add a test-scope `org.bouncycastle:bcprov-jdk18on:1.84` pin (minio 8.6.0
      pulls vulnerable 1.81 — `GHSA-c3fc-8qff-9hwx`; remediated by upgrade).

## 2. Re-lock and re-scan

- [x] 2.1 Regenerate the lockfile: `./gradlew dependencies --write-locks`.
- [x] 2.2 Confirm in `gradle.lockfile` the suppressed transitives moved off their
      vulnerable versions: spring-boot 3.5.15, tomcat-embed-core 10.1.55,
      spring-* 6.2.19, logback 1.5.34, jackson 2.21.4, json-smart, postgresql,
      assertj, minio 8.6.0, bcprov 1.84.
- [x] 2.3 Run `osv-scanner`/`osvScan` against the fresh lockfile and capture the
      residual: 5 findings, all Medium, all in the `spotbugs` tool configuration.

## 3. Prune the suppression baseline

- [x] 3.1 In `backend/config/osv-scanner.toml`, delete all 52 CR-4
      `[[IgnoredVulns]]` entries (each cross-checked: its GAV/version actually
      moved in the lockfile — cleared-because-fixed).
- [x] 3.2 Rewrite the 5 residual entries (log4j-core ×4 + commons-lang3) with a
      fresh `reason` (SpotBugs-tool-classpath only, not in app/production/test
      runtime; fixable only via a SpotBugs-plugin upgrade) and a NEW short
      `ignoreUntil = 2026-09-20`.
- [x] 3.3 Replace the CR-4 baseline header comment with the post-bump narrative
      (52 remediated by the 3.5.15 bump; 5 tool-classpath residual; record in the
      archived CR).

## 4. Verify (regression gate)

- [x] 4.1 `./run-tests.sh check` with Docker up → BUILD SUCCESSFUL: unit +
      Testcontainers integration tests, `ModularityTests`, JaCoCo, and
      `securityScan` (OSV "No issues found" + SpotBugs/FindSecBugs) all green.
- [x] 4.2 `osvScan` exits 0 with no unsuppressed finding; `osv-scanner.toml` is
      52 → 5 entries, each remaining one a Medium tool-classpath finding with
      fresh justification + short expiry (per "remediated, not renewed").

## 5. Follow-up

- [ ] 5.1 (separate CR `bump-spotbugs-plugin`) Upgrade the SpotBugs Gradle plugin
      so its tool-classpath log4j-core/commons-lang3 transitives move to fixed
      versions, clearing the 5 residual suppressions. Due before 2026-09-20.
