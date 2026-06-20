## Context

CR-8 (`bump-spring-boot-security-patches`) remediated 52 OSV CVEs by bumping Spring
Boot 3.4.2 → 3.5.15, leaving **5 residual suppressions** in
`backend/config/osv-scanner.toml`, all expiring **2026-09-20** — after which
`./gradlew check` fails closed.

The 5 are not product vulnerabilities. They live on the **SpotBugs Gradle plugin's
tool classpath**:
- `org.apache.logging.log4j:log4j-core:2.24.3` — 4 advisories, lockfile tag
  `=spotbugs` only (purely tool).
- `org.apache.commons:commons-lang3:3.17.0` — 1 advisory, lockfile tag
  `=spotbugs,testCompileClasspath,testRuntimeClasspath`.

Why they reach the OSV gate at all: `backend/build.gradle.kts:25` calls
`dependencyLocking { lockAllConfigurations() }`, so **every** configuration —
including the build-tool configs — is written to `gradle.lockfile`. The `osvScan`
Exec task scans that whole lockfile, so tool-only CVEs trip a gate meant for
shipped code.

The lockfile (Gradle **8.12**) currently carries **13 distinct configuration tags**.
Enumerated and bucketed (the apply must decide *each*, not just spotbugs):

| Configuration | Bucket | Decision |
|---|---|---|
| `compileClasspath` | shipped (compiles our code) | **lock + scan** |
| `runtimeClasspath` | shipped | **lock + scan** |
| `productionRuntimeClasspath` | shipped (the deployed graph) | **lock + scan** |
| `testCompileClasspath` | test | **lock + scan** |
| `testRuntimeClasspath` | test | **lock + scan** |
| `annotationProcessor` | build-tool (our build host; currently empty) | exclude |
| `testAnnotationProcessor` | build-tool (currently empty) | exclude |
| `developmentOnly` | dev-only (devtools; never deployed) | exclude |
| `testAndDevelopmentOnly` | dev-only (currently empty) | exclude |
| `jacocoAgent` | build-tool (coverage runner) | exclude |
| `jacocoAnt` | build-tool (coverage runner) | exclude |
| `spotbugs` | build-tool (SAST runner) | exclude |
| `spotbugsPlugins` | build-tool (SAST runner) | exclude |

So **in-scope = the 5 classpath configs** (3 shipping + 2 test); the other 8 are
build/dev/tool and excluded by design. Three of the excluded already hold no entries
(`empty=annotationProcessor,testAndDevelopmentOnly,testAnnotationProcessor`); the
material exclusions are `spotbugs`/`spotbugsPlugins`, `jacocoAnt`/`jacocoAgent`, and
`developmentOnly`. Expect the regenerated lockfile to **shrink by ~25 lines** (all
tool-only entries), not merely the 5 suppressed ones.

Two facts shape the lock rewrite:
1. `commons-lang3` is **not** purely tool-classpath — it is also on the test
   classpath, so the current suppression reason ("not in test runtime") is wrong.
   `dependencyInsight` shows `commons-compress:1.28.0` already *requests*
   `commons-lang3:3.18.0` (the fixed version); the **stale lock entry** is the only
   thing forcing it down to `3.17.0` on the test config.
2. `log4j-core` is genuinely tool-only (`=spotbugs`); `log4j-api` (shipped, on
   `compile/production/runtime`) is a *different* artifact, unaffected, and stays
   locked + scanned.

## Goals / Non-Goals

**Goals:**
- Clear all 5 suppressions and let `./gradlew check` pass green with an **empty**
  `osv-scanner.toml` baseline, permanently — not by pushing the expiry out.
- Stop the OSV gate from ever scanning build-tool/dev classpaths again (retire the
  whole false-positive class), and make that an explicit, documented, per-config
  policy.
- Correct the `commons-lang3` test-classpath finding by moving it to the fixed
  `3.18.0` that its own graph already wants.

**Non-Goals:**
- **Not** bumping the SpotBugs Gradle plugin (the rejected "Lever A"). It stays at
  `6.0.26`. Chasing tool-classpath CVEs via plugin bumps is the treadmill this CR
  exists to end.
