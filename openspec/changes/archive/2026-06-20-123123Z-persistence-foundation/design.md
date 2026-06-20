## Context

The backend has JPA + the Postgres driver on the classpath but no schema-management tool, no
entities, and no migrations. Production runs `ddl-auto: validate` (against an empty schema),
`application-local.yml` overrides to `update`, and the Testcontainers harness uses `create-drop`.
Three different schema-provisioning strategies across three environments is exactly the
inconsistency Flyway exists to remove. This CR introduces Flyway as the single source of schema
truth before any stateful feature (the signing flow) needs to persist. It is pure infrastructure:
no feature tables, no entities.

## Goals / Non-Goals

**Goals:**
- Flyway wired and running on startup, applying `db/migration` migrations before JPA initializes.
- One consistent posture across local/test/prod: Flyway owns schema, JPA `validate` only checks it.
- A migration convention (location, naming, forward-only) future feature CRs follow.
- The integration-test harness builds its schema from the real migrations, not `create-drop`.
- An integration test that proves migrations apply and the context boots under `validate`.

**Non-Goals:**
- No feature schema (`agreement`, `signing_request`), no entities, no repositories — each feature
  CR owns its own migration.
- No Flyway baseline-on-existing-db / `baselineOnMigrate` handling — the database is empty; this is
  a greenfield V1.
- No undo/rollback migrations (Flyway Teams feature; we are forward-only).
- No CI wiring (that is the `ci-pipeline` follow-up CR).

## Decisions

### D1 — Flyway over Liquibase, and over staying on `ddl-auto`
Flyway: plain SQL migrations, first-class Spring Boot auto-configuration, BOM-managed version, and
the lowest-ceremony fit for a Postgres-only app. Liquibase's XML/changeset abstraction buys
database portability we do not need (Postgres everywhere). Staying on `ddl-auto: update` is
rejected outright — it is non-deterministic, never drops/renames safely, and is unsafe for the
audit-bearing legal data this app will hold. JPA stays at `validate` so Hibernate is a checker, not
a generator.

