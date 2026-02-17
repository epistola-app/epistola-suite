-- Domain type for environment identifiers (slugs)
CREATE DOMAIN ENVIRONMENT_ID AS VARCHAR(30)
    CHECK (VALUE ~ '^[a-z][a-z0-9]*(-[a-z0-9]+)*$');

-- Tenant environments (staging, production, etc.)
-- Uses composite PK (tenant_id, id) consistent with all other tenant-scoped tables.
CREATE TABLE environments (
    id ENVIRONMENT_ID NOT NULL,
    tenant_id TENANT_ID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name VARCHAR(100) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    created_by UUID REFERENCES users(id),
    last_modified_by UUID REFERENCES users(id),
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, name)
);

COMMENT ON TABLE environments IS 'Deployment targets for template versions (e.g., staging, production). Each tenant defines its own set.';
COMMENT ON COLUMN environments.id IS 'URL-safe slug identifier unique within the tenant';
COMMENT ON COLUMN environments.tenant_id IS 'Owning tenant';
COMMENT ON COLUMN environments.name IS 'Human-readable display name, unique within tenant';
COMMENT ON COLUMN environments.created_at IS 'When the environment was created';
COMMENT ON COLUMN environments.created_by IS 'User who created this environment';
COMMENT ON COLUMN environments.last_modified_by IS 'User who last modified this environment';
