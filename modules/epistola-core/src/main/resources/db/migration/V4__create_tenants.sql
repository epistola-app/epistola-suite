-- Domain type for tenant identifiers (slugs)
CREATE DOMAIN TENANT_KEY AS VARCHAR(63)
    CHECK (VALUE ~ '^[a-z][a-z0-9]*(-[a-z0-9]+)*$');

-- Domain type for catalog identifiers (slugs) — used by all resource tables from V5 onward
CREATE DOMAIN CATALOG_KEY AS VARCHAR(50)
    CHECK (VALUE ~ '^[a-z][a-z0-9]*(-[a-z0-9]+)*$');

-- Tenants table for multi-tenancy support
-- IDs are client-provided slugs (e.g., "acme-corp") for human-readable, URL-safe identifiers
CREATE TABLE tenants (
    id TENANT_KEY PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    default_theme_catalog_key CATALOG_KEY, -- FK added in V5 after themes table exists
    default_theme_key VARCHAR(20),  -- FK added in V5 after themes table exists
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_tenants_name ON tenants(name);

COMMENT ON TABLE tenants IS 'Top-level organizational units for multi-tenancy. Each tenant has isolated data.';
COMMENT ON COLUMN tenants.id IS 'URL-safe slug identifier (e.g., acme-corp)';
COMMENT ON COLUMN tenants.name IS 'Human-readable display name';
COMMENT ON COLUMN tenants.default_theme_key IS 'Fallback theme applied when templates and variants do not specify one. FK added in V5.';
COMMENT ON COLUMN tenants.created_at IS 'When the tenant was created';

-- ============================================================================
-- TENANT MEMBERSHIPS
-- ============================================================================

-- Tenant memberships (many-to-many between users and tenants)
-- Roles are sourced from Keycloak (JWT claim) and synced to the DB
-- for offline queries, audit trails, and API key fallback.
CREATE TABLE tenant_memberships (
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    tenant_key TENANT_KEY NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    roles VARCHAR(20)[] NOT NULL DEFAULT ARRAY['READER']::VARCHAR[],
    joined_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    last_synced_at TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (user_id, tenant_key)
);

CREATE INDEX idx_tenant_memberships_user_id ON tenant_memberships(user_id);
CREATE INDEX idx_tenant_memberships_tenant_key ON tenant_memberships(tenant_key);

COMMENT ON TABLE tenant_memberships IS 'Many-to-many link between users and tenants they can access';
COMMENT ON COLUMN tenant_memberships.user_id IS 'FK to users.id';
COMMENT ON COLUMN tenant_memberships.tenant_key IS 'FK to tenants.id';
COMMENT ON COLUMN tenant_memberships.roles IS 'Composable tenant roles: READER, EDITOR, GENERATOR, MANAGER. Synced from Keycloak JWT claim.';
COMMENT ON COLUMN tenant_memberships.joined_at IS 'When the user was granted access to this tenant';
COMMENT ON COLUMN tenant_memberships.last_synced_at IS 'When this membership was last confirmed from the IDP (JWT claim sync).';

-- ============================================================================
-- CATALOGS
-- ============================================================================

-- Catalogs are organizational containers for resources. Every resource belongs to exactly one catalog.
-- Authored catalogs are created locally; subscribed catalogs track an external source.
CREATE TABLE catalogs (
    id CATALOG_KEY NOT NULL,
    tenant_key TENANT_KEY NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    type VARCHAR(20) NOT NULL CHECK (type IN ('AUTHORED', 'SUBSCRIBED')),
    mutability VARCHAR(20) NOT NULL DEFAULT 'EDITABLE' CHECK (mutability IN ('EDITABLE', 'READ_ONLY')),
    source_url TEXT,
    source_auth_type VARCHAR(20) DEFAULT 'NONE' CHECK (source_auth_type IN ('NONE', 'API_KEY', 'BEARER')),
    source_auth_credential TEXT,
    installed_release_version VARCHAR(50),
    installed_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    last_modified TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    PRIMARY KEY (tenant_key, id)
);

COMMENT ON TABLE catalogs IS 'Organizational containers for resources. Every resource belongs to exactly one catalog.';
COMMENT ON COLUMN catalogs.type IS 'AUTHORED = created locally and editable, SUBSCRIBED = installed from external source and read-only';
COMMENT ON COLUMN catalogs.source_url IS 'Remote catalog manifest URL (SUBSCRIBED only)';
COMMENT ON COLUMN catalogs.installed_release_version IS 'Version of the currently installed release (SUBSCRIBED only)';
