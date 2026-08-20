-- SPDX-FileCopyrightText: Epistola Nederland B.V.
--
-- SPDX-License-Identifier: AGPL-3.0-only

ALTER TABLE catalogs
    ADD COLUMN portable_metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN content_updated_at TIMESTAMPTZ;

UPDATE catalogs
SET content_updated_at = created_at;

ALTER TABLE catalogs
    ALTER COLUMN content_updated_at SET NOT NULL,
    ALTER COLUMN content_updated_at SET DEFAULT NOW();

COMMENT ON COLUMN catalogs.portable_metadata IS
    'Portable catalog attributes, keywords, presentation, and license metadata from the catalog wire contract.';
COMMENT ON COLUMN catalogs.content_updated_at IS
    'When portable catalog content (identity or metadata) last changed; participates in authored unreleased-change detection.';
