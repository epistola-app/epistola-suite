-- ADR 0033, step 1: themes are stored as JSON. V20261024114440 moved every draft's themes into
-- release_resources draft rows naming their content; this keeps what the themes table did, on those
-- rows, and drops the table. Nothing is lost: every row was converted, with its identity, revision,
-- authors and timestamps.

-- 1. A ready mark recorded the digest of the theme's columns; it now records its content hash, so a
--    theme that was ready stays ready (the digest moves, the content does not change).
UPDATE resource_ready_marks m
SET digest = r.content_hash
FROM themes th
JOIN release_resources r
  ON r.tenant_key = th.tenant_key AND r.version_id = th.version_id AND r.resource_type = 'theme' AND r.resource_key = th.id
WHERE m.tenant_key = th.tenant_key AND m.version_id = th.version_id AND m.resource_id = th.resource_id AND m.part = ''
  AND m.digest = encode(sha256(convert_to(jsonb_build_array(
          th.name, th.description, th.document_styles, th.page_settings, th.block_style_presets, th.spacing_unit
      )::text, 'UTF8')), 'hex');

-- 2. What a draft row's identity is, as sync_catalog_resource_identity keeps it for a content table:
--    an insert adopts the identity registered at its address (or registers one), a move or rename
--    carries it along (refused while another draft holds it), and the last row holding one, of any
--    draft or release, takes it along when it goes.
CREATE FUNCTION sync_draft_resource_identity() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    existing_resource_id UUID;
    held_elsewhere BOOLEAN;
BEGIN
    IF TG_OP = 'DELETE' THEN
        SELECT EXISTS (SELECT 1 FROM release_resources WHERE tenant_key = OLD.tenant_key AND resource_id = OLD.resource_id)
            INTO held_elsewhere;
        IF NOT held_elsewhere THEN
            DELETE FROM catalog_resources WHERE tenant_key = OLD.tenant_key AND resource_id = OLD.resource_id;
        END IF;
        RETURN OLD;
    END IF;

    IF TG_OP = 'INSERT' THEN
        SELECT resource_id INTO existing_resource_id
        FROM catalog_resources
        WHERE tenant_key = NEW.tenant_key AND resource_type = NEW.resource_type
          AND catalog_id = NEW.catalog_id AND resource_key = NEW.resource_key;
        IF NEW.resource_id IS NULL THEN
            NEW.resource_id := COALESCE(existing_resource_id, epistola_uuidv7());
        ELSIF existing_resource_id IS NOT NULL AND existing_resource_id <> NEW.resource_id THEN
            RAISE EXCEPTION 'catalog resource % is already registered at %/% under identity %',
                NEW.resource_id, NEW.catalog_id, NEW.resource_key, existing_resource_id;
        END IF;
        INSERT INTO catalog_resources (tenant_key, resource_id, resource_type, catalog_id, resource_key)
        VALUES (NEW.tenant_key, NEW.resource_id, NEW.resource_type, NEW.catalog_id, NEW.resource_key)
        ON CONFLICT (tenant_key, resource_id) DO UPDATE
        SET catalog_id = EXCLUDED.catalog_id, resource_key = EXCLUDED.resource_key
        WHERE catalog_resources.resource_type = EXCLUDED.resource_type
          AND catalog_resources.catalog_id = EXCLUDED.catalog_id
          AND catalog_resources.resource_key = EXCLUDED.resource_key;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'catalog resource % is registered with another type or at another address than %/%',
                NEW.resource_id, NEW.catalog_id, NEW.resource_key;
        END IF;
        RETURN NEW;
    END IF;

    IF NEW.resource_id <> OLD.resource_id THEN
        RAISE EXCEPTION 'catalog resource_id is immutable';
    END IF;
    IF NEW.catalog_id IS DISTINCT FROM OLD.catalog_id OR NEW.resource_key IS DISTINCT FROM OLD.resource_key THEN
        SELECT EXISTS (SELECT 1 FROM release_resources WHERE tenant_key = OLD.tenant_key AND resource_id = OLD.resource_id
                       AND version_id IS NOT NULL AND version_id <> OLD.version_id)
            INTO held_elsewhere;
        IF held_elsewhere THEN
            RAISE EXCEPTION 'catalog resource % is held by another version of its catalog; it cannot move or be renamed', OLD.resource_id;
        END IF;
        UPDATE catalog_resources SET catalog_id = NEW.catalog_id, resource_key = NEW.resource_key
        WHERE tenant_key = OLD.tenant_key AND resource_id = OLD.resource_id;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'catalog resource % has no identity row', OLD.resource_id;
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

