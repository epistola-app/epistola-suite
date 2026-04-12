-- Asset storage for tenant-scoped, catalog-scoped images
--
-- Assets are immutable binary objects (images) stored in PostgreSQL BYTEA.
-- Consistent with the existing documents.content pattern.
-- A 5MB per-image limit keeps storage reasonable.

-- ============================================================================
-- DOMAIN TYPE
-- ============================================================================

CREATE DOMAIN ASSET_KEY AS UUID;

-- ============================================================================
-- ASSETS TABLE
-- ============================================================================

CREATE TABLE assets (
    id ASSET_KEY NOT NULL,
    tenant_key TENANT_KEY NOT NULL,
    catalog_key CATALOG_KEY NOT NULL DEFAULT 'default',
    name VARCHAR(255) NOT NULL,
    media_type VARCHAR(50) NOT NULL,
    size_bytes BIGINT NOT NULL,
    width INTEGER,
    height INTEGER,
    content BYTEA NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    created_by UUID REFERENCES users(id),

    PRIMARY KEY (tenant_key, catalog_key, id),

    FOREIGN KEY (tenant_key) REFERENCES tenants(id) ON DELETE CASCADE,

    CONSTRAINT chk_assets_name_not_empty CHECK (LENGTH(name) > 0),
    CONSTRAINT chk_assets_media_type CHECK (media_type IN ('image/png', 'image/jpeg', 'image/svg+xml', 'image/webp')),
    CONSTRAINT chk_assets_size_positive CHECK (size_bytes > 0),
    CONSTRAINT chk_assets_size_limit CHECK (size_bytes <= 5242880),
    CONSTRAINT chk_assets_dimensions CHECK (
        (width IS NULL AND height IS NULL) OR (width > 0 AND height > 0)
    )
);

CREATE INDEX idx_assets_tenant_created ON assets(tenant_key, created_at DESC);
CREATE INDEX idx_assets_tenant_name ON assets(tenant_key, name);

COMMENT ON TABLE assets IS 'Tenant-scoped, catalog-scoped image assets stored as BYTEA. Used in template image blocks and PDF generation.';
COMMENT ON COLUMN assets.id IS 'UUIDv7-based asset identifier';
COMMENT ON COLUMN assets.tenant_key IS 'Owning tenant';
COMMENT ON COLUMN assets.catalog_key IS 'Catalog this asset belongs to';
COMMENT ON COLUMN assets.name IS 'Human-readable asset name';
COMMENT ON COLUMN assets.media_type IS 'MIME type: image/png, image/jpeg, image/svg+xml, or image/webp';
COMMENT ON COLUMN assets.size_bytes IS 'File size in bytes (max 5MB)';
COMMENT ON COLUMN assets.width IS 'Image width in pixels (NULL for SVG)';
COMMENT ON COLUMN assets.height IS 'Image height in pixels (NULL for SVG)';
COMMENT ON COLUMN assets.content IS 'Raw image bytes';
COMMENT ON COLUMN assets.created_at IS 'When the asset was uploaded';
COMMENT ON COLUMN assets.created_by IS 'User who uploaded the asset';
