-- The catalogs of a 1.x installation, queued for what 2.0 needs of them and SQL cannot do; and the
-- alpha toggle 2.0 retires.

-- The catalogs of a 1.x installation, queued for what 2.0 needs of them and SQL cannot do: which
-- catalogs a catalog references is read from its content by application code, and a release is cut
-- or recorded by it. For an authored catalog:
--
-- - Declarations: a 1.x catalog references other catalogs (the system catalog's fonts and
--   attributes, a shared theme) without declaring them, and 2.0 refuses to release it until it does
--   (contract ADR 0003).
-- - A first release: no 1.x release kept its content, so nothing could render. Each catalog gets one,
--   cut from what was last published, numbered the patch after its highest 1.x release (or 1.0.0),
--   and deployed nowhere.
--
-- For a subscribed catalog with an installed release: that release recorded as one it can render
-- from, with the content the install put in place. 1.x kept none, so its templates could not render.
--
-- CatalogUpgradeRunner does both and removes the catalog from the queue; a catalog it cannot do yet
-- (a dependency not released) stays, with the reason. A catalog created under 2.0 is never queued.
CREATE TABLE catalog_upgrade_queue (
    tenant_key TENANT_KEY NOT NULL,
    catalog_id UUID       NOT NULL,
    last_error TEXT,
    continues_after VARCHAR(50),
    PRIMARY KEY (tenant_key, catalog_id),
    FOREIGN KEY (tenant_key, catalog_id) REFERENCES catalogs (tenant_key, catalog_id) ON DELETE CASCADE
);

COMMENT ON TABLE catalog_upgrade_queue IS
    'Catalogs from before 2.0 still to be given what 2.0 needs (declarations and a first release, or a recorded installed release); emptied by CatalogUpgradeRunner.';
COMMENT ON COLUMN catalog_upgrade_queue.last_error IS
    'Why the last attempt did not finish; the runner tries again.';
COMMENT ON COLUMN catalog_upgrade_queue.continues_after IS
    'The highest SemVer release of the catalog in 1.x; its first 2.0 release is the patch after it, so numbering does not go back.';

INSERT INTO catalog_upgrade_queue (tenant_key, catalog_id, continues_after)
SELECT c.tenant_key, c.catalog_id, latest.version
FROM catalogs c
LEFT JOIN LATERAL (
    SELECT r.version
    FROM catalog_releases r
    WHERE r.tenant_key = c.tenant_key AND r.catalog_id = c.catalog_id AND r.version_major IS NOT NULL
    ORDER BY r.version_major DESC, r.version_minor DESC, r.version_patch DESC
    LIMIT 1
) latest ON TRUE
WHERE c.type = 'AUTHORED' OR (c.type = 'SUBSCRIBED' AND c.installed_release_version IS NOT NULL);

-- The 1.x releases go. A 1.x release is a number, a fingerprint and notes: it kept no content, so
-- nothing can render, export or deploy it, and Exchange, the one place its number could matter, was
-- not in use. Each catalog's first 2.0 release (above) replaces them. A deliberate exception to
-- "no destructive migrations" (docs/migrations.md), decided for the 2.0 upgrade. Every row in the
-- table is one: 2.0 has written none yet.
DELETE FROM catalog_releases;

-- The `catalog-context` feature toggle is retired: in 2.0 every catalog page states the catalog
-- version it shows, always (docs/catalog-workspace-navigation.md). Delete any tenant override rows
-- so no dangling state remains.
DELETE FROM feature_toggles
WHERE feature_key = 'catalog-context';
