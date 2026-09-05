-- V17 -- Gateway payment orders (razorpay-payment CR).
--
-- Additive and forward-only. Never edits V1..V16.
--
-- WHAT THIS IS. The payment gate (V16) recorded WHETHER an agreement was paid for; it had no
-- record of the order the money was taken against, because no gateway existed. This table is that
-- record: one row per provider order, joined to the agreement, carrying the amount we asked for so
-- a confirmation can be cross-checked against it rather than believed.
--
-- MONEY IS INTEGER MINOR UNITS (design D6). `amount_minor_units` is paise as a BIGINT - never a
-- float, never a double. Razorpay itself requires paise and rejects strings and floats, and
-- floating-point money produces off-by-one-paise errors that fail amount-equality checks. The
-- agreement's own `payment_amount` stays NUMERIC(12,2) (V16, the vendor-neutral seam); the
-- conversion happens once, at the boundary, and is exact.
--
-- PII: nothing here is party PII. `provider_order_id` / `provider_payment_id` are vendor
-- identifiers (redacted in logs), `receipt` is our own agreement identifier. No card, UPI, or bank
-- credential can be stored here - there is deliberately no column that could carry one.

CREATE TABLE payment_order
(
    id                  UUID PRIMARY KEY,
    agreement_id        UUID        NOT NULL REFERENCES agreement (id),
    -- Which gateway placed the order. Present from day one so a second provider is an adapter
    -- rather than a migration.
    provider            VARCHAR(32) NOT NULL,
    provider_order_id   VARCHAR(64) NOT NULL,
    -- Our identifier for the agreement as sent to the provider. The agreement UUID (36 chars) fits
    -- the provider's 40-character limit; a retry after expiry/failure appends a short "-N" suffix,
    -- so the value stays unique AND stays prefixed by the agreement it belongs to.
    receipt             VARCHAR(40) NOT NULL,
    amount_minor_units  BIGINT      NOT NULL CHECK (amount_minor_units > 0),
    currency            VARCHAR(3)  NOT NULL,
    -- CREATED (outstanding) | PAID | FAILED | EXPIRED. Ours, not the vendor's vocabulary.
    status              VARCHAR(16) NOT NULL,
    provider_payment_id VARCHAR(64),
    created_at          TIMESTAMPTZ NOT NULL,
    confirmed_at        TIMESTAMPTZ,
    -- Optimistic-locking column, so two concurrent webhook deliveries for one order cannot both
    -- apply a confirmation.
    version             BIGINT      NOT NULL DEFAULT 0
);

-- One provider order belongs to exactly one agreement: a provider order id can never be recorded
-- twice, so one payment cannot be credited to two agreements.
CREATE UNIQUE INDEX uq_payment_order_provider_order_id
    ON payment_order (provider_order_id);

-- The receipt is unique in our records too, matching the provider's own per-account uniqueness.
CREATE UNIQUE INDEX uq_payment_order_receipt
    ON payment_order (receipt);

-- Idempotency backstop for order creation (design D5): at most ONE outstanding order per
-- agreement. The application reuses an outstanding order rather than creating another; this index
-- is what makes that true under a double-submit, not just usually true. A new order becomes legal
-- again only once the previous one is EXPIRED or FAILED.
CREATE UNIQUE INDEX uq_payment_order_open_per_agreement
    ON payment_order (agreement_id)
    WHERE status = 'CREATED';

-- Backs the reconciliation scan: outstanding orders older than the configured age, oldest first.
CREATE INDEX ix_payment_order_status_created_at
    ON payment_order (status, created_at);
