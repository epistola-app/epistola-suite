-- backup-restore-compatibility: backward=true forward=true
-- reason: Adds a table nothing else reads. A backup from either side restores into the other; an
-- older suite simply never writes an alias.

-- Historical public addresses preserved after a catalog resource moves.
--
-- The source catalog intentionally has no foreign key, so an alias survives its catalog at the
-- database level. It is not left behind on purpose: UnregisterCatalog deletes the aliases pointing
-- out of a catalog it removes, because an alias with no page to release it from would keep the
-- address reserved against a catalog registered later under the same key. The absent FK is what
-- makes that a decision the application takes rather than one the database takes for it -- an
-- alias survives a *resource* being deleted, which the FK on the target already handles.
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
