-- V11 -- identity + Google OAuth login (google-oauth-login CR / CR-A).
--
-- Activates the previously-stub `identity` module with the login layer ONLY: a provider-agnostic
-- Identity aggregate carrying one or more IdentityCredential rows (Google first), an opaque
-- server-side hashed session, and the two single-use short-lived handshake rows (login state +
-- handoff). NO agreement behaviour changes in this CR -- the `agreement.owner_identity_id` column
-- is CR-B's V12 and is deliberately NOT added here.
--
-- Forward-only: never edits V1..V10; does NOT touch the `agreement` table.
--
-- PII/secret hygiene: only a display name + email are stored to render the identity; NO Aadhaar,
-- OTP, VID, biometric, or government id. The session value, handoff, PKCE state, and (implicitly)
-- the raw tokens are NEVER persisted -- only SHA-256/HMAC hashes of the state/handoff/session are
-- kept. The PKCE `code_verifier` is a short-lived per-login secret, deleted-by-consume semantics
-- (consumed_at set), plaintext-at-rest is acceptable for the sandbox given the ~60-90s TTL.
--
-- Column types match the JPA mapping so `ddl-auto: validate` passes (UUID -> uuid, String -> text,
-- boolean -> boolean, Instant -> timestamptz).

CREATE TABLE identity (
  id           UUID        PRIMARY KEY,
  display_name TEXT,
  email        TEXT,
  created_at   TIMESTAMPTZ NOT NULL
);

CREATE TABLE identity_credential (
  id               UUID        PRIMARY KEY,
  identity_id      UUID        NOT NULL REFERENCES identity(id),
  provider         TEXT        NOT NULL,
  provider_subject TEXT        NOT NULL,
  email            TEXT,
  email_verified   BOOLEAN     NOT NULL DEFAULT FALSE,
  created_at       TIMESTAMPTZ NOT NULL,
  -- One credential per external subject: find-or-create keys on this pair. A second provider
  -- (e.g. MOBILE) can attach another credential row to the same identity later.
  CONSTRAINT uq_identity_credential_provider_subject UNIQUE (provider, provider_subject)
);

CREATE INDEX idx_identity_credential_identity_id ON identity_credential (identity_id);

CREATE TABLE auth_session (
  id           UUID        PRIMARY KEY,
  identity_id  UUID        NOT NULL REFERENCES identity(id),
  -- Only the hash of the opaque 256-bit session value is stored; the value itself is returned to
  -- the client exactly once at exchange and never persisted. UNIQUE so a presented value resolves
  -- to at most one live session.
  value_hash   TEXT        NOT NULL UNIQUE,
  created_at   TIMESTAMPTZ NOT NULL,
  last_seen_at TIMESTAMPTZ,
  expires_at   TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_auth_session_identity_id ON auth_session (identity_id);

CREATE TABLE oauth_login_state (
  id            UUID        PRIMARY KEY,
  -- Hash of the random OAuth `state` (never the raw value). The PKCE `code_verifier` is a
  -- short-lived per-login secret, kept plaintext for the sandbox and consumed within seconds.
  state_hash    TEXT        NOT NULL UNIQUE,
  code_verifier TEXT        NOT NULL,
  redirect_uri  TEXT,
  created_at    TIMESTAMPTZ NOT NULL,
  expires_at    TIMESTAMPTZ NOT NULL,
  consumed_at   TIMESTAMPTZ
);

CREATE TABLE login_handoff (
  id           UUID        PRIMARY KEY,
  -- Hash of the single-use handoff code delivered in the SPA redirect. Carries NO session
  -- material; exchanged once for an opaque session, then consumed.
  handoff_hash TEXT        NOT NULL UNIQUE,
  identity_id  UUID        NOT NULL REFERENCES identity(id),
  created_at   TIMESTAMPTZ NOT NULL,
  expires_at   TIMESTAMPTZ NOT NULL,
  consumed_at  TIMESTAMPTZ
);

CREATE INDEX idx_login_handoff_identity_id ON login_handoff (identity_id);
