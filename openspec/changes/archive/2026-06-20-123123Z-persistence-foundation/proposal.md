## Why

The backend persists nothing today: `spring-boot-starter-data-jpa` and the Postgres driver are
on the classpath, but there are no entities, no repositories, and no schema-management tool.
Production runs `ddl-auto: validate` against an empty schema and the integration-test harness
uses `ddl-auto: create-drop`. Every stateful feature on the roadmap — starting with the signing
flow (a persisted async state machine that survives across webhook callbacks) — needs a real,
versioned schema first. Flyway was explicitly deferred in the `backend-test-harness` CR; this CR
closes that gap so each later feature CR can ship its own migration on a solid base.

## What Changes

- Add Flyway (`flyway-core` + `flyway-database-postgresql`) to the backend, managed by the
  Spring Boot 3.5.15 BOM.
- Establish the migration convention and location: `classpath:db/migration`, `V<n>__<desc>.sql`
  naming, forward-only.
- Add a minimal infra-only baseline migration. It contains **no feature schema** (no `agreement`,
  no `signing_request` — each later feature CR owns its own migration) and is shaped so JPA
  `ddl-auto: validate` stays green with no entities present.
- Switch the integration-test harness off `ddl-auto: create-drop` and onto Flyway-managed schema,
  so tests `validate` against the same migrations production runs. **BREAKING** for the
  `backend-test-harness` capability's test-schema requirement (internal test config only; no
  runtime API change).
- Reconcile `application-local.yml`: with Flyway present, remove the `ddl-auto: update` local
  override so Flyway owns the schema locally too (consistent dev/test/prod).
- Update docs: CLAUDE.md (mark Flyway no longer deferred) and the affected specs.

## Capabilities

### New Capabilities
- `backend-persistence`: Flyway-managed, versioned database schema for the backend — migration
  location/naming convention, forward-only baseline, and `validate`-against-migrations posture
  consistent across local, test, and production.

### Modified Capabilities
- `backend-test-harness`: the test profile's schema is now provisioned by **Flyway-managed
  migrations**, replacing the `ddl-auto: create-drop` requirement, so integration tests exercise
  the real migration path.

## Impact

- **Build**: `backend/build.gradle.kts` — add `flyway-core` + `flyway-database-postgresql`
  (BOM-managed; regenerate `gradle.lockfile` and re-run the OSV gate).
- **Config**: `application.yml` (Flyway enabled; `validate` retained), `application-local.yml`
  (drop `ddl-auto: update` override); add `src/main/resources/db/migration/V1__baseline.sql`.
- **Tests**: the Testcontainers harness base switches from `create-drop` to Flyway; add an
  integration test proving migrations apply cleanly against the Testcontainers Postgres and the
  context boots with `validate` against the Flyway-managed schema.
- **Docs/specs**: CLAUDE.md; new `backend-persistence` spec; delta on `backend-test-harness`.
- No module added (Spring Modulith boundaries unchanged; `ModularityTests` stays green). No new
  endpoints, no aggregate, no signing-FSM change.

## PII / Security review checklist

- **PII / secret flow: none.** This is schema-management plumbing — no Aadhaar, OTP, VID, KYC, or
  signer PII is read, written, logged, or transmitted, and no feature tables are created. The
  baseline migration holds infra-only DDL.
- **Signing FSM:** untouched. No transition added or changed.
- **Secrets:** unchanged — Flyway reuses the existing env-sourced datasource credentials; nothing
  new is committed.
- **Sandbox + dummy data only:** preserved; no real data introduced.