### D2 — Dependencies: `flyway-core` + `flyway-database-postgresql`
Flyway 10+ split database support into per-vendor modules; `flyway-core` alone does not register
the Postgres dialect. Both are managed by the Spring Boot 3.5.15 BOM, so no explicit version pin.
Boot's `FlywayAutoConfiguration` activates when `flyway-core` is on the classpath and a `DataSource`
exists — no manual bean wiring. **Verify, don't assume, the BOM management:** after `./gradlew
dependencies --write-locks`, confirm a concrete `flyway-database-postgresql` version actually landed
in `gradle.lockfile` and matches the `flyway-core` line — if the BOM doesn't manage that artifact on
3.5.15, pin it explicitly to the `flyway-core` version. Both Flyway artifacts are shipping/runtime
deps, so they are in the OSV scan scope (per the scan-scope policy); re-run the gate after the
lockfile regen.

### D3 — Baseline `V1__baseline.sql`: comment-only anchor, no DDL
The baseline is a **comment-only migration** — a header documenting the convention (forward-only,
`V<n>__<desc>.sql`, feature tables live in their feature-CR migrations) and no executable
statements. Flyway parses it, executes nothing, and records a `V1` row in `flyway_schema_history`,
which is exactly what establishes the migration chain and proves the mechanism. No feature tables,
no entities.
- *Decision history:* an earlier draft enabled the `pgcrypto` extension to make the baseline
  "non-empty." Review reversed this: (a) its only cited use — UUID generation — is `gen_random_uuid()`,
  **core since PG13**, so feature tables need no extension; (b) a security reviewer flagged that
  enabling a crypto extension app-wide needlessly widens the in-DB function surface on a future
  PII-bearing DB; (c) it forced verifying contrib availability against the *actual* test image
  (`postgres:16-alpine`), not the standard image — a verification burden for zero functional gain.
  Comment-only removes all three concerns and still records `V1`. The "reviewers distrust an empty
  baseline" worry was aesthetic, not technical, and is outweighed.
- *If a real extension is needed later* (e.g. `pgcrypto` for `crypt()`/`digest()`, or `citext`), it
  ships as its own migration in the CR that actually needs it — not pre-provisioned here.

### D3a — `spring.flyway.clean-disabled: true` set explicitly
Boot 3.x already defaults `clean-disabled=true`, but this CR crowns Flyway "single source of schema
truth" for audit-bearing legal/identity infra, so the destructive `flyway.clean` (drops the whole
schema) is **explicitly disabled** in `application.yml` with a one-line rationale — defense-in-depth
and intent-documenting, so a stray `clean` in CI/local can't wipe a schema. `baseline-on-migrate`
is left at its default `false` (greenfield DB; nothing to baseline over).

### D4 — `validate` stays green with zero entities
Hibernate `validate` fails when a *mapped entity* lacks a table; it tolerates *tables/objects with
no mapped entity*. With no entities present, an extension-only baseline leaves nothing for validate
to reject, so the context boots clean. This invariant must hold as the baseline is the only schema.

### D5 — Test harness: Flyway replaces `create-drop`
Remove the test-profile `ddl-auto: create-drop` and let Flyway provision the container schema with
`ddl-auto: validate` (matching prod). The harness already wires the Postgres container via
`@ServiceConnection`; Boot's Flyway auto-config runs against that same single datasource bean before
JPA (`FlywayAutoConfiguration` registers an `EntityManagerFactoryDependsOnPostProcessor`), so no
harness wiring changes — only the test-profile property flips. Migration files resolve from Boot's
default `spring.flyway.locations` (`classpath:db/migration`) — no `locations` property is needed.
This makes tests exercise the real migration path (catches a broken migration at test time, not in
prod). *Behavior change to note:* `create-drop` rebuilt the schema per run; with Flyway against the
context-cached shared container the schema now persists across tests in a JVM. Harmless here (no
entities, no data), but explicit.

### D7 — Honest limit: the headline invariants aren't yet falsifiable
"Flyway runs before JPA" and "`validate` passes" are stated as requirements, but with **zero mapped
entities** `validate` has nothing to check, so a broken Flyway→JPA ordering would not redden the
test. The integration test therefore proves only that Flyway applied (`flyway_schema_history` has
`V1`) and the context boots. These invariants are first *genuinely* exercised by the first
entity-bearing feature CR (which adds an entity + its table migration); that is acceptable — this CR
stands up the mechanism, the next one stresses it.

### D6 — Reconcile `application-local.yml`
Remove the `ddl-auto: update` override so local dev also runs Flyway + `validate`, consistent with
test/prod. Local schema now comes from `docker compose` Postgres + Flyway on `bootRun`.

## Risks / Trade-offs

- **Empty/near-empty baseline feels like over-engineering** → It is deliberate: standing up the
  migration *mechanism* (history table, convention, validate posture) now means every feature CR
  drops in a `V<n>` with zero setup. The cost is one small CR; the payoff is no retrofitting.
- **Comment-only baseline applies as a no-op** → Flyway still parses it, executes nothing, and
  records `V1` in `flyway_schema_history` (verified: live `bootRun` and the integration test both
  show `version=1, success=true`). The chain is established; later migrations carry real DDL.
- **Existing dev databases already touched by `ddl-auto: update`** → Local only, throwaway. Drop and
  recreate the local DB (or the compose volume) so Flyway starts from clean; no production data
  exists. Call this out in the manual-test step.
- **Flyway + JaCoCo/OSV interaction** → New deps shift the lockfile; the OSV gate must stay green
  after `--write-locks`. If Flyway pulls a flagged transitive, handle per the suppression policy
  (reason + expiry), not by widening scope.

## Migration Plan

1. Add the two Flyway deps; `./gradlew dependencies --write-locks`; confirm OSV gate green.
2. Add `src/main/resources/db/migration/V1__baseline.sql` (comment-only anchor; see D3).
3. Enable Flyway in `application.yml` (auto-on with the dep; keep `ddl-auto: validate`).
4. Remove the `ddl-auto: update` override from `application-local.yml`.
5. Flip the test profile off `create-drop` → `validate` (Flyway provisions the container schema).
6. Add the integration test (migrations apply + context boots under validate vs Testcontainers).
7. `./run-tests.sh check` green; update CLAUDE.md + specs.

**Rollback:** revert the deps + the `V1` file + the config edits; the test profile returns to
`create-drop`. No data migration to unwind (greenfield, no prod data).

## Open Questions

None blocking. (Whether to fold the first real feature table into this CR was already decided
against — feature schema stays with its feature CR.)