COMMENT ON FUNCTION sync_draft_resource_identity() IS
    'Keeps catalog_resources for draft rows of release_resources (ADR 0033), as sync_catalog_resource_identity does for a content table.';

CREATE TRIGGER trg_release_resources_draft_identity
    BEFORE INSERT OR UPDATE OF resource_id, catalog_id, resource_key ON release_resources
    FOR EACH ROW WHEN (NEW.version_id IS NOT NULL) EXECUTE FUNCTION sync_draft_resource_identity();
CREATE TRIGGER trg_release_resources_draft_delete
    AFTER DELETE ON release_resources
    FOR EACH ROW WHEN (OLD.version_id IS NOT NULL) EXECUTE FUNCTION sync_draft_resource_identity();
-- A draft losing a resource changes its catalog's working copy, as a content table's delete does.
CREATE TRIGGER trg_release_resources_draft_touched
    AFTER DELETE ON release_resources
    FOR EACH ROW WHEN (OLD.version_id IS NOT NULL) EXECUTE FUNCTION touch_catalog_content_on_delete();
-- A draft row's revision and updated_at, as every content table's (bump_content_revision).
CREATE TRIGGER trg_release_resources_draft_updated_at
    BEFORE UPDATE ON release_resources
    FOR EACH ROW WHEN (OLD.version_id IS NOT NULL) EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_release_resources_draft_revision
    BEFORE UPDATE ON release_resources
    FOR EACH ROW WHEN (OLD.version_id IS NOT NULL) EXECUTE FUNCTION bump_content_revision();

-- 3. A draft started from another copies its draft rows, as it copies a content table's rows.
CREATE OR REPLACE FUNCTION copy_catalog_version_content(p_tenant TEXT, p_from UUID, p_to UUID) RETURNS VOID
    LANGUAGE plpgsql
AS $$
DECLARE
    t TEXT;
    columns TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['release_resources', 'assets', 'code_lists', 'code_list_entries', 'variant_attribute_definitions', 'fonts',
                             'font_variants', 'stencils', 'document_templates', 'template_variants', 'resource_ready_marks',
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

-- 4. A theme's digest is its draft row's content hash.
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
SELECT c.tenant_key, c.resource_id, '',
       encode(sha256(convert_to(jsonb_build_array(
           c.display_name, c.description, c.source_type, c.source_url,
           (SELECT jsonb_agg(jsonb_build_array(e.code, e.label, e.sort_order, e.hidden) ORDER BY e.sort_order, e.code)
            FROM code_list_entries e
            WHERE e.tenant_key = c.tenant_key AND e.version_id = c.version_id AND e.code_list_resource_id = c.resource_id)
       )::text, 'UTF8')), 'hex'),
       c.version_id
FROM code_lists c
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

-- 5. The themes of a draft or a release, from their rows alone.
CREATE OR REPLACE VIEW current_themes AS
SELECT u.*,
       u.payload ->> 'description' AS description,
       COALESCE(u.payload -> 'documentStyles', '{}'::jsonb) AS document_styles,
       NULLIF(u.payload -> 'pageSettings', 'null'::jsonb) AS page_settings,
       NULLIF(u.payload -> 'blockStylePresets', 'null'::jsonb) AS block_style_presets,
       CAST(u.payload ->> 'spacingUnit' AS NUMERIC(6, 2)) AS spacing_unit
FROM (
    -- The content by its key, row by row, rather than a join: a join's order is the planner's to
    -- choose, and a plan made before the tables have statistics put every stored content of the
    -- tenant outermost (ThemePlanStabilityTest).
    SELECT r.tenant_key, r.catalog_id, r.version_id, r.version, r.resource_id, r.resource_key AS id, CAST(r.name AS TEXT) AS name,
           (SELECT c.payload FROM resource_contents c WHERE c.tenant_key = r.tenant_key AND c.content_hash = r.content_hash) AS payload,
           r.created_at, r.updated_at, r.created_by, r.updated_by, r.revision
    FROM catalog_version_resources('theme') r
) u;

-- 6. Nothing reads or writes the table any more.
DROP TABLE themes;

-- 7. The conversion filled release_resources and resource_contents at once; the planner must see
--    them as they are before the first node serves (a catalog's reads are planned on their size).
ANALYZE release_resources;
ANALYZE resource_contents;
