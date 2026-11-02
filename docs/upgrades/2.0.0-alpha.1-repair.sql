-- SPDX-FileCopyrightText: Epistola Nederland B.V.
--
-- SPDX-License-Identifier: AGPL-3.0-only

-- Run once on a database 2.0.0-alpha.1 migrated, before starting a later 2.0 version on it; it is
-- safe to run more than once, and on a database that never ran 1.x. See docs/upgrades.md,
-- "From 2.0.0-alpha.1". Run it in one transaction: psql -1 -f 2.0.0-alpha.1-repair.sql
--
-- After alpha.1, step 9 of the 2.0 upgrade (V20261101000900) was corrected. 1.x let a stencil version
-- a published template used be archived; a stencil left with no published version was not put in the
-- "Published in 1.x" draft, which named it and so could not be released. The draft now holds it, as
-- its latest archived version. The conversion code the upgrade steps share changed with it, and again
-- when a stencil instance turned into plain content stopped losing its own styles (step 8), so the
-- checksum of each of them changed too. Content alpha.1 already converted keeps what it lost.
--
-- The read model (V20261101001500) was rewritten too: the reads of the version a caller means take
-- it as arguments instead of reading session settings (ADR 0049). Its checksum changed, and section 3
-- puts its functions in place of alpha.1's views.

-- 1. Record the corrected steps, so a later version starts instead of refusing changed migrations.
UPDATE flyway_schema_history h SET checksum = c.corrected
FROM (VALUES
    ('20261101000500', -953239124, -1431776976),
    ('20261101000600', 344802958, -133734894),
    ('20261101000700', -1369683661, -1848221513),
    ('20261101000800', -361602266, -840140118),
    ('20261101000900', 1306419192, -396409456),
    ('20261101001000', -1475090478, -1953628330),
    ('20261101001500', 1379821351, 1793499913)
) AS c (version, alpha_1, corrected)
WHERE h.version = c.version AND h.checksum = c.alpha_1;

-- 2. Give each "Published in 1.x" draft alpha.1 made the stencils its content uses from its own
--    catalog, and those they use, as its catalog's next draft holds them, with their ready marks
--    there. Without them it cannot be released. The 1.x versions are gone by now, so this is the
--    stencil's working copy: its latest archived version, unless it had a draft. A draft that then
--    holds what its next draft holds is removed: it has nothing of its own to release.
CREATE TEMPORARY TABLE published_in_1x_repaired (
    tenant_key TEXT NOT NULL,
    version_id UUID NOT NULL,
    next_id    UUID NOT NULL,
    resource_id UUID NOT NULL
) ON COMMIT DROP;

