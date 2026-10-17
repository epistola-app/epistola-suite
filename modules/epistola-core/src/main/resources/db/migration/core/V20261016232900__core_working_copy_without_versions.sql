-- SPDX-FileCopyrightText: Epistola Nederland B.V.
--
-- SPDX-License-Identifier: AGPL-3.0-only

-- The catalog release is the only version (contract ADR 0002): a template variant holds one working
-- model and a template one data contract, as stencils already do. What was published becomes ready.
--
-- template_versions and contract_versions are left in place, unread by the working copy: they are
-- history, and a generation request queued before 2.0 still renders the version it named.

ALTER TABLE template_variants
    ADD COLUMN template_model JSONB,
    ADD COLUMN referenced_paths JSONB NOT NULL DEFAULT '[]'::jsonb;

ALTER TABLE document_templates
    ADD COLUMN data_model JSONB,
    ADD COLUMN data_examples JSONB NOT NULL DEFAULT '[]'::jsonb;

-- What each author was working on: the variant's draft where it has one, else its latest published
-- version, else its latest archived one.
UPDATE template_variants v
SET template_model = src.template_model,
    referenced_paths = src.referenced_paths
FROM (
    SELECT DISTINCT ON (tenant_key, template_resource_id, variant_key)
           tenant_key, template_resource_id, variant_key, template_model, referenced_paths
    FROM template_versions
    ORDER BY tenant_key, template_resource_id, variant_key,
             CASE status WHEN 'draft' THEN 0 WHEN 'published' THEN 1 ELSE 2 END,
             id DESC
) src
WHERE src.tenant_key = v.tenant_key AND src.template_resource_id = v.template_resource_id AND src.variant_key = v.id;

-- A variant that never had a version gets the empty document a new variant starts with.
UPDATE template_variants
SET template_model = '{"modelVersion": 1, "root": "root", "nodes": {"root": {"id": "root", "type": "root", "slots": ["slot-root"]}}, "slots": {"slot-root": {"id": "slot-root", "nodeId": "root", "name": "children", "children": []}}, "themeRef": {"type": "inherit"}}'::jsonb
WHERE template_model IS NULL;

ALTER TABLE template_variants ALTER COLUMN template_model SET NOT NULL;

UPDATE document_templates t
SET data_model = src.data_model,
    data_examples = COALESCE(src.data_examples, '[]'::jsonb)
FROM (
    SELECT DISTINCT ON (tenant_key, template_resource_id)
           tenant_key, template_resource_id, data_model, data_examples
    FROM contract_versions
    ORDER BY tenant_key, template_resource_id,
             CASE status WHEN 'draft' THEN 0 ELSE 1 END,
             id DESC
) src
WHERE src.tenant_key = t.tenant_key AND src.template_resource_id = t.resource_id;

COMMENT ON COLUMN template_variants.template_model IS 'The variant''s working model; the catalog release is its only version.';
COMMENT ON COLUMN template_variants.referenced_paths IS 'Data-contract paths the working model reads, kept for contract compatibility checks.';
COMMENT ON COLUMN document_templates.data_model IS 'The template''s working data contract (JSON Schema); NULL when it has none.';
COMMENT ON COLUMN document_templates.data_examples IS 'The template''s working data examples, each with a key.';

-- The content of a resource as one digest per part, computed from what is stored: a template has a
-- part per variant (a variant's readiness covers the template's shared fields, its data contract
-- and which variants exist), every other resource the empty part. A ready mark stores the digest it
-- was given, so an edit makes it stale without any bookkeeping.
CREATE VIEW resource_content_digests AS
SELECT v.tenant_key, t.resource_id, v.id::text AS part,
       encode(sha256(convert_to(jsonb_build_array(
           t.name, t.pdfa_enabled, t.theme_resource_id, t.data_model, t.data_examples,
           (SELECT jsonb_agg(o.id ORDER BY o.id) FROM template_variants o
            WHERE o.tenant_key = t.tenant_key AND o.template_resource_id = t.resource_id),
           v.title, v.description, v.attributes, v.is_default, v.template_model
       )::text, 'UTF8')), 'hex') AS digest
