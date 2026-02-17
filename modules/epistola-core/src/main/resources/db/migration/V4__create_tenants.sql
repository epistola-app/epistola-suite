-- Tenants table for multi-tenancy support
-- IDs are client-provided slugs (e.g., "acme-corp") for human-readable, URL-safe identifiers
CREATE TABLE tenants (
    id VARCHAR(63) PRIMARY KEY
        CHECK (id ~ '^[a-z][a-z0-9]*(-[a-z0-9]+)*$'),
    name VARCHAR(255) NOT NULL,
    default_theme_id VARCHAR(20),  -- FK added in V5 after themes table exists
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_tenants_name ON tenants(name);

-- ============================================================================
-- TENANT MEMBERSHIPS
-- ============================================================================

-- Tenant memberships (many-to-many between users and tenants)
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
