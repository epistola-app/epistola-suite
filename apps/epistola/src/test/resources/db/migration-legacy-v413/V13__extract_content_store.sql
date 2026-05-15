-- ============================================================
-- V13: Extract binary content into a dedicated content_store table
-- ============================================================
-- Binary content (images up to 5MB, PDFs up to 50MB) was previously stored
-- inline as BYTEA columns in assets and documents. This migration extracts
-- content into a single key-value table that mirrors S3 semantics, enabling
-- pluggable storage backends (PostgreSQL, S3, filesystem) via configuration.
--
-- When using the PostgreSQL backend (default), content is stored in this table.
-- When using S3 or filesystem backends, this table is unused but still created
-- for schema consistency.
-- ============================================================

-- 1. Create the content_store key-value table
CREATE TABLE content_store (
    key          VARCHAR(512) PRIMARY KEY,
    content      BYTEA        NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    size_bytes   BIGINT       NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- 2. Drop inline content columns (metadata stays in the original tables)
ALTER TABLE assets DROP COLUMN IF EXISTS content;
ALTER TABLE documents DROP COLUMN IF EXISTS content;
