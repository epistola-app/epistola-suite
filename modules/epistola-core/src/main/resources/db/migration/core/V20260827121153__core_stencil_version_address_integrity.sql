-- V20260825090100 replaced stencil_versions' parent foreign key with one on the stable
-- (tenant_key, stencil_resource_id), but left catalog_key and stencil_key behind as unconstrained
-- copies of the parent's address. Roughly ten queries still filter on them and relocation updated
-- them by hand, so nothing but that hand-written statement kept them true.
--
-- Restore the address foreign key alongside the stable one, with ON UPDATE CASCADE so a relocation
-- that moves the parent carries its versions along. Drift becomes impossible rather than merely
-- unlikely, and relocation no longer needs to update the copies itself.

-- Defensive: realign any row that drifted while the constraint was absent. Expected to be a no-op.
UPDATE stencil_versions versions
SET catalog_key = stencils.catalog_key,
    stencil_key = stencils.id
FROM stencils
WHERE stencils.tenant_key = versions.tenant_key
  AND stencils.resource_id = versions.stencil_resource_id
  AND (stencils.catalog_key <> versions.catalog_key OR stencils.id <> versions.stencil_key);

ALTER TABLE stencil_versions
    ADD CONSTRAINT fk_stencil_versions_parent_address
        FOREIGN KEY (tenant_key, catalog_key, stencil_key)
        REFERENCES stencils(tenant_key, catalog_key, id)
        ON UPDATE CASCADE
        ON DELETE CASCADE;

COMMENT ON CONSTRAINT fk_stencil_versions_parent_address ON stencil_versions IS
    'Keeps the denormalised parent address true. ON UPDATE CASCADE carries versions along when a relocation moves the stencil; fk_stencil_versions_stable_parent owns identity.';
