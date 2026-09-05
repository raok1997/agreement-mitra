-- V16 -- ZOOP Aadhaar eSign (v5) + the payment gate (zoop-aadhaar-esign CR).
--
-- Two additive concerns, one forward-only migration (design "Migration Plan" step 1). Never edits
-- V1..V15.
--
-- 1. PER-TRANSACTION WEBHOOK KEY. ZOOP eSign v5 authenticates its callback with a
--    `webhook-security-key` HTTP header whose value is issued PER TRANSACTION by `/v5/init`.
--    That value is a CREDENTIAL, not metadata: anyone holding it can present it for that
--    transaction. It is therefore stored ENCRYPTED at rest (AES-GCM, application-side; the column
--    holds base64(iv||ciphertext), never the plaintext), never logged, and compared in constant
--    time (design D3). Nullable: the Leegality adapter authenticates with a body MAC over a
--    config-wide secret and stores nothing here, and every pre-existing row has no key.
--
-- 2. PAYMENT GATE. Payment state plus the vendor-neutral confirmation seam (amount, currency,
--    external reference, actor, time). No payment gateway is introduced by this change - only the
--    gate and its state (design D5). `WAIVED` is deliberately a separate value from `PAID`:
--    collapsing them would destroy the ability to answer "how much money came in" from the same
--    field that answers "may this proceed".
--
-- PII: nothing here is party PII. The payment reference is a vendor payment id, the actor is a
-- staff identity id. The webhook key is a secret and is encrypted before it ever reaches this
-- column.

ALTER TABLE signing_request
    ADD COLUMN webhook_security_key TEXT;

-- Payment state is server-managed and NOT NULL with a default, so every existing row validates
-- immediately as `UNPAID` and no read has to treat NULL as a fourth, undefined state. The rest of
-- the confirmation seam is nullable - an unpaid agreement has no amount, reference, actor or time.
ALTER TABLE agreement
    ADD COLUMN payment_state             VARCHAR(16) NOT NULL DEFAULT 'UNPAID',
    ADD COLUMN payment_amount            NUMERIC(12, 2),
    ADD COLUMN payment_currency          VARCHAR(3),
    ADD COLUMN payment_reference         VARCHAR(128),
    ADD COLUMN payment_actor_identity_id UUID REFERENCES identity (id),
    ADD COLUMN payment_recorded_at       TIMESTAMPTZ;

-- One external payment may be recorded against at most one agreement. Indexed on the NORMALISED
-- value so casing/spacing variants collide as they must. This is the race backstop: the
-- application never pre-checks, it catches the constraint violation and maps it to 409, so two
-- concurrent confirmations of the same payment reference cannot both succeed.
CREATE UNIQUE INDEX uq_agreement_payment_reference
    ON agreement (UPPER(BTRIM(payment_reference)))
    WHERE payment_reference IS NOT NULL;
