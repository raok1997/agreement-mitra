## Context

CR-4 introduced the OSV-Scanner dependency gate (`securityScan`, wired into
`check`). On day one it found **52 CVEs**, all inherited from the **Spring Boot
3.4.2** BOM that the project still pins (`build.gradle.kts:7`). To let CR-4 land
green they were suppressed in `backend/config/osv-scanner.toml`, each with
`ignoreUntil = 2026-07-20`. That expiry is a deliberate forcing function: it
makes the build break unless the versions are actually fixed.

Current pins:
- `org.springframework.boot` plugin **3.4.2** + the Boot-managed BOM (drives
  Tomcat, Spring Framework, Jackson, logback, log4j, json-smart, commons-lang3,
  httpclient5, postgresql, assertj, bouncycastle — all sources of the 52).
- `io.minio:minio:8.5.14` — pinned *directly* (test scope), **not** BOM-managed,
  so the Boot bump won't move it; it carries its own finding.
- `org.springframework.modulith` **1.3.4** via its own BOM — compatible with the
  whole 3.4.x line, so it stays.

This is a build/dependency change. No `src/` code, module boundary, signing FSM,
or webhook path is in scope.

> **Apply-time revision (2026-06-20).** The design below was written for a
> patch-only 3.4.7 target. The re-scan disproved that approach: 3.4.7 left 38
> findings, 3.4.13 (latest 3.4.x) still ships Tomcat 10.1.50 with three open
> 9.8/9.1 RCEs, and the spring-boot self-CVEs have **no 3.4.x fix in any
> release**. Muting fixable RCEs would violate this CR's own new rule, so the
> target moved (with user agreement) to the latest **3.5.x minor: Spring Boot
> 3.5.15 + Spring Modulith 1.4.12**. Actual result: 52 → 5 findings, **0
> Critical / 0 High**; the 5 residual are Medium SpotBugs-tool-classpath findings
> (the escape-valve case). The Goals/Non-Goals/Decisions that say "3.4.x /
> patch-only" are superseded by this note; the escape-valve and remediation-record
> decisions still hold.

## Goals / Non-Goals

**Goals:**
- Clear the 52 CR-4 CVEs by upgrading to patched versions, so the OSV gate
  passes because the findings are **gone**, not muted.
- Reach **0 Critical / 0 High** in the dependency scan (achieved: only Medium
  tool-classpath residue remains).
- Leave `osv-scanner.toml` containing only genuine escape-valve entries, each
  freshly justified with a short expiry.
- Prove no regression via a full `./gradlew check`.

**Non-Goals:**
- A major (4.x) bump — too far; rejected.
- Upgrading the SpotBugs plugin to clear the residual tool-classpath log4j/
  commons-lang3 findings — deferred to follow-up CR `bump-spotbugs-plugin`.
- Changing any scan *requirement* (fail-on-any, fail-closed, SAST scope,
  suppression policy) — those are CR-4's and stay as-is.
- Frontend dependency scanning (CR-6) and CI (CR-7) — separate follow-ups.

## Decisions

**Decision: target Spring Boot 3.5.15 + Spring Modulith 1.4.12 (latest 3.5.x).**
*Original plan was 3.4.7 (patch-only) — superseded.* The re-scan proved the
patch line can't reach a clean graph: spring-boot's own GHSA-wwpq-f5c3-7hvx /
GHSA-56v8-86gj-66jp have **no 3.4.x fix** (only 3.5.14+), and the latest 3.4.x
(3.4.13) still ships Tomcat 10.1.50 — three open 9.8/9.1 RCEs vs the 10.1.55 fix.
The new "remediate, not renew" rule forbids muting fixable RCEs, so staying on
3.4.x would force exactly the bad outcome this CR exists to prevent. 3.5.15 ships
Tomcat 10.1.55 + Spring 6.2.19 + Jackson 2.21.4 + logback 1.5.34 → clears every
Critical/High. Spring Boot 3.5.x requires Spring Modulith 1.4.x, so Modulith
moves 1.3.4 → 1.4.12 in lockstep. *Alternatives:* 3.4.13 + suppress the residual
RCEs — rejected (mutes fixable 9.8s); 4.x major — rejected (too far).

**Decision: bump directly-pinned, non-BOM deps that carry a finding.**
`io.minio:minio 8.5.14 → 8.6.0` (the `GHSA-h7rh-xfpj-hpcm` fix landed in 8.6.0, a
minor — the 8.5.x line never carried it; test scope). minio 8.6.0 transitively
pulls vulnerable bouncycastle 1.81 (`GHSA-c3fc-8qff-9hwx`), so add an explicit
test-scope `org.bouncycastle:bcprov-jdk18on:1.84` pin to remediate it by upgrade.
assertj is BOM-managed and moves with the Boot bump.

**Decision: remediate by deletion, re-justify only the genuinely-unfixed.**
After re-locking and re-scanning, every finding that 3.4.7 fixes has its
`[[IgnoredVulns]]` entry **removed** from `osv-scanner.toml`. A finding that
still has no upstream fix keeps a suppression, but rewritten with a fresh reason
and a *new* expiry — never the old rubber-stamped date. This is the
"remediate, not renew" spec requirement this CR adds, made concrete.

