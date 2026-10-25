-- ADR 0033, step 7b: templates are stored as JSON. V20261025170700 moved every draft's templates into
-- release_resources draft rows naming their content; this keeps what the tables did, on those rows,
-- and drops them.

-- 1. A variant's digest, for its ready mark: what its template says of every variant alike (but its
--    description and tags) and the variant's own entry, model and description. Templates hold their
--    variants in one content, so a variant is ready apart from the others.
CREATE FUNCTION template_variant_digest(p_payload JSONB, p_variant JSONB) RETURNS TEXT
    LANGUAGE sql
    IMMUTABLE
AS $$
SELECT encode(sha256(convert_to(jsonb_build_array(
    (p_payload -> 'resource') - 'variants' - 'templateModel' - 'description' - 'tags',
    (SELECT jsonb_agg(e ->> 'key' ORDER BY e ->> 'key') FROM jsonb_array_elements(p_payload -> 'resource' -> 'variants') e),
    p_variant - 'templateModel',
    COALESCE(NULLIF(p_variant -> 'templateModel', 'null'::jsonb), p_payload -> 'resource' -> 'templateModel'),
    p_payload -> 'variantDescriptions' -> (p_variant ->> 'key')
)::text, 'UTF8')), 'hex')
$$;

COMMENT ON FUNCTION template_variant_digest(JSONB, JSONB) IS
    'The digest a ready mark records for one variant of a template stored as JSON (ADR 0033).';

-- 2. A mark recorded the digest of the template's and the variant's columns; it now records the
--    variant's digest of its content, where it still matched.
UPDATE resource_ready_marks m
SET digest = template_variant_digest(c.payload, e.variant)
FROM document_templates t
JOIN template_variants v ON v.tenant_key = t.tenant_key AND v.version_id = t.version_id AND v.template_resource_id = t.resource_id
JOIN release_resources r ON r.tenant_key = t.tenant_key AND r.version_id = t.version_id AND r.resource_type = 'template' AND r.resource_key = t.id
JOIN resource_contents c ON c.tenant_key = r.tenant_key AND c.content_hash = r.content_hash
CROSS JOIN LATERAL jsonb_array_elements(c.payload -> 'resource' -> 'variants') AS e(variant)
WHERE e.variant ->> 'key' = v.id
  AND m.tenant_key = t.tenant_key AND m.version_id = t.version_id AND m.resource_id = t.resource_id AND m.part = v.id
  AND m.digest = encode(sha256(convert_to(jsonb_build_array(
          t.name, t.pdfa_enabled, t.theme_resource_id, t.data_model, t.data_examples,
          (SELECT jsonb_agg(o.id ORDER BY o.id) FROM template_variants o
           WHERE o.tenant_key = t.tenant_key AND o.version_id = t.version_id AND o.template_resource_id = t.resource_id),
          v.title, v.description, v.attributes, v.is_default, v.template_model
      )::text, 'UTF8')), 'hex');

-- 3. A draft started from another no longer copies tables of templates: its rows are draft rows.
CREATE OR REPLACE FUNCTION copy_catalog_version_content(p_tenant TEXT, p_from UUID, p_to UUID) RETURNS VOID
    LANGUAGE plpgsql
AS $$
DECLARE
    t TEXT;
    columns TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['release_resources', 'resource_ready_marks', 'code_list_refreshes', 'catalog_dependencies']
    LOOP
        SELECT string_agg(quote_ident(column_name), ', ' ORDER BY ordinal_position) INTO columns
        FROM information_schema.columns
        WHERE table_schema = current_schema() AND table_name = t AND is_generated = 'NEVER' AND column_name <> 'version_id';
        EXECUTE format('INSERT INTO %I (%s, version_id) SELECT %s, $2 FROM %I WHERE tenant_key = $1 AND version_id = $3', t, columns, columns, t)
            USING p_tenant, p_to, p_from;
    END LOOP;
END;
$$;

-- 4. Every digest comes from a draft row now: a template's per variant, the rest the row's content hash.
CREATE OR REPLACE VIEW resource_content_digests AS
SELECT r.tenant_key, r.resource_id, CAST(e.variant ->> 'key' AS TEXT) AS part,
       template_variant_digest(c.payload, e.variant) AS digest,
       r.version_id
FROM release_resources r
JOIN resource_contents c ON c.tenant_key = r.tenant_key AND c.content_hash = r.content_hash
CROSS JOIN LATERAL jsonb_array_elements(c.payload -> 'resource' -> 'variants') AS e(variant)
WHERE r.version_id IS NOT NULL AND r.resource_type = 'template'
UNION ALL
-- A resource type stored as JSON (ADR 0033): its draft row's content hash is its digest.
SELECT r.tenant_key, r.resource_id, '',
       CAST(r.content_hash AS TEXT),
       r.version_id
FROM release_resources r
WHERE r.version_id IS NOT NULL AND r.resource_type <> 'template';

-- 5. A document or a generation request that names its template by address finds its identity in
--    the registry, which holds every template's, whichever version holds it.
CREATE OR REPLACE FUNCTION fill_generation_template_identity() RETURNS TRIGGER AS $$
BEGIN
    IF NEW.template_resource_id IS NULL THEN
        SELECT r.resource_id INTO NEW.template_resource_id
        FROM catalog_resources r
        JOIN catalogs c ON c.tenant_key = r.tenant_key AND c.catalog_id = r.catalog_id
        WHERE r.tenant_key = NEW.tenant_key
          AND r.resource_type = 'template'
          AND c.registry = NEW.registry
          AND c.namespace = NEW.namespace
          AND c.catalog_key = NEW.catalog_key
          AND r.resource_key = NEW.template_key;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'no template % in catalog %/%/% for tenant %',
                NEW.template_key, NEW.registry, NEW.namespace, NEW.catalog_key, NEW.tenant_key;
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- 6. The templates and their variants of a draft or a release, from their rows alone (ADR 0033): one
--    read for both, as for every other type. A template names its theme by alias and key, resolved
--    as everything that reads content resolves an alias.
DROP VIEW current_template_variants;
DROP VIEW current_templates;

