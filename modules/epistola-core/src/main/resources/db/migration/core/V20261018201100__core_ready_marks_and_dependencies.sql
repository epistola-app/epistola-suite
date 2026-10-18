-- What is kept per catalog version besides content: which resource parts were marked ready, and which
-- catalogs a draft declares it depends on; and copying a version's content into a new draft.

-- The content of a resource as one digest per part, computed from what is stored: a template has a
-- part per variant (a variant's readiness covers the template's shared fields, its data contract
-- and which variants exist), every other resource the empty part. A ready mark stores the digest it
-- was given, so an edit makes it stale without any bookkeeping.
-- Each part's digest is computed within one version, and says which: a template's variant list, a
-- code list's entries and a font's faces are those of the same version as their parent.
CREATE VIEW resource_content_digests AS
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
SELECT th.tenant_key, th.resource_id, '',
       encode(sha256(convert_to(jsonb_build_array(
           th.name, th.description, th.document_styles, th.page_settings, th.block_style_presets, th.spacing_unit
       )::text, 'UTF8')), 'hex'),
       th.version_id
FROM themes th
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

COMMENT ON VIEW resource_content_digests IS
    'The stored content of every catalog resource as one digest per part (a template per variant) and catalog version; what a ready mark records.';
COMMENT ON COLUMN resource_content_digests.version_id IS 'The catalog version whose content the digest is of.';

-- That a resource part was reviewed and may be released (contract markVariantReady and friends):
-- it is ready while its digest still equals the content's current one.
CREATE TABLE resource_ready_marks (
    tenant_key  TENANT_KEY  NOT NULL,
    resource_id UUID        NOT NULL,
    part        TEXT        NOT NULL,
    digest      CHAR(64)    NOT NULL,
    marked_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    marked_by   UUID,
    version_id  UUID        NOT NULL,
    CONSTRAINT resource_ready_marks_pkey PRIMARY KEY (tenant_key, version_id, resource_id, part),
    FOREIGN KEY (tenant_key, resource_id) REFERENCES catalog_resources (tenant_key, resource_id) ON DELETE CASCADE,
    CONSTRAINT fk_resource_ready_marks_version
        FOREIGN KEY (tenant_key, version_id) REFERENCES catalog_versions (tenant_key, version_id) ON DELETE CASCADE
);

COMMENT ON TABLE resource_ready_marks IS
    'Ready marks: the content digest (resource_content_digests) a part had when it was marked ready.';

COMMENT ON COLUMN resource_ready_marks.version_id IS 'The catalog version in which the resource part was marked ready.';

-- What was publishable before is ready now: a variant without an open draft, of a template without
-- an open draft contract; a stencil without an open draft; every other resource, which had no draft.
INSERT INTO resource_ready_marks (tenant_key, resource_id, part, digest, version_id)
SELECT d.tenant_key, d.resource_id, d.part, d.digest, d.version_id
FROM resource_content_digests d
JOIN catalog_resources r ON r.tenant_key = d.tenant_key AND r.resource_id = d.resource_id
WHERE CASE r.resource_type
    WHEN 'template' THEN NOT EXISTS (
            SELECT 1 FROM template_versions tv
            WHERE tv.tenant_key = d.tenant_key AND tv.template_resource_id = d.resource_id
              AND tv.variant_key = d.part AND tv.status = 'draft'
              -- A draft identical to the published model it came from is not unfinished work.
              AND tv.template_model IS DISTINCT FROM (
                  SELECT p.template_model FROM template_versions p
                  WHERE p.tenant_key = tv.tenant_key AND p.template_resource_id = tv.template_resource_id
                    AND p.variant_key = tv.variant_key AND p.status = 'published'
                  ORDER BY p.id DESC LIMIT 1))
        AND NOT EXISTS (
            SELECT 1 FROM contract_versions cv
            WHERE cv.tenant_key = d.tenant_key AND cv.template_resource_id = d.resource_id AND cv.status = 'draft'
              AND EXISTS (SELECT 1 FROM contract_versions p
                          WHERE p.tenant_key = cv.tenant_key AND p.template_resource_id = cv.template_resource_id
                            AND p.status = 'published')
              AND jsonb_build_array(cv.data_model, cv.data_examples) IS DISTINCT FROM (
                  SELECT jsonb_build_array(p.data_model, p.data_examples) FROM contract_versions p
                  WHERE p.tenant_key = cv.tenant_key AND p.template_resource_id = cv.template_resource_id
                    AND p.status = 'published'
                  ORDER BY p.id DESC LIMIT 1))
    WHEN 'stencil' THEN NOT EXISTS (
            SELECT 1 FROM stencil_versions sv
            WHERE sv.tenant_key = d.tenant_key AND sv.stencil_resource_id = d.resource_id AND sv.status = 'draft')
    ELSE TRUE