WITH RECURSIVE named AS (
    SELECT d.tenant_key, d.catalog_id, c.catalog_key, d.version_id, c.next_version_id AS next_id
    FROM catalog_versions d
    JOIN catalogs c ON c.tenant_key = d.tenant_key AND c.catalog_id = d.catalog_id
    WHERE d.state = 'draft' AND d.name = 'Published in 1.x'
),
-- The stencils of its own catalog a named draft's content uses (alias none, or its own key), and
-- those the next draft's copies of them use in turn.
needed (tenant_key, catalog_key, version_id, next_id, resource_key) AS (
    SELECT n.tenant_key, n.catalog_key, n.version_id, n.next_id, x.resource_key
    FROM named n
    JOIN release_resources r ON r.tenant_key = n.tenant_key AND r.version_id = n.version_id
    JOIN resource_references x ON x.tenant_key = r.tenant_key AND x.content_hash = r.content_hash
    WHERE x.resource_type = 'stencil' AND (x.alias IS NULL OR x.alias = n.catalog_key)
    UNION
    SELECT n.tenant_key, n.catalog_key, n.version_id, n.next_id, x.resource_key
    FROM needed n
    JOIN release_resources s ON s.tenant_key = n.tenant_key AND s.version_id = n.next_id
        AND s.resource_type = 'stencil' AND s.resource_key = n.resource_key AND s.parent_id IS NULL
    JOIN resource_references x ON x.tenant_key = s.tenant_key AND x.content_hash = s.content_hash
    WHERE x.resource_type = 'stencil' AND (x.alias IS NULL OR x.alias = n.catalog_key)
),
added AS (
    INSERT INTO release_resources (tenant_key, catalog_id, version_id, resource_type, resource_key, resource_id, parent_id, content_hash,
        fingerprint, name, description, revision, created_at, updated_at, created_by, updated_by)
    SELECT s.tenant_key, s.catalog_id, n.version_id, s.resource_type, s.resource_key, s.resource_id, s.parent_id, s.content_hash,
        s.fingerprint, s.name, s.description, s.revision, s.created_at, s.updated_at, s.created_by, s.updated_by
    FROM (SELECT DISTINCT tenant_key, version_id, next_id, resource_key FROM needed) n
    JOIN release_resources s ON s.tenant_key = n.tenant_key AND s.version_id = n.next_id
        AND s.resource_type = 'stencil' AND s.resource_key = n.resource_key AND s.parent_id IS NULL
    WHERE NOT EXISTS (
        SELECT 1 FROM release_resources h
        WHERE h.tenant_key = n.tenant_key AND h.version_id = n.version_id AND h.resource_type = 'stencil' AND h.resource_key = n.resource_key
    )
    RETURNING tenant_key, version_id, resource_id
)
INSERT INTO published_in_1x_repaired (tenant_key, version_id, next_id, resource_id)
SELECT a.tenant_key, a.version_id, n.next_id, a.resource_id
FROM added a
JOIN (SELECT DISTINCT tenant_key, version_id, next_id FROM named) n ON n.tenant_key = a.tenant_key AND n.version_id = a.version_id;

INSERT INTO resource_ready_marks (tenant_key, version_id, resource_id, part, digest, marked_at, marked_by)
SELECT m.tenant_key, r.version_id, m.resource_id, m.part, m.digest, m.marked_at, m.marked_by
FROM published_in_1x_repaired r
JOIN resource_ready_marks m ON m.tenant_key = r.tenant_key AND m.version_id = r.next_id AND m.resource_id = r.resource_id;

DELETE FROM catalog_versions d
USING (SELECT DISTINCT tenant_key, version_id, next_id FROM published_in_1x_repaired) r
WHERE d.tenant_key = r.tenant_key AND d.version_id = r.version_id
  AND NOT EXISTS (
      (SELECT resource_type, resource_key, COALESCE(parent_id::text, ''), content_hash FROM release_resources WHERE tenant_key = r.tenant_key AND version_id = r.version_id
       EXCEPT SELECT resource_type, resource_key, COALESCE(parent_id::text, ''), content_hash FROM release_resources WHERE tenant_key = r.tenant_key AND version_id = r.next_id)
      UNION ALL
      (SELECT resource_type, resource_key, COALESCE(parent_id::text, ''), content_hash FROM release_resources WHERE tenant_key = r.tenant_key AND version_id = r.next_id
       EXCEPT SELECT resource_type, resource_key, COALESCE(parent_id::text, ''), content_hash FROM release_resources WHERE tenant_key = r.tenant_key AND version_id = r.version_id)
  );

-- 3. The reads of the version a caller means take it as arguments (ADR 0049), where alpha.1 made them
--    read transaction-local settings (epistola.content_version, epistola.release_catalog,
--    epistola.release_version): catalog_current_versions and the eight current_* reads become
--    functions of the draft and release a caller means, and catalog_version_resources and
--    catalog_version_references pass them on. The definitions are V20261101001500's, as corrected.
--    Alpha.1's are dropped only while catalog_current_versions is still a view, so this changes
--    nothing on a database a later version made, or one already repaired.
DO $repair$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_views WHERE schemaname = current_schema() AND viewname = 'catalog_current_versions') THEN
        DROP VIEW current_template_variants, current_templates, current_stencils, current_images, current_fonts,
            current_attributes, current_code_lists, current_themes;
        DROP FUNCTION catalog_version_references(text);
        DROP FUNCTION catalog_version_resources(text);
        DROP VIEW catalog_current_versions;
    END IF;
END
$repair$;

CREATE OR REPLACE FUNCTION catalog_current_versions(p_draft_version UUID, p_release_catalog UUID, p_release_version TEXT)
    RETURNS TABLE (
        tenant_key tenant_key,
        catalog_id UUID,
        version_id UUID
    )
    LANGUAGE sql
    STABLE
