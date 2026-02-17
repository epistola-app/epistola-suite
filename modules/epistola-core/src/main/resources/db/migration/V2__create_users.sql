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
    provider VARCHAR(50) NOT NULL,          -- KEYCLOAK, LOCAL, GENERIC_OIDC
    enabled BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    last_login_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT users_external_id_provider_unique UNIQUE (external_id, provider)
);

CREATE INDEX idx_users_email ON users(email);
CREATE INDEX idx_users_external_id ON users(external_id);

COMMENT ON TABLE users IS 'User accounts with global identity across tenants';
COMMENT ON COLUMN users.id IS 'UUIDv7 primary key';
COMMENT ON COLUMN users.external_id IS 'User ID from OAuth2 provider (sub claim) or local username';
COMMENT ON COLUMN users.email IS 'User email address, RFC 5321 compliant (max 320 chars)';
COMMENT ON COLUMN users.display_name IS 'Human-readable name shown in the UI';
COMMENT ON COLUMN users.provider IS 'Authentication provider: KEYCLOAK, LOCAL, or GENERIC_OIDC';
COMMENT ON COLUMN users.enabled IS 'Whether the user can log in';
COMMENT ON COLUMN users.created_at IS 'When the user account was created';
COMMENT ON COLUMN users.last_login_at IS 'Most recent successful login timestamp';
