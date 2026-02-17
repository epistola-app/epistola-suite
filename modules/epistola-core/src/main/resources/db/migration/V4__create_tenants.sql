-- Domain type for tenant identifiers (slugs)
CREATE DOMAIN TENANT_ID AS VARCHAR(63)
    CHECK (VALUE ~ '^[a-z][a-z0-9]*(-[a-z0-9]+)*$');

-- Tenants table for multi-tenancy support
-- IDs are client-provided slugs (e.g., "acme-corp") for human-readable, URL-safe identifiers
CREATE TABLE tenants (
    id TENANT_ID PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    default_theme_id VARCHAR(20),  -- FK added in V5 after themes table exists
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_tenants_name ON tenants(name);

COMMENT ON TABLE tenants IS 'Top-level organizational units for multi-tenancy. Each tenant has isolated data.';
COMMENT ON COLUMN tenants.id IS 'URL-safe slug identifier (e.g., acme-corp)';
COMMENT ON COLUMN tenants.name IS 'Human-readable display name';
COMMENT ON COLUMN tenants.default_theme_id IS 'Fallback theme applied when templates and variants do not specify one. FK added in V5.';
COMMENT ON COLUMN tenants.created_at IS 'When the tenant was created';

-- ============================================================================
-- TENANT MEMBERSHIPS
-- ============================================================================

-- Tenant memberships (many-to-many between users and tenants)
CREATE TABLE tenant_memberships (
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    tenant_id TENANT_ID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    joined_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    PRIMARY KEY (user_id, tenant_id)
);

CREATE INDEX idx_tenant_memberships_user_id ON tenant_memberships(user_id);
CREATE INDEX idx_tenant_memberships_tenant_id ON tenant_memberships(tenant_id);

COMMENT ON TABLE tenant_memberships IS 'Many-to-many link between users and tenants they can access';
COMMENT ON COLUMN tenant_memberships.user_id IS 'FK to users.id';
COMMENT ON COLUMN tenant_memberships.tenant_id IS 'FK to tenants.id';
COMMENT ON COLUMN tenant_memberships.joined_at IS 'When the user was granted access to this tenant';