AS $$
SELECT DISTINCT ON (v.tenant_key, v.catalog_id) v.tenant_key, v.catalog_id, v.version_id
FROM catalog_versions v
WHERE CASE
          WHEN v.catalog_id = p_release_catalog
              THEN v.state = 'released' AND v.version = p_release_version
          ELSE v.version_id = p_draft_version
              OR v.version_id = (SELECT c.next_version_id FROM catalogs c WHERE c.tenant_key = v.tenant_key AND c.catalog_id = v.catalog_id)
      END
ORDER BY v.tenant_key, v.catalog_id, ((v.version_id = p_draft_version) IS TRUE) DESC
$$;

COMMENT ON FUNCTION catalog_current_versions(uuid, uuid, text) IS 'Per catalog, the version a caller means: the release it names, else the draft it names, else its next version.';

CREATE OR REPLACE FUNCTION catalog_version_resources(p_type TEXT, p_draft_version UUID, p_release_catalog UUID, p_release_version TEXT)
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
SELECT cv.tenant_key, cv.catalog_id, cv.version_id, vr.version, r.resource_type, r.resource_key, r.resource_id,
       r.content_hash, r.fingerprint, r.name, r.description, r.revision, r.created_at, r.updated_at, r.created_by, r.updated_by
FROM catalog_current_versions(p_draft_version, p_release_catalog, p_release_version) cv
JOIN catalog_versions vr ON vr.tenant_key = cv.tenant_key AND vr.version_id = cv.version_id
CROSS JOIN LATERAL (
    SELECT * FROM release_resources d
    WHERE d.tenant_key = cv.tenant_key AND d.catalog_id = cv.catalog_id AND d.version_id = cv.version_id AND d.resource_type = p_type
      AND d.parent_id IS NULL
    OFFSET 0
) r
$$;

COMMENT ON FUNCTION catalog_version_resources(text, uuid, uuid, text) IS 'Per catalog, the resource rows of one type of the version a caller means (catalog_current_versions).';

CREATE OR REPLACE FUNCTION catalog_version_references(p_type TEXT, p_draft_version UUID, p_release_catalog UUID, p_release_version TEXT)
    RETURNS TABLE (
        tenant_key      tenant_key,
        catalog_id      UUID,
        version_id      UUID,
        version         VARCHAR(128),
        resource_key    TEXT,
        resource_id     UUID,
        ref_alias       VARCHAR(50),
        ref_type        VARCHAR(20),
        ref_key         TEXT,
        ref_catalog_id  UUID
    )
    LANGUAGE sql
    STABLE
AS $$
SELECT r.tenant_key, r.catalog_id, r.version_id, r.version, r.resource_key, r.resource_id,
       x.alias, x.resource_type, x.resource_key,
       resolve_catalog_alias(r.tenant_key, r.catalog_id, r.version_id, r.version, x.alias)
FROM catalog_version_resources(p_type, p_draft_version, p_release_catalog, p_release_version) r
CROSS JOIN LATERAL (
    SELECT * FROM resource_references x WHERE x.tenant_key = r.tenant_key AND x.content_hash = r.content_hash OFFSET 0
) x
$$;

COMMENT ON FUNCTION catalog_version_references(text, uuid, uuid, text) IS 'The references of one resource type''s rows of the versions a caller means, each with the catalog it names (resolve_catalog_alias).';

CREATE OR REPLACE FUNCTION current_themes(p_draft_version UUID, p_release_catalog UUID, p_release_version TEXT)
    RETURNS TABLE (
        tenant_key          tenant_key,
        catalog_id          UUID,
        version_id          UUID,
        version             VARCHAR(128),
        resource_id         UUID,
        id                  TEXT,
        name                TEXT,
        payload             JSONB,
        created_at          TIMESTAMPTZ,
        updated_at          TIMESTAMPTZ,
        created_by          UUID,
        updated_by          UUID,
        revision            BIGINT,
        description         TEXT,
        document_styles     JSONB,
        page_settings       JSONB,
        block_style_presets JSONB,
        spacing_unit        NUMERIC(6, 2)
    )
    LANGUAGE sql
    STABLE
