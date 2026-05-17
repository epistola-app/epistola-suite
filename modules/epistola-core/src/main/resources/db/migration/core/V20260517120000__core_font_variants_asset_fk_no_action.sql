-- font_variants.asset_key → assets: RESTRICT → NO ACTION (deferred check).
--
-- The original constraint used ON DELETE RESTRICT to forbid deleting an asset
-- that a font face still points at. RESTRICT is checked *immediately*, per
-- affected row. That makes `DELETE FROM tenants` fail whenever the tenant owns
-- any uploaded (ASSET-sourced) font: the tenant→assets cascade and the
-- tenant→fonts→font_variants cascade are independent sibling chains, and
-- Postgres can reach the `assets` rows before the `font_variants` cascade has
-- cleared the references — tripping RESTRICT mid-statement.
--
-- ON DELETE NO ACTION keeps the exact same guarantee (you still cannot delete
-- an asset that a font face references) but defers the check to the end of the
-- statement, by which point the tenant cascade has already removed the
-- dependent font_variants rows. Direct asset deletion is still blocked while a
-- live font face references it (DeleteFont removes the font row first, so its
-- font_variants cascade away before the backing assets are dropped).
--
-- Pre-production (CLAUDE.md): plain constraint swap, no data migration needed.

ALTER TABLE font_variants
    DROP CONSTRAINT font_variants_tenant_key_catalog_key_asset_key_fkey;

ALTER TABLE font_variants
    ADD CONSTRAINT font_variants_tenant_key_catalog_key_asset_key_fkey
    FOREIGN KEY (tenant_key, catalog_key, asset_key)
    REFERENCES assets(tenant_key, catalog_key, id)
    ON DELETE NO ACTION
    DEFERRABLE INITIALLY DEFERRED;
