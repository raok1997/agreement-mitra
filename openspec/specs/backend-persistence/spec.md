# backend-persistence Specification

## Purpose
TBD - created by archiving change persistence-foundation. Update Purpose after archive.
## Requirements
### Requirement: Flyway-managed versioned schema
The backend SHALL manage its PostgreSQL schema with Flyway migrations rather than Hibernate
`ddl-auto` generation. Flyway (`flyway-core` plus the PostgreSQL support module) SHALL run on
application startup against the configured datasource and apply any pending migrations before the
JPA `EntityManagerFactory` initializes. JPA `ddl-auto` SHALL remain `validate` in every
environment (local, test, production) so Hibernate never creates or alters schema — Flyway is the
single source of schema truth.

#### Scenario: Migrations apply on startup
- **WHEN** the application starts against a database with pending migrations
- **THEN** Flyway applies them in version order before JPA initializes
- **AND** the `flyway_schema_history` table records each applied migration

#### Scenario: JPA validates, never generates
- **WHEN** the Spring context boots in any environment
- **THEN** `spring.jpa.hibernate.ddl-auto` is `validate`
- **AND** Hibernate performs no `create`, `update`, or `create-drop` schema generation

#### Scenario: Destructive clean is disabled
- **WHEN** the application is configured
- **THEN** `spring.flyway.clean-disabled` is `true` (explicitly set, not relied on as a default)
- **AND** an attempt to run Flyway `clean` against the schema is refused

### Requirement: Migration convention and forward-only baseline
Migrations SHALL live at `classpath:db/migration` (Boot's default `spring.flyway.locations`, so no
explicit property is required) and follow Flyway versioned naming
(`V<n>__<snake_case_description>.sql`), applied in ascending version order and never edited once
applied (forward-only; corrections ship as a new migration). The repository SHALL include an
initial baseline migration (`V1`). The baseline SHALL define no feature tables (for example
`agreement` or `signing_request`) — each later feature change owns the migration that introduces its
own tables. The baseline MAY be comment-only (no executable DDL): Flyway still records it in
`flyway_schema_history`, which is what establishes the migration chain.

#### Scenario: Baseline establishes the migration chain
- **WHEN** Flyway runs against an empty database
- **THEN** the `V1` baseline migration applies successfully
- **AND** it creates no feature-domain tables

#### Scenario: Feature schema is deferred to feature changes
- **WHEN** the baseline migration is inspected
- **THEN** it contains no `agreement` or `signing_request` table definitions