AS $$
SELECT u.*,
       -- The released form is the content's `resource` (ADR 0033: one shape for every type).
       u.payload -> 'resource' ->> 'description' AS description,
       COALESCE(u.payload -> 'resource' -> 'documentStyles', '{}'::jsonb) AS document_styles,
       NULLIF(u.payload -> 'resource' -> 'pageSettings', 'null'::jsonb) AS page_settings,
       NULLIF(u.payload -> 'resource' -> 'blockStylePresets', 'null'::jsonb) AS block_style_presets,
       CAST(u.payload -> 'resource' ->> 'spacingUnit' AS NUMERIC(6, 2)) AS spacing_unit
FROM (
    -- The content by its key, row by row, rather than a join: a join's order is the planner's to
    -- choose, and a plan made before the tables have statistics put every stored content of the
    -- tenant outermost (ThemePlanStabilityTest).
    SELECT r.tenant_key, r.catalog_id, r.version_id, r.version, r.resource_id, r.resource_key AS id, CAST(r.name AS TEXT) AS name,
           (SELECT c.payload FROM resource_contents c WHERE c.tenant_key = r.tenant_key AND c.content_hash = r.content_hash) AS payload,
           r.created_at, r.updated_at, r.created_by, r.updated_by, r.revision
    FROM catalog_version_resources('theme', p_draft_version, p_release_catalog, p_release_version) r
) u
$$;

COMMENT ON FUNCTION current_themes(uuid, uuid, text) IS 'The themes of the version a caller means, each as its JSON content (payload, the wire form): of the release it names, else of the draft catalog_current_versions answers.';

CREATE OR REPLACE FUNCTION current_code_lists(p_draft_version UUID, p_release_catalog UUID, p_release_version TEXT)
    RETURNS TABLE (
        tenant_key      tenant_key,
        catalog_id      UUID,
        version_id      UUID,
        version         VARCHAR(128),
        resource_id     UUID,
        id              TEXT,
        name            TEXT,
        payload         JSONB,
        row_description TEXT,
        created_at      TIMESTAMPTZ,
        updated_at      TIMESTAMPTZ,
        created_by      UUID,
        updated_by      UUID,
        revision        BIGINT,
        resource        JSONB,
        display_name    TEXT,
        description     TEXT,
        entries         JSONB,
        source_type     TEXT,
        source_url      TEXT,
        auth_type       TEXT,
        credential_id   UUID
    )
    LANGUAGE sql
    STABLE
AS $$
SELECT u.*,
       -- Its name and description from its row: reading them from the content would read the whole
       -- content, every entry of it.
       u.name AS display_name,
       u.row_description AS description,
       COALESCE(u.resource -> 'entries', '[]'::jsonb) AS entries,
       COALESCE(u.payload -> 'source' ->> 'type', 'INLINE') AS source_type,
       u.payload -> 'source' ->> 'url' AS source_url,
       COALESCE(u.payload -> 'source' ->> 'auth', 'NONE') AS auth_type,
       CAST(u.payload -> 'source' ->> 'credential' AS UUID) AS credential_id
FROM (
    SELECT p.*, p.payload -> 'resource' AS resource
    FROM (
        -- The content by its key, row by row, rather than a join (ThemePlanStabilityTest).
        SELECT r.tenant_key, r.catalog_id, r.version_id, r.version, r.resource_id, r.resource_key AS id, CAST(r.name AS TEXT) AS name,
               (SELECT c.payload FROM resource_contents c WHERE c.tenant_key = r.tenant_key AND c.content_hash = r.content_hash) AS payload,
               r.description AS row_description, r.created_at, r.updated_at, r.created_by, r.updated_by, r.revision
        FROM catalog_version_resources('codeList', p_draft_version, p_release_catalog, p_release_version) r
    ) p
) u
$$;

COMMENT ON FUNCTION current_code_lists(uuid, uuid, text) IS 'The code lists of the version a caller means, each as its JSON content (payload: resource, the wire form, and source beside it).';

