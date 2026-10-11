-- V29: the payment event ledger (change payment-event-ledger, D6-D9).
--
-- A row is one DISTINCT gateway payment confirmation attempt and a count of its repeats: an attempt
-- identical to an earlier one in everything but its time and its presented event id raises
-- attempt_count and widens first_received_at / last_received_at instead of adding a row.
--
-- It is written outside the confirmation transaction, so it survives that transaction's rollback.
-- It holds no payer PII and no webhook payload. presented_event_id is NOT authenticated: the
-- header it comes from is outside the webhook signature, so it is a label and nothing more.
--
-- No foreign key: an attempt for an order we do not hold has no order row to reference. No check
-- constraint on source or outcome: the enums that write them are the one home for those lists.
CREATE TABLE payment_event (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    attempt_key         CHAR(64)     NOT NULL,
    source              VARCHAR(24)  NOT NULL,
    presented_event_id  VARCHAR(64),
    event_type          VARCHAR(32),
    provider_order_id   VARCHAR(255) NOT NULL,
    provider_payment_id VARCHAR(255),
    amount_minor_units  BIGINT       NOT NULL,
    currency            VARCHAR(16),
    outcome             VARCHAR(24)  NOT NULL,
    refused_rule        VARCHAR(128),
    sql_state           VARCHAR(8),
    first_received_at   TIMESTAMPTZ  NOT NULL,
    last_received_at    TIMESTAMPTZ  NOT NULL,
    attempt_count       BIGINT       NOT NULL
);

CREATE UNIQUE INDEX uq_payment_event_attempt_key
    ON payment_event (attempt_key);

CREATE INDEX idx_payment_event_order_first_received
    ON payment_event (provider_order_id, first_received_at);
