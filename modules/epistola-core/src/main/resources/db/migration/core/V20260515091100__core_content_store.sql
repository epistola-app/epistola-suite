-- SPDX-FileCopyrightText: Epistola Nederland B.V.
--
-- SPDX-License-Identifier: AGPL-3.0-only

-- content_store: a single key-value table that mirrors S3 semantics, enabling
-- pluggable storage backends (PostgreSQL, S3, filesystem) via configuration.
--
-- When using the PostgreSQL backend (default), binary content (images up to
-- 5MB, PDFs up to 50MB) is stored here. When using S3 or filesystem backends,
-- this table is unused but still created for schema consistency.

CREATE TABLE content_store (
    key          VARCHAR(512) PRIMARY KEY,
    content      BYTEA        NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    size_bytes   BIGINT       NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
