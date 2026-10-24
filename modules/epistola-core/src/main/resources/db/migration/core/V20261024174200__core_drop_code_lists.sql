-- ADR 0033, step 2: code lists are stored as JSON. V20261024174143 moved every draft's code lists
-- into release_resources draft rows naming their content; this keeps what the code list tables did,
-- on those rows, and drops the tables. Nothing is lost: every row and entry was converted, with its
-- identity, revision and timestamps, its credential and its last refresh.

-- 1. A ready mark recorded the digest of the code list's columns and entries; it now records its
--    content hash, so a code list that was ready stays ready.
UPDATE resource_ready_marks m
SET digest = r.content_hash
FROM code_lists c
JOIN release_resources r
  ON r.tenant_key = c.tenant_key AND r.version_id = c.version_id AND r.resource_type = 'codeList' AND r.resource_key = c.slug
WHERE m.tenant_key = c.tenant_key AND m.version_id = c.version_id AND m.resource_id = c.resource_id AND m.part = ''
  AND m.digest = encode(sha256(convert_to(jsonb_build_array(
           c.display_name, c.description, c.source_type, c.source_url,
           (SELECT jsonb_agg(jsonb_build_array(e.code, e.label, e.sort_order, e.hidden) ORDER BY e.sort_order, e.code)
            FROM code_list_entries e
            WHERE e.tenant_key = c.tenant_key AND e.version_id = c.version_id AND e.code_list_resource_id = c.resource_id)
       )::text, 'UTF8')), 'hex');

-- 2. A draft started from another carries its code lists' last refresh, as it copies its rows.
CREATE OR REPLACE FUNCTION copy_catalog_version_content(p_tenant TEXT, p_from UUID, p_to UUID) RETURNS VOID
    LANGUAGE plpgsql
AS $$
DECLARE
    t TEXT;
    columns TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['release_resources', 'assets', 'variant_attribute_definitions', 'fonts',
                             'font_variants', 'stencils', 'document_templates', 'template_variants', 'resource_ready_marks', 'code_list_refreshes',
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

-- 3. A code list's digest is its draft row's content hash (the JSON storage branch below).
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
SELECT s.tenant_key, s.resource_id, '',
       encode(sha256(convert_to(jsonb_build_array(
           s.name, s.description, s.tags, s.content, s.parameter_schema, s.reads_template_data
       )::text, 'UTF8')), 'hex'),
       s.version_id
FROM stencils s
UNION ALL
-- A resource type stored as JSON (ADR 0033): its draft row's content hash is its digest.
SELECT r.tenant_key, r.resource_id, '',
       CAST(r.content_hash AS TEXT),
       r.version_id
FROM release_resources r
WHERE r.version_id IS NOT NULL
UNION ALL
SELECT a.tenant_key, a.resource_id, '',
       encode(sha256(convert_to(jsonb_build_array(
           a.display_name, a.allowed_values, a.code_list_resource_id
       )::text, 'UTF8')), 'hex'),
       a.version_id
FROM variant_attribute_definitions a
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

-- 4. The code lists of a draft or a release, from their rows alone. `resource` is the released form.
--    Columns derived from the content are for the statements that filter or order on them.
CREATE VIEW current_code_lists AS
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
        FROM catalog_version_resources('codeList') r
    ) p
) u;

COMMENT ON VIEW current_code_lists IS
    'The code lists of the version a caller means, each as its JSON content (payload: resource, the wire form, and source beside it).';

-- 5. Nothing reads or writes the tables any more.
DROP TABLE code_list_entries;
DROP TABLE code_lists;

-- 6. The conversion filled release_resources and resource_contents at once.
ANALYZE release_resources;
ANALYZE resource_contents;
