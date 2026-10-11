-- V30: staff alerts for a captured payment that was refused or not recorded (change
-- payment-recording-failure-staff-alert, D4 and D7).
--
-- The key becomes the alert's subject AND its kind. The subject alone was the key, and every kind
-- but ORDER_PAID uses the payment order id as its subject: an order that fails recording and is
-- later confirmed as surplus needs two alerts, and could have held only one.
--
-- No row is rewritten: id and kind are already NOT NULL. Existing rows keep both, so a sent or
-- failed alert is never raised again.
--
-- The previous build cannot run against this key. It inserts with ON CONFLICT (id), which Postgres
-- rejects once no unique index covers id alone, and that insert runs before any dispatch. Rolling
-- back needs the schema step in docs/DEPLOYMENT.md first.
ALTER TABLE staff_alert
    DROP CONSTRAINT staff_alert_pkey,
    ADD PRIMARY KEY (id, kind),
    DROP CONSTRAINT staff_alert_kind_check,
    ADD CONSTRAINT staff_alert_kind_check CHECK (kind IN (
        'ORDER_PAID',
        'DUPLICATE_PAYMENT',
        'PAYMENT_NOT_RECORDED',
        'PAYMENT_ID_REUSED',
        'PAYMENT_AMOUNT_MISMATCH'));
-- staff_alert_order_paid_key_check is kept: "one paid-order alert per agreement" is still enforced
-- by the key.

-- The alert sweep reads the ledger every 30 seconds for attempts last received inside its
-- look-back window. Not partial on outcome: the outcomes it asks for are listed once, in Java.
CREATE INDEX idx_payment_event_last_received
    ON payment_event (last_received_at);