**Decision: re-lock before re-scan.** The OSV task reads `gradle.lockfile`
(`build.gradle.kts:147`), so `./gradlew dependencies --write-locks` must run
*before* `osvScan`, or the scan would read the stale graph and lie. Each
suppression deleted in the prune step must be cross-checked against the lockfile
diff: a finding that "no longer appears" is only truly remediated if the named
GAV/version actually moved — otherwise the clear could be spurious (e.g. OSV not
re-reading that line). Confirm the version change per deletion.

**Decision: escape valve for findings with no policy-compatible fix.** The
patch-only non-goal (no 3.5.x) can collide with a finding whose only fix is a
higher minor, or a *new* CVE introduced by 3.4.7 with no 3.4.x fix. In that case
the implementer does **not** silently violate the version policy: the finding
keeps an OSV suppression, re-justified with a fresh reason stating the case and a
**short** expiry, and is called out in the wrap-up. This matches the new
"remediated, not renewed" requirement's carve-out (no fix in any release, or
fixed only outside accepted version policy). It is not a rubber-stamp: the short
expiry forces revisiting, and a cluster of such findings is the signal to
schedule the minor bump as its own CR.

**Decision: the durable remediation record is the archived CR, not the TOML.**
Pruning deletes the entries that documented which CVEs were live, so the audit
trail of "these 52 RCE-class findings were exposed between CR-4 and CR-8 and
cleared on <date> by bumping to 3.4.7" lives in this change's archived
proposal/design + `.flow-journal.md` (and git history of the TOML). The
`osv-scanner.toml` header comment is refreshed to summarise the post-bump
baseline so the file itself still points at that record.

## Risks / Trade-offs

- **A patch bump still shifts transitive versions / defaults** → Mitigation: the
  full `./gradlew check` (unit + integration + `ModularityTests` + JaCoCo +
  `securityScan`) is the gate; run Testcontainers integration tests where Docker
  is available before declaring done.
- **3.4.7 may not fix 100% of the 52** (a CVE with no released fix, or one in
  minio's own deps) → Mitigation: the re-scan is the source of truth; any
  residual stays suppressed with fresh justification + expiry and is called out
  in the wrap-up, not hidden.
- **New CVEs could appear in the newly-pulled versions** → Mitigation: same
  re-scan catches them; fail-on-any means they can't slip through — they're
  triaged (fix or justified-suppress) before the gate goes green.
- **minio 8.5.x latest may itself introduce/retain a finding** → Mitigation:
  re-scan verifies; if a newer minor is needed it's a small, isolated follow-up
  rather than blocking this CR. Pin an **explicit** version (not "latest") whose
  floor is at/above the `GHSA-h7rh-xfpj-hpcm` fixed version, so the bump is
  reproducible and actually clears the advisory.
- **Spring Modulith 1.3.4 BOM is imported separately and is NOT moved by the
  Boot bump** (`build.gradle.kts:60-64`) → Mitigation: Modulith-managed versions
  must be re-confirmed to resolve cleanly against the 3.4.7 BOM and to carry no
  finding (they do — same minor line — but it is an explicit check, not an
  assumption).
- **`lockAllConfigurations()` re-locks every configuration** (incl. `spotbugs`,
  `spotbugsPlugins`, test configs), so `--write-locks` will show some non-Boot
  churn and fails *wholesale* if any single configuration won't resolve against
  3.4.7 → Mitigation: expect the broader diff; read a lock-regen failure as a
  resolution conflict to fix, not a flaky run.
- **SAST is unaffected by the bump** — SpotBugs/FindSecBugs analyse our own
  `src/main` bytecode (`spotbugsMain`), not the upgraded libraries, and no `src`
  changes here; the FindSecBugs-lag caveat from CR-4 does not bite. It still runs
  in the regression `check` for completeness.
- **Sequencing vs CR-5 (`spring-security-baseline`)** — CR-8 must land first; it
  assumes no Spring Security starter is on the graph yet (true today). If CR-5
  landed first, CR-8's re-lock would absorb the security transitives and the
  "build-only, no behaviour" framing would be stale.

## Migration Plan (as executed)

1. Edit `build.gradle.kts`: Boot plugin `3.4.2 → 3.5.15`; Modulith
   `1.3.4 → 1.4.12`; `io.minio:minio 8.5.14 → 8.6.0`; add test pin
   `bcprov-jdk18on:1.84`.
2. `./gradlew dependencies --write-locks` → regenerate `gradle.lockfile`.
3. `./gradlew osvScan` → residual triaged (52 → 5; all Medium SpotBugs-tool
   classpath).
4. Prune `osv-scanner.toml`: delete the 52 cleared entries; rewrite the 5
   residual with a fresh reason + short expiry (2026-09-20); refresh the header.
5. `./run-tests.sh check` (Docker up) → all gates green.

**Rollback:** revert the four touched files (`build.gradle.kts`,
`gradle.lockfile`, `osv-scanner.toml`). The *old* CR-4 suppressions were valid
only until 2026-07-20, so a revert restores the prior green state but re-arms the
deadline — re-apply before then.
