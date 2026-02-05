-- V8: Users and Tenant Memberships
--
-- This migration adds authentication support with:
-- - Users table for global user accounts
-- - Tenant memberships for user access control
-- - Support for OAuth2/OIDC providers (Keycloak, etc.)
-- - In-memory users for local development
--
-- IDs are UUIDv7 for users, matching existing document generation patterns

-- ============================================================================
-- USERS TABLE
-- ============================================================================

-- Users table for authentication
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

COMMENT ON COLUMN users.external_id IS 'User ID from OAuth2 provider (sub claim) or local username';
COMMENT ON COLUMN users.email IS 'User email address (used for display and notifications)';
COMMENT ON COLUMN users.provider IS 'Authentication provider: KEYCLOAK, LOCAL, GENERIC_OIDC';
COMMENT ON TABLE users IS 'User accounts with global identity across tenants';

-- ============================================================================
-- TENANT MEMBERSHIPS TABLE
-- ============================================================================

-- Tenant memberships (many-to-many)
CREATE TABLE tenant_memberships (
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    tenant_id VARCHAR(63) NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    joined_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    PRIMARY KEY (user_id, tenant_id)
);

CREATE INDEX idx_tenant_memberships_user_id ON tenant_memberships(user_id);
CREATE INDEX idx_tenant_memberships_tenant_id ON tenant_memberships(tenant_id);

COMMENT ON TABLE tenant_memberships IS 'User access to tenants (prepared for future role-based access control)';
COMMENT ON COLUMN tenant_memberships.joined_at IS 'When the user was granted access to this tenant';
