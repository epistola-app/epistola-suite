-- ADR 0033, step 7a: one read interface for a draft's and a release's templates. Drafts still keep
-- templates in `document_templates` and `template_variants` (they move in step 7b), so a draft's are
-- read from those rows; a targeted release's from its rows and their content, a variant's model from
-- the content it was lifted into. Every reader reads both through these two views.

-- 1. The templates of the version a caller means. A template names its theme by the theme's catalog
--    and key, resolved: a draft's from the theme it names, a release's from its content's alias
--    through resolve_catalog_alias, as everything that reads content resolves one.
CREATE VIEW current_templates AS
SELECT t.tenant_key, t.catalog_id, t.version_id, CAST(NULL AS VARCHAR(128)) AS version, t.resource_id,
       t.id::text AS id, t.name::text AS name, t.description::text AS description, t.pdfa_enabled,
       t.data_model, t.data_examples,
       theme.resource_key AS theme_key, theme.catalog_id AS theme_catalog_id, t.theme_resource_id,
       (SELECT COUNT(*) FROM template_variants v
        WHERE v.tenant_key = t.tenant_key AND v.version_id = t.version_id AND v.template_resource_id = t.resource_id) AS variant_count,
       t.created_at, t.updated_at, t.created_by, t.updated_by, t.revision
FROM document_templates t
JOIN catalog_current_versions cv ON cv.tenant_key = t.tenant_key AND cv.catalog_id = t.catalog_id AND cv.version_id = t.version_id
LEFT JOIN catalog_resources theme ON theme.tenant_key = t.tenant_key AND theme.resource_id = t.theme_resource_id
WHERE t.catalog_id IS DISTINCT FROM NULLIF(current_setting('epistola.release_catalog', TRUE), '')::uuid
UNION ALL
SELECT r.tenant_key, r.catalog_id, r.version_id, r.version, r.resource_id,
       r.resource_key, CAST(r.name AS TEXT), p.payload ->> 'description',
       COALESCE(CAST(p.payload ->> 'pdfaEnabled' AS BOOLEAN), TRUE),
       NULLIF(p.payload -> 'dataModel', 'null'::jsonb),
       -- An example has no id on the wire; its key, unique in its template, stands in for one.
       COALESCE((SELECT jsonb_agg(e.example || jsonb_build_object('id', COALESCE(e.example ->> 'id', e.example ->> 'key')) ORDER BY e.n)
                 FROM jsonb_array_elements(NULLIF(p.payload -> 'dataExamples', 'null'::jsonb)) WITH ORDINALITY AS e(example, n)), '[]'::jsonb),
       theme.theme_key, theme.theme_catalog_id,
       (SELECT cr.resource_id FROM catalog_resources cr
        WHERE cr.tenant_key = r.tenant_key AND cr.catalog_id = theme.theme_catalog_id AND cr.resource_type = 'theme' AND cr.resource_key = theme.theme_key),
       CAST(jsonb_array_length(COALESCE(p.payload -> 'variants', '[]'::jsonb)) AS BIGINT),
       r.created_at, r.updated_at, r.created_by, r.updated_by, r.revision
FROM catalog_version_resources('template') r
-- The content by its key, row by row: a fenced LATERAL (ThemePlanStabilityTest).
CROSS JOIN LATERAL (
    SELECT c.payload FROM resource_contents c WHERE c.tenant_key = r.tenant_key AND c.content_hash = r.content_hash OFFSET 0
) p
CROSS JOIN LATERAL (
    SELECT p.payload ->> 'themeKey' AS theme_key,
           CASE WHEN p.payload ->> 'themeKey' IS NOT NULL
                THEN resolve_catalog_alias(r.tenant_key, r.catalog_id, NULL, r.version, p.payload ->> 'themeCatalogKey')
           END AS theme_catalog_id
) theme
WHERE r.version IS NOT NULL;

COMMENT ON VIEW current_templates IS
    'The templates of the version a caller means: a draft''s from document_templates, a targeted release''s from its rows and content (ADR 0033, step 7a).';

-- 2. The variants of those templates, each with its model. A release lifts every model into content
--    of its own and names it by `revisionDigest`; its default variant has none of its own and renders
--    the template's.
CREATE VIEW current_template_variants AS
SELECT v.tenant_key, t.catalog_id, v.version_id, CAST(NULL AS VARCHAR(128)) AS version, v.template_resource_id,
       v.id::text AS id, v.title::text AS title, v.description, v.attributes, v.is_default, v.metadata, v.template_model,
       v.created_at, v.updated_at, v.created_by, v.updated_by, v.revision
FROM template_variants v
JOIN document_templates t ON t.tenant_key = v.tenant_key AND t.version_id = v.version_id AND t.resource_id = v.template_resource_id
JOIN catalog_current_versions cv ON cv.tenant_key = t.tenant_key AND cv.catalog_id = t.catalog_id AND cv.version_id = t.version_id
WHERE t.catalog_id IS DISTINCT FROM NULLIF(current_setting('epistola.release_catalog', TRUE), '')::uuid
UNION ALL
SELECT r.tenant_key, r.catalog_id, r.version_id, r.version, r.resource_id,
       e.variant ->> 'key', COALESCE(e.variant ->> 'title', e.variant ->> 'key'), CAST(NULL AS TEXT),
       COALESCE(NULLIF(e.variant -> 'attributes', 'null'::jsonb), '{}'::jsonb),
       COALESCE(CAST(e.variant ->> 'isDefault' AS BOOLEAN), FALSE),
       NULLIF(e.variant -> 'metadata', 'null'::jsonb),
       m.model,
       r.created_at, r.updated_at, r.created_by, r.updated_by, r.revision
FROM catalog_version_resources('template') r
CROSS JOIN LATERAL (
    SELECT c.payload FROM resource_contents c WHERE c.tenant_key = r.tenant_key AND c.content_hash = r.content_hash OFFSET 0
) p
CROSS JOIN LATERAL jsonb_array_elements(COALESCE(p.payload -> 'variants', '[]'::jsonb)) AS e(variant)
CROSS JOIN LATERAL (
    SELECT COALESCE(
               (SELECT c.payload FROM resource_contents c WHERE c.tenant_key = r.tenant_key AND c.content_hash = x.model ->> 'revisionDigest'),
               x.model) AS model
    FROM (SELECT COALESCE(NULLIF(e.variant -> 'templateModel', 'null'::jsonb), p.payload -> 'templateModel') AS model) x
    OFFSET 0
) m
WHERE r.version IS NOT NULL;

COMMENT ON VIEW current_template_variants IS
    'The variants of the templates of the version a caller means, each with its model (ADR 0033, step 7a).';
