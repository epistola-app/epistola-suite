-- backup-restore-compatibility: backward=false forward=false
-- reason: Re-keys themes onto its identity and replaces the address columns on document_templates
-- and tenants. A backup taken either side carries a different column set for three backed-up tables.

-- Themes keyed by identity
--
-- A theme is referenced three ways: from a template's own binding, from the tenant-wide default,
-- and from content (`themeRef`). The two relational ones now name the theme itself, so moving or
-- renaming one updates a single row. Content references keep naming an address -- that is what
-- travels in an export -- and resolve through the alias when the theme has moved.
--
-- ON DELETE is preserved exactly: deleting a theme clears the binding and falls the template back
-- to the tenant default, rather than deleting the template.

-- 1. Add ---------------------------------------------------------------------------------------
ALTER TABLE document_templates ADD COLUMN theme_resource_id UUID;
ALTER TABLE tenants ADD COLUMN default_theme_resource_id UUID;

-- 2. Backfill, asserting every bound row was matched ---------------------------------------------
UPDATE document_templates templates
SET theme_resource_id = themes.resource_id
FROM themes
WHERE themes.tenant_key = templates.tenant_key
  AND themes.catalog_key = templates.theme_catalog_key
  AND themes.id = templates.theme_key;

UPDATE tenants
SET default_theme_resource_id = themes.resource_id
FROM themes
WHERE themes.tenant_key = tenants.id
  AND themes.catalog_key = tenants.default_theme_catalog_key
  AND themes.id = tenants.default_theme_key;

DO $$
DECLARE unmatched BIGINT;
BEGIN
    SELECT count(*) INTO unmatched FROM document_templates
    WHERE theme_key IS NOT NULL AND theme_resource_id IS NULL;
    IF unmatched > 0 THEN
        RAISE EXCEPTION '% templates reference a theme that does not exist', unmatched;
    END IF;

    SELECT count(*) INTO unmatched FROM tenants
    WHERE default_theme_key IS NOT NULL AND default_theme_resource_id IS NULL;
    IF unmatched > 0 THEN
        RAISE EXCEPTION '% tenants default to a theme that does not exist', unmatched;
    END IF;
END $$;

-- 3. Release the address --------------------------------------------------------------------------
-- Both referencing constraints hold the primary key in place, so they let go before it is swapped.
ALTER TABLE document_templates
    DROP CONSTRAINT document_templates_tenant_key_theme_catalog_key_theme_key_fkey;
ALTER TABLE tenants
    DROP CONSTRAINT fk_tenants_default_theme;

-- 4. Swap the key ---------------------------------------------------------------------------------
ALTER TABLE themes
    DROP CONSTRAINT themes_pkey,
    ADD CONSTRAINT themes_pkey PRIMARY KEY (tenant_key, resource_id),
    ADD CONSTRAINT uq_themes_address UNIQUE (tenant_key, catalog_key, id);

-- 5. Reference by identity, and drop the address copies ---------------------------------------------
ALTER TABLE document_templates
    DROP COLUMN theme_catalog_key,
    DROP COLUMN theme_key,
    ADD CONSTRAINT document_templates_theme_fkey
        FOREIGN KEY (tenant_key, theme_resource_id)
        REFERENCES themes(tenant_key, resource_id)
        ON DELETE SET NULL (theme_resource_id);

ALTER TABLE tenants
    DROP COLUMN default_theme_catalog_key,
    DROP COLUMN default_theme_key,
    ADD CONSTRAINT fk_tenants_default_theme
        FOREIGN KEY (id, default_theme_resource_id)
        REFERENCES themes(tenant_key, resource_id);

-- The address indexes went with their columns; both lookups (which templates use this theme,
-- which tenant defaults to it) now go through the identity.
CREATE INDEX idx_document_templates_theme_resource_id ON document_templates(theme_resource_id)
    WHERE theme_resource_id IS NOT NULL;
CREATE INDEX idx_tenants_default_theme_resource_id ON tenants(default_theme_resource_id)
    WHERE default_theme_resource_id IS NOT NULL;

COMMENT ON COLUMN document_templates.theme_resource_id IS
    'Identity of this template''s theme, in any catalog of the same tenant; NULL falls back to the tenant default.';
COMMENT ON COLUMN tenants.default_theme_resource_id IS
    'Identity of the tenant-wide default theme; NULL means the built-in default.';
