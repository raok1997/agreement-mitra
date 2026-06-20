# Test exemption: pure build-tooling/config change — adds Gradle scanner gates + suppression files + docs, no runnable app behavior — so per dev-policy it is exempt from the unit+integration test-task requirement. Verification is by running the gates themselves (see Group 4).

## 1. Dependency-scan input (Gradle dependency locking)

- [x] 1.1 Enable Gradle dependency locking so the scan runs against a pinned, reproducible graph; commit the generated `gradle.lockfile`
- [x] 1.2 Pin the OSV-Scanner version to use and document it (the binary joins the trusted build base)

## 2. Wire the dependency-vulnerability gate (OSV-Scanner)

- [x] 2.1 Add a Gradle task that runs OSV-Scanner over the locked dependency graph
- [x] 2.2 Configure the fail decision: report ALL findings, fail on ANY unsuppressed finding (OSV-Scanner has no CVSS threshold); the checked-in `osv-scanner.toml` ignore list is the risk-acceptance log
- [x] 2.3 Make the task FAIL-CLOSED when the OSV-Scanner binary is absent — fail with an actionable "install OSV-Scanner" message, do NOT skip
- [x] 2.4 Add a checked-in OSV ignore/suppression file under `backend/config/`, each entry carrying a reason AND an expiry date

## 3. Wire the SAST gate (SpotBugs + FindSecBugs)

- [x] 3.1 Add the SpotBugs Gradle plugin + FindSecBugs on `spotbugsPlugins` to `backend/build.gradle.kts`
- [x] 3.2 Pin SpotBugs/FindSecBugs versions (candidates: SpotBugs plugin 6.x, FindSecBugs 1.13.0) and VERIFY they analyze the Java 21 / Spring Boot 3 bytecode without error (retire risk R1 early)
- [x] 3.3 Configure an include filter that fails the build ONLY on FindSecBugs security-category findings at high confidence (not a blanket reportLevel); report all findings; emit a report
- [x] 3.4 Add a checked-in SpotBugs exclude/suppression filter under `backend/config/`, each entry with a reason AND expiry date

## 4. Aggregate task, task graph, verify gates

- [x] 4.1 Register a `securityScan` aggregate task depending on the OSV scan and the SpotBugs task; ensure SpotBugs depends on `compileJava` and the OSV scan on the resolved/locked graph
- [x] 4.2 Wire `securityScan` into `check` (route the SpotBugs plugin's default check-attached tasks through `securityScan` rather than relying on the default attachment)
- [x] 4.3 Run `./gradlew check` and confirm both gates run and pass on the current suppressed-baseline tree
- [x] 4.4 Verify the fail path for BOTH gates (e.g. temporarily remove a suppression / add a known-vulnerable dep; add a known-bad SAST fixture) to confirm a real finding fails the build, then revert
- [x] 4.5 Confirm `securityScan` run directly also runs both gates against compiled classes + locked graph

## 5. Document

- [x] 5.1 Update CLAUDE.md Scanning section: how to run (now part of `check`), how to install/pin OSV-Scanner, the fail-on-any-unsuppressed (deps) / high-confidence-security (SAST) thresholds, and how to add a justified, time-boxed suppression
- [x] 5.2 Note in CLAUDE.md the explicit limitation: stock FindSecBugs does NOT catch never-log-Aadhaar / verify-HMAC — those need custom rules / a PII-lint (candidate future CR); and that frontend dep scanning is CR-6, CI promotion is CR-7
