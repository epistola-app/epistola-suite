-- SPDX-FileCopyrightText: Epistola Nederland B.V.
--
-- SPDX-License-Identifier: AGPL-3.0-only

-- A catalog consists of versions (contract ADR 0002): released ones, which catalog_releases holds,
-- and the versions whose content lives in the content tables, which this table identifies:
--
-- - each open draft of an authored catalog: its number, the released version it started from (its
--   base) and, when it was created from another version, that one;
-- - the installed release of a subscribed catalog: its content is the mirror an install put in
--   place, read-only, numbered by the installed release.
--
-- A content row belongs to one of these by version_id, so that several drafts can each hold their
-- own copy of a resource. For now a catalog has one such version: uq_catalog_versions_one_per_catalog
-- holds that until the content and every reader of it name the version they mean.
CREATE TABLE catalog_versions (
    tenant_key    TENANT_KEY  NOT NULL,
    version_id    UUID        NOT NULL DEFAULT epistola_uuidv7(),
    catalog_id    UUID        NOT NULL,
    version       VARCHAR(50) NOT NULL,
    kind          VARCHAR(16) NOT NULL,
    base_version  VARCHAR(50),
    created_from  VARCHAR(50),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    version_major INTEGER GENERATED ALWAYS AS ((substring(version, '^(\d+)\.\d+\.\d+$'))::integer) STORED,
    version_minor INTEGER GENERATED ALWAYS AS ((substring(version, '^\d+\.(\d+)\.\d+$'))::integer) STORED,
    version_patch INTEGER GENERATED ALWAYS AS ((substring(version, '^\d+\.\d+\.(\d+)$'))::integer) STORED,
    PRIMARY KEY (tenant_key, version_id),
    CONSTRAINT uq_catalog_versions_number UNIQUE (tenant_key, catalog_id, version),
    -- The target of the content tables' (tenant_key, version_id, catalog_id) references, which keep a
    -- content row's catalog_id and version_id in agreement.
    CONSTRAINT uq_catalog_versions_catalog UNIQUE (tenant_key, version_id, catalog_id),
    CONSTRAINT chk_catalog_versions_kind CHECK (kind IN ('draft', 'installed')),
    FOREIGN KEY (tenant_key, catalog_id) REFERENCES catalogs (tenant_key, catalog_id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX uq_catalog_versions_one_per_catalog ON catalog_versions (tenant_key, catalog_id);

COMMENT ON TABLE catalog_versions IS
    'The versions of a catalog whose content is in the content tables: each open draft of an authored catalog, and the installed release of a subscribed one.';
COMMENT ON COLUMN catalog_versions.kind IS
    '''draft'': an editable draft of an authored catalog; ''installed'': the read-only installed release of a subscribed catalog.';

-- Every authored catalog gets the draft it is working on: the patch after its latest release, or
-- 1.0.0 when it was never released. A release whose version is not SemVer has no order and is
-- skipped as a base; the authored releases the suite cut always are.
INSERT INTO catalog_versions (tenant_key, catalog_id, version, kind, base_version)
SELECT c.tenant_key, c.catalog_id,
       COALESCE(latest.version_major || '.' || latest.version_minor || '.' || (latest.version_patch + 1), '1.0.0'),
       'draft',
       latest.version
FROM catalogs c
LEFT JOIN LATERAL (
    SELECT r.version, r.version_major, r.version_minor, r.version_patch
    FROM catalog_releases r
    WHERE r.tenant_key = c.tenant_key AND r.catalog_id = c.catalog_id AND r.version_major IS NOT NULL
    ORDER BY r.version_major DESC, r.version_minor DESC, r.version_patch DESC
    LIMIT 1
) latest ON TRUE
WHERE c.type = 'AUTHORED';

-- Every subscribed catalog's content is its installed release, numbered by it ('0.0.0' until it
-- installs one). Kept so by the triggers below, whoever creates or upgrades it.
INSERT INTO catalog_versions (tenant_key, catalog_id, version, kind)
SELECT tenant_key, catalog_id, COALESCE(installed_release_version, '0.0.0'), 'installed'
FROM catalogs
WHERE type = 'SUBSCRIBED';

CREATE FUNCTION catalog_installed_version() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        INSERT INTO catalog_versions (tenant_key, catalog_id, version, kind)
        VALUES (NEW.tenant_key, NEW.catalog_id, COALESCE(NEW.installed_release_version, '0.0.0'), 'installed');
    ELSE
        UPDATE catalog_versions
        SET version = COALESCE(NEW.installed_release_version, '0.0.0')
        WHERE tenant_key = NEW.tenant_key AND catalog_id = NEW.catalog_id AND kind = 'installed';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_catalogs_installed_version_insert
    AFTER INSERT ON catalogs
    FOR EACH ROW WHEN (NEW.type = 'SUBSCRIBED')
EXECUTE FUNCTION catalog_installed_version();

CREATE TRIGGER trg_catalogs_installed_version_update
    AFTER UPDATE OF installed_release_version ON catalogs
    FOR EACH ROW WHEN (NEW.type = 'SUBSCRIBED' AND NEW.installed_release_version IS DISTINCT FROM OLD.installed_release_version)
EXECUTE FUNCTION catalog_installed_version();
