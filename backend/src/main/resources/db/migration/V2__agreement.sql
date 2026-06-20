-- V2 — agreement aggregate (agreement-aggregate CR; first feature table).
--
-- The first entity-bearing migration. Column types/nullability mirror the `Agreement` JPA entity
-- exactly so JPA `ddl-auto: validate` passes against this schema (a drift fails context startup —
-- the guardrail this migration first genuinely exercises). Forward-only: never edit once applied;
-- ship a corrective V<n+1> instead.
--
--   * id              — application-assigned UUID (UUID.randomUUID() in the aggregate factory),
--                       not a DB default; identity exists the instant the aggregate is constructed.
--   * monthly_rent /  — exact money as numeric(12,2) (no binary-float rounding); maps to BigDecimal
--     security_deposit   with @Column(precision = 12, scale = 2).
--   * created_at      — an instant (a moment in time): timestamptz / Java Instant, stored UTC.
CREATE TABLE agreement (
    id               uuid           PRIMARY KEY,
    landlord_name    text           NOT NULL,
    tenant_name      text           NOT NULL,
    property_address text           NOT NULL,
    monthly_rent     numeric(12, 2) NOT NULL,
    security_deposit numeric(12, 2) NOT NULL,
    term_months      integer        NOT NULL,
    created_at       timestamptz    NOT NULL
);
