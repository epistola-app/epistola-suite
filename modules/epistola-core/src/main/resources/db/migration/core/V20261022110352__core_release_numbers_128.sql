-- A release number may be up to 128 characters, and a patch release is numbered
-- MAJOR.MINOR.PATCH-<namespace>.<N> (contract ADR 0003, amendment of 2026-10-22; ADR 0034).
--
-- Someone who does not hold a catalog's namespace may only issue a patch release, labelled with a
-- namespace they hold and a sequence number from 1 (`1.3.1-gemeente-x.1`), so that every valid
-- namespace (up to 63 characters) can label one, a release number is up to 128 characters. Every
-- column that holds a release number grows from VARCHAR(50) (VARCHAR(100) for a pin) to
-- VARCHAR(128); widening a VARCHAR rewrites nothing.
--
-- A patch release sorts as SemVer says: above 1.3.0 and below 1.3.1. The generated columns that
-- order releases read the core of a patch number too, and two more name its label: the namespace
-- (compared as ASCII, as SemVer compares an alphanumeric identifier) and the sequence number. A
-- release without a label sorts above every patch release of its core. The generated columns are
-- recreated, as a column they are generated from cannot change its type under them.

-- The generated ordering columns, and the index on them, go first, and so does the trigger that names
-- installed_release_version (it is created again as it was, below).
DROP TRIGGER trg_catalogs_installed_version_update ON catalogs;
DROP INDEX idx_catalog_releases_version_order;
ALTER TABLE catalog_releases DROP COLUMN version_major, DROP COLUMN version_minor, DROP COLUMN version_patch;
ALTER TABLE catalog_versions DROP COLUMN version_major, DROP COLUMN version_minor, DROP COLUMN version_patch;

ALTER TABLE catalogs
    ALTER COLUMN installed_release_version TYPE VARCHAR(128),
    ALTER COLUMN released_version TYPE VARCHAR(128);
ALTER TABLE catalog_releases
    ALTER COLUMN version TYPE VARCHAR(128),
    ALTER COLUMN base_version TYPE VARCHAR(128);
ALTER TABLE catalog_versions
    ALTER COLUMN version TYPE VARCHAR(128),
    ALTER COLUMN base_version TYPE VARCHAR(128),
    ALTER COLUMN created_from TYPE VARCHAR(128);
ALTER TABLE catalog_dependencies ALTER COLUMN release_version TYPE VARCHAR(128);
ALTER TABLE catalog_release_dependencies
    ALTER COLUMN version TYPE VARCHAR(128),
    ALTER COLUMN release_version TYPE VARCHAR(128);
ALTER TABLE catalog_release_merges
    ALTER COLUMN version TYPE VARCHAR(128),
    ALTER COLUMN merged_version TYPE VARCHAR(128);
ALTER TABLE catalog_draft_merges ALTER COLUMN release_version TYPE VARCHAR(128);
ALTER TABLE catalog_release_publications ALTER COLUMN version TYPE VARCHAR(128);
ALTER TABLE release_resources ALTER COLUMN version TYPE VARCHAR(128);
ALTER TABLE environment_catalog_deployments ALTER COLUMN version TYPE VARCHAR(128);
ALTER TABLE environment_deployment_log
    ALTER COLUMN version TYPE VARCHAR(128),
    ALTER COLUMN previous_version TYPE VARCHAR(128);
ALTER TABLE document_generation_requests ALTER COLUMN release_version TYPE VARCHAR(128);
ALTER TABLE documents ALTER COLUMN release_version TYPE VARCHAR(128);
ALTER TABLE generation_results ALTER COLUMN release_version TYPE VARCHAR(128);
ALTER TABLE catalog_upgrade_queue ALTER COLUMN continues_after TYPE VARCHAR(128);

CREATE TRIGGER trg_catalogs_installed_version_update
    AFTER UPDATE OF installed_release_version ON catalogs
    FOR EACH ROW WHEN (NEW.installed_release_version IS DISTINCT FROM OLD.installed_release_version)
EXECUTE FUNCTION catalog_installed_version();

-- The ordering columns again, reading the core of a patch number, and its label.
ALTER TABLE catalog_releases
    ADD COLUMN version_major INTEGER GENERATED ALWAYS AS ((substring(version, '^(\d+)\.\d+\.\d+(?:-[a-z][a-z0-9-]*\.[1-9][0-9]*)?$'))::integer) STORED,
    ADD COLUMN version_minor INTEGER GENERATED ALWAYS AS ((substring(version, '^\d+\.(\d+)\.\d+(?:-[a-z][a-z0-9-]*\.[1-9][0-9]*)?$'))::integer) STORED,
    ADD COLUMN version_patch INTEGER GENERATED ALWAYS AS ((substring(version, '^\d+\.\d+\.(\d+)(?:-[a-z][a-z0-9-]*\.[1-9][0-9]*)?$'))::integer) STORED,
    ADD COLUMN version_label TEXT GENERATED ALWAYS AS (substring(version, '^\d+\.\d+\.\d+-([a-z][a-z0-9-]*)\.[1-9][0-9]*$')) STORED,
    ADD COLUMN version_label_number INTEGER GENERATED ALWAYS AS ((substring(version, '^\d+\.\d+\.\d+-[a-z][a-z0-9-]*\.([1-9][0-9]*)$'))::integer) STORED;
ALTER TABLE catalog_versions
    ADD COLUMN version_major INTEGER GENERATED ALWAYS AS ((substring(version, '^(\d+)\.\d+\.\d+(?:-[a-z][a-z0-9-]*\.[1-9][0-9]*)?$'))::integer) STORED,
    ADD COLUMN version_minor INTEGER GENERATED ALWAYS AS ((substring(version, '^\d+\.(\d+)\.\d+(?:-[a-z][a-z0-9-]*\.[1-9][0-9]*)?$'))::integer) STORED,
    ADD COLUMN version_patch INTEGER GENERATED ALWAYS AS ((substring(version, '^\d+\.\d+\.(\d+)(?:-[a-z][a-z0-9-]*\.[1-9][0-9]*)?$'))::integer) STORED,
    ADD COLUMN version_label TEXT GENERATED ALWAYS AS (substring(version, '^\d+\.\d+\.\d+-([a-z][a-z0-9-]*)\.[1-9][0-9]*$')) STORED,
    ADD COLUMN version_label_number INTEGER GENERATED ALWAYS AS ((substring(version, '^\d+\.\d+\.\d+-[a-z][a-z0-9-]*\.([1-9][0-9]*)$'))::integer) STORED;

CREATE INDEX idx_catalog_releases_version_order
    ON catalog_releases (tenant_key, catalog_id, version_major DESC, version_minor DESC, version_patch DESC,
                         (version_label IS NULL) DESC, version_label COLLATE "C" DESC, version_label_number DESC);

COMMENT ON COLUMN catalog_releases.version_label IS
    'The namespace a patch release is labelled with (1.3.1-gemeente-x.1: gemeente-x), null for a release without a label.';
COMMENT ON COLUMN catalog_releases.version_label_number IS
    'The sequence number of a patch release (1.3.1-gemeente-x.1: 1), null for a release without a label.';
