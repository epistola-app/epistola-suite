-- The resources of any catalog version, draft or released, read in one place (ADR 0033, step 1).
--
-- A release's resources are `release_resources` rows naming the release by its number. A draft's
-- resources become rows too, as each resource type moves to JSON storage: until `catalog_versions`
-- folds into `catalog_releases` (ADR 0032 section 9, the last step), a draft row names its draft by
-- `version_id` instead. Exactly one of the two is set.

ALTER TABLE release_resources DROP CONSTRAINT pk_release_resources;
ALTER TABLE release_resources ALTER COLUMN version DROP NOT NULL;
ALTER TABLE release_resources ADD COLUMN version_id UUID;
-- A draft row is of its draft's catalog, and goes with the draft.
ALTER TABLE release_resources ADD CONSTRAINT fk_release_resources_draft
    FOREIGN KEY (tenant_key, version_id, catalog_id) REFERENCES catalog_versions (tenant_key, version_id, catalog_id) ON DELETE CASCADE;
ALTER TABLE release_resources ADD CONSTRAINT chk_release_resources_release_or_draft
    CHECK ((version IS NULL) <> (version_id IS NULL));
CREATE UNIQUE INDEX uq_release_resources_release ON release_resources (tenant_key, catalog_id, version, resource_type, resource_key)
    WHERE version IS NOT NULL;
CREATE UNIQUE INDEX uq_release_resources_draft ON release_resources (tenant_key, version_id, resource_type, resource_key)
    WHERE version_id IS NOT NULL;
-- A draft resource is compared by its content hash; its contract fingerprint is computed when it is released.
ALTER TABLE release_resources ALTER COLUMN fingerprint DROP NOT NULL;
ALTER TABLE release_resources ADD CONSTRAINT chk_release_resources_released_fingerprint
    CHECK (version IS NULL OR fingerprint IS NOT NULL);
-- What a draft row is edited by: its optimistic lock, and who changed it when. A released row keeps
-- when it was released.
ALTER TABLE release_resources
    ADD COLUMN revision   BIGINT      NOT NULL DEFAULT 1,
    ADD COLUMN created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    ADD COLUMN created_by UUID REFERENCES users (id) ON DELETE SET NULL,
    ADD COLUMN updated_by UUID REFERENCES users (id) ON DELETE SET NULL;

COMMENT ON COLUMN release_resources.version IS
    'The release this row is of, by its number; NULL for a draft row, which names its draft by version_id.';
COMMENT ON COLUMN release_resources.version_id IS
    'The draft (catalog_versions) this row is of; NULL for a released row. Goes when catalog_versions folds into catalog_releases.';
COMMENT ON COLUMN release_resources.revision IS
    'A draft row''s optimistic lock, raised by every change (Revisions); 1 for a released row.';

-- The one place that says which rows a caller means (ADR 0033): per catalog, the rows of the
-- version its transaction targets. A message that names a release sets `epistola.release_catalog`
-- and `epistola.release_version`, and that catalog's rows are the release's; every other catalog,
-- and that one otherwise, is read at the draft `catalog_current_versions` names (the targeted draft,
-- else the next version). When catalog_versions folds, this view is what changes.
CREATE VIEW catalog_version_resources AS
SELECT r.*
FROM release_resources r
WHERE r.version_id IS NOT NULL
  AND r.version_id IN (SELECT cv.version_id FROM catalog_current_versions cv WHERE cv.tenant_key = r.tenant_key)
  AND r.catalog_id IS DISTINCT FROM NULLIF(current_setting('epistola.release_catalog', TRUE), '')::uuid
UNION ALL
SELECT r.*
FROM release_resources r
WHERE r.version IS NOT NULL
  AND r.catalog_id = NULLIF(current_setting('epistola.release_catalog', TRUE), '')::uuid
  AND r.version = current_setting('epistola.release_version', TRUE);

COMMENT ON VIEW catalog_version_resources IS
    'Per catalog, the resource rows of the version a caller means: the release its transaction targets (epistola.release_catalog, epistola.release_version), else the draft catalog_current_versions names.';

-- The themes of the version a caller means, each as its JSON content (ADR 0033, step 1a). Drafts
-- still keep themes in `themes` (moved in step 1b), so their content is built from the row here,
-- the wire form a release keeps; a targeted release's come from its rows. One mapper reads both.
CREATE VIEW current_themes AS
SELECT u.*,
       u.payload ->> 'description' AS description,
       COALESCE(u.payload -> 'documentStyles', '{}'::jsonb) AS document_styles,
       NULLIF(u.payload -> 'pageSettings', 'null'::jsonb) AS page_settings,
       NULLIF(u.payload -> 'blockStylePresets', 'null'::jsonb) AS block_style_presets,
       CAST(u.payload ->> 'spacingUnit' AS NUMERIC(6, 2)) AS spacing_unit
FROM (
    SELECT t.tenant_key, t.catalog_id, t.version_id, CAST(NULL AS VARCHAR(128)) AS version, t.resource_id, t.id::text AS id, t.name::text AS name,
           jsonb_build_object(
               'key', t.id, 'name', t.name, 'description', t.description, 'documentStyles', t.document_styles,
               'pageSettings', t.page_settings, 'blockStylePresets', t.block_style_presets,
               'spacingUnit', t.spacing_unit, 'type', 'theme'
           ) AS payload,
           t.created_at, t.updated_at, t.created_by, t.updated_by, t.revision
    FROM themes t
    WHERE t.version_id IN (SELECT cv.version_id FROM catalog_current_versions cv WHERE cv.tenant_key = t.tenant_key)
      AND t.catalog_id IS DISTINCT FROM NULLIF(current_setting('epistola.release_catalog', TRUE), '')::uuid
    UNION ALL
    SELECT r.tenant_key, r.catalog_id, r.version_id, r.version, r.resource_id, r.resource_key, r.name::text,
           c.payload, r.created_at, r.updated_at, r.created_by, r.updated_by, r.revision
    FROM catalog_version_resources r
    JOIN resource_contents c ON c.tenant_key = r.tenant_key AND c.content_hash = r.content_hash
    WHERE r.resource_type = 'theme' AND r.version IS NOT NULL
) u;

COMMENT ON VIEW current_themes IS
    'The themes of the version a caller means, each as its JSON content (payload, the wire form): of the targeted release, else of the draft catalog_current_versions names.';
