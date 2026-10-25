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
-- Keyed by catalog first: what a catalog's reads filter on. A draft belongs to one catalog, so this is
-- as unique as (draft, type, key), and it plans well without statistics (no other index offers the
-- catalog). Lookups of one draft's rows by draft alone use the index below.
CREATE UNIQUE INDEX uq_release_resources_draft ON release_resources (tenant_key, catalog_id, version_id, resource_type, resource_key)
    WHERE version_id IS NOT NULL;
CREATE INDEX idx_release_resources_draft_version ON release_resources (tenant_key, version_id)
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

-- The one place that says which rows a caller means (ADR 0033): per catalog, the rows of one
-- resource type of the version its transaction targets. A message that names a release sets
-- `epistola.release_catalog` and `epistola.release_version`, and that catalog's rows are the
-- release's; every other catalog, and that one otherwise, is read at the draft
-- `catalog_current_versions` names (the targeted draft, else the next version). When
-- catalog_versions folds, this function is what changes.
--
-- It plans the same with statistics or without (ThemePlanStabilityTest). A fresh installation's
-- tables are empty when its first statements are planned, and pgjdbc keeps those plans; a plan
-- that weighed rows it could not count rescanned the tenant's rows once per stored content. So the
-- shape is fixed here: the current versions first, then each one's rows by the draft key (the
-- fenced LATERAL), and the address columns are the version's, so a caller's tenant and catalog
-- filter the versions. A simple SQL function, inlined into the statement that calls it.
CREATE FUNCTION catalog_version_resources(p_type TEXT)
    RETURNS TABLE (
        tenant_key    tenant_key,
        catalog_id    UUID,
        version_id    UUID,
        version       VARCHAR(128),
        resource_type VARCHAR(20),
        resource_key  TEXT,
        resource_id   UUID,
        content_hash  CHAR(64),
        fingerprint   VARCHAR(80),
        name          VARCHAR(255),
        description   TEXT,
        revision      BIGINT,
        created_at    TIMESTAMPTZ,
        updated_at    TIMESTAMPTZ,
        created_by    UUID,
        updated_by    UUID
    )
    LANGUAGE sql
    STABLE
AS $$
SELECT cv.tenant_key, cv.catalog_id, cv.version_id, r.version, r.resource_type, r.resource_key, r.resource_id,
       r.content_hash, r.fingerprint, r.name, r.description, r.revision, r.created_at, r.updated_at, r.created_by, r.updated_by
FROM catalog_current_versions cv
CROSS JOIN LATERAL (
    SELECT * FROM release_resources d
    WHERE d.tenant_key = cv.tenant_key AND d.catalog_id = cv.catalog_id AND d.version_id = cv.version_id AND d.resource_type = p_type
    OFFSET 0
) r
WHERE cv.catalog_id IS DISTINCT FROM NULLIF(current_setting('epistola.release_catalog', TRUE), '')::uuid
UNION ALL
SELECT r.tenant_key, r.catalog_id, r.version_id, r.version, r.resource_type, r.resource_key, r.resource_id,
       r.content_hash, r.fingerprint, r.name, r.description, r.revision, r.created_at, r.updated_at, r.created_by, r.updated_by
FROM release_resources r
WHERE r.version IS NOT NULL
  AND r.resource_type = p_type
  AND r.catalog_id = NULLIF(current_setting('epistola.release_catalog', TRUE), '')::uuid
  AND r.version = current_setting('epistola.release_version', TRUE)
$$;

COMMENT ON FUNCTION catalog_version_resources(TEXT) IS
    'Per catalog, the resource rows of one type of the version a caller means: the release its transaction targets (epistola.release_catalog, epistola.release_version), else the draft catalog_current_versions names.';

-- The themes of the version a caller means, each as its JSON content (ADR 0033, step 1a). Drafts
-- still keep themes in `themes` (moved in step 1b), so their content is built from the row here,
-- the wire form a release keeps; a targeted release's come from its rows. One mapper reads both.
CREATE VIEW current_themes AS
SELECT u.*,
       -- The released form is the content's `resource` (ADR 0033: one shape for every type).
       u.payload -> 'resource' ->> 'description' AS description,
       COALESCE(u.payload -> 'resource' -> 'documentStyles', '{}'::jsonb) AS document_styles,
       NULLIF(u.payload -> 'resource' -> 'pageSettings', 'null'::jsonb) AS page_settings,
       NULLIF(u.payload -> 'resource' -> 'blockStylePresets', 'null'::jsonb) AS block_style_presets,
       CAST(u.payload -> 'resource' ->> 'spacingUnit' AS NUMERIC(6, 2)) AS spacing_unit
FROM (
    SELECT t.tenant_key, t.catalog_id, t.version_id, CAST(NULL AS VARCHAR(128)) AS version, t.resource_id, t.id::text AS id, t.name::text AS name,
           jsonb_build_object('resource', jsonb_build_object(
               'key', t.id, 'name', t.name, 'description', t.description, 'documentStyles', t.document_styles,
               'pageSettings', t.page_settings, 'blockStylePresets', t.block_style_presets,
               'spacingUnit', t.spacing_unit, 'type', 'theme'
           )) AS payload,
           t.created_at, t.updated_at, t.created_by, t.updated_by, t.revision
    FROM themes t
    WHERE t.version_id IN (SELECT cv.version_id FROM catalog_current_versions cv WHERE cv.tenant_key = t.tenant_key)
      AND t.catalog_id IS DISTINCT FROM NULLIF(current_setting('epistola.release_catalog', TRUE), '')::uuid
    UNION ALL
    SELECT r.tenant_key, r.catalog_id, r.version_id, r.version, r.resource_id, r.resource_key, r.name::text,
           c.payload, r.created_at, r.updated_at, r.created_by, r.updated_by, r.revision
    FROM catalog_version_resources('theme') r
    JOIN resource_contents c ON c.tenant_key = r.tenant_key AND c.content_hash = r.content_hash
    WHERE r.version IS NOT NULL
) u;

COMMENT ON VIEW current_themes IS
    'The themes of the version a caller means, each as its JSON content (payload, the wire form): of the targeted release, else of the draft catalog_current_versions names.';
