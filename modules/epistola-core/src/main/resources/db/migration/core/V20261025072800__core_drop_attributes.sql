-- ADR 0033, step 3: variant attributes are stored as JSON. V20261025072712 moved every draft's
-- attributes into release_resources draft rows naming their content; this keeps what the table did,
-- on those rows, and drops it. A code list binding is content now (alias and key, the wire form),
-- indexed in resource_references; the foreign key that held it goes with the table, and its checks
-- are made on write and through the functions below (ADR 0033: references are not foreign keys).

-- 1. A ready mark recorded the digest of the attribute's columns; it now records its content hash.
UPDATE resource_ready_marks m
SET digest = r.content_hash
FROM variant_attribute_definitions a
JOIN release_resources r
  ON r.tenant_key = a.tenant_key AND r.version_id = a.version_id AND r.resource_type = 'attribute' AND r.resource_key = a.id
WHERE m.tenant_key = a.tenant_key AND m.version_id = a.version_id AND m.resource_id = a.resource_id AND m.part = ''
  AND m.digest = encode(sha256(convert_to(jsonb_build_array(
          a.display_name, a.allowed_values, a.code_list_resource_id
      )::text, 'UTF8')), 'hex');

-- 2. A draft started from another no longer copies a table of attributes: its rows are draft rows.
CREATE OR REPLACE FUNCTION copy_catalog_version_content(p_tenant TEXT, p_from UUID, p_to UUID) RETURNS VOID
    LANGUAGE plpgsql
AS $$
DECLARE
    t TEXT;
    columns TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['release_resources', 'assets', 'fonts',
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

-- 3. An attribute's digest is its draft row's content hash.
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

-- 4. The one place an alias in content is resolved (ADR 0034 section 9, ADR 0033): the catalog a
--    reference by [p_alias] means in content of catalog [p_catalog], of its draft [p_version_id] or
--    else its release [p_version]. Its own catalog without an alias (or by its own key), else what
--    the version declares under the alias (a draft's catalog_dependencies, a release's pins). NULL
--    when it declares none: catalogs use each other only through declared dependencies (#1116, #1130).
--    "Used by", "in use?" and the graph resolve through this, never with an alias join of their own.
CREATE FUNCTION resolve_catalog_alias(p_tenant TEXT, p_catalog UUID, p_version_id UUID, p_version TEXT, p_alias TEXT)
    RETURNS UUID
    LANGUAGE sql
    STABLE
AS $$
SELECT CASE
    WHEN p_alias IS NULL OR p_alias = (SELECT c.catalog_key FROM catalogs c WHERE c.tenant_key = p_tenant AND c.catalog_id = p_catalog)
        THEN p_catalog
    ELSE COALESCE(
        (SELECT epistola_catalog_id(p_tenant, d.namespace, d.dependency_key) FROM catalog_dependencies d
          WHERE p_version_id IS NOT NULL AND d.tenant_key = p_tenant AND d.version_id = p_version_id AND d.alias = p_alias),
        (SELECT epistola_catalog_id(p_tenant, d.namespace, d.dependency_key) FROM catalog_release_dependencies d
          WHERE p_version_id IS NULL AND d.tenant_key = p_tenant AND d.catalog_id = p_catalog AND d.version = p_version AND d.alias = p_alias))
END
$$;

COMMENT ON FUNCTION resolve_catalog_alias(TEXT, UUID, UUID, TEXT, TEXT) IS
    'The catalog an alias in content means, from a catalog''s draft or release, by its declarations (ADR 0033, ADR 0034 section 9); NULL when none declares it. The one place aliases are resolved.';

-- 5. What the resources of one type of the versions a caller means refer to, resolved: each row of
--    catalog_version_resources(type) with the references its content holds, and the catalog each
--    names. "Used by" and "in use?" read this.
CREATE FUNCTION catalog_version_references(p_type TEXT)
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
FROM catalog_version_resources(p_type) r
CROSS JOIN LATERAL (
    SELECT * FROM resource_references x WHERE x.tenant_key = r.tenant_key AND x.content_hash = r.content_hash OFFSET 0
) x
$$;

COMMENT ON FUNCTION catalog_version_references(TEXT) IS
    'The references of one resource type''s rows of the versions a caller means, each with the catalog it names (resolve_catalog_alias).';

-- 6. The attributes of a draft or a release, from their rows alone.
CREATE VIEW current_attributes AS
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
    FROM catalog_version_resources('attribute') r
) u;

COMMENT ON VIEW current_attributes IS
    'The variant attributes of the version a caller means, each as its JSON content, with its code list binding resolved (resolve_catalog_alias).';

-- 7. Nothing reads or writes the table any more.
DROP TABLE variant_attribute_definitions;

-- 8. The conversion filled release_resources and resource_contents at once.
ANALYZE release_resources;
ANALYZE resource_contents;
ANALYZE resource_references;
