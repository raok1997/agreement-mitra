-- V24: bind a Google login to the browser that started it (change login-browser-binding, D2/D7).
--
-- browser_binding_hash is the keyed hash of the login-binding nonce carried in the browser's
-- HttpOnly login cookie. Each consume UPDATE matches on it, so a callback or exchange from any
-- other browser updates no row.
--
-- NULLABLE ON PURPOSE -- do not tighten to NOT NULL with a default. The column is nullable so a
-- rollback to the previous build (which inserts rows without it) keeps working. The security
-- invariant does not depend on the constraint: a NULL never equals the presented hash, so a row
-- without a binding (including every row written before this migration) can never be consumed.
-- Tightening to NOT NULL, once rollback past this migration is off the table, belongs with
-- auth-expired-row-purge.
ALTER TABLE oauth_login_state ADD COLUMN browser_binding_hash TEXT NULL;
ALTER TABLE login_handoff ADD COLUMN browser_binding_hash TEXT NULL;
