# Backend Test Harness

## Purpose

The backend integration-test harness that makes "tests from the first story" possible:
Testcontainers-backed PostgreSQL + MinIO infra wired into Spring (Postgres via
`@ServiceConnection`, MinIO via a `DynamicPropertyRegistrar`), shared by composition
(`@Import`) so it works for both `@SpringBootTest` and Spring Modulith
`@ApplicationModuleTest`; a `create-drop` test schema profile; a JaCoCo coverage gate wired
into `check`; and a smoke test proving the whole thing boots green. Container-backed tests
skip cleanly (not fail) when no Docker daemon is present.
## Requirements
### Requirement: Testcontainers-backed integration-test base
The backend SHALL provide a reusable test base that starts a PostgreSQL container and a
MinIO (S3-compatible) object-storage container for integration tests. The PostgreSQL
datasource SHALL be wired into the Spring context via `@ServiceConnection` (no hand-rolled
property wiring); the MinIO endpoint and credentials SHALL be surfaced to a test-scoped
client (MinIO has no `@ServiceConnection` starter). The containers SHALL be shared across
tests via the test context cache rather than re-declared per test, and integration tests
SHALL obtain this infra by importing the shared test configuration rather than declaring
containers themselves. The test profile SHALL provision the schema via **Flyway-managed
migrations** (the same `db/migration` migrations production runs), with JPA `ddl-auto:
validate`, so JPA-backed tests build their schema from the real migration path rather than
from Hibernate `create-drop`. Containers SHALL use ephemeral,
container-default or random credentials and container-mapped (not fixed host) ports —
never real or committed secrets; test storage properties SHALL win over any real `S3_*`
environment values.

#### Scenario: Integration test boots against live infra
- **WHEN** an integration test that uses the base test configuration starts
- **THEN** a PostgreSQL container and a MinIO container are running
- **AND** the Spring context's datasource is wired to the PostgreSQL container via
  `@ServiceConnection`, with no manual `@DynamicPropertySource` for the datasource

#### Scenario: Schema is provisioned by Flyway migrations
- **WHEN** an integration test starts against the PostgreSQL container
- **THEN** Flyway applies the `db/migration` migrations to build the test schema
- **AND** JPA runs with `ddl-auto: validate` (no Hibernate `create-drop` generation)

#### Scenario: Containers are shared, not re-declared, per test
- **WHEN** a second integration test imports the same shared test configuration
- **THEN** it obtains the container wiring without re-declaring container beans or fields

#### Scenario: Integration tests skip cleanly without Docker
- **WHEN** the suite runs on a machine with no Docker daemon available
- **THEN** the Testcontainers-backed tests are skipped (not failed), and the build does
  not turn red solely for lack of Docker

### Requirement: Spring Modulith slice-test wiring
The harness SHALL provide a base supporting `@ApplicationModuleTest` so that, once modules
have behavior, a single module can be booted in isolation against the Testcontainers infra.
Because all modules are currently empty stubs, this change SHALL prove only that the
slice-test **mechanism boots in isolation** — it makes no behavioral assertion (the first
real module slice test ships with the feature that adds behavior). The existing
module-boundary verification (`ModularityTests`) SHALL continue to pass unchanged.

#### Scenario: Slice-test mechanism boots in isolation
- **WHEN** an `@ApplicationModuleTest` for a single application module runs using the
  harness base
- **THEN** that module's slice bootstraps against the harness without loading unrelated
  modules, confirming the mechanism is wired (no behavioral assertion is required while
  modules are stubs)

#### Scenario: Module boundaries stay verified
- **WHEN** the test suite runs
- **THEN** `ModularityTests` passes, confirming no module reaches into another's internals

### Requirement: JaCoCo coverage gate
The Gradle build SHALL produce a JaCoCo coverage report and enforce a coverage
verification rule wired into the `check` lifecycle, so a coverage threshold is checked on
every build. The initial threshold SHALL be an explicit floor low enough to pass while the
codebase is stub-only and when the Testcontainers tests are skipped (no Docker) — present
so the gate exists from the first feature story and can ratchet upward later, never failing
the build spuriously. Generated and entry-point classes SHALL be excluded from counts.

#### Scenario: Coverage report and verification run on check
- **WHEN** `./gradlew check` runs
- **THEN** a JaCoCo coverage report is generated
- **AND** the coverage verification task runs and passes against the explicit floor
- **AND** the build does not fail on coverage alone, including when Testcontainers tests
  were skipped for lack of Docker

### Requirement: Harness smoke test
The harness SHALL include a smoke test that proves it works end to end: the Spring
context boots against the Testcontainers PostgreSQL, the object-storage container is
reachable via a bucket create/exists round-trip, and the suite stays green. This smoke
test stands in for feature integration tests until the first feature CR lands; the
stubbed signing API SHALL NOT be integration-tested in this change.

#### Scenario: Smoke test proves the harness boots green
- **WHEN** the harness smoke test runs
- **THEN** the Spring context starts against the Testcontainers PostgreSQL
- **AND** an object-storage bucket can be created and confirmed to exist in the container
- **AND** the test passes

#### Scenario: Stubbed signing API is not integration-tested here
- **WHEN** this change's test suite is reviewed
- **THEN** it contains no integration test asserting behavior of the stubbed signing
  endpoints (which still throw `UnsupportedOperationException`)

