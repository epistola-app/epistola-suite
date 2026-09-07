-- backup-restore-compatibility: backward=false forward=false
-- reason: Re-keys stencils onto their identity and drops the parent address from stencil_versions.
-- A backup taken either side carries a different column set for two backed-up tables.

-- Stencils keyed by identity
--
-- A stencil owns its versions and is named from template and stencil content. The versions follow
-- it, so they name the stencil itself; content keeps naming an address -- that is what travels in
-- an export -- and resolves through the alias when the stencil has moved.
--
-- The version's copy of the parent address goes with it. Two columns describing where the parent
-- lives, alongside a pointer to the parent, is a fact stated twice: nothing forced them to agree,
-- and roughly ten queries filtered on the copy rather than the pointer. They read through the
-- parent now, so there is one statement of where a stencil lives and it is the stencil's own row.
--
-- ON DELETE is preserved exactly: deleting a stencil deletes its versions.

-- 1. Add ------------------------------------------------------------------------------------------
ALTER TABLE stencil_versions ADD COLUMN stencil_resource_id UUID;

-- 2. Backfill, asserting every version found its parent ---------------------------------------------
UPDATE stencil_versions versions
SET stencil_resource_id = stencils.resource_id
FROM stencils
WHERE stencils.tenant_key = versions.tenant_key
  AND stencils.catalog_key = versions.catalog_key
  AND stencils.id = versions.stencil_key;

DO $$
DECLARE unmatched BIGINT;
BEGIN
    SELECT count(*) INTO unmatched FROM stencil_versions WHERE stencil_resource_id IS NULL;
    IF unmatched > 0 THEN
        RAISE EXCEPTION '% stencil versions belong to a stencil that does not exist', unmatched;
    END IF;
END $$;

ALTER TABLE stencil_versions ALTER COLUMN stencil_resource_id SET NOT NULL;

-- 3. Release the address ----------------------------------------------------------------------------
-- The versions' foreign key holds the stencils primary key in place, so it lets go before the swap.
ALTER TABLE stencil_versions
    DROP CONSTRAINT stencil_versions_tenant_key_catalog_key_stencil_key_fkey;

-- 4. Swap the key -----------------------------------------------------------------------------------
ALTER TABLE stencils
    DROP CONSTRAINT stencils_pkey,
    ADD CONSTRAINT stencils_pkey PRIMARY KEY (tenant_key, resource_id),
    ADD CONSTRAINT uq_stencils_address UNIQUE (tenant_key, catalog_key, id);

-- 5. Reference by identity, and drop the address copies -----------------------------------------------
-- A version is identified by its stencil and its number, which is what the old key said through the
-- stencil's address.
ALTER TABLE stencil_versions
    DROP CONSTRAINT stencil_versions_pkey,
    ADD CONSTRAINT stencil_versions_pkey PRIMARY KEY (tenant_key, stencil_resource_id, id),
    DROP COLUMN catalog_key,
    DROP COLUMN stencil_key,
    ADD CONSTRAINT fk_stencil_versions_parent
        FOREIGN KEY (tenant_key, stencil_resource_id)
        REFERENCES stencils(tenant_key, resource_id)
        ON DELETE CASCADE;

-- The at-most-one-draft rule was stated over the parent's address; it is stated over the parent.
CREATE UNIQUE INDEX idx_one_draft_per_stencil
    ON stencil_versions (tenant_key, stencil_resource_id)
    WHERE status = 'draft';

COMMENT ON COLUMN stencil_versions.stencil_resource_id IS
    'Identity of the stencil this version belongs to. Replaces the parent''s address, so a stencil move leaves its versions untouched.';