FROM document_templates t
JOIN template_variants v ON v.tenant_key = t.tenant_key AND v.template_resource_id = t.resource_id
UNION ALL
SELECT s.tenant_key, s.resource_id, '',
       encode(sha256(convert_to(jsonb_build_array(
           s.name, s.description, s.tags, s.content, s.parameter_schema, s.reads_template_data
       )::text, 'UTF8')), 'hex')
FROM stencils s
UNION ALL
SELECT th.tenant_key, th.resource_id, '',
       encode(sha256(convert_to(jsonb_build_array(
           th.name, th.description, th.document_styles, th.page_settings, th.block_style_presets, th.spacing_unit
       )::text, 'UTF8')), 'hex')
FROM themes th
UNION ALL
SELECT a.tenant_key, a.resource_id, '',
       encode(sha256(convert_to(jsonb_build_array(
           a.display_name, a.allowed_values, a.code_list_resource_id
       )::text, 'UTF8')), 'hex')
FROM variant_attribute_definitions a
UNION ALL
SELECT c.tenant_key, c.resource_id, '',
       encode(sha256(convert_to(jsonb_build_array(
           c.display_name, c.description, c.source_type, c.source_url,
           (SELECT jsonb_agg(jsonb_build_array(e.code, e.label, e.sort_order, e.hidden) ORDER BY e.sort_order, e.code)
            FROM code_list_entries e WHERE e.tenant_key = c.tenant_key AND e.code_list_resource_id = c.resource_id)
       )::text, 'UTF8')), 'hex')
FROM code_lists c
UNION ALL
SELECT f.tenant_key, f.resource_id, '',
       encode(sha256(convert_to(jsonb_build_array(
           f.name, f.kind,
           (SELECT jsonb_agg(jsonb_build_array(fv.weight, fv.italic, fv.source, fv.classpath_location, fv.content_hash, fv.asset_resource_id)
                             ORDER BY fv.weight, fv.italic)
            FROM font_variants fv WHERE fv.tenant_key = f.tenant_key AND fv.font_resource_id = f.resource_id)
       )::text, 'UTF8')), 'hex')
FROM fonts f
UNION ALL
SELECT i.tenant_key, i.resource_id, '',
       encode(sha256(convert_to(jsonb_build_array(
           i.name, i.media_type, i.content_hash, i.width, i.height, i.sensitive
       )::text, 'UTF8')), 'hex')
FROM assets i;

COMMENT ON VIEW resource_content_digests IS
    'The stored content of every catalog resource as one digest per part (a template per variant); what a ready mark records.';

-- That a resource part was reviewed and may be released (contract markVariantReady and friends):
-- it is ready while its digest still equals the content's current one.
CREATE TABLE resource_ready_marks (
    tenant_key  TENANT_KEY  NOT NULL,
    resource_id UUID        NOT NULL,
    part        TEXT        NOT NULL,
    digest      CHAR(64)    NOT NULL,
    marked_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    marked_by   UUID,
    PRIMARY KEY (tenant_key, resource_id, part),
    FOREIGN KEY (tenant_key, resource_id) REFERENCES catalog_resources (tenant_key, resource_id) ON DELETE CASCADE
);

COMMENT ON TABLE resource_ready_marks IS
    'Ready marks: the content digest (resource_content_digests) a part had when it was marked ready.';

-- What was publishable before is ready now: a variant without an open draft, of a template without
-- an open draft contract; a stencil without an open draft; every other resource, which had no draft.
INSERT INTO resource_ready_marks (tenant_key, resource_id, part, digest)
SELECT d.tenant_key, d.resource_id, d.part, d.digest
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