CREATE OR REPLACE FUNCTION current_attributes(p_draft_version UUID, p_release_catalog UUID, p_release_version TEXT)
    RETURNS TABLE (
        tenant_key           tenant_key,
        catalog_id           UUID,
        version_id           UUID,
        version              VARCHAR(128),
        resource_id          UUID,
        id                   TEXT,
        name                 TEXT,
        payload              JSONB,
        created_at           TIMESTAMPTZ,
        updated_at           TIMESTAMPTZ,
        created_by           UUID,
        updated_by           UUID,
        revision             BIGINT,
        display_name         TEXT,
        allowed_values       JSONB,
        code_list_alias      TEXT,
        code_list_key        TEXT,
        code_list_catalog_id UUID
    )
    LANGUAGE sql
    STABLE
AS $$
SELECT u.*,
       u.name AS display_name,
       COALESCE(u.payload -> 'resource' -> 'allowedValues', '[]'::jsonb) AS allowed_values,
       u.payload -> 'resource' -> 'codeListBinding' ->> 'catalogKey' AS code_list_alias,
       u.payload -> 'resource' -> 'codeListBinding' ->> 'key' AS code_list_key,
       CASE WHEN u.payload -> 'resource' -> 'codeListBinding' ->> 'key' IS NOT NULL
            THEN resolve_catalog_alias(u.tenant_key, u.catalog_id, u.version_id, u.version, u.payload -> 'resource' -> 'codeListBinding' ->> 'catalogKey')
       END AS code_list_catalog_id
FROM (
    -- The content by its key, row by row, rather than a join (ThemePlanStabilityTest).
    SELECT r.tenant_key, r.catalog_id, r.version_id, r.version, r.resource_id, r.resource_key AS id, CAST(r.name AS TEXT) AS name,
           (SELECT c.payload FROM resource_contents c WHERE c.tenant_key = r.tenant_key AND c.content_hash = r.content_hash) AS payload,
           r.created_at, r.updated_at, r.created_by, r.updated_by, r.revision
    FROM catalog_version_resources('attribute', p_draft_version, p_release_catalog, p_release_version) r
) u
$$;

COMMENT ON FUNCTION current_attributes(uuid, uuid, text) IS 'The variant attributes of the version a caller means, each as its JSON content, with its code list binding resolved (resolve_catalog_alias).';

CREATE OR REPLACE FUNCTION current_fonts(p_draft_version UUID, p_release_catalog UUID, p_release_version TEXT)
    RETURNS TABLE (
        tenant_key  tenant_key,
        catalog_id  UUID,
        version_id  UUID,
        version     VARCHAR(128),
        resource_id UUID,
        slug        TEXT,
        name        TEXT,
        payload     JSONB,
        created_at  TIMESTAMPTZ,
        updated_at  TIMESTAMPTZ,
        created_by  UUID,
        updated_by  UUID,
        revision    BIGINT,
        kind        TEXT,
        variants    JSONB
    )
    LANGUAGE sql
    STABLE
AS $$
SELECT u.*,
       u.payload -> 'resource' ->> 'kind' AS kind,
       COALESCE(u.payload -> 'resource' -> 'variants', '[]'::jsonb) AS variants
FROM (
    -- The content by its key, row by row: a fenced LATERAL (ThemePlanStabilityTest).
    SELECT r.tenant_key, r.catalog_id, r.version_id, r.version, r.resource_id, r.resource_key AS slug, CAST(r.name AS TEXT) AS name,
           c.payload, r.created_at, r.updated_at, r.created_by, r.updated_by, r.revision
    FROM catalog_version_resources('font', p_draft_version, p_release_catalog, p_release_version) r
    CROSS JOIN LATERAL (
        SELECT c.payload FROM resource_contents c WHERE c.tenant_key = r.tenant_key AND c.content_hash = r.content_hash OFFSET 0
    ) c
) u
$$;

COMMENT ON FUNCTION current_fonts(uuid, uuid, text) IS 'The fonts of the version a caller means, each as its JSON content (payload: resource, the wire form); its faces'' bytes are its resource_binaries.';

