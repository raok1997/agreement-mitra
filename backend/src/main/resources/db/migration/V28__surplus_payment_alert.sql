-- V28: surplus payments and the duplicate-payment staff alert (change
-- double-charge-invisible-to-staff, D3-D5).
--
-- A payment order is SURPLUS when its payment was captured but was not recorded as the agreement's
-- payment, because the agreement already held one. The mark is decided once, under the agreement
-- lock, when the order is confirmed, and never changes afterwards. Orders paid before this
-- migration are not backfilled.
ALTER TABLE payment_order
    ADD COLUMN surplus BOOLEAN NOT NULL DEFAULT FALSE;

-- One gateway payment id, one payment order. Until now the only uniqueness check on a gateway
-- payment id was uq_agreement_payment_reference, which fires when the agreement's reference is
-- written - and a surplus confirmation writes no agreement reference.
CREATE UNIQUE INDEX uq_payment_order_provider_payment_id
    ON payment_order (provider_payment_id)
    WHERE provider_payment_id IS NOT NULL;

-- staff_alert is re-keyed so one agreement can hold more than one alert. The key is the alert's
-- subject: the agreement id for ORDER_PAID, the payment order id for DUPLICATE_PAYMENT. Existing
-- rows keep their key (id = agreement_id), so a sent or failed alert is never raised again.
--
-- No foreign key from id to payment_order: the insert would take a key-share lock on the order row
-- the payment confirmation locks.
ALTER TABLE staff_alert
    ADD COLUMN id   UUID,
    ADD COLUMN kind VARCHAR(24);

UPDATE staff_alert
SET id   = agreement_id,
    kind = 'ORDER_PAID';

ALTER TABLE staff_alert
    ALTER COLUMN id SET NOT NULL,
    ALTER COLUMN kind SET NOT NULL,
    DROP CONSTRAINT staff_alert_pkey,
    ADD PRIMARY KEY (id),
    ADD CONSTRAINT staff_alert_kind_check CHECK (kind IN ('ORDER_PAID', 'DUPLICATE_PAYMENT')),
    -- "One paid-order alert per agreement" stays enforced by the key, not by convention.
    ADD CONSTRAINT staff_alert_order_paid_key_check CHECK (kind <> 'ORDER_PAID' OR id = agreement_id);

CREATE INDEX idx_staff_alert_agreement_id
    ON staff_alert (agreement_id);