- No change to SAST (SpotBugs/FindSecBugs) behavior, the fail-on-any /
  fail-closed posture of the dependency gate over shipped+test code, or the
  "remediated, not renewed" rule.
- No application code, module, signing/eSign, or API change.

## Decisions

### D1 — Scope locking to the 5 shipping+test configs (Lever B) over bumping the plugin

Replace `lockAllConfigurations()` with locking only the in-scope configs. Tool/dev
configs then never enter `gradle.lockfile`, so OSV never sees them.

- **Why over Lever A (bump plugin):** a SAST/coverage runner's transitive libs are
  not a *product* risk surface; gating on them is noise. Bumping the plugin re-opens
  this CR on every future tool-transitive CVE. Scoping retires the class once.
- **Why locking-scope, not an OSV scan-scope filter:** OSV reads the lockfile and has
  no per-Gradle-configuration filter; the lockfile *is* the scan's scope. Narrowing
  what we lock is therefore the available, single-source mechanism. The cost — we no
  longer pin tool-classpath versions for reproducibility — is acceptable: tools are
  not shipped, and SpotBugs/FindSecBugs/JaCoCo versions are already pinned explicitly
  in `build.gradle.kts` (`toolVersion`, the `findsecbugs-plugin` coordinate), so the
  analysis itself stays deterministic.
- **Mechanism (the load-bearing detail):** dropping `lockAllConfigurations()` means
  each in-scope config must opt in individually. Use per-configuration activation,
  e.g.:
  ```kotlin
  dependencyLocking { lockMode.set(LockMode.DEFAULT) }
  listOf(
      "compileClasspath", "runtimeClasspath", "productionRuntimeClasspath",
      "testCompileClasspath", "testRuntimeClasspath",
  ).forEach { name ->
      configurations.named(name) { resolutionStrategy.activateDependencyLocking() }
  }
  ```
  **Lazy-config hazard:** `productionRuntimeClasspath` (and the test classpaths) are
  created lazily by the Spring Boot / java plugins. `configurations.named(...)` is a
  lazy lookup and must run *after* those plugins are applied (it is, given the
  `plugins {}` block runs first) — but a naive eager `configurations.getByName(...)`
  at the wrong point would throw "configuration not found" or silently lock nothing.
  Apply-time check: after the rewrite, assert the lockfile actually contains each of
  the 5 configs' graphs (not an `empty=` line for them).

### D2 — Rewrite the lockfile via `--write-locks`, don't hand-edit

Run `./gradlew dependencies --write-locks` so Gradle regenerates `gradle.lockfile`
from the narrowed scope. This simultaneously (a) drops the tool/dev entries and (b)
lets `commons-lang3` resolve to `3.18.0` on the test classpath (the value
`commons-compress:1.28.0` already requests) once the `spotbugs` constraint no longer
participates. Hand-editing would desync from the real graph.

- **Shared-entry rewrite:** entries tagged across in- and out-of-scope configs must
  be re-emitted with only the in-scope tags. Concretely
  `commons-lang3:3.17.0=spotbugs,testCompileClasspath,testRuntimeClasspath` must
  become `commons-lang3:3.18.0=testCompileClasspath,testRuntimeClasspath` (version
  moved AND `spotbugs` tag gone), and the `empty=` marker line regenerated for
  whatever now-locked configs resolve empty. Verify the *tag set* on shared entries,
  not just the absence of `=spotbugs`-only lines.
- **commons-lang3 fallback:** the move to 3.18.0 is likely but depends on the test
  graph's highest requested version winning once descoped. If the re-run still pins
  3.17.0 on test, force 3.18.0 so the shipped graph is untouched, then re-write locks.
  - **APPLY-TIME OUTCOME (recorded):** `--write-locks` alone did **not** move it —
    the Spring Boot 3.5.15 BOM *manages* `commons-lang3` to 3.17.0, and
    `io.spring.dependency-management` forces that managed version onto transitives,
    which also **overrides a plain Gradle `constraints { testImplementation(...) }`**
    (so the originally-named constraint fallback would not have worked). Remediated
    instead by overriding the BOM version property:
    `extra["commons-lang3.version"] = "3.18.0"`. This is global in form but
    **test-only in effect** — `commons-lang3` appears on no shipping configuration
    (it enters only via `commons-compress`, a Testcontainers/MinIO test transitive),
    verified against the regenerated lockfile. Shipped graph unchanged.

