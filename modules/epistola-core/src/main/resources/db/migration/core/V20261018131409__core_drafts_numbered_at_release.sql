-- A draft is numbered when it is released (contract ADR 0002, amendment 2).
--
-- Until now a draft was identified by a version number chosen when it was opened, renumbered as the
-- work became known, and the catalog's current version was its highest-numbered draft. Now:
--
-- - a draft is identified by its version_id, and has no number until it is released;
-- - every authored catalog has exactly one next version (is_next), the draft everyday work goes
--   into, and the one a caller that names no version means;
-- - every other draft is long-term work, and has a name people see.
--
-- Existing drafts keep their content, base and identity. The highest-numbered one, the one callers
-- meant until now, becomes the next version; any other keeps its old number as its name, so its
-- authors still recognise it.

ALTER TABLE catalog_versions ADD COLUMN is_next BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE catalog_versions ADD COLUMN name VARCHAR(100);

UPDATE catalog_versions v SET is_next = TRUE
WHERE v.kind = 'draft'
  AND v.version_id = (
      SELECT d.version_id FROM catalog_versions d
      WHERE d.tenant_key = v.tenant_key AND d.catalog_id = v.catalog_id AND d.kind = 'draft'
      ORDER BY d.version_major DESC NULLS LAST, d.version_minor DESC NULLS LAST, d.version_patch DESC NULLS LAST, d.version DESC
      LIMIT 1
  );

UPDATE catalog_versions SET name = 'Version ' || version WHERE kind = 'draft' AND NOT is_next;

-- An authored catalog whose every draft was discarded has no next version until one is opened again
-- (DiscardCatalogDraft reopens it on the latest release; the application never leaves one without).
-- None is expected: the UI always kept a draft open, and the API's draft deletion is not released.

ALTER TABLE catalog_versions ALTER COLUMN version DROP NOT NULL;
UPDATE catalog_versions SET version = NULL WHERE kind = 'draft';

ALTER TABLE catalog_versions ADD CONSTRAINT chk_catalog_versions_numbered
    CHECK ((kind = 'draft') = (version IS NULL));
ALTER TABLE catalog_versions ADD CONSTRAINT chk_catalog_versions_next_is_draft
    CHECK (NOT is_next OR kind = 'draft');
ALTER TABLE catalog_versions ADD CONSTRAINT chk_catalog_versions_long_term_named
    CHECK (kind <> 'draft' OR is_next OR name IS NOT NULL);

CREATE UNIQUE INDEX uq_catalog_versions_one_next ON catalog_versions (tenant_key, catalog_id) WHERE is_next;

COMMENT ON COLUMN catalog_versions.version IS
    'The installed release of a subscribed catalog. NULL for a draft, which is numbered when it is released.';
COMMENT ON COLUMN catalog_versions.is_next IS
    'The catalog''s next version: the draft everyday work goes into, and the one a caller that names no version means. One per authored catalog.';
COMMENT ON COLUMN catalog_versions.name IS
    'What people call a draft that is long-term work (not the next version), e.g. "Redesign 2027". NULL for the next version, which is always "Next version".';
COMMENT ON COLUMN catalog_versions.created_from IS
    'The released version a draft was copied from when that is not its base; ''next'' when it was copied from the next version, unreleased changes included.';

-- The version a caller means: the one its transaction targets (epistola.content_version), else a
-- subscribed catalog's installed release, else the authored catalog's next version. Long-term work
-- is reached only by targeting it: a catalog without a next version has no current version, so a
-- write that names none fails instead of landing in long-term work.
CREATE OR REPLACE VIEW catalog_current_versions AS
SELECT DISTINCT ON (v.tenant_key, v.catalog_id) v.tenant_key, v.catalog_id, v.version_id
FROM catalog_versions v
WHERE v.version_id::text = current_setting('epistola.content_version', TRUE) OR v.kind = 'installed' OR v.is_next
ORDER BY v.tenant_key, v.catalog_id,
         (v.version_id::text = current_setting('epistola.content_version', TRUE)) IS TRUE DESC,
         (v.kind = 'installed') DESC;

COMMENT ON VIEW catalog_current_versions IS
    'Per catalog, the version a caller means: the one its transaction targets (epistola.content_version), else a subscribed catalog''s installed release, else the authored catalog''s next version.';
