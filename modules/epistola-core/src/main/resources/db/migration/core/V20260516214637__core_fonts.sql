-- SPDX-FileCopyrightText: Epistola Nederland B.V.
--
-- SPDX-License-Identifier: AGPL-3.0-only

-- Font families: a thin, catalog-scoped grouping over font-face binaries.
--
-- A font does NOT own its binaries. Each face points at either:
--   * an ASSET   — an ordinary `assets` row in the same catalog (uploaded
--                  font binary; reuses all asset machinery), or
--   * a CLASSPATH — a bundled system font shipped once in the JAR (no asset
--                  row, no content_store byte, no per-tenant copy).
--
-- A face is keyed by a CSS numeric `weight` (1–1000; 400 = regular, 700 =
-- bold) plus an `italic` flag — a family carries as many faces as it ships.
-- `fontFamily` style values reference a family as `{ slug, catalogKey }`
-- (the same convention as code-list bindings); the bundled defaults live in
-- every tenant's `system` catalog.
--
-- Consolidated baseline: the font feature's iterative migrations (variant
-- model, deferred asset FK, weight redesign, content hash) are folded into
-- this single CREATE per the repo's migration-consolidation practice. The
-- obsolete one-shot string→ref `fontFamily` data backfill is dropped — a
-- from-scratch apply has no legacy string values (CreateTenant and the demo
-- catalog already emit structured refs).

-- ============================================================================
-- FONT MEDIA TYPES (register in the asset_types lookup)
-- ============================================================================
-- Uploaded font-face binaries are ordinary assets, so the font formats must
-- be allowed asset media types. TTF/OTF only — the PDF font embedder consumes
-- sfnt-flavoured binaries (WOFF2 is rejected at upload). Just an INSERT now
-- (no CHECK to alter), the whole point of the asset_types lookup.
INSERT INTO asset_types (media_type) VALUES
    ('font/ttf'),
    ('font/otf');

-- ============================================================================
-- DOMAIN TYPES
-- ============================================================================

-- Slug shape mirrors CODE_LIST_KEY (catalog-scoped, human-authored).
CREATE DOMAIN FONT_KEY AS VARCHAR(64)
    CHECK (VALUE ~ '^[a-z][a-z0-9]*(-[a-z0-9]+)*$');

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

COMMENT ON TABLE fonts IS 'Catalog-scoped font families. Faces live in font_variants; binaries are assets or classpath.';

-- ============================================================================
-- FONT VARIANTS TABLE (per-face pointer)
-- ============================================================================
-- PK is (tenant, catalog, font, weight, italic): a family carries as many
-- faces as it ships. The asset FK is ON DELETE NO ACTION DEFERRABLE INITIALLY
-- DEFERRED — it still forbids deleting an asset a live font face references,
-- but defers the check to statement end so a `DELETE FROM tenants` (whose
-- tenant→assets and tenant→fonts→font_variants cascades are independent
-- sibling chains) can clear the font_variants rows before the assets rows.

CREATE TABLE font_variants (
    tenant_key         TENANT_KEY NOT NULL,
    catalog_key        CATALOG_KEY NOT NULL,
    font_slug          FONT_KEY NOT NULL,
    weight             SMALLINT NOT NULL CHECK (weight BETWEEN 1 AND 1000),
    italic             BOOLEAN NOT NULL,
    source             FONT_VARIANT_SOURCE NOT NULL,
    asset_key          ASSET_KEY,
    classpath_location TEXT,
    content_hash       TEXT,
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
    'One face per row, keyed by CSS numeric weight (1-1000) + italic. Every face is a static binary (variable fonts are instanced into static faces at upload). ASSET -> assets row (uploaded); CLASSPATH -> bundled JAR resource.';

COMMENT ON COLUMN font_variants.content_hash IS
    'Lowercase hex SHA-256 of the face binary, recomputed by ImportFont. Pinned (per family) in the published theme snapshot for deterministic renders. NULL = not yet hashed (treated as a mismatch).';

CREATE TRIGGER trg_fonts_updated_at
    BEFORE UPDATE ON fonts
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
