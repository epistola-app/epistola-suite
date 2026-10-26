-- ADR 0036, step (e): one table for every catalog version, so `catalog_releases` goes.
--
-- Since step (a) every release is a released row of `catalog_versions`, and `catalog_releases` has
-- only been a mirror kept by a trigger. Nothing reads or writes it any more after this step, so the
-- mirror, the table and the pins table that (c) emptied (`catalog_release_dependencies`) go, and the
-- last two foreign keys into it move to the version row:
--
-- - `catalog_release_merges` (a release's merge parents) is keyed by the release's row, `version_id`,
--   and goes with it (cascade), as it went with the release before.
-- - `catalog_release_publications` (the publication outbox) keeps the catalog and number it sent:
--   a publication records what left the installation, by name. Its foreign key now names the version
--   row by that natural key (a released row's number is unique per catalog and never changes), with
--   the same cascade.

-- 1. A release's merge parents, by its row --------------------------------------------------------
ALTER TABLE catalog_release_merges ADD COLUMN version_id UUID;
UPDATE catalog_release_merges m SET version_id = v.version_id
FROM catalog_versions v
WHERE v.tenant_key = m.tenant_key AND v.catalog_id = m.catalog_id AND v.version = m.version AND v.state = 'released';
-- A parent of a release that is no longer here has nothing to hang from (the old cascade would have
-- removed it with the release).
DELETE FROM catalog_release_merges WHERE version_id IS NULL;
ALTER TABLE catalog_release_merges DROP CONSTRAINT catalog_release_merges_tenant_key_catalog_id_version_fkey;
ALTER TABLE catalog_release_merges DROP CONSTRAINT catalog_release_merges_pkey;
ALTER TABLE catalog_release_merges DROP COLUMN catalog_id, DROP COLUMN version;
ALTER TABLE catalog_release_merges ALTER COLUMN version_id SET NOT NULL;
ALTER TABLE catalog_release_merges ADD PRIMARY KEY (tenant_key, version_id, merged_version);
ALTER TABLE catalog_release_merges
    ADD CONSTRAINT fk_catalog_release_merges_version FOREIGN KEY (tenant_key, version_id)
        REFERENCES catalog_versions (tenant_key, version_id) ON DELETE CASCADE;
COMMENT ON COLUMN catalog_release_merges.version_id IS
    'The release whose merge parent this is: its version row (ADR 0036).';

-- 2. The publication outbox names the version row by its catalog and number -----------------------
ALTER TABLE catalog_release_publications DROP CONSTRAINT fk_catalog_release_publications_release;
ALTER TABLE catalog_release_publications
    ADD CONSTRAINT fk_catalog_release_publications_version FOREIGN KEY (tenant_key, catalog_id, version)
        REFERENCES catalog_versions (tenant_key, catalog_id, version) ON DELETE CASCADE;

-- 3. The released rows get the indexes the releases' readers used ---------------------------------
CREATE INDEX idx_catalog_versions_release_order ON catalog_versions
    (tenant_key, catalog_id, version_major DESC, version_minor DESC, version_patch DESC,
     (version_label IS NULL) DESC, version_label COLLATE "C" DESC, version_label_number DESC)
    WHERE state = 'released';
CREATE INDEX idx_catalog_versions_release_content_hash ON catalog_versions (tenant_key, catalog_id, content_hash)
    WHERE state = 'released';
CREATE INDEX idx_catalog_versions_released_at ON catalog_versions (tenant_key, catalog_id, released_at DESC)
    WHERE state = 'released';

-- 4. The mirror and the old tables go -------------------------------------------------------------
DROP TRIGGER trg_catalog_releases_mirror ON catalog_releases;
DROP FUNCTION catalog_release_mirror();
DROP TABLE catalog_release_dependencies;
DROP TABLE catalog_releases;
