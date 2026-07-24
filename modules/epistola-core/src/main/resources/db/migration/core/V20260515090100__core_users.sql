-- Users table for authentication
-- Created early so that domain tables (V5-V9) can reference users for audit columns.
--
-- Supports OAuth2/OIDC providers (Keycloak, etc.) and in-memory users for local development.
-- IDs are UUIDv7 for users, matching existing document generation patterns.

CREATE TABLE users (
    id UUID PRIMARY KEY,
    external_id VARCHAR(255) NOT NULL,     -- OAuth2 "sub" claim or local username
    email VARCHAR(320) NOT NULL,            -- RFC 5321 max length
    display_name VARCHAR(255) NOT NULL,
    provider VARCHAR(50) NOT NULL REFERENCES auth_providers(id),
    enabled BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_login_at TIMESTAMPTZ,
    CONSTRAINT users_external_id_provider_unique UNIQUE (external_id, provider)
);

CREATE INDEX idx_users_email ON users(email);
-- external_id lookups are served by the leftmost prefix of the
-- users_external_id_provider_unique (external_id, provider) index.

COMMENT ON TABLE users IS 'User accounts with global identity across tenants';
COMMENT ON COLUMN users.id IS 'UUIDv7 primary key (the all-zeros UUID is the reserved system principal — see seed below)';
COMMENT ON COLUMN users.external_id IS 'User ID from OAuth2 provider (sub claim) or local username';
COMMENT ON COLUMN users.email IS 'User email address, RFC 5321 compliant (max 320 chars)';
COMMENT ON COLUMN users.display_name IS 'Human-readable name shown in the UI';
COMMENT ON COLUMN users.provider IS 'Authentication provider — FK to auth_providers(id) (KEYCLOAK, LOCAL, GENERIC_OIDC, API_KEY; extend by inserting a row)';
COMMENT ON COLUMN users.enabled IS 'Whether the user can log in';
COMMENT ON COLUMN users.created_at IS 'When the user account was created';
COMMENT ON COLUMN users.last_login_at IS 'Most recent successful login timestamp';

-- The single well-known system principal that owns every system-initiated
-- write: background document generation, demo bootstrap, the demo login
-- resolver, and system-catalog install/upgrade. Audit FKs (created_by /
-- updated_by) reference users(id), so this identity must always exist —
-- seeding it here makes it a database invariant (same approach as the
-- 'installation' row in the app_metadata baseline). The all-zeros UUID is the
-- conventional synthetic system id. Keep in sync with
-- app.epistola.suite.security.SystemUser.
INSERT INTO users (id, external_id, email, display_name, provider, enabled, created_at)
VALUES ('00000000-0000-0000-0000-000000000000', 'system', 'system@epistola.app', 'System', 'LOCAL', true, NOW());