CREATE VIEW current_templates AS
SELECT u.*,
       u.payload -> 'resource' ->> 'description' AS description,
       COALESCE(CAST(u.payload -> 'resource' ->> 'pdfaEnabled' AS BOOLEAN), TRUE) AS pdfa_enabled,
       NULLIF(u.payload -> 'resource' -> 'dataModel', 'null'::jsonb) AS data_model,
       -- An example's id is its key; one a received release left without a key reads by its name.
       COALESCE((SELECT jsonb_agg(e.example || jsonb_build_object('id', COALESCE(e.example ->> 'key', e.example ->> 'name')) ORDER BY e.n)
                 FROM jsonb_array_elements(NULLIF(u.payload -> 'resource' -> 'dataExamples', 'null'::jsonb)) WITH ORDINALITY AS e(example, n)), '[]'::jsonb) AS data_examples,
       u.payload -> 'resource' ->> 'themeKey' AS theme_key,
       theme.catalog_id AS theme_catalog_id,
       (SELECT cr.resource_id FROM catalog_resources cr
        WHERE cr.tenant_key = u.tenant_key AND cr.catalog_id = theme.catalog_id AND cr.resource_type = 'theme'
          AND cr.resource_key = u.payload -> 'resource' ->> 'themeKey') AS theme_resource_id,
       CAST(jsonb_array_length(COALESCE(u.payload -> 'resource' -> 'variants', '[]'::jsonb)) AS BIGINT) AS variant_count
FROM (
    -- The content by its key, row by row: a fenced LATERAL (ThemePlanStabilityTest).
    SELECT r.tenant_key, r.catalog_id, r.version_id, r.version, r.resource_id, r.resource_key AS id, CAST(r.name AS TEXT) AS name,
           r.content_hash, c.payload, r.created_at, r.updated_at, r.created_by, r.updated_by, r.revision
    FROM catalog_version_resources('template') r
    CROSS JOIN LATERAL (
        SELECT c.payload FROM resource_contents c WHERE c.tenant_key = r.tenant_key AND c.content_hash = r.content_hash OFFSET 0
    ) c
) u
CROSS JOIN LATERAL (
    SELECT CASE WHEN u.payload -> 'resource' ->> 'themeKey' IS NOT NULL
                THEN resolve_catalog_alias(u.tenant_key, u.catalog_id, u.version_id, u.version, u.payload -> 'resource' ->> 'themeCatalogKey')
           END AS catalog_id
) theme;

COMMENT ON VIEW current_templates IS
    'The templates of the version a caller means, each as its JSON content (payload: resource, the wire form; variantDescriptions beside it).';

CREATE VIEW current_template_variants AS
SELECT t.tenant_key, t.catalog_id, t.version_id, t.version, t.resource_id AS template_resource_id,
       e.variant ->> 'key' AS id,
       COALESCE(e.variant ->> 'title', e.variant ->> 'key') AS title,
       t.payload -> 'variantDescriptions' ->> (e.variant ->> 'key') AS description,
       COALESCE(NULLIF(e.variant -> 'attributes', 'null'::jsonb), '{}'::jsonb) AS attributes,
       COALESCE(CAST(e.variant ->> 'isDefault' AS BOOLEAN), FALSE) AS is_default,
       NULLIF(e.variant -> 'metadata', 'null'::jsonb) AS metadata,
       m.model_hash,
       m.model AS template_model,
       e.position,
       t.created_at, t.updated_at, t.created_by, t.updated_by, t.revision
FROM (
    SELECT r.tenant_key, r.catalog_id, r.version_id, r.version, r.resource_id, c.payload,
           r.created_at, r.updated_at, r.created_by, r.updated_by, r.revision
    FROM catalog_version_resources('template') r
    CROSS JOIN LATERAL (
        SELECT c.payload FROM resource_contents c WHERE c.tenant_key = r.tenant_key AND c.content_hash = r.content_hash OFFSET 0
    ) c
) t
CROSS JOIN LATERAL jsonb_array_elements(COALESCE(t.payload -> 'resource' -> 'variants', '[]'::jsonb)) WITH ORDINALITY AS e(variant, position)
-- Its model: the content it was lifted into, the template's for a variant without one of its own.
CROSS JOIN LATERAL (
    SELECT x.model_hash, (SELECT c.payload FROM resource_contents c WHERE c.tenant_key = t.tenant_key AND c.content_hash = x.model_hash) AS model
    FROM (SELECT COALESCE(e.variant -> 'templateModel' ->> 'revisionDigest', t.payload -> 'resource' -> 'templateModel' ->> 'revisionDigest') AS model_hash) x
    OFFSET 0
) m;

COMMENT ON VIEW current_template_variants IS
    'The variants of the templates of the version a caller means, each with its model, read from the content it was lifted into.';

-- 7. Nothing reads or writes the tables any more; their triggers go with them. Quality findings and
--    load-test runs follow a variant through release_resources now (their modules' migrations).
DROP TABLE template_variants;
DROP TABLE document_templates;

-- 8. The conversion filled release_resources, resource_contents and resource_references at once.
ANALYZE release_resources;
ANALYZE resource_contents;
ANALYZE resource_references;