CREATE OR REPLACE FUNCTION current_images(p_draft_version UUID, p_release_catalog UUID, p_release_version TEXT)
    RETURNS TABLE (
        tenant_key   tenant_key,
        catalog_id   UUID,
        version_id   UUID,
        version      VARCHAR(128),
        resource_id  UUID,
        id           TEXT,
        name         TEXT,
        payload      JSONB,
        created_at   TIMESTAMPTZ,
        updated_at   TIMESTAMPTZ,
        created_by   UUID,
        updated_by   UUID,
        revision     BIGINT,
        media_type   TEXT,
        width        INTEGER,
        height       INTEGER,
        content_hash TEXT,
        size_bytes   BIGINT
    )
    LANGUAGE sql
    STABLE
AS $$
SELECT u.*,
       u.payload -> 'resource' ->> 'mediaType' AS media_type,
       CAST(u.payload -> 'resource' ->> 'width' AS INTEGER) AS width,
       CAST(u.payload -> 'resource' ->> 'height' AS INTEGER) AS height,
       u.payload -> 'resource' ->> 'contentHash' AS content_hash,
       (SELECT ac.size_bytes FROM asset_content ac WHERE ac.content_hash = CAST(u.payload -> 'resource' ->> 'contentHash' AS CHAR(64))) AS size_bytes
FROM (
    -- The content by its key, row by row: a fenced LATERAL (ThemePlanStabilityTest).
    SELECT r.tenant_key, r.catalog_id, r.version_id, r.version, r.resource_id, r.resource_key AS id, CAST(r.name AS TEXT) AS name,
           c.payload, r.created_at, r.updated_at, r.created_by, r.updated_by, r.revision
    FROM catalog_version_resources('image', p_draft_version, p_release_catalog, p_release_version) r
    CROSS JOIN LATERAL (
        SELECT c.payload FROM resource_contents c WHERE c.tenant_key = r.tenant_key AND c.content_hash = r.content_hash OFFSET 0
    ) c
) u
$$;

COMMENT ON FUNCTION current_images(uuid, uuid, text) IS 'The images of the version a caller means, each as its JSON content (payload: resource, the wire form); its bytes are its resource_binaries.';

CREATE OR REPLACE FUNCTION current_stencils(p_draft_version UUID, p_release_catalog UUID, p_release_version TEXT)
    RETURNS TABLE (
        tenant_key          tenant_key,
        catalog_id          UUID,
        version_id          UUID,
        version             VARCHAR(128),
        resource_id         UUID,
        id                  TEXT,
        name                TEXT,
        payload             JSONB,
        created_at          TIMESTAMPTZ,
        updated_at          TIMESTAMPTZ,
        created_by          UUID,
        updated_by          UUID,
        revision            BIGINT,
        description         TEXT,
        tags                JSONB,
        content             JSONB,
        parameter_schema    JSONB,
        reads_template_data BOOLEAN
    )
    LANGUAGE sql
    STABLE
AS $$
SELECT u.*,
       -- The content's, not the row's: a release row carries its catalog index's summary, which
       -- an archive may word differently from the stencil it holds.
       u.payload -> 'resource' ->> 'description' AS description,
       COALESCE(u.payload -> 'resource' -> 'tags', '[]'::jsonb) AS tags,
       u.payload -> 'resource' -> 'content' AS content,
       NULLIF(u.payload -> 'resource' -> 'parameterSchema', 'null'::jsonb) AS parameter_schema,
       COALESCE(CAST(u.payload -> 'resource' ->> 'readsTemplateData' AS BOOLEAN), FALSE) AS reads_template_data
FROM (
    -- The content by its key, row by row: a fenced LATERAL, so the planner can neither reorder the
    -- join (ThemePlanStabilityTest) nor repeat the lookup for every column read from it.
    SELECT r.tenant_key, r.catalog_id, r.version_id, r.version, r.resource_id, r.resource_key AS id, CAST(r.name AS TEXT) AS name,
           c.payload, r.created_at, r.updated_at, r.created_by, r.updated_by, r.revision
    FROM catalog_version_resources('stencil', p_draft_version, p_release_catalog, p_release_version) r
    CROSS JOIN LATERAL (
        SELECT c.payload FROM resource_contents c WHERE c.tenant_key = r.tenant_key AND c.content_hash = r.content_hash OFFSET 0
    ) c
) u
$$;

