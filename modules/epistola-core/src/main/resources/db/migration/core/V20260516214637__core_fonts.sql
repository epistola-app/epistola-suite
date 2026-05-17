-- Font families: a thin, catalog-scoped grouping over up to four font-face
-- binaries (regular / bold / italic / bold-italic).
--
-- A font does NOT own its binaries. Each variant points at either:
--   * an ASSET   — an ordinary `assets` row in the same catalog (uploaded
--                  font binary; reuses all asset machinery), or
--   * a CLASSPATH — a bundled system font shipped once in the JAR (no asset
--                  row, no content_store byte, no per-tenant copy).
--
-- `fontFamily` style values reference a family as `{ slug, catalogKey }`
-- (the same convention as code-list bindings); the bundled defaults live in
-- every tenant's `system` catalog.

-- ============================================================================
-- FONT MEDIA TYPES (extend the shared assets constraint)
-- ============================================================================
-- Uploaded font-face binaries are ordinary assets, so the assets media-type
-- whitelist must accept font formats too.
ALTER TABLE assets DROP CONSTRAINT chk_assets_media_type;
ALTER TABLE assets ADD CONSTRAINT chk_assets_media_type CHECK (
    media_type IN (
        'image/png', 'image/jpeg', 'image/svg+xml', 'image/webp',
        'font/ttf', 'font/otf', 'font/woff2'
    )
);

-- ============================================================================
-- DOMAIN TYPES
-- ============================================================================

-- Slug shape mirrors CODE_LIST_KEY (catalog-scoped, human-authored).
CREATE DOMAIN FONT_KEY AS VARCHAR(64)
    CHECK (VALUE ~ '^[a-z][a-z0-9]*(-[a-z0-9]+)*$');

CREATE DOMAIN FONT_VARIANT AS TEXT
    CHECK (VALUE IN ('regular', 'bold', 'italic', 'bold_italic'));

CREATE DOMAIN FONT_VARIANT_SOURCE AS TEXT
    CHECK (VALUE IN ('ASSET', 'CLASSPATH'));

-- ============================================================================
-- FONTS TABLE (family metadata)
-- ============================================================================

CREATE TABLE fonts (
    slug        FONT_KEY NOT NULL,
    tenant_key  TENANT_KEY NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    catalog_key CATALOG_KEY NOT NULL,
    name        VARCHAR(100) NOT NULL,
    kind        VARCHAR(16) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (tenant_key, catalog_key, slug),
    FOREIGN KEY (tenant_key, catalog_key) REFERENCES catalogs(tenant_key, id) ON DELETE CASCADE,
    CONSTRAINT chk_fonts_kind CHECK (kind IN ('sans', 'serif', 'mono', 'condensed', 'display'))
);

COMMENT ON TABLE fonts IS 'Catalog-scoped font families. Variants live in font_variants; binaries are assets or classpath.';

-- ============================================================================
-- FONT VARIANTS TABLE (per-face pointer)
-- ============================================================================

CREATE TABLE font_variants (
    tenant_key         TENANT_KEY NOT NULL,
    catalog_key        CATALOG_KEY NOT NULL,
    font_slug          FONT_KEY NOT NULL,
    variant            FONT_VARIANT NOT NULL,
    source             FONT_VARIANT_SOURCE NOT NULL,
    asset_key          ASSET_KEY,
    classpath_location TEXT,
    PRIMARY KEY (tenant_key, catalog_key, font_slug, variant),
    FOREIGN KEY (tenant_key, catalog_key, font_slug)
        REFERENCES fonts(tenant_key, catalog_key, slug) ON DELETE CASCADE,
    FOREIGN KEY (tenant_key, catalog_key, asset_key)
        REFERENCES assets(tenant_key, catalog_key, id) ON DELETE RESTRICT,
    CONSTRAINT chk_font_variant_source CHECK (
        (source = 'ASSET'     AND asset_key IS NOT NULL AND classpath_location IS NULL) OR
        (source = 'CLASSPATH' AND classpath_location IS NOT NULL AND asset_key IS NULL)
    )
);

COMMENT ON TABLE font_variants IS 'One face per row. ASSET → assets row (uploaded); CLASSPATH → bundled JAR resource.';

CREATE TRIGGER trg_fonts_updated_at
    BEFORE UPDATE ON fonts
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
