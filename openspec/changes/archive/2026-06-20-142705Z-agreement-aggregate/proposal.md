## Why

`agreementId` flows through the signing domain (`SignRequest.agreementId`) but means nothing —
there is no `Agreement` entity, no row, no way to create or fetch one. The signing flow cannot
begin until an agreement is a real, persisted thing. This is prereq 2 of the signing-flow roadmap:
a thin, create-and-fetch rental Agreement built on the Flyway base laid by `persistence-foundation`.
It is also the **first entity-bearing change**, so it graduates that change's transitional
"validate-green-with-zero-entities" posture into a real entity-vs-migration `validate`.

## What Changes

- Add a minimal `Agreement` aggregate to the `signing` module: a JPA entity with an app-assigned
  UUID id, a `createdAt` timestamp, and six typed rental-content columns — `landlordName`,
  `tenantName`, `propertyAddress`, `monthlyRent`, `securityDeposit`, `termMonths`.
- Add a package-private Spring Data repository for the aggregate (internal to `signing`; not
  exposed cross-module).
- Add the first feature migration, `V2__agreement.sql`, creating the `agreement` table to match
  the entity mapping exactly (forward-only).
- Add two JSON endpoints under a new `/api/agreements` resource: `POST /api/agreements` (create)
  and `GET /api/agreements/{id}` (fetch). Request/response bodies are records; the create request
  is bean-validated. No list/update/delete.
- Permit both endpoints in the default-deny Spring Security baseline (from `backend-security-baseline`)
  so they are reachable; the integration test exercises the real security filter chain (not a
  filter-blind MockMvc slice).
- **No signing status on the aggregate.** The signing FSM (`DRAFT → … → SIGNED`) is deferred to the
  `create-signing-request` change; this Agreement carries no `SignatureStatus`.

## Capabilities

### New Capabilities
- `agreement`: the rental-agreement aggregate — its persisted shape (id, audit timestamp, the six
  rental-content fields and their validation), and the create + fetch API contract. Status-less by
  design; the signing lifecycle is a separate capability.

### Modified Capabilities
- `backend-persistence`: supersedes the **transitional** "Schema validation stays green with no
  entities" requirement. With the `Agreement` entity present, `ddl-auto: validate` and the
  Flyway-before-JPA ordering are now genuinely exercised against a real mapped entity and its
  `V2` migration, not merely tolerated against an empty schema.

## Impact

- **Module:** `signing` (`in.agreementmitra.signing`) — new entity, repository, DTO records, and an
  `api` controller. No other module touched; `ModularityTests` stays green.
- **Schema:** new `agreement` table via `V2__agreement.sql`; `gradle.lockfile` unaffected (no new
  deps — JPA, validation, and Postgres are already on the classpath).
- **Security:** `SecurityConfig` gains a permit rule for `/api/agreements/**`.
- **API:** new public surface `POST /api/agreements`, `GET /api/agreements/{id}`.
- **Tests:** unit (aggregate factory, DTO mapping, validation) + integration (Testcontainers
  Postgres: persist/fetch, migration `validate`, endpoint slice through the real security filter).

## PII / Security review

No Aadhaar/OTP/VID/KYC or signer-identity PII flows through this change — a rental Agreement holds
only landlord/tenant display names and contract terms (dummy data in this repo), not government IDs
or authentication secrets. No new outbound flow, no secrets, no webhook. **Signing FSM:** none
touched — the aggregate is status-less and the signing state machine lands in a later change.
Sandbox + dummy-data-only posture is preserved.
