-- backup-restore-compatibility: backward=false forward=false
-- reason: Re-keys code_lists onto its identity and drops the denormalised address from
-- code_list_entries and variant_attribute_definitions. A backup taken either side of this
-- carries a different column set for three backed-up tables.

-- Code lists keyed by identity
--
-- The address (catalog_key, slug) stops being the key and becomes what it is: a mutable,
-- human-readable name. Dependants reference the code list by resource_id, so moving or renaming
-- one is a single-row update with nothing to cascade and nothing to rewrite.
--
-- Queries that need the address compute it by joining code_lists, which is what keeps the public
-- shape (catalog + slug) unchanged for REST, export and the UI while storage stops repeating it.

-- 1. Add ---------------------------------------------------------------------------------------
ALTER TABLE code_list_entries ADD COLUMN code_list_resource_id UUID;
ALTER TABLE variant_attribute_definitions ADD COLUMN code_list_resource_id UUID;

-- 2. Backfill, asserting every row was matched ---------------------------------------------------
UPDATE code_list_entries entries
SET code_list_resource_id = lists.resource_id
FROM code_lists lists
WHERE lists.tenant_key = entries.tenant_key
  AND lists.catalog_key = entries.catalog_key
  AND lists.slug = entries.code_list_slug;

DO $$
DECLARE unmatched BIGINT;
BEGIN
    SELECT count(*) INTO unmatched FROM code_list_entries WHERE code_list_resource_id IS NULL;
    IF unmatched > 0 THEN
        RAISE EXCEPTION '% code_list_entries rows did not match a code list', unmatched;
    END IF;
END $$;

UPDATE variant_attribute_definitions attributes
SET code_list_resource_id = lists.resource_id
FROM code_lists lists
WHERE lists.tenant_key = attributes.tenant_key
  AND lists.catalog_key = attributes.code_list_catalog_key
  AND lists.slug = attributes.code_list_slug;

DO $$
DECLARE unmatched BIGINT;
BEGIN
    SELECT count(*) INTO unmatched FROM variant_attribute_definitions
    WHERE code_list_slug IS NOT NULL AND code_list_resource_id IS NULL;
    IF unmatched > 0 THEN
        RAISE EXCEPTION '% bound attributes did not match a code list', unmatched;
    END IF;
END $$;

-- 3. Constrain ----------------------------------------------------------------------------------
ALTER TABLE code_list_entries ALTER COLUMN code_list_resource_id SET NOT NULL;

-- 4. Release the address ------------------------------------------------------------------------
-- Both dependants reference code_lists by its address, so they hold the primary key in place and
-- have to let go before it can be swapped.
ALTER TABLE code_list_entries
    DROP CONSTRAINT code_list_entries_tenant_key_catalog_key_code_list_slug_fkey;
ALTER TABLE variant_attribute_definitions
    DROP CONSTRAINT attr_code_list_fk;

-- 5. Swap the key -------------------------------------------------------------------------------
-- The address keeps its uniqueness; it is simply no longer what anything references.
ALTER TABLE code_lists
    DROP CONSTRAINT code_lists_pkey,
    ADD CONSTRAINT code_lists_pkey PRIMARY KEY (tenant_key, resource_id),
    ADD CONSTRAINT uq_code_lists_address UNIQUE (tenant_key, catalog_key, slug);

-- 6. Reference by identity, and drop the address copies -------------------------------------------
ALTER TABLE code_list_entries
    DROP CONSTRAINT code_list_entries_pkey,
    ADD CONSTRAINT code_list_entries_pkey PRIMARY KEY (tenant_key, code_list_resource_id, code),
    ADD CONSTRAINT code_list_entries_code_list_fkey
        FOREIGN KEY (tenant_key, code_list_resource_id)
        REFERENCES code_lists(tenant_key, resource_id) ON DELETE CASCADE,
    DROP COLUMN catalog_key,
    DROP COLUMN code_list_slug;

DROP INDEX IF EXISTS code_list_entries_visible;
CREATE INDEX code_list_entries_visible
    ON code_list_entries(tenant_key, code_list_resource_id, sort_order, code)
    WHERE NOT hidden;

ALTER TABLE variant_attribute_definitions
    DROP CONSTRAINT attr_code_list_columns_consistent,
    DROP CONSTRAINT attr_constraint_kind_xor,
    DROP COLUMN code_list_catalog_key,
    DROP COLUMN code_list_slug,
    -- RESTRICT preserved: it is what stops a bound code list being deleted.
    ADD CONSTRAINT attr_code_list_fk
        FOREIGN KEY (tenant_key, code_list_resource_id)
        REFERENCES code_lists(tenant_key, resource_id) ON DELETE RESTRICT,
    -- One column now, so "consistent" has nothing left to state; the XOR still does.
    ADD CONSTRAINT attr_constraint_kind_xor
        CHECK (code_list_resource_id IS NULL OR jsonb_array_length(allowed_values) = 0);

COMMENT ON COLUMN variant_attribute_definitions.code_list_resource_id IS
    'Identity of the bound code list, in any catalog of the same tenant; NULL when free-format or using inline allowed_values.';
COMMENT ON COLUMN code_list_entries.code_list_resource_id IS
    'Identity of the owning code list. The address it lives at is read from code_lists when needed.';
