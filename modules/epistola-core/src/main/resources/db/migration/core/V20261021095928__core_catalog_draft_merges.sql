-- ADR 0032: the releases a draft has merged become rows of their own, so a merge records what it was
-- merged against and how its conflicts were resolved, instead of a release number in an array. A
-- draft contains a release it has merged, so it is not behind it.
CREATE TABLE catalog_draft_merges (
    tenant_key      TENANT_KEY  NOT NULL,
    version_id      UUID        NOT NULL,
    release_version VARCHAR(50) NOT NULL,
    merge_base      VARCHAR(50),
    resolutions     JSONB       NOT NULL DEFAULT '{}',
    held_fingerprints JSONB,
    merged_by       UUID REFERENCES users (id) ON DELETE SET NULL,
    merged_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (tenant_key, version_id, release_version),
    FOREIGN KEY (tenant_key, version_id) REFERENCES catalog_versions (tenant_key, version_id) ON DELETE CASCADE
);

CREATE INDEX idx_catalog_draft_merges_merged_by ON catalog_draft_merges (merged_by);

COMMENT ON TABLE catalog_draft_merges IS
    'The releases merged into a draft (ADR 0032), one row per release: the draft is not behind them.';
COMMENT ON COLUMN catalog_draft_merges.version_id IS 'The draft (catalog_versions, kind draft) the release was merged into.';
COMMENT ON COLUMN catalog_draft_merges.release_version IS 'The release of the same catalog that was merged in.';
COMMENT ON COLUMN catalog_draft_merges.merge_base IS 'The release the merge compared against (the closest both descend from); NULL when none, or recorded before merges recorded it.';
COMMENT ON COLUMN catalog_draft_merges.resolutions IS 'How each conflict was resolved: {"type/key": "OURS" | "THEIRS"}.';
COMMENT ON COLUMN catalog_draft_merges.held_fingerprints IS 'What the draft held of the release once merged, a fingerprint per entry (type/key): the base a later merge compares the draft with. NULL when not recorded.';
COMMENT ON COLUMN catalog_draft_merges.merged_by IS 'User who merged it (NULL if unknown, or the user was deleted)';
COMMENT ON COLUMN catalog_draft_merges.merged_at IS 'When it was merged; for rows moved from catalog_versions.merged_releases, when they were moved.';

-- What drafts recorded before: moved, not lost.
INSERT INTO catalog_draft_merges (tenant_key, version_id, release_version)
SELECT v.tenant_key, v.version_id, m.release
FROM catalog_versions v
CROSS JOIN LATERAL unnest(v.merged_releases) AS m(release)
ON CONFLICT DO NOTHING;

-- The next version moved to the release an install puts in place has merged nothing on that base.
CREATE OR REPLACE FUNCTION catalog_installed_version() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        INSERT INTO catalog_versions (tenant_key, catalog_id, kind, is_next, base_version)
        SELECT NEW.tenant_key, NEW.catalog_id, 'draft', TRUE, NEW.installed_release_version
        WHERE NOT EXISTS (
            SELECT 1 FROM catalog_versions v
            WHERE v.tenant_key = NEW.tenant_key AND v.catalog_id = NEW.catalog_id AND v.is_next
        );
    ELSE
        UPDATE catalog_versions
        SET base_version = NEW.installed_release_version, created_at = NOW(), base_fingerprints = NULL
        WHERE tenant_key = NEW.tenant_key AND catalog_id = NEW.catalog_id AND is_next;
        DELETE FROM catalog_draft_merges m
        USING catalog_versions v
        WHERE v.tenant_key = NEW.tenant_key AND v.catalog_id = NEW.catalog_id AND v.is_next
          AND m.tenant_key = v.tenant_key AND m.version_id = v.version_id;
    END IF;
    RETURN NEW;
END;
$$;

ALTER TABLE catalog_versions DROP COLUMN merged_releases;
