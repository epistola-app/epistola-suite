-- ADR 0036: the release a catalog installed is a query over its versions, not a pointer.
--
-- `catalogs.installed_release_version`, `installed_fingerprint` and `installed_at` named the release
-- last received from the publisher, and a trigger moved the next version to it: the pointer was
-- written first and the release row after, so the two could disagree, and the fingerprint was the
-- publisher's, taken as given. Receiving now records the release first and moves the next version
-- to it. The installed release is the nearest received one the next version descends from: its base,
-- or, when the catalog released here since, that release's base, and so on.
-- `installed_resource_fingerprints`, the baseline an upgrade preview read, is computed again from
-- the source instead.

-- The next version named its base by number only where the trigger moved it.
UPDATE catalog_versions v
SET base_version_id = r.version_id
FROM catalog_versions r
WHERE v.base_version_id IS NULL AND v.base_version IS NOT NULL
  AND r.tenant_key = v.tenant_key AND r.catalog_id = v.catalog_id AND r.state = 'released' AND r.version = v.base_version;

-- A catalog opens its next version on no release; receiving one moves it there.
CREATE FUNCTION catalog_open_next_version() RETURNS TRIGGER AS $$
DECLARE
    created UUID;
BEGIN
    IF NEW.next_version_id IS NULL THEN
        INSERT INTO catalog_versions (tenant_key, catalog_id, state)
        VALUES (NEW.tenant_key, NEW.catalog_id, 'draft')
        RETURNING version_id INTO created;
        UPDATE catalogs SET next_version_id = created
        WHERE tenant_key = NEW.tenant_key AND catalog_id = NEW.catalog_id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER trg_catalogs_installed_version_update ON catalogs;
DROP TRIGGER trg_catalogs_installed_version_insert ON catalogs;
DROP FUNCTION catalog_installed_version();
CREATE TRIGGER trg_catalogs_open_next_version
    AFTER INSERT ON catalogs
    FOR EACH ROW
EXECUTE FUNCTION catalog_open_next_version();

-- The received release version [version_id] descends from, nearest first: itself, its base, its
-- base's base, until one received from the publisher.
CREATE FUNCTION catalog_installed_release_id(p_tenant TENANT_KEY, p_version UUID) RETURNS UUID AS $$
    WITH RECURSIVE chain AS (
        SELECT v.version_id, v.base_version_id, v.state, v.origin, 0 AS depth
        FROM catalog_versions v
        WHERE v.tenant_key = p_tenant AND v.version_id = p_version
        UNION ALL
        SELECT b.version_id, b.base_version_id, b.state, b.origin, chain.depth + 1
        FROM chain
        JOIN catalog_versions b ON b.tenant_key = p_tenant AND b.version_id = chain.base_version_id
        WHERE NOT (chain.state = 'released' AND chain.origin = 'installed')
    )
    SELECT version_id FROM chain WHERE state = 'released' AND origin = 'installed' ORDER BY depth LIMIT 1;
$$ LANGUAGE sql STABLE;

CREATE VIEW catalog_installed_releases AS
SELECT c.tenant_key, c.catalog_id, r.version_id, r.version, r.fingerprint, r.released_at
FROM catalogs c
JOIN catalog_versions r ON r.tenant_key = c.tenant_key AND r.version_id = catalog_installed_release_id(c.tenant_key, c.next_version_id);

COMMENT ON VIEW catalog_installed_releases IS
    'Per catalog, the release it installed (ADR 0036): the nearest release received from its publisher that its next version descends from. What catalogs.installed_release_version and its companions pointed at.';

ALTER TABLE catalogs
    DROP COLUMN installed_release_version,
    DROP COLUMN installed_fingerprint,
    DROP COLUMN installed_resource_fingerprints,
    DROP COLUMN installed_at;
