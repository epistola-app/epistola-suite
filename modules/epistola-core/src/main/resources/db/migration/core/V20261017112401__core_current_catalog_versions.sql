-- SPDX-FileCopyrightText: Epistola Nederland B.V.
--
-- SPDX-License-Identifier: AGPL-3.0-only

-- Every statement on catalog content names the catalog version it means (contract ADR 0002, step 4b,
-- slice b). A caller that names none -- the UI, the REST paths without a version, tenant-wide reads --
-- means the catalog's current version, which this view answers in one place.

-- The version a caller that names none means, per catalog: a subscribed catalog's installed release,
-- else the authored catalog's highest open draft. While a catalog has one content version
-- (uq_catalog_versions_one_per_catalog) that is the only one; with several drafts it is the newest,
-- a version that is not SemVer sorting below every one that is.
CREATE VIEW catalog_current_versions AS
SELECT DISTINCT ON (v.tenant_key, v.catalog_id) v.tenant_key, v.catalog_id, v.version_id
FROM catalog_versions v
ORDER BY v.tenant_key, v.catalog_id,
         (v.kind = 'installed') DESC,
         v.version_major DESC NULLS LAST, v.version_minor DESC NULLS LAST, v.version_patch DESC NULLS LAST,
         v.version DESC;

COMMENT ON VIEW catalog_current_versions IS
    'Per catalog, the version a caller that names none means: a subscribed catalog''s installed release, else the authored catalog''s highest open draft.';

-- Each part's digest is computed within one version, and says which: a template's variant list,
-- a code list's entries and a font's faces are those of the same version as their parent.
-- version_id is appended, so the view is replaced in place.
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