### D3 — Empty the suppression baseline and rewrite the header

Delete all 5 `[[IgnoredVulns]]` entries. Rewrite the file's header comment to (a)
keep the existing suppression-workflow guidance and (b) state the new scope policy:
the gate covers the 5 shipping+test configs; build/dev/tool classpaths are excluded
by design — and **name the nature of what's now unscanned** (the SpotBugs/JaCoCo
tool classpaths, which today carry open `log4j-core`/`commons-lang3` advisories) so a
future engineer who re-widens scope isn't surprised by re-appearing findings. An
empty baseline is the desired end state (the "shrinking risk-acceptance log"
requirement, taken to zero).

### D4 — Document the scope policy in CLAUDE.md

Add to the Testing & Scanning section a line stating the dependency gate's scope
(the 5 shipping+test configs; build/dev/tool classpaths excluded by design, they ship
to no one and run only on the build host) **and** a forward note: this exclusion
should be revisited before any production / real-PII deployment, consistent with the
file's "identity/legal infra" posture — e.g. via a periodic full-lockfile
`osv-scanner` audit or an eventual SpotBugs-plugin bump. This converts the narrowing
from an implementation detail into reviewable policy with a compensating-control
pointer, addressing the one real risk of Lever B.

## Risks / Trade-offs

- **[Blind spot: a real CVE in a build/dev tool goes unscanned — a genuine, if
  lower, supply-chain vector]** Build tools are not deployed, but they *do* execute on
  the build host (SpotBugs parses our compiled bytecode; JaCoCo instruments it), so a
  toolchain RCE is a build-pipeline risk, not zero risk — and "pinned" ≠ "patched"
  (the pinned SpotBugs 6.0.26 still drags the vulnerable `log4j-core` now made
  invisible). → Mitigated, not eliminated: the exclusion is explicit, documented
  policy (D4), reviewable in diff, and reversible; SAST coverage of *our* code is
  unchanged; compensating control is a periodic full-lockfile `osv-scanner` audit /
  eventual plugin bump, and a "revisit before production" note ties it to the repo's
  legal-infra posture. Acceptable today given sandbox + dummy-data only.
- **[Over-narrow: accidentally drop a shipping/test config from locking]** →
  Mitigated: D1 apply-time check that the regenerated lockfile contains all 5 in-scope
  configs' graphs at expected versions; `./gradlew check` resolving + scanning that
  graph is the backstop.
- **[Locking mechanism / lazy-config ordering breaks the build]** → Mitigated:
  per-config `activateDependencyLocking()` via lazy `configurations.named(...)` after
  plugins apply (D1); verified by a green `./gradlew check`.
- **[`commons-lang3` doesn't move to 3.18.0]** → Caught by the D2 verification; falls
  back to a test-scope constraint (D2).
- **[Diff looks alarming (~25 lines vanish)]** → Expected and called out (Context /
  tasks): the shrink is the tool-only entries leaving, not loss of shipped-graph
  pinning.

## Migration Plan

1. Edit `dependencyLocking` in `build.gradle.kts` — per-config activation for the 5
   in-scope configs (D1).
2. `./gradlew dependencies --write-locks` to regenerate `gradle.lockfile` (D2).
3. Verify: no tool/dev entries; shared `commons-lang3` entry rewritten to
   `3.18.0=testCompile,testRuntime`; all 5 in-scope configs present (D1/D2 checks).
4. Empty `osv-scanner.toml` + rewrite header naming the excluded advisory class (D3).
5. Update `CLAUDE.md` with scope policy + revisit note (D4).
6. `./gradlew check` green with zero suppressions; spot-check the gate still *fails*
   on an in-scope finding (negative path).

**Rollback:** revert the four files (`build.gradle.kts`, `gradle.lockfile`,
`osv-scanner.toml`, `CLAUDE.md`); the previous lockfile + suppressions restore the
prior (passing-until-2026-09-20) state. No data or runtime migration.

## Open Questions

None blocking. Exact behavior of `--write-locks` on shared/`empty=` lines is verified
empirically against Gradle 8.12 at apply time (D2 checks), not a design unknown.
