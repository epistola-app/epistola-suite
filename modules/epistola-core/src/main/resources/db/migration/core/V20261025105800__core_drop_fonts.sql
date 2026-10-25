-- ADR 0033, step 5: fonts are stored as JSON. V20261025105724 moved every draft's fonts into
-- release_resources draft rows naming their content, with a face's file a binary of the font; this
-- keeps what the tables did, on those rows, drops them, and removes the asset rows that only held a
-- face's file.

-- 1. A ready mark recorded the digest of the font's columns; it now records its content hash.
UPDATE resource_ready_marks m
SET digest = r.content_hash
FROM fonts f
JOIN release_resources r
  ON r.tenant_key = f.tenant_key AND r.version_id = f.version_id AND r.resource_type = 'font' AND r.resource_key = f.slug
WHERE m.tenant_key = f.tenant_key AND m.version_id = f.version_id AND m.resource_id = f.resource_id AND m.part = ''
  AND m.digest = encode(sha256(convert_to(jsonb_build_array(
           f.name, f.kind,
           (SELECT jsonb_agg(jsonb_build_array(fv.weight, fv.italic, fv.source, fv.classpath_location, fv.content_hash, fv.asset_resource_id)
                             ORDER BY fv.weight, fv.italic)
            FROM font_variants fv
            WHERE fv.tenant_key = f.tenant_key AND fv.version_id = f.version_id AND fv.font_resource_id = f.resource_id)
       )::text, 'UTF8')), 'hex');

-- 2. The asset rows that only held a face's file: not an image, named by nothing but a face, and
--    its bytes in asset_content. A face's bytes are its font's binary now (resource_binaries). An image a face
--    used is kept as the image it is; the face links to its bytes as any face does.
CREATE TEMPORARY TABLE font_face_assets ON COMMIT DROP AS
SELECT DISTINCT a.tenant_key, a.resource_id
FROM assets a
JOIN font_variants fv ON fv.tenant_key = a.tenant_key AND fv.asset_resource_id = a.resource_id
WHERE a.media_type NOT LIKE 'image/%'
  -- Only where its bytes are in asset_content, which the font's content links to. A row the 1.x
  -- content backfill has not reached yet still holds the only way to its legacy bytes: it stays.
  AND EXISTS (
      SELECT 1 FROM asset_content ac
      WHERE ac.content_hash = a.content_hash AND ac.scope IN (a.tenant_key::text, 'global')
  )
  AND NOT EXISTS (
      SELECT 1 FROM resource_references x
      WHERE x.tenant_key = a.tenant_key AND x.resource_type = 'image' AND x.resource_key = a.id::text
  )
  AND NOT EXISTS (
      SELECT 1 FROM template_variants tv
      WHERE tv.tenant_key = a.tenant_key
        AND jsonb_path_exists(tv.template_model, '$.** ? (@.imageKey == $k)', jsonb_build_object('k', a.id::text))
  );

DO $$
DECLARE kept INTEGER;
BEGIN
    SELECT count(DISTINCT (a.tenant_key, a.resource_id)) INTO kept
    FROM assets a JOIN font_variants fv ON fv.tenant_key = a.tenant_key AND fv.asset_resource_id = a.resource_id
    WHERE NOT EXISTS (SELECT 1 FROM font_face_assets f WHERE f.tenant_key = a.tenant_key AND f.resource_id = a.resource_id);
    IF kept > 0 THEN
        RAISE NOTICE 'Kept % asset(s) a font face used: images, files named elsewhere, or files whose bytes the content backfill has not moved yet', kept;
    END IF;
END $$;

-- 3. A draft started from another no longer copies tables of fonts: its rows are draft rows.
CREATE OR REPLACE FUNCTION copy_catalog_version_content(p_tenant TEXT, p_from UUID, p_to UUID) RETURNS VOID
    LANGUAGE plpgsql
AS $$
DECLARE
    t TEXT;
    columns TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['release_resources', 'assets', 'document_templates', 'template_variants', 'resource_ready_marks', 'code_list_refreshes',
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

-- 4. A font's digest is its draft row's content hash.
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
SELECT i.tenant_key, i.resource_id, '',
       encode(sha256(convert_to(jsonb_build_array(
           i.name, i.media_type, i.content_hash, i.width, i.height, i.sensitive
       )::text, 'UTF8')), 'hex'),
       i.version_id
FROM assets i;

-- 5. The fonts of a draft or a release, from their rows alone.
CREATE VIEW current_fonts AS
SELECT u.*,
       u.payload -> 'resource' ->> 'kind' AS kind,
       COALESCE(u.payload -> 'resource' -> 'variants', '[]'::jsonb) AS variants
FROM (
    -- The content by its key, row by row: a fenced LATERAL (ThemePlanStabilityTest).
    SELECT r.tenant_key, r.catalog_id, r.version_id, r.version, r.resource_id, r.resource_key AS slug, CAST(r.name AS TEXT) AS name,
           c.payload, r.created_at, r.updated_at, r.created_by, r.updated_by, r.revision
    FROM catalog_version_resources('font') r
    CROSS JOIN LATERAL (
        SELECT c.payload FROM resource_contents c WHERE c.tenant_key = r.tenant_key AND c.content_hash = r.content_hash OFFSET 0
    ) c
) u;

COMMENT ON VIEW current_fonts IS
    'The fonts of the version a caller means, each as its JSON content (payload: resource, the wire form); its faces'' bytes are its resource_binaries.';

-- 6. Nothing reads or writes the tables any more.
DROP TABLE font_variants;
DROP TABLE fonts;

-- 7. The face-only asset rows go, with their ready marks; their identity follows (sync_catalog_resource_identity).
DELETE FROM resource_ready_marks m USING font_face_assets f WHERE m.tenant_key = f.tenant_key AND m.resource_id = f.resource_id;
DELETE FROM assets a USING font_face_assets f WHERE a.tenant_key = f.tenant_key AND a.resource_id = f.resource_id;

-- 8. The conversion filled release_resources, resource_contents and resource_binaries at once.
ANALYZE release_resources;
ANALYZE resource_contents;
ANALYZE resource_binaries;
