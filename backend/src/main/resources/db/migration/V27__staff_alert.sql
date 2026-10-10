-- V27: staff alert delivery state (change staff-paid-order-alert, D9).
--
-- One row per agreement that has a gateway-paid payment order: the delivery state of the single
-- alert staff get on their operations channel. The agreement is the key - one alert per agreement,
-- so there is no surrogate id - and a terminal row keeps the key, so a sent or failed alert is
-- never raised again.
--
-- The row is written by the alert sweep, never by the payment confirmation: the paid payment_order
-- row is the durable trigger and this table only records what was done about it.
--
-- No version column: every mutation is a conditional update guarded by status and attempts.
--
-- PII: none. The agreement id is a bearer capability and is redacted in logs like everywhere else;
-- the message itself is composed at send time and is never stored.
CREATE TABLE staff_alert
(
    agreement_id    UUID PRIMARY KEY REFERENCES agreement (id),
    status          VARCHAR(24) NOT NULL,
    -- Sends claimed so far. Counted at claim time, before the send, so a crash mid-send still counts.
    attempts        INTEGER     NOT NULL DEFAULT 0,
    -- When the row is next due. Written at claim time, which makes it the lease as well as the backoff.
    next_attempt_at TIMESTAMPTZ NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL,
    sent_at         TIMESTAMPTZ,
    CONSTRAINT staff_alert_status_check CHECK (status IN ('PENDING', 'SENT', 'FAILED'))
);

-- The sweep's due query: pending rows by next attempt time. The status literal here must match the
-- query's for the planner to use this index.
CREATE INDEX idx_staff_alert_pending_next_attempt
    ON staff_alert (next_attempt_at)
    WHERE status = 'PENDING';

-- The sweep's look-back query reads orders paid in the last 24 hours, every 30 seconds. V17 indexes
-- (status, created_at), which would make that a read of every paid order ever.
CREATE INDEX idx_payment_order_paid_confirmed_at
    ON payment_order (confirmed_at)
    WHERE status = 'PAID';
