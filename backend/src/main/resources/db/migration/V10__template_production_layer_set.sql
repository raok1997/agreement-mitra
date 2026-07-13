-- V10 -- repoint the seeded catalog rows at the production rental layer set.
--
-- The template-catalog seeder (local/sandbox) inserts the National + Telangana rows only when the
-- `template` table is empty, so an already-seeded database keeps the old fixture pointer. This
-- forward-only data migration repoints any row still pointing at the retired reference/fixture layer
-- set (`documents/template/examples/layers/`) to the production set (`documents/template/sets/rental/`)
-- so a running instance renders the production template without a manual re-seed.
--
-- Idempotent by construction: it matches on the old pointer, so on a fresh database (rows not yet
-- inserted, or already inserted with the new pointer) it updates zero rows and is a no-op. The
-- bodies stay classpath resources -- only the pointer moves. Never edits V1..V9.

UPDATE template
   SET layer_set_ref = 'documents/template/sets/rental/'
 WHERE layer_set_ref = 'documents/template/examples/layers/';