END;

-- A catalog declares each catalog it depends on once, with a version selector in the npm range
-- grammar (contract ADR 0003). A declaration names the catalog (registry, namespace, key) rather
-- than referencing a row: an imported catalog may depend on one the tenant does not have yet.
--
-- Each declaration is locked to one release of the dependency, like a lockfile: the release the
-- working copy's next release is cut with. A lock moves only when the declaration changes or the
-- author resolves again, never because the dependency released; an import takes the archive's.
-- A declaration no release matched yet has no lock, and both lock columns stay null together.
CREATE TABLE catalog_dependencies (
    tenant_key          TENANT_KEY   NOT NULL,
    catalog_id          UUID         NOT NULL,
    registry            TEXT         NOT NULL,
    namespace           TEXT         NOT NULL,
    dependency_key      VARCHAR(50)  NOT NULL,
    version_selector    VARCHAR(100) NOT NULL,
    -- Declaration order, which the manifest keeps.
    position            INT          NOT NULL,
    release_version     VARCHAR(100),
    release_fingerprint VARCHAR(80),
    -- The draft that declares it: each draft declares its own.
    version_id          UUID         NOT NULL,
    CONSTRAINT catalog_dependencies_pkey PRIMARY KEY (tenant_key, version_id, registry, namespace, dependency_key),
    FOREIGN KEY (tenant_key, catalog_id) REFERENCES catalogs (tenant_key, catalog_id) ON DELETE CASCADE,
    CONSTRAINT fk_catalog_dependencies_version
        FOREIGN KEY (tenant_key, version_id, catalog_id) REFERENCES catalog_versions (tenant_key, version_id, catalog_id) ON DELETE CASCADE,
    CONSTRAINT chk_catalog_dependencies_locked CHECK ((release_version IS NULL) = (release_fingerprint IS NULL))
);

COMMENT ON TABLE catalog_dependencies IS
    'The catalogs a catalog depends on, each declared once with a version selector and locked to one release (wire v8 catalogDependencies).';
COMMENT ON COLUMN catalog_dependencies.release_version IS
    'The release the declaration is locked to; null while no release of the dependency matched the selector.';
COMMENT ON COLUMN catalog_dependencies.version_id IS 'The catalog version that declares the dependency.';

-- Copies every content row of version from_version into to_version, of the same catalog (the
-- (tenant_key, version_id, catalog_id) references refuse another), keeping each resource_id: the copy
-- is the same resources. Ready marks and dependency declarations come along. Asset binaries are
-- content-addressed, so the copied rows share them. Columns are read from the catalog, so a column
-- added to a content table later is copied without changing this.
CREATE FUNCTION copy_catalog_version_content(p_tenant TEXT, p_from UUID, p_to UUID) RETURNS VOID
    LANGUAGE plpgsql
AS $$
DECLARE
    t TEXT;
    columns TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['themes', 'assets', 'code_lists', 'code_list_entries', 'variant_attribute_definitions', 'fonts',
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

COMMENT ON FUNCTION copy_catalog_version_content(TEXT, UUID, UUID) IS
    'Copies the content of one catalog version into another of the same catalog, keeping resource identities.';

