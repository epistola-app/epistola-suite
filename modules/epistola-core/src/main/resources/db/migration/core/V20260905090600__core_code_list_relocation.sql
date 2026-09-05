-- Code list relocation
--
-- A code list's address is still its primary key, so moving one means updating
-- (catalog_key, slug) in place. Everything that keys on that address follows by
-- database rule rather than by the move command touching each table:
--
--   code_list_entries              -- owned rows, follow their parent
--   variant_attribute_definitions  -- a binding to a code list in any catalog
--
-- ON UPDATE CASCADE fires on any referenced column, so a rename carries the
-- entries too, not just a catalog change. This is the same interim shape
-- templates are in (V20260905090400) rather than the target model: the address
-- leaves those keys once the type is re-keyed onto resource_id, per
-- docs/catalog-resource-identity-migration.md.
--
-- ON DELETE is preserved exactly: CASCADE for owned entries, RESTRICT for the
-- attribute binding, which is what stops a bound code list being deleted.

ALTER TABLE code_list_entries
    DROP CONSTRAINT code_list_entries_tenant_key_catalog_key_code_list_slug_fkey,
    ADD CONSTRAINT code_list_entries_tenant_key_catalog_key_code_list_slug_fkey
        FOREIGN KEY (tenant_key, catalog_key, code_list_slug)
        REFERENCES code_lists(tenant_key, catalog_key, slug)
        ON UPDATE CASCADE ON DELETE CASCADE;

ALTER TABLE variant_attribute_definitions
    DROP CONSTRAINT attr_code_list_fk,
    ADD CONSTRAINT attr_code_list_fk
        FOREIGN KEY (tenant_key, code_list_catalog_key, code_list_slug)
        REFERENCES code_lists(tenant_key, catalog_key, slug)
        ON UPDATE CASCADE ON DELETE RESTRICT;
