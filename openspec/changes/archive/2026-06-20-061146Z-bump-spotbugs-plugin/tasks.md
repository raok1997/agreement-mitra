> **Test-task exemption:** This is a build/config + docs change (Gradle dependency
> locking, OSV suppression baseline, CLAUDE.md) with **no runnable application
> behavior** — no Java source, no API, no module logic. Per the project's tasks rule,
> the unit + integration test requirement does not apply. Verification is the
> `./gradlew check` gate itself (incl. `securityScan` → `osvScan`) passing green with
> zero suppressions, plus a negative-path spot-check that the gate still bites
> (Section 4).

## 1. Narrow dependency-locking scope (Lever B)

- [x] 1.1 Confirm the per-config in/out decision against `./gradlew dependencies`
      (Gradle 8.12). **In-scope (lock + scan)** = the 5 classpath configs:
      `compileClasspath`, `runtimeClasspath`, `productionRuntimeClasspath`,
      `testCompileClasspath`, `testRuntimeClasspath`. **Excluded (build/dev/tool)** =
      `spotbugs`, `spotbugsPlugins`, `jacocoAnt`, `jacocoAgent`, `developmentOnly`,
      `testAndDevelopmentOnly`, `annotationProcessor`, `testAnnotationProcessor`.
- [x] 1.2 In `backend/build.gradle.kts`, replace
      `dependencyLocking { lockAllConfigurations() }` with **per-configuration**
      activation of only the 5 in-scope configs via lazy lookups
      (`configurations.named("<name>") { resolutionStrategy.activateDependencyLocking() }`),
      applied after the `plugins {}` block so lazily-created configs
      (`productionRuntimeClasspath`, test classpaths) exist. Do NOT use eager
      `getByName` at a point that risks "configuration not found".

## 2. Regenerate the lockfile

- [x] 2.1 Run `./gradlew dependencies --write-locks` (from `backend/`) to regenerate
      `backend/gradle.lockfile` under the narrowed scope.
- [x] 2.2 Verify the regenerated lockfile: (a) contains all **5** in-scope configs'
      graphs at expected versions (shipping-dep reproducibility preserved); (b) has
      **no** `=spotbugs`/`=spotbugsPlugins`/`=jacoco*`/`=developmentOnly`-tagged
      entries; (c) the shared `commons-lang3` entry is rewritten to
      `…:3.18.0=testCompileClasspath,testRuntimeClasspath` (version moved AND
      `spotbugs` tag gone); (d) the `empty=` marker line is regenerated for in-scope
      configs that resolve empty. Expect the file to **shrink by ~25 lines** (the
      tool-only entries leaving) — this is expected, not data loss.
- [x] 2.3 Verify `org.apache.commons:commons-lang3` resolves to **3.18.0** on the
      test classpath. If it still pins 3.17.0, add a **test-scope-only** constraint
      (`constraints { testImplementation("org.apache.commons:commons-lang3:3.18.0") }`)
      so the shipped graph is untouched, then re-run `--write-locks`.
- [x] 2.4 Confirm shipped `log4j-api` (on `compile/production/runtime`) stays locked +
      scanned and that no *shipped* log4j advisory was swept up by the descope (only
      tool-only `log4j-core` leaves).

## 3. Empty the suppression baseline + document policy

- [x] 3.1 Delete all 5 `[[IgnoredVulns]]` entries from
      `backend/config/osv-scanner.toml`, leaving an empty baseline.
- [x] 3.2 Rewrite the `osv-scanner.toml` header comment: keep the suppression-workflow
      guidance and state the scope policy (gate covers the 5 shipping+test configs;
      build/dev/tool classpaths excluded by design) — and **name what is now
      unscanned** (the SpotBugs/JaCoCo tool classpaths, which today carry open
      `log4j-core`/`commons-lang3` advisories) so a future scope-widener isn't
      surprised by re-appearing findings.
- [x] 3.3 Add the scope policy to `CLAUDE.md` (Testing & Scanning section): the backend
      dependency gate covers the 5 shipping+test configs; build/dev/tool classpaths
      are excluded by design (they ship to no one, run only on the build host) — plus
      a **revisit-before-production** note (tie to the file's identity/legal-infra
      posture; compensating control = periodic full-lockfile `osv-scanner` audit or an
      eventual SpotBugs-plugin bump).

## 4. Verify the gate

- [x] 4.1 Run `./gradlew securityScan` (from `backend/`) — `osvScan` passes with the
      empty `osv-scanner.toml` baseline (no unsuppressed findings, no suppressions
      needed).
- [x] 4.2 Run a full `./gradlew check` (via `./run-tests.sh` for the Testcontainers
      env) — green end to end, confirming the narrowed locking did not drop any
      required configuration.
- [x] 4.3 Negative-path spot-check: temporarily introduce/downgrade an in-scope
      (shipping or test) dependency to a known-vulnerable version, confirm `osvScan`
      goes **red** (the gate still bites on in-scope findings), then revert. Record
      the result as a one-line acceptance note.
- [x] 4.4 Confirm the SpotBugs plugin is unchanged at `6.0.26` (non-goal guardrail:
      no Lever A bump).
