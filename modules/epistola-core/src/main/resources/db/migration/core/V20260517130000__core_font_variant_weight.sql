-- Numeric-weight font variant model.
--
-- The font-face model moves from a fixed four named faces
-- (regular / bold / italic / bold_italic) to N faces keyed by a CSS numeric
-- `weight` (1–1000; 400 = regular, 700 = bold) plus an `italic` flag, with a
-- reserved `is_variable` flag for single-binary weight-axis ("variable")
-- fonts (stored only — variable-font instancing is not rendered yet).
--
-- Pre-production (CLAUDE.md): this is a DESTRUCTIVE rebuild — no data is
-- preserved. `font_variants` is dropped and recreated with the new shape, and
-- the obsolete `FONT_VARIANT` domain is dropped. The `fonts` rows are LEFT
-- INTACT: `EnsureSystemFonts` re-seeds every tenant's bundled system variants
-- on the next boot / tenant create (idempotent UPSERT + delete-and-reinsert),
-- and catalog-authored families re-import from their catalog source. There is
-- therefore no need to TRUNCATE `fonts`.

-- ============================================================================
-- DROP THE OLD FACE TABLE + DOMAIN
-- ============================================================================

DROP TABLE font_variants;
DROP DOMAIN FONT_VARIANT;

-- ============================================================================
-- RECREATE font_variants KEYED BY (weight, italic)
-- ============================================================================
-- PK is (tenant, catalog, font, weight, italic): a family carries as many
-- faces as it ships. FKs and the source/asset-or-classpath CHECK are copied
-- verbatim from V20260516214637 (asset FK keeps the deferred NO ACTION shape
-- introduced in V20260517120000 so a tenant cascade can clear references
-- before the sibling assets cascade runs).

CREATE TABLE font_variants (
    tenant_key         TENANT_KEY NOT NULL,
    catalog_key        CATALOG_KEY NOT NULL,
    font_slug          FONT_KEY NOT NULL,
    weight             SMALLINT NOT NULL CHECK (weight BETWEEN 1 AND 1000),
    italic             BOOLEAN NOT NULL,
    is_variable        BOOLEAN NOT NULL DEFAULT FALSE,
    source             FONT_VARIANT_SOURCE NOT NULL,
    asset_key          ASSET_KEY,
    classpath_location TEXT,
    PRIMARY KEY (tenant_key, catalog_key, font_slug, weight, italic),
    FOREIGN KEY (tenant_key, catalog_key, font_slug)
        REFERENCES fonts(tenant_key, catalog_key, slug) ON DELETE CASCADE,
    FOREIGN KEY (tenant_key, catalog_key, asset_key)
        REFERENCES assets(tenant_key, catalog_key, id)
        ON DELETE NO ACTION
        DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT chk_font_variant_source CHECK (
        (source = 'ASSET'     AND asset_key IS NOT NULL AND classpath_location IS NULL) OR
        (source = 'CLASSPATH' AND classpath_location IS NOT NULL AND asset_key IS NULL)
    )
);

COMMENT ON TABLE font_variants IS
    'One face per row, keyed by CSS numeric weight (1-1000) + italic. is_variable marks a reserved weight-axis variable font (not yet rendered). ASSET -> assets row (uploaded); CLASSPATH -> bundled JAR resource.';

-- ============================================================================
-- TIGHTEN THE ASSETS MEDIA-TYPE WHITELIST: DROP font/woff2
-- ============================================================================
-- Uploaded font binaries are TTF/OTF only now (woff2 is dropped — the PDF
-- font embedder consumes sfnt-flavoured binaries). Mirror the ALTER style of
-- V20260516214637.

ALTER TABLE assets DROP CONSTRAINT chk_assets_media_type;
ALTER TABLE assets ADD CONSTRAINT chk_assets_media_type CHECK (
    media_type IN (
        'image/png', 'image/jpeg', 'image/svg+xml', 'image/webp',
        'font/ttf', 'font/otf'
    )
);
