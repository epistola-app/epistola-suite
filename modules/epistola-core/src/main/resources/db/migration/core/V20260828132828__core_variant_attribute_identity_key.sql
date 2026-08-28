-- First table re-keyed onto its stable identity, per
-- docs/catalog-resource-identity-migration.md.
--
-- variant_attribute_definitions is the simplest case in the suite: nothing holds a foreign key to
-- it, and it owns no child tables. That makes it the right place to prove the recipe before a table
-- with dependants relies on the pattern being correct.
--
-- After this, (tenant_key, resource_id) identifies the attribute and (tenant_key, catalog_key, id)
-- is merely its current address -- unique, but no longer identity. Moving the attribute to another
-- catalog becomes an ordinary column update.

ALTER TABLE variant_attribute_definitions
    -- Redundant once (tenant_key, resource_id) is the primary key.
    DROP CONSTRAINT uq_variant_attributes_resource_id,
    DROP CONSTRAINT variant_attribute_definitions_pkey,
    ADD CONSTRAINT variant_attribute_definitions_pkey PRIMARY KEY (tenant_key, resource_id),
    -- The address stays unambiguous; it is just not what identifies the row any more.
    ADD CONSTRAINT uq_variant_attributes_address UNIQUE (tenant_key, catalog_key, id);

COMMENT ON COLUMN variant_attribute_definitions.resource_id IS
    'Stable identity. Survives a move between catalogs; never exported.';
COMMENT ON COLUMN variant_attribute_definitions.catalog_key IS
    'Current catalog. Mutable: a relocation updates it and the address changes with it.';
