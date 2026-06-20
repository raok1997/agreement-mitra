## 1. Migration

- [x] 1.1 Add `backend/src/main/resources/db/migration/V2__agreement.sql` creating the `agreement`
  table: `id uuid primary key`, `landlord_name text not null`, `tenant_name text not null`,
  `property_address text not null`, `monthly_rent numeric(12,2) not null`,
  `security_deposit numeric(12,2) not null`, `term_months integer not null`,
  `created_at timestamptz not null`. Forward-only; column types/nullability must match the entity (D5).

## 2. Aggregate

- [x] 2.1 Add the `Agreement` JPA entity in `in.agreementmitra.signing` (package-private), mapping
  the six content fields + `UUID id` + `Instant createdAt`, with a static factory that assigns
  `UUID.randomUUID()` and `createdAt` at creation (D1/D3). No `SignatureStatus` field.
  **Pin the entity mapping so `ddl-auto: validate` cannot drift from `V2` (D5):** a
  protected/package-private no-arg constructor for Hibernate; `monthlyRent`/`securityDeposit` as
  `@Column(precision = 12, scale = 2)` `BigDecimal` (matches `numeric(12,2)`); `createdAt` as
  `Instant` mapping to `timestamptz` (not `timestamp`); id-based `equals`/`hashCode` (safe because
  the id is assigned at construction, not on flush). `createdAt` and `id` are server-assigned only.
- [x] 2.2 Add a package-private Spring Data `AgreementRepository` (internal to `signing`, not
  cross-module exported) (D7).

## 3. API

- [x] 3.1 Add request/response DTO records (e.g. `CreateAgreementRequest`, `AgreementResponse`) with
  bean-validation on the request: `@NotBlank` names/address, `@PositiveOrZero` rent/deposit,
  `@Positive` `termMonths` (D2/D4). The request record **excludes `id` and `createdAt`** (no
  mass-assignment of server-assigned fields). Add `@Size` max-length caps to the free-text fields
  (`landlordName`, `tenantName`, `propertyAddress`) so an anonymous create cannot write unbounded text,
  and a sane `@Max` upper bound on `termMonths` (a lease term has a realistic ceiling).
- [x] 3.2 Add an `AgreementController` under `signing.api` exposing `POST /api/agreements` (persist →
  `201 Created` with id + fields + `createdAt`) and `GET /api/agreements/{id}` (`200`, or `404` when
  absent), with create/fetch logic in a package-private service (constructor injection).

## 4. Security

- [x] 4.1 Add a `permitAll` rule for `/api/agreements/**` to `SecurityConfig` so the endpoints clear
  the default-deny baseline (D6).

## 5. Unit tests

- [x] 5.1 Unit test the aggregate factory: constructing an `Agreement` assigns a non-null UUID and
  `createdAt`, and stores the six fields (no Spring context, no I/O).
- [x] 5.2 Unit test DTO mapping (entity ↔ `AgreementResponse`) and the bean-validation rules: blank
  name/address, negative rent/deposit, and non-positive `termMonths` each fail validation.

## 6. Integration tests

- [x] 6.1 Testcontainers Postgres integration test: Flyway applies `V1`+`V2`, the context boots under
  `ddl-auto: validate` against the `agreement` entity, and an `Agreement` saved via the repository
  round-trips (all six fields + id + `createdAt` unchanged) — graduating the persistence-foundation
  D7 transitional posture (D5).
- [x] 6.2 Full-context endpoint integration test through the **real security filter chain** (not a
  filter-blind MockMvc slice): `POST /api/agreements` valid → `201` + persisted row **and the
  response body echoes the server-assigned `id` + `createdAt` and the six stored fields**; invalid
  body → `400`, no row, **error body sanitized (no stack trace / internal class names)**; `GET`
  existing → `200`; `GET` unknown id → `404`; confirm the default-deny baseline does not `403`
  these paths (D6).
- [x] 6.3 Confirm `ModularityTests` stays green (no cross-module leakage of the aggregate internals).

## 7. Verify

- [x] 7.1 Run `./run-tests.sh check` from `backend/`: BUILD SUCCESSFUL — Flyway `V1`+`V2` applied,
  validate clean, all new unit/integration tests + `ModularityTests` pass, `securityScan` clean.
  Confirm `gradle.lockfile` is unchanged (no new dependencies).
- [x] 7.2 Update docs if needed: note the `agreement` table / first feature migration where the
  schema conventions are described (CLAUDE.md / `docs/ARCHITECTURE.md`), if not already implied.
