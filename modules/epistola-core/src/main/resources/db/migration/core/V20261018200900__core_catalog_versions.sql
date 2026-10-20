-- A catalog consists of versions (contract ADR 0002): released ones, which catalog_releases holds,
-- and the versions whose content lives in the content tables, which this table identifies:
-- - drafts of an authored catalog. A draft is numbered when it is released (ADR 0002, amendment 2),
--   so it has no number here: it is identified by its version_id. Every authored catalog has one
--   next version (is_next), the draft everyday work goes into and the one a caller that names no
--   version means; any other draft is long-term work, with a name people see.
-- - the installed release of a subscribed catalog: its content is the mirror an install put in
--   place, read-only, numbered by the installed release.
-- A content row belongs to one of these by version_id (the content-per-version migration), so several
-- drafts can each hold their own copy of a resource.
CREATE TABLE catalog_versions (
    tenant_key    TENANT_KEY  NOT NULL,
    version_id    UUID        NOT NULL DEFAULT epistola_uuidv7(),
    -- The draft's public id (contract CatalogVersionId), which its release keeps. version_id stays
    -- the internal identity the content rows reference.
    version_key   VARCHAR(36) NOT NULL DEFAULT epistola_version_key(),
    catalog_id    UUID        NOT NULL,
    version       VARCHAR(50),
    kind          VARCHAR(16) NOT NULL,
    base_version  VARCHAR(50),
    created_from  VARCHAR(50),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    version_major INTEGER GENERATED ALWAYS AS ((substring(version, '^(\d+)\.\d+\.\d+$'))::integer) STORED,
    version_minor INTEGER GENERATED ALWAYS AS ((substring(version, '^\d+\.(\d+)\.\d+$'))::integer) STORED,
    version_patch INTEGER GENERATED ALWAYS AS ((substring(version, '^\d+\.\d+\.(\d+)$'))::integer) STORED,
    is_next       BOOLEAN     NOT NULL DEFAULT FALSE,
    name          VARCHAR(100),
    PRIMARY KEY (tenant_key, version_id),
    CONSTRAINT uq_catalog_versions_number UNIQUE (tenant_key, catalog_id, version),
    CONSTRAINT uq_catalog_versions_key UNIQUE (tenant_key, catalog_id, version_key),
    -- The target of the content tables' (tenant_key, version_id, catalog_id) references, which keep a
    -- content row's catalog_id and version_id in agreement.
    CONSTRAINT uq_catalog_versions_catalog UNIQUE (tenant_key, version_id, catalog_id),
    CONSTRAINT chk_catalog_versions_kind CHECK (kind IN ('draft', 'installed')),
    CONSTRAINT chk_catalog_versions_numbered CHECK ((kind = 'draft') = (version IS NULL)),
    CONSTRAINT chk_catalog_versions_next_is_draft CHECK (NOT is_next OR kind = 'draft'),
    CONSTRAINT chk_catalog_versions_long_term_named CHECK (kind <> 'draft' OR is_next OR name IS NOT NULL),
    FOREIGN KEY (tenant_key, catalog_id) REFERENCES catalogs (tenant_key, catalog_id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX uq_catalog_versions_one_installed ON catalog_versions (tenant_key, catalog_id) WHERE kind = 'installed';
CREATE UNIQUE INDEX uq_catalog_versions_one_next ON catalog_versions (tenant_key, catalog_id) WHERE is_next;

COMMENT ON TABLE catalog_versions IS
    'The versions of a catalog whose content is in the content tables: every open draft of an authored catalog, and the installed release of a subscribed one.';
COMMENT ON COLUMN catalog_versions.kind IS
    '''draft'': an editable draft of an authored catalog; ''installed'': the read-only installed release of a subscribed catalog.';

COMMENT ON COLUMN catalog_versions.version IS
    'The installed release of a subscribed catalog. NULL for a draft, which is numbered when it is released.';
COMMENT ON COLUMN catalog_versions.is_next IS
    'The catalog''s next version: the draft everyday work goes into, and the one a caller that names no version means. One per authored catalog.';
COMMENT ON COLUMN catalog_versions.name IS
    'What people call a draft that is long-term work (not the next version), e.g. "Redesign 2027". NULL for the next version, which is always "Next version".';
COMMENT ON COLUMN catalog_versions.created_from IS
    'The version_key of the draft this draft was copied from, unreleased changes included; NULL when it was started from a release, its base, or from nothing.';
COMMENT ON COLUMN catalog_versions.version_key IS
    'The draft''s id (contract CatalogVersionId). Releasing the draft gives its release this id; the next version then continues under a new one.';

-- Every authored catalog's working copy becomes its next version. It has no base: no 1.x release
-- kept its content, so the upgrade removes them (V20261018201400) and cuts a first release instead.
INSERT INTO catalog_versions (tenant_key, catalog_id, kind, is_next)
SELECT tenant_key, catalog_id, 'draft', TRUE
FROM catalogs
WHERE type = 'AUTHORED';

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

-- The version a caller means: the one its transaction targets (epistola.content_version), else a
-- subscribed catalog's installed release, else the authored catalog's next version. Long-term work
-- is reached only by targeting it: a catalog without a next version has no current version, so a
-- write that names none fails instead of landing in long-term work.
CREATE VIEW catalog_current_versions AS
SELECT DISTINCT ON (v.tenant_key, v.catalog_id) v.tenant_key, v.catalog_id, v.version_id
FROM catalog_versions v
WHERE v.version_id::text = current_setting('epistola.content_version', TRUE) OR v.kind = 'installed' OR v.is_next
ORDER BY v.tenant_key, v.catalog_id,
         (v.version_id::text = current_setting('epistola.content_version', TRUE)) IS TRUE DESC,
         (v.kind = 'installed') DESC;

COMMENT ON VIEW catalog_current_versions IS
    'Per catalog, the version a caller means: the one its transaction targets (epistola.content_version), else a subscribed catalog''s installed release, else the authored catalog''s next version.';
