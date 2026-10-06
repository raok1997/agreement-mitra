-- V26: a plain index on payment_order.agreement_id (change stale-draft-purge, D4).
--
-- V17 indexes agreement_id only partially (the unique open-order index, WHERE status = 'CREATED').
-- The retention purge's candidate query (NOT EXISTS payment_order), its lock-time existence check
-- and the FK check on every agreement delete all look up every order of an agreement, whatever
-- its status, so they need a full index. Separate from V25 because V25 was already applied
-- locally before this was added, and an applied migration is never edited.
CREATE INDEX idx_payment_order_agreement_id ON payment_order (agreement_id);