COMMENT ON FUNCTION current_stencils(uuid, uuid, text) IS 'The stencils of the version a caller means, each as its JSON content (payload: resource, the wire form).';

CREATE OR REPLACE FUNCTION current_templates(p_draft_version UUID, p_release_catalog UUID, p_release_version TEXT)
    RETURNS TABLE (
        tenant_key        tenant_key,
        catalog_id        UUID,
        version_id        UUID,
        version           VARCHAR(128),
        resource_id       UUID,
        id                TEXT,
        name              TEXT,
        content_hash      CHAR(64),
        payload           JSONB,
        created_at        TIMESTAMPTZ,
        updated_at        TIMESTAMPTZ,
        created_by        UUID,
        updated_by        UUID,
        revision          BIGINT,
        description       TEXT,
        pdfa_enabled      BOOLEAN,
        data_model        JSONB,
        data_examples     JSONB,
        theme_key         TEXT,
        theme_catalog_id  UUID,
        theme_resource_id UUID,
        variant_count     BIGINT
    )
    LANGUAGE sql
    STABLE
AS $$
SELECT u.*,
       u.payload -> 'resource' ->> 'description' AS description,
       COALESCE(CAST(u.payload -> 'resource' ->> 'pdfaEnabled' AS BOOLEAN), TRUE) AS pdfa_enabled,
       NULLIF(u.payload -> 'resource' -> 'dataModel', 'null'::jsonb) AS data_model,
       -- An example's id is its key; one a received release left without a key reads by its name.
       COALESCE((SELECT jsonb_agg(e.example || jsonb_build_object('id', COALESCE(e.example ->> 'key', e.example ->> 'name')) ORDER BY e.n)
                 FROM jsonb_array_elements(NULLIF(u.payload -> 'resource' -> 'dataExamples', 'null'::jsonb)) WITH ORDINALITY AS e(example, n)), '[]'::jsonb) AS data_examples,
       u.payload -> 'resource' ->> 'themeKey' AS theme_key,
       theme.catalog_id AS theme_catalog_id,
       (SELECT cr.resource_id FROM catalog_resources cr
        WHERE cr.tenant_key = u.tenant_key AND cr.catalog_id = theme.catalog_id AND cr.resource_type = 'theme'
          AND cr.resource_key = u.payload -> 'resource' ->> 'themeKey') AS theme_resource_id,
       CAST(jsonb_array_length(COALESCE(u.payload -> 'resource' -> 'variants', '[]'::jsonb)) AS BIGINT) AS variant_count
FROM (
    -- The content by its key, row by row: a fenced LATERAL (ThemePlanStabilityTest).
    SELECT r.tenant_key, r.catalog_id, r.version_id, r.version, r.resource_id, r.resource_key AS id, CAST(r.name AS TEXT) AS name,
           r.content_hash, c.payload, r.created_at, r.updated_at, r.created_by, r.updated_by, r.revision
    FROM catalog_version_resources('template', p_draft_version, p_release_catalog, p_release_version) r
    CROSS JOIN LATERAL (
        SELECT c.payload FROM resource_contents c WHERE c.tenant_key = r.tenant_key AND c.content_hash = r.content_hash OFFSET 0
    ) c
) u
CROSS JOIN LATERAL (
    SELECT CASE WHEN u.payload -> 'resource' ->> 'themeKey' IS NOT NULL
                THEN resolve_catalog_alias(u.tenant_key, u.catalog_id, u.version_id, u.version, u.payload -> 'resource' ->> 'themeCatalogKey')
           END AS catalog_id
) theme
$$;

COMMENT ON FUNCTION current_templates(uuid, uuid, text) IS 'The templates of the version a caller means, each as its JSON content (payload: resource, the wire form without its variants, which are rows of their own).';

