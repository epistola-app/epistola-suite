-- Font and asset relocation
--
-- font_variants.catalog_key served two foreign keys at once: the font family's
-- catalog and, through (tenant_key, catalog_key, asset_key), the backing
-- asset's. One column could not follow two parents, so moving either resource
-- would have dragged the other's reference with it.
--
-- Splitting the asset's catalog into its own column lets each side cascade
-- independently: a font move updates catalog_key, an asset move updates
-- asset_catalog_key, and neither disturbs the other. It also lifts an
-- undocumented restriction that a face's asset had to live in the font's own
-- catalog, which was a consequence of the column sharing rather than a rule
-- anyone chose.
--
-- ON DELETE is preserved exactly: CASCADE for the owned faces, and NO ACTION
-- DEFERRABLE for the asset so DeleteFont can still drop the family first and
-- release its assets within one transaction (see DeleteFont's KDoc).

ALTER TABLE font_variants ADD COLUMN asset_catalog_key CATALOG_KEY;

-- A face that has no asset (source = CLASSPATH) has no asset catalog either.
UPDATE font_variants SET asset_catalog_key = catalog_key WHERE asset_key IS NOT NULL;

ALTER TABLE font_variants
    ADD CONSTRAINT chk_font_variant_asset_catalog CHECK (
        (asset_key IS NULL AND asset_catalog_key IS NULL) OR
        (asset_key IS NOT NULL AND asset_catalog_key IS NOT NULL)
    );

ALTER TABLE font_variants
    DROP CONSTRAINT font_variants_tenant_key_catalog_key_asset_key_fkey,
    ADD CONSTRAINT font_variants_asset_fkey
        FOREIGN KEY (tenant_key, asset_catalog_key, asset_key)
        REFERENCES assets(tenant_key, catalog_key, id)
        ON UPDATE CASCADE
        ON DELETE NO ACTION
        DEFERRABLE INITIALLY DEFERRED,
    DROP CONSTRAINT font_variants_tenant_key_catalog_key_font_slug_fkey,
    ADD CONSTRAINT font_variants_tenant_key_catalog_key_font_slug_fkey
        FOREIGN KEY (tenant_key, catalog_key, font_slug)
        REFERENCES fonts(tenant_key, catalog_key, slug)
        ON UPDATE CASCADE ON DELETE CASCADE;

COMMENT ON COLUMN font_variants.asset_catalog_key IS
    'Catalog holding this face''s backing asset; NULL for CLASSPATH faces. Separate from catalog_key so a font and its asset can be relocated independently.';
