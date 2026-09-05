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

-- Stencil versions are the first owned hierarchy migrated to stable parent identity.
-- Legacy address columns remain for backwards-compatible reads during the alpha.
ALTER TABLE stencil_versions ADD COLUMN stencil_resource_id UUID;

UPDATE stencil_versions versions
SET stencil_resource_id = stencils.resource_id
FROM stencils
WHERE stencils.tenant_key = versions.tenant_key
  AND stencils.catalog_key = versions.catalog_key
  AND stencils.id = versions.stencil_key;

ALTER TABLE stencil_versions ALTER COLUMN stencil_resource_id SET NOT NULL;
ALTER TABLE stencil_versions
    DROP CONSTRAINT stencil_versions_tenant_key_catalog_key_stencil_key_fkey,
    ADD CONSTRAINT fk_stencil_versions_stable_parent
        FOREIGN KEY (tenant_key, stencil_resource_id)
        REFERENCES stencils(tenant_key, resource_id)
        ON DELETE CASCADE;

CREATE INDEX idx_stencil_versions_stable_parent
    ON stencil_versions(tenant_key, stencil_resource_id);
