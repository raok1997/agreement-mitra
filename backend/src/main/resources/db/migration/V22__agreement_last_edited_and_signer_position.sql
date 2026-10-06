-- V22: last-edit time on the agreement and a stored entry order for its parties
-- (change my-agreements-rich-rows, D1-D3).
--
-- last_edited_at. When the agreement's content was last changed through the drafting surface
-- (create, edit of terms or parties, contacts edit, draft attach). Set explicitly by the service,
-- never by a JPA hook, so payment, stamping, signing, claim and closure do not move it. Existing
-- rows start at created_at.
--
-- entry_position. A party's 0-based position in the order it was entered. Without it, reads came
-- back in heap order, which a contacts edit (an UPDATE writes a new row version) silently changes.
-- There is no record of the true entry order, so the backfill puts owners first and keeps the
-- current heap order within a role - the order every existing reader already sees for a role, so
-- the signing flow's within-role order is the same before and after this migration.
--
-- DEFAULTS exist only so the previous build, which does not write these columns, can still insert
-- if the release is rolled back. The new build always writes both. No unique (agreement_id,
-- entry_position): Hibernate flushes a parties edit's inserts before its orphan deletes.
--
-- NO PERSONAL DATA. A timestamp and an integer.
ALTER TABLE agreement ADD COLUMN last_edited_at TIMESTAMP WITH TIME ZONE;
UPDATE agreement SET last_edited_at = created_at;
ALTER TABLE agreement ALTER COLUMN last_edited_at SET NOT NULL,
                      ALTER COLUMN last_edited_at SET DEFAULT now();

ALTER TABLE signer ADD COLUMN entry_position INT;
UPDATE signer s SET entry_position = r.pos
  FROM (SELECT id, row_number() OVER (PARTITION BY agreement_id
          ORDER BY CASE role WHEN 'OWNER' THEN 0 ELSE 1 END, ctid) - 1 AS pos
        FROM signer) r
 WHERE s.id = r.id;
ALTER TABLE signer ALTER COLUMN entry_position SET NOT NULL,
                   ALTER COLUMN entry_position SET DEFAULT 0;
