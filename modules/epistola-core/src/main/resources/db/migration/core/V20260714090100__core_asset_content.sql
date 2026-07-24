-- SPDX-FileCopyrightText: Epistola Nederland B.V.
--
-- SPDX-License-Identifier: AGPL-3.0-only

-- asset_content: content-addressable, deduplicated, keep-forever blob storage for
-- assets (logos, images, font files) — issue #738.
--
-- Split out of the single shared `content_store` and always stored in PostgreSQL
-- (regardless of epistola.storage.backend): assets are small (<= 5MB), low-churn,
-- keep-forever reference data. Blobs are keyed by (scope, content_hash) so identical
-- bytes are stored ONCE. The `assets` row becomes a pointer via `assets.content_hash`.
--
-- Dedup scope is a privacy boundary DERIVED from the owning asset's `sensitive` flag
-- at write/read/GC time (CASE WHEN sensitive THEN tenant_key ELSE 'global' END), so it
-- can never drift:
--   * 'global'  -> normal (non-sensitive) assets — branding, images, fonts. Dedup
--                  GLOBALLY: identical bytes stored once installation-wide. Default.
--   * <tenant>  -> sensitive assets. Stored in isolation per tenant, so there is no
--                  cross-tenant existence side-channel (a tenant inferring another
--                  uploaded the same bytes via put-if-exists) and physical erasure is
--                  clean. Within one tenant, sensitive assets still dedup against each
--                  other. The flag is surfaced on the UI / REST / catalog surfaces by
--                  issue #751; the backend honours it today.
--
-- content_hash is the lowercase hex SHA-256 of the bytes — the same digest the font
-- feature already stores in font_variants.content_hash, so font-face binaries dedup
-- consistently through this table.
--
-- Deletion is NOT direct: a blob may back many assets, so DeleteAsset only removes
-- the `assets` row. Reclamation is mark-and-sweep by the content reaper, which drops
-- an asset_content row once no `assets` row references its (scope, content_hash).

CREATE TABLE asset_content (
    scope        TEXT         NOT NULL,
    content_hash TEXT         NOT NULL,
    content      BYTEA        NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    size_bytes   BIGINT       NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    PRIMARY KEY (scope, content_hash)
);

COMMENT ON TABLE asset_content IS 'Content-addressable, deduplicated, keep-forever asset blobs (always PostgreSQL). Keyed by (scope, sha256); assets.content_hash points here. See issue #738.';
COMMENT ON COLUMN asset_content.scope IS 'Dedup namespace: ''system'' (global, bundled assets) or the owning tenant_key (per-tenant, user uploads). Derived from asset catalog_key.';
COMMENT ON COLUMN asset_content.content_hash IS 'Lowercase hex SHA-256 of the bytes. Same digest as font_variants.content_hash.';

-- assets becomes a pointer into asset_content. Nullable during the ContentBackfillRunner
-- transition; a later release (after cluster-wide backfill) sets NOT NULL.
ALTER TABLE assets ADD COLUMN content_hash TEXT;

-- Whether this asset's bytes are stored in a per-tenant isolated dedup scope (sensitive)
-- or the shared global scope (default). The backend honours it today; UI/REST/catalog
-- exposure is issue #751.
ALTER TABLE assets ADD COLUMN sensitive BOOLEAN NOT NULL DEFAULT false;

COMMENT ON COLUMN assets.content_hash IS 'Lowercase hex SHA-256 pointer into asset_content. NULL only until the one-time content backfill has run. See issue #738.';
COMMENT ON COLUMN assets.sensitive IS 'When true, the blob is stored in a per-tenant dedup scope (isolated); otherwise the global scope. See issue #738 / #751.';