CREATE OR REPLACE FUNCTION current_template_variants(p_draft_version UUID, p_release_catalog UUID, p_release_version TEXT)
    RETURNS TABLE (
        tenant_key           tenant_key,
        catalog_id           UUID,
        version_id           UUID,
        version              VARCHAR(128),
        template_resource_id UUID,
        id                   TEXT,
        title                TEXT,
        description          TEXT,
        attributes           JSONB,
        is_default           BOOLEAN,
        metadata             JSONB,
        model_hash           CHAR(64),
        template_model       JSONB,
        "position"           BIGINT,
        created_at           TIMESTAMPTZ,
        updated_at           TIMESTAMPTZ,
        created_by           UUID,
        updated_by           UUID,
        revision             BIGINT
    )
    LANGUAGE sql
    STABLE
AS $$
SELECT t.tenant_key, t.catalog_id, t.version_id, t.version, t.resource_id AS template_resource_id,
       e.slot ->> 'key' AS id,
       COALESCE(v.payload -> 'resource' ->> 'title', e.slot ->> 'title', e.slot ->> 'key') AS title,
       COALESCE(v.payload -> 'resource' ->> 'description', v.payload ->> 'description') AS description,
       COALESCE(NULLIF(e.slot -> 'attributes', 'null'::jsonb), '{}'::jsonb) AS attributes,
       COALESCE(CAST(e.slot ->> 'isDefault' AS BOOLEAN), FALSE) AS is_default,
       NULLIF(COALESCE(v.payload -> 'resource' -> 'metadata', e.slot -> 'metadata'), 'null'::jsonb) AS metadata,
       v.content_hash AS model_hash,
       -- Its model; the default variant's for one that has none of its own (as a received release may
       -- say).
       COALESCE(v.payload -> 'resource' -> 'templateModel', d.payload -> 'resource' -> 'templateModel') AS template_model,
       e.position,
       COALESCE(v.created_at, t.created_at) AS created_at, COALESCE(v.updated_at, t.updated_at) AS updated_at,
       COALESCE(v.created_by, t.created_by) AS created_by, COALESCE(v.updated_by, t.updated_by) AS updated_by,
       COALESCE(v.revision, t.revision) AS revision
FROM (
    SELECT r.tenant_key, r.catalog_id, r.version_id, r.version, r.resource_id, c.payload,
           r.created_at, r.updated_at, r.created_by, r.updated_by, r.revision
    FROM catalog_version_resources('template', p_draft_version, p_release_catalog, p_release_version) r
    CROSS JOIN LATERAL (
        SELECT c.payload FROM resource_contents c WHERE c.tenant_key = r.tenant_key AND c.content_hash = r.content_hash OFFSET 0
    ) c
) t
CROSS JOIN LATERAL jsonb_array_elements(COALESCE(t.payload -> 'resource' -> 'variants', '[]'::jsonb)) WITH ORDINALITY AS e(slot, position)
-- Each variant is a row of its own, naming its template's row: its content and revision.
LEFT JOIN LATERAL (
    SELECT p.content_hash, c.payload, p.created_at, p.updated_at, p.created_by, p.updated_by, p.revision
    FROM release_resources p
    JOIN resource_contents c ON c.tenant_key = p.tenant_key AND c.content_hash = p.content_hash
    WHERE p.tenant_key = t.tenant_key AND p.parent_id = t.resource_id AND p.resource_key = e.slot ->> 'key'
      AND p.catalog_id = t.catalog_id AND p.resource_type = 'variant'
      AND p.version_id = t.version_id
    OFFSET 0
) v ON TRUE
LEFT JOIN LATERAL (
    SELECT c.payload
    FROM jsonb_array_elements(t.payload -> 'resource' -> 'variants') x(slot)
    JOIN release_resources p ON p.tenant_key = t.tenant_key AND p.parent_id = t.resource_id AND p.resource_key = x.slot ->> 'key'
      AND p.catalog_id = t.catalog_id AND p.resource_type = 'variant'
      AND p.version_id = t.version_id
    JOIN resource_contents c ON c.tenant_key = p.tenant_key AND c.content_hash = p.content_hash
    WHERE CAST(x.slot ->> 'isDefault' AS BOOLEAN) AND v.content_hash IS NOT NULL AND v.payload -> 'resource' -> 'templateModel' IS NULL
    OFFSET 0
) d ON TRUE
$$;

COMMENT ON FUNCTION current_template_variants(uuid, uuid, text) IS 'The variants of the templates of the version a caller means, each its own row (storage type variant) with its own content and revision.';
