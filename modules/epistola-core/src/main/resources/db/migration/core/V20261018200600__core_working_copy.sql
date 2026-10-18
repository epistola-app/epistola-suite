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

-- Catalog metadata is stored in the catalog wire shape, and wire v8 renamed three of its members
-- (contract CatalogKeyRenames): the presentation's iconAssetSlug and imageAssetSlugs became
-- iconImageKey and imageKeys, and an attribute assignment's catalog became catalogKey. Binding reads
-- either name, but SQL reads members by name (an image is in use when a catalog presents it), so
-- rename them in place, keeping each value: every stored row then has the one shape v8 writes.

CREATE FUNCTION pg_temp.renamed_member(member_owner JSONB, old_name TEXT, new_name TEXT) RETURNS JSONB
    LANGUAGE sql IMMUTABLE AS
$$
SELECT CASE
           WHEN jsonb_typeof(member_owner) <> 'object' OR NOT member_owner ? old_name THEN member_owner
           -- A member already under its new name wins; the old one is dropped either way.
           ELSE (member_owner - old_name) || jsonb_build_object(new_name, COALESCE(member_owner -> new_name, member_owner -> old_name))
       END
$$;

UPDATE catalogs
SET catalog_metadata = jsonb_set(
        catalog_metadata, '{presentation}',
        pg_temp.renamed_member(
            pg_temp.renamed_member(catalog_metadata -> 'presentation', 'iconAssetSlug', 'iconImageKey'),
            'imageAssetSlugs', 'imageKeys'))
WHERE jsonb_typeof(catalog_metadata -> 'presentation') = 'object';

UPDATE catalogs
SET catalog_metadata = jsonb_set(
        catalog_metadata, '{attributes}',
        (SELECT COALESCE(jsonb_agg(pg_temp.renamed_member(assignment, 'catalog', 'catalogKey') ORDER BY position), '[]'::jsonb)
         FROM jsonb_array_elements(catalog_metadata -> 'attributes') WITH ORDINALITY AS a(assignment, position)))
WHERE jsonb_typeof(catalog_metadata -> 'attributes') = 'array';
