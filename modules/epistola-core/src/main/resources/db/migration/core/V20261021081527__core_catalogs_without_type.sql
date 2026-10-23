-- ADR 0032: a catalog has no type. Whether it can be changed is a switch of the installation and
-- whether Epistola supplies it; where its releases come from is its source (source_url).
--
-- The column stays, so no data is dropped (it goes in a later major release), but the suite no longer
-- reads or writes it: a catalog inserted without one gets the default. Only the 1.x-to-2.0 upgrade,
-- which reasons about 1.x data, still reads it.
ALTER TABLE catalogs ALTER COLUMN type SET DEFAULT 'AUTHORED';
COMMENT ON COLUMN catalogs.type IS
    'Unused since ADR 0032 (catalogs have no type); kept for the 1.x-to-2.0 upgrade and removed in a later major release. Defaults to ''AUTHORED'' for catalogs created since.';

-- Every catalog has a next version, whoever creates it: opened here when the catalog is inserted, on
-- the release it was inserted with (installed_release_version), and moved to the release an install
-- puts in place (a fast-forward). The insert leaves a catalog that already has a next version alone.
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
        SET base_version = NEW.installed_release_version, created_at = NOW(), merged_releases = '{}', base_fingerprints = NULL
        WHERE tenant_key = NEW.tenant_key AND catalog_id = NEW.catalog_id AND is_next;
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER trg_catalogs_installed_version_insert ON catalogs;
CREATE TRIGGER trg_catalogs_installed_version_insert
    AFTER INSERT ON catalogs
    FOR EACH ROW
EXECUTE FUNCTION catalog_installed_version();

DROP TRIGGER trg_catalogs_installed_version_update ON catalogs;
CREATE TRIGGER trg_catalogs_installed_version_update
    AFTER UPDATE OF installed_release_version ON catalogs
    FOR EACH ROW WHEN (NEW.installed_release_version IS DISTINCT FROM OLD.installed_release_version)
EXECUTE FUNCTION catalog_installed_version();

COMMENT ON COLUMN catalog_versions.is_next IS
    'The catalog''s next version: the draft everyday work goes into, and the one a caller that names no version means. Every catalog has one (ADR 0032), opened when the catalog is inserted.';
