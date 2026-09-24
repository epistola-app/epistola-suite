-- backup-restore-compatibility: backward=true forward=true
-- reason: Adds an index. No column, no data, nothing a backup carries either way.
-- SPDX-FileCopyrightText: Epistola Nederland B.V.
--
-- SPDX-License-Identifier: AGPL-3.0-only

-- Let the content sweep find an asset by the bytes it points at.
--
-- `ContentReaper` asks, for each candidate blob, whether any live `assets` row still points at it.
-- `assets` was indexed only by `(tenant_key, created_at)` and `(tenant_key, name)`, so that question
-- had no index to answer it. It matters most in the case the sweep actually runs: `created_at <
-- cutoff` narrows the candidates first, and a small candidate set is exactly when the planner picks
-- a nested loop -- turning each probe into a sequential scan of every asset in the installation.
--
-- Deliberately NOT prefixed with `tenant_key`, unlike every other index on this table. The sweep
-- has no tenant to filter by and cannot have one: a blob in the 'global' scope is held by ANY
-- tenant's asset, so the question is installation-wide. Put `tenant_key` in front and the leading
-- column is unconstrained, the index stops being usable, and the scan comes back. The two
-- tenant-scoped readers -- `ResolveAssetKeysByContentHash` and the backup's blob dump -- are served
-- by this one too, since a content hash is far more selective than a tenant.
--
-- Partial, because the join can only match a row that has a hash. `content_hash` is still nullable
-- pending the backfill in #742, and those rows can never satisfy the predicate.
CREATE INDEX idx_assets_content_hash ON assets (content_hash) WHERE content_hash IS NOT NULL;

COMMENT ON INDEX idx_assets_content_hash IS
    'Serves the content reaper''s reachability check: which assets still point at a given blob.';
