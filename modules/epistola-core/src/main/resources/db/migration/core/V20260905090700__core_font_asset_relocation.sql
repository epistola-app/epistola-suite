-- backup-restore-compatibility: backward=false forward=false
-- reason: Re-keys fonts and assets onto their identities and replaces the address columns on
-- font_variants. A backup taken either side carries a different column set for three backed-up
-- tables.

-- Fonts and assets keyed by identity
--
-- font_variants.catalog_key served two foreign keys at once: the font family's catalog and,
-- through (tenant_key, catalog_key, asset_key), the backing asset's. One column could not follow
-- two parents, so moving either resource would have dragged the other's reference with it.
--
-- Naming each parent by its identity dissolves that: a face points at its family and at its asset,
-- and neither pointer mentions a catalog at all. Moving either resource updates one row and the
-- face is untouched. It also lifts an undocumented restriction that a face's asset had to live in
-- the font's own catalog, which was a consequence of the column sharing rather than a rule anyone
-- chose.
--
-- ON DELETE is preserved exactly: CASCADE for the owned faces, and NO ACTION DEFERRABLE for the
-- asset so DeleteFont can still drop the family first and release its assets within one
-- transaction (see DeleteFont's KDoc).

-- 1. Add ------------------------------------------------------------------------------------------
ALTER TABLE font_variants ADD COLUMN font_resource_id UUID;
ALTER TABLE font_variants ADD COLUMN asset_resource_id UUID;

-- 2. Backfill, asserting every reference was matched -----------------------------------------------
UPDATE font_variants faces
SET font_resource_id = families.resource_id
FROM fonts families
WHERE families.tenant_key = faces.tenant_key
  AND families.catalog_key = faces.catalog_key
  AND families.slug = faces.font_slug;

-- A CLASSPATH face carries no asset, so it is legitimately left NULL here.
UPDATE font_variants faces
SET asset_resource_id = binaries.resource_id
FROM assets binaries
WHERE binaries.tenant_key = faces.tenant_key
  AND binaries.catalog_key = faces.catalog_key
  AND binaries.id = faces.asset_key;

-- The face -> asset foreign key is DEFERRABLE INITIALLY DEFERRED, so the updates above leave a
-- check queued for every row they touched, and Postgres refuses to ALTER a table with pending
-- trigger events. Firing them now clears the queue; they pass, since neither address changed.
SET CONSTRAINTS ALL IMMEDIATE;

DO $$
DECLARE unmatched BIGINT;
BEGIN
    SELECT count(*) INTO unmatched FROM font_variants WHERE font_resource_id IS NULL;
    IF unmatched > 0 THEN
        RAISE EXCEPTION '% font faces belong to a family that does not exist', unmatched;
    END IF;

    SELECT count(*) INTO unmatched FROM font_variants
    WHERE asset_key IS NOT NULL AND asset_resource_id IS NULL;
    IF unmatched > 0 THEN
        RAISE EXCEPTION '% font faces reference an asset that does not exist', unmatched;
    END IF;
END $$;

ALTER TABLE font_variants ALTER COLUMN font_resource_id SET NOT NULL;

-- 3. Release the addresses -------------------------------------------------------------------------
-- Both foreign keys hold their parent's primary key in place, so they let go before it is swapped.
ALTER TABLE font_variants
    DROP CONSTRAINT font_variants_tenant_key_catalog_key_font_slug_fkey,
    DROP CONSTRAINT font_variants_tenant_key_catalog_key_asset_key_fkey,
    DROP CONSTRAINT chk_font_variant_source;

-- 4. Swap the keys ---------------------------------------------------------------------------------
ALTER TABLE fonts
    DROP CONSTRAINT fonts_pkey,
    ADD CONSTRAINT fonts_pkey PRIMARY KEY (tenant_key, resource_id),
    ADD CONSTRAINT uq_fonts_address UNIQUE (tenant_key, catalog_key, slug);

ALTER TABLE assets
    DROP CONSTRAINT assets_pkey,
    ADD CONSTRAINT assets_pkey PRIMARY KEY (tenant_key, resource_id),
    ADD CONSTRAINT uq_assets_address UNIQUE (tenant_key, catalog_key, id);

-- 5. Reference by identity, and drop the address copies ----------------------------------------------
-- A face is identified by its family and its shape, which is what the old key said through the
-- family's address.
ALTER TABLE font_variants
    DROP CONSTRAINT font_variants_pkey,
    ADD CONSTRAINT font_variants_pkey PRIMARY KEY (tenant_key, font_resource_id, weight, italic),
    DROP COLUMN catalog_key,
    DROP COLUMN font_slug,
    DROP COLUMN asset_key,
    ADD CONSTRAINT font_variants_font_fkey
        FOREIGN KEY (tenant_key, font_resource_id)
        REFERENCES fonts(tenant_key, resource_id)
        ON DELETE CASCADE,
    ADD CONSTRAINT font_variants_asset_fkey
        FOREIGN KEY (tenant_key, asset_resource_id)
        REFERENCES assets(tenant_key, resource_id)
        ON DELETE NO ACTION
        DEFERRABLE INITIALLY DEFERRED,
    ADD CONSTRAINT chk_font_variant_source CHECK (
        (source = 'ASSET'     AND asset_resource_id IS NOT NULL AND classpath_location IS NULL) OR
        (source = 'CLASSPATH' AND classpath_location IS NOT NULL AND asset_resource_id IS NULL)
    );

CREATE INDEX idx_font_variants_asset_resource_id ON font_variants(asset_resource_id)
    WHERE asset_resource_id IS NOT NULL;

COMMENT ON COLUMN font_variants.font_resource_id IS
    'Identity of the family this face belongs to. Replaces the family''s address, so a family move leaves its faces untouched.';
COMMENT ON COLUMN font_variants.asset_resource_id IS
    'Identity of this face''s backing binary; NULL for CLASSPATH faces. Independent of the family''s catalog, so a font and its asset relocate separately.';
