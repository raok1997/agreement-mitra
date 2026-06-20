## Why

The CR-8 dependency bump left **5 OSV suppressions** in `backend/config/osv-scanner.toml`
with a hard expiry of **2026-09-20** — after which `./gradlew check` breaks. All 5 are
build-tool-classpath findings: the SpotBugs Gradle plugin's transitive logging libs
(`log4j-core` ×4, `commons-lang3` ×1). These libraries execute only on the build machine
against our own bytecode; they ship to no one and are not a product risk surface. Gating
on them produces findings that can never be exploited in the product — noise that erodes
the gate's credibility and forces a recurring "bump the plugin" treadmill. We fix the
root cause instead: stop locking and scanning what we don't ship, and correct a stale
lock entry that was misclassifying a real test-classpath dependency.

## What Changes

- **Narrow `dependencyLocking`** in `backend/build.gradle.kts` from
  `lockAllConfigurations()` to lock only the **5 shipping + test** configurations
  (`compileClasspath`, `runtimeClasspath`, `productionRuntimeClasspath`,
  `testCompileClasspath`, `testRuntimeClasspath`). The other 8 build/dev/tool configs —
  `spotbugs`, `spotbugsPlugins`, `jacocoAnt`, `jacocoAgent`, `developmentOnly`,
  `testAndDevelopmentOnly`, and the annotation-processor configs — are no longer written
  to `gradle.lockfile`, and therefore no longer seen by the OSV scan, which reads that
  lockfile. This retires the tool-classpath false-positive class **permanently**. (Expect
  the lockfile to shrink by ~25 tool-only lines; shipped `log4j-api` stays locked +
  scanned — only tool-only `log4j-core` leaves. See design.md for the per-config table.)
- **Rewrite `backend/gradle.lockfile`** (`./gradlew dependencies --write-locks`). With the
  narrowed scope the tool entries drop out, and the **test-classpath `commons-lang3`** moves
  from the stale-locked `3.17.0` to `3.18.0` — `commons-compress:1.28.0` already requests
  `3.18.0`; the old lockfile was the only thing pinning it down. (The current suppression's
  reason falsely claims `commons-lang3` is "not in test runtime" — the lockfile shows it
  *was* on `testCompile/testRuntimeClasspath`.)
- **Delete all 5 `[[IgnoredVulns]]` entries** from `backend/config/osv-scanner.toml`,
  leaving an empty baseline, and rewrite the file header to state the new scope policy.
- **Document the scope policy** in `CLAUDE.md`: the backend dependency gate covers
  **shipping + test** configurations; **build-tool classpaths are excluded by design**
  (they ship to no one and run only on the build host). This makes the narrowing a
  deliberate, reviewable policy — not an accidental blind spot.
- **Explicit non-goal:** do NOT bump the SpotBugs Gradle plugin (the rejected "Lever A").
  It stays at `6.0.26`. FindSecBugs/SpotBugs SAST behavior is unchanged.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `backend-security-scanning`: the dependency-vulnerability scan gate gains an explicit
  **scope** requirement — the locked/scanned graph covers shipping + test configurations
  and excludes build-tool classpaths by design. (The existing fail-on-any, fail-closed,
  suppression, and "remediated not renewed" requirements are unchanged.)

## Impact

- **Build config:** `backend/build.gradle.kts` (`dependencyLocking` block),
  `backend/gradle.lockfile` (regenerated, tool entries removed, `commons-lang3` → 3.18.0
  on test).
- **Suppression baseline:** `backend/config/osv-scanner.toml` — 5 entries removed → 0;
  header rewritten with the scope policy.
- **Docs:** `CLAUDE.md` Testing & Scanning section.
- **No application/runtime change:** no Java source, no module boundary, no signing/eSign,
  no API surface touched. The shipped artifact and its runtime dependency graph are
  unchanged (locking the same shipping configs at the same versions).
- **Verification:** `./gradlew check` (incl. `securityScan` → `osvScan`) passes green with
  **zero suppressions** in `osv-scanner.toml`.

## PII / Security Review Checklist

- **Aadhaar / OTP / VID / signer PII introduced or moved?** None. This is a build/CI
  configuration change with no runtime data path.
- **Outbound PII flow?** None.
- **Secrets touched?** None — no credentials, keys, or env wiring involved.
- **Signing-status FSM transitions touched?** None.
- **Sandbox + dummy data only preserved?** Yes — no data handling changes at all.
- **Net security posture:** the *product* dependency gate is unchanged (same shipping +
  test configs scanned, fail-on-any, fail-closed). What is removed from scanning is
  build-tool-only classpaths that are never deployed — a scope correction, not a relaxation
  of coverage over shipped code.
