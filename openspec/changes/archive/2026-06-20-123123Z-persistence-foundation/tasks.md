<!-- Test note: this is config/infra plumbing with no pure business logic. Unit test is
EXEMPTED — Flyway/JPA wiring is declarative config; a mocked unit test would be tautological
(same precedent as the spring-security-baseline CR's filter chain). The authoritative test is the
INTEGRATION test (task 4.x): migrations only apply, and validate only passes, against a real
Postgres (Testcontainers). -->

## 1. Dependencies

- [x] 1.1 Add `org.flywaydb:flyway-core` and `org.flywaydb:flyway-database-postgresql` to
  `backend/build.gradle.kts` (BOM-managed by Spring Boot 3.5.15 — no explicit version)
- [x] 1.2 Regenerate the lockfile: `./gradlew dependencies --write-locks`
- [x] 1.3 Verify the BOM actually managed `flyway-database-postgresql`: confirm a concrete version
  landed in `gradle.lockfile` matching the `flyway-core` line; if unmanaged, pin it explicitly
- [x] 1.4 Confirm the OSV dependency-vuln gate stays green (`./gradlew securityScan`); if a new
  transitive is flagged, handle via the reason+expiry suppression policy (do not widen scan scope)

## 2. Baseline migration

- [x] 2.1 Create `backend/src/main/resources/db/migration/V1__baseline.sql` as a **comment-only**
  migration: a header documenting the convention (forward-only, `V<n>__<desc>.sql`, feature tables
  live in their own feature-CR migrations) and no executable DDL. Flyway still records `V1` in
  `flyway_schema_history`, establishing the chain
- [x] 2.2 Verify the file defines NO feature tables and no extension/DDL (pure comment anchor)

## 3. Configuration

- [x] 3.1 In `application.yml`, keep `spring.jpa.hibernate.ddl-auto: validate`; Flyway
  auto-activates with the dependency (add an explicit `spring.flyway.enabled: true` + a short
  comment documenting Flyway as the schema source of truth). Also set
  `spring.flyway.clean-disabled: true` explicitly (Boot's default, made intentional) so the
  destructive `flyway.clean` cannot wipe the schema — defense-in-depth for audit-bearing data
- [x] 3.2 In `application-local.yml`, remove the `ddl-auto: update` override so local runs Flyway +
  `validate` (consistent with test/prod). The file is now functionally empty — either keep it with a
  one-line comment explaining the `local` profile intentionally carries no schema override, or remove
  it (decide at apply time; document whichever)
- [x] 3.3 In the test profile, replace `ddl-auto: create-drop` with `validate` so Flyway provisions
  the Testcontainers schema (locate the test-profile config used by the harness base)

## 4. Integration test (authoritative)

- [x] 4.1 Add an integration test (using the shared Testcontainers harness base) that boots the
  Spring context against the Postgres container and asserts the context starts cleanly under
  Flyway + `ddl-auto: validate`
- [x] 4.2 Assert Flyway applied the baseline as the **primary** acceptance check: query
  `flyway_schema_history` for the `V1` row (`version = '1'`, `success = true`). This proves Flyway
  ran against the real migration path
- [x] 4.3 Confirm the test SKIPS cleanly without Docker (Testcontainers `disabledWithoutDocker`),
  not fails — consistent with the existing harness behavior
- [x] 4.4 Keep `ModularityTests` green (no new module/boundary introduced)

## 5. Verify

- [x] 5.1 Run `./run-tests.sh check` (auto-detects Docker) — full build, JaCoCo gate, security
  scans, and the new integration test all green
- [x] 5.2 Smoke `./gradlew bootRun` locally (SPRING_PROFILES_ACTIVE=local) against compose Postgres:
  Flyway applies `V1`, context boots under `validate` (drop/recreate the local DB first if a prior
  `ddl-auto: update` run left a dirty schema)

## 6. Docs & specs

- [x] 6.1 Update `CLAUDE.md`: note Flyway now manages schema (remove/adjust any "Flyway deferred"
  wording; document the `db/migration` convention and that each feature CR ships its own migration)
- [x] 6.2 Confirm the change's specs reflect reality (`backend-persistence` ADDED,
  `backend-test-harness` MODIFIED) before validate/archive
