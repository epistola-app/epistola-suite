-- ADR 0032: a release remembers what it contains. Besides the release its draft started from
-- (catalog_releases.base_version), a release records the releases merged into its draft at the time
-- it was released: its merge parents. Lineage is then base + merge parents, so a fix merged before a
-- release does not look missing after it, and a draft is behind exactly the releases its line does
-- not contain. A received release keeps the merge parents its archive names
-- (release.metadata."app.epistola.suite".merged).
CREATE TABLE catalog_release_merges (
    tenant_key         TENANT_KEY  NOT NULL,
    catalog_id         UUID        NOT NULL,
    version            VARCHAR(50) NOT NULL,
    merged_version     VARCHAR(50) NOT NULL,
    merged_fingerprint VARCHAR(80),
    PRIMARY KEY (tenant_key, catalog_id, version, merged_version),
    FOREIGN KEY (tenant_key, catalog_id, version) REFERENCES catalog_releases (tenant_key, catalog_id, version) ON DELETE CASCADE
);

COMMENT ON TABLE catalog_release_merges IS
    'The releases merged into a release''s draft before it was released (ADR 0032): its merge parents, beside its base.';
COMMENT ON COLUMN catalog_release_merges.version IS 'The release that contains them.';
COMMENT ON COLUMN catalog_release_merges.merged_version IS 'A release of the same catalog merged into its draft. Not a foreign key: it may be deleted later, or not be installed here.';
COMMENT ON COLUMN catalog_release_merges.merged_fingerprint IS 'The qualified catalog fingerprint of merged_version, as recorded or received; NULL when not known.';
