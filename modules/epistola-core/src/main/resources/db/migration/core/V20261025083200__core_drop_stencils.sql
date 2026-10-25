-- ADR 0033, step 4: stencils are stored as JSON. V20261025083103 moved every draft's stencils into
-- release_resources draft rows naming their content; this keeps what the table did, on those rows,
-- and drops it.

-- 1. A ready mark recorded the digest of the stencil's columns; it now records its content hash.
UPDATE resource_ready_marks m
SET digest = r.content_hash
FROM stencils s
JOIN release_resources r
  ON r.tenant_key = s.tenant_key AND r.version_id = s.version_id AND r.resource_type = 'stencil' AND r.resource_key = s.id
WHERE m.tenant_key = s.tenant_key AND m.version_id = s.version_id AND m.resource_id = s.resource_id AND m.part = ''
  AND m.digest = encode(sha256(convert_to(jsonb_build_array(
          s.name, s.description, s.tags, s.content, s.parameter_schema, s.reads_template_data
      )::text, 'UTF8')), 'hex');

-- 2. A draft started from another no longer copies a table of stencils: its rows are draft rows.
CREATE OR REPLACE FUNCTION copy_catalog_version_content(p_tenant TEXT, p_from UUID, p_to UUID) RETURNS VOID
    LANGUAGE plpgsql
AS $$
DECLARE
    t TEXT;
    columns TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['release_resources', 'assets', 'fonts',
                             'font_variants', 'document_templates', 'template_variants', 'resource_ready_marks', 'code_list_refreshes',
                             'catalog_dependencies']
    LOOP
        SELECT string_agg(quote_ident(column_name), ', ' ORDER BY ordinal_position) INTO columns
        FROM information_schema.columns
        WHERE table_schema = current_schema() AND table_name = t AND is_generated = 'NEVER' AND column_name <> 'version_id';
        EXECUTE format('INSERT INTO %I (%s, version_id) SELECT %s, $2 FROM %I WHERE tenant_key = $1 AND version_id = $3', t, columns, columns, t)
            USING p_tenant, p_to, p_from;
    END LOOP;
END;
$$;

-- 3. A stencil's digest is its draft row's content hash.
CREATE OR REPLACE VIEW resource_content_digests AS
SELECT v.tenant_key, t.resource_id, v.id::text AS part,
       encode(sha256(convert_to(jsonb_build_array(
           t.name, t.pdfa_enabled, t.theme_resource_id, t.data_model, t.data_examples,
           (SELECT jsonb_agg(o.id ORDER BY o.id) FROM template_variants o
            WHERE o.tenant_key = t.tenant_key AND o.version_id = t.version_id AND o.template_resource_id = t.resource_id),
           v.title, v.description, v.attributes, v.is_default, v.template_model
       )::text, 'UTF8')), 'hex') AS digest,
       t.version_id
FROM document_templates t
JOIN template_variants v ON v.tenant_key = t.tenant_key AND v.version_id = t.version_id AND v.template_resource_id = t.resource_id
UNION ALL
-- A resource type stored as JSON (ADR 0033): its draft row's content hash is its digest.
SELECT r.tenant_key, r.resource_id, '',
       CAST(r.content_hash AS TEXT),
       r.version_id
FROM release_resources r
WHERE r.version_id IS NOT NULL
UNION ALL
SELECT f.tenant_key, f.resource_id, '',
       encode(sha256(convert_to(jsonb_build_array(
           f.name, f.kind,
           (SELECT jsonb_agg(jsonb_build_array(fv.weight, fv.italic, fv.source, fv.classpath_location, fv.content_hash, fv.asset_resource_id)
                             ORDER BY fv.weight, fv.italic)
            FROM font_variants fv
            WHERE fv.tenant_key = f.tenant_key AND fv.version_id = f.version_id AND fv.font_resource_id = f.resource_id)
       )::text, 'UTF8')), 'hex'),
       f.version_id
FROM fonts f
UNION ALL
SELECT i.tenant_key, i.resource_id, '',
       encode(sha256(convert_to(jsonb_build_array(
           i.name, i.media_type, i.content_hash, i.width, i.height, i.sensitive
       )::text, 'UTF8')), 'hex'),
       i.version_id
FROM assets i;

-- 4. The stencils of a draft or a release, from their rows alone. Columns derived from the content
--    are what the stencil reads map and filter on (tags in SQL over the JSON, ADR 0033).
CREATE VIEW current_stencils AS
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
    FROM catalog_version_resources('stencil') r
    CROSS JOIN LATERAL (
        SELECT c.payload FROM resource_contents c WHERE c.tenant_key = r.tenant_key AND c.content_hash = r.content_hash OFFSET 0
    ) c
) u;

COMMENT ON VIEW current_stencils IS
    'The stencils of the version a caller means, each as its JSON content (payload: resource, the wire form).';

-- 5. Nothing reads or writes the table any more.
DROP TABLE stencils;

-- 6. The conversion filled release_resources, resource_contents and resource_references at once.
ANALYZE release_resources;
ANALYZE resource_contents;
ANALYZE resource_references;
