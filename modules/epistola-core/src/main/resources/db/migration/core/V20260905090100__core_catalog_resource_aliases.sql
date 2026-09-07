-- Historical public addresses preserved after a catalog resource moves.
-- The source catalog intentionally has no FK: an old address remains resolvable
-- and reserved even when that catalog is later removed.
CREATE TABLE catalog_resource_aliases (
    tenant_key TENANT_KEY NOT NULL,
    resource_type VARCHAR(20) NOT NULL,
    catalog_key CATALOG_KEY NOT NULL,
    resource_key TEXT NOT NULL,
    target_resource_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (tenant_key, resource_type, catalog_key, resource_key),
    FOREIGN KEY (tenant_key, target_resource_id, resource_type)
        REFERENCES catalog_resources(tenant_key, resource_id, resource_type)
        ON DELETE CASCADE
);

COMMENT ON TABLE catalog_resource_aliases IS
    'Tenant-local historical resource addresses. Each alias points directly to the current stable identity.';
