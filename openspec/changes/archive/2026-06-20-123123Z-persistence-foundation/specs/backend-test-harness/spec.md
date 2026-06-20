## MODIFIED Requirements

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
