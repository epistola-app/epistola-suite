-- ADR 0032: a catalog has no installed copy any more. What a subscribed catalog's `installed` row
-- held becomes its next version, on the release it held as its base; its releases stay in their
-- snapshots. Content rows keep their version_id, so nothing moves.
UPDATE catalog_versions
SET kind = 'draft', is_next = TRUE, base_version = NULLIF(version, '0.0.0'), version = NULL
WHERE kind = 'installed';

-- What a draft held when a release was put in place in it, per resource (its fingerprints), so that
-- changes of its own are told apart from how an installed copy differs from its publisher's digests.
-- Set when a received release is recorded, cleared whenever the draft moves to another base.
ALTER TABLE catalog_versions ADD COLUMN base_fingerprints JSONB;
COMMENT ON COLUMN catalog_versions.base_fingerprints IS
    'The per-resource fingerprints of the draft as a received release put it in place; NULL compares it with its base release instead (ADR 0032).';

DROP INDEX uq_catalog_versions_one_installed;
ALTER TABLE catalog_versions DROP CONSTRAINT chk_catalog_versions_kind;
ALTER TABLE catalog_versions ADD CONSTRAINT chk_catalog_versions_kind CHECK (kind = 'draft');

COMMENT ON TABLE catalog_versions IS
    'The drafts of a catalog, whose content is in the content tables: its next version and any other open draft. Releases are in catalog_releases with their snapshots (ADR 0032).';
COMMENT ON COLUMN catalog_versions.kind IS
    'Always ''draft'' since ADR 0032; the installed kind is gone.';
COMMENT ON COLUMN catalog_versions.version IS
    'Always NULL since ADR 0032: a draft is numbered when it is released.';

-- A catalog received from a source keeps its next version on the release it installs: created with
-- the catalog, and moved to the new release when an install brings the next version along (a
-- fast-forward). A release recorded beside the others, because the next version has changes of its
-- own, does not move installed_release_version, so the next version stays behind it.
CREATE OR REPLACE FUNCTION catalog_installed_version() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        INSERT INTO catalog_versions (tenant_key, catalog_id, kind, is_next, base_version)
        VALUES (NEW.tenant_key, NEW.catalog_id, 'draft', TRUE, NEW.installed_release_version);
    ELSE
        UPDATE catalog_versions
        SET base_version = NEW.installed_release_version, created_at = NOW(), merged_releases = '{}', base_fingerprints = NULL
        WHERE tenant_key = NEW.tenant_key AND catalog_id = NEW.catalog_id AND is_next;
    END IF;
    RETURN NEW;
END;
$$;

-- The version a caller means: the one its transaction targets (epistola.content_version), else the
-- catalog's next version.
CREATE OR REPLACE VIEW catalog_current_versions AS
SELECT DISTINCT ON (v.tenant_key, v.catalog_id) v.tenant_key, v.catalog_id, v.version_id
FROM catalog_versions v
WHERE v.version_id::text = current_setting('epistola.content_version', TRUE) OR v.is_next
ORDER BY v.tenant_key, v.catalog_id,
         (v.version_id::text = current_setting('epistola.content_version', TRUE)) IS TRUE DESC;

COMMENT ON VIEW catalog_current_versions IS
    'Per catalog, the version a caller means: the one its transaction targets (epistola.content_version), else its next version.';
