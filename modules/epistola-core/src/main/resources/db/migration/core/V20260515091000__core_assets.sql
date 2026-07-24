-- Asset storage for tenant-scoped, catalog-scoped images
--
-- Assets are immutable binary objects (images). Metadata lives here; the bytes
-- live in content_store (pluggable backend). A 5MB per-image limit keeps
-- storage reasonable.

-- ============================================================================
-- DOMAIN TYPE
-- ============================================================================

CREATE DOMAIN ASSET_KEY AS UUID;

-- ============================================================================
-- ASSET TYPES (lookup table)
-- ============================================================================
-- Supported asset media types live in a seeded lookup table, not a CHECK /
-- enum, so adding a new asset type (e.g. a font format) is an INSERT — no
-- schema or Kotlin-enum-CHECK change. Created before `assets` so the FK is
-- inline (no ALTER). Same pattern as the `auth_providers` lookup table.

CREATE TABLE asset_types (
    media_type VARCHAR(50) PRIMARY KEY
);

COMMENT ON TABLE asset_types IS 'Allowed asset media types. Extend by inserting a row — no enum/schema change.';

INSERT INTO asset_types (media_type) VALUES
    ('image/png'),
    ('image/jpeg'),
    ('image/svg+xml'),
    ('image/webp');

-- ============================================================================
-- ASSETS TABLE
-- ============================================================================

CREATE TABLE assets (
    id ASSET_KEY NOT NULL,
    tenant_key TENANT_KEY NOT NULL,
    catalog_key CATALOG_KEY NOT NULL DEFAULT 'default',
    name VARCHAR(255) NOT NULL,
    media_type VARCHAR(50) NOT NULL REFERENCES asset_types(media_type),
    size_bytes BIGINT NOT NULL,
    width INTEGER,
    height INTEGER,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by UUID REFERENCES users(id) ON DELETE SET NULL,

    PRIMARY KEY (tenant_key, catalog_key, id),

    FOREIGN KEY (tenant_key) REFERENCES tenants(id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_key, catalog_key) REFERENCES catalogs(tenant_key, id) ON DELETE CASCADE,

    CONSTRAINT chk_assets_name_not_empty CHECK (LENGTH(name) > 0),
    CONSTRAINT chk_assets_size_positive CHECK (size_bytes > 0),
    CONSTRAINT chk_assets_size_limit CHECK (size_bytes <= 5242880),
    CONSTRAINT chk_assets_dimensions CHECK (
        (width IS NULL AND height IS NULL) OR (width > 0 AND height > 0)
    )
);

CREATE INDEX idx_assets_tenant_created ON assets(tenant_key, created_at DESC);
CREATE INDEX idx_assets_tenant_name ON assets(tenant_key, name);

COMMENT ON TABLE assets IS 'Tenant-scoped, catalog-scoped image assets. Metadata lives here; binary content lives in content_store. Used in template image blocks and PDF generation.';
COMMENT ON COLUMN assets.id IS 'UUIDv7-based asset identifier';
COMMENT ON COLUMN assets.tenant_key IS 'Owning tenant';
COMMENT ON COLUMN assets.catalog_key IS 'Catalog this asset belongs to';
COMMENT ON COLUMN assets.name IS 'Human-readable asset name';
COMMENT ON COLUMN assets.media_type IS 'MIME type; FK to asset_types (extensible lookup, e.g. image/* and font/*)';
COMMENT ON COLUMN assets.size_bytes IS 'File size in bytes (max 5MB)';
COMMENT ON COLUMN assets.width IS 'Image width in pixels (NULL for SVG)';
COMMENT ON COLUMN assets.height IS 'Image height in pixels (NULL for SVG)';
COMMENT ON COLUMN assets.created_at IS 'When the asset was uploaded';
COMMENT ON COLUMN assets.created_by IS 'User who uploaded the asset (NULL if the user was deleted)';
