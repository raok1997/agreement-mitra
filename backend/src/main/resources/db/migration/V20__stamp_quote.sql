-- V20: the stamp quote frozen with each payment order (change state-stamp-duty-quoting, design D7).
--
-- WHAT THIS IS. A payment order already records WHAT was charged (payment_order.amount_minor_units).
-- This records WHY: the legal stamp duty computed under a named, content-hashed rule; the stamp value
-- the customer chose; and, when that choice was below the legal duty, the audited acknowledgement.
-- Staff stamp intake reconciles the purchased certificate against stamp_value_minor_units.
--
-- WHY A SEPARATE TABLE. payment_order stays provider-shaped and write-once; the quote is domain data.
-- A 1:1 primary key on the order id makes "frozen with the order" true by construction. Columns on
-- agreement were rejected: a later order would overwrite the audit.
--
-- WRITE-ONCE. Nothing updates a row after insert. A rule edit after payment can never move what the
-- customer was quoted and charged.
--
-- NO PERSONAL DATA. Amounts, hashes, a date, flags and an identity id only -- no party name, contact
-- or address. The breakdown lines carry quantity names and amounts (rent and deposit totals), which
-- is agreement data the payer already holds, and are never exposed on the staff queue.
CREATE TABLE stamp_quote
(
    payment_order_id        UUID PRIMARY KEY REFERENCES payment_order (id),
    agreement_id            UUID         NOT NULL REFERENCES agreement (id),
    duty_minor_units        BIGINT       NOT NULL CHECK (duty_minor_units >= 0),
    stamp_value_minor_units BIGINT       NOT NULL CHECK (stamp_value_minor_units >= 0),
    below_duty              BOOLEAN      NOT NULL,
    medium_id               VARCHAR(64)  NOT NULL,
    rule_id                 VARCHAR(128) NOT NULL,
    rule_content_hash       VARCHAR(64)  NOT NULL,
    rule_reviewed           BOOLEAN      NOT NULL,
    catalog_content_hash    VARCHAR(64)  NOT NULL,
    execution_date          DATE         NOT NULL,
    registration_required   BOOLEAN      NOT NULL,
    breakdown               JSONB        NOT NULL,
    ack_warning_version     VARCHAR(64),
    ack_identity_id         UUID,
    ack_at                  TIMESTAMPTZ,
    created_at              TIMESTAMPTZ  NOT NULL,
    -- A below-duty choice always carries its acknowledgement, and only a below-duty choice does.
    CONSTRAINT stamp_quote_ack_iff_below_duty
        CHECK (below_duty = (ack_warning_version IS NOT NULL AND ack_at IS NOT NULL)),
    CONSTRAINT stamp_quote_below_duty_consistent
        CHECK (below_duty = (stamp_value_minor_units < duty_minor_units))
);

CREATE INDEX stamp_quote_agreement ON stamp_quote (agreement_id, created_at);
