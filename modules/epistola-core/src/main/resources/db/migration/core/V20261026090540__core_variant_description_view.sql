-- A variant's description is part of its released form since contract 2.0.0-alpha.2
-- (`VariantEntry.description`; V20261026085934 moved every draft variant's there). The view reads it
-- there first; a release holding a content of the older shape keeps it beside the resource, and one
-- retained before variants had rows in its template's `variantDescriptions`.
CREATE OR REPLACE VIEW current_template_variants AS
SELECT t.tenant_key, t.catalog_id, t.version_id, t.version, t.resource_id AS template_resource_id,
       e.slot ->> 'key' AS id,
       COALESCE(v.payload -> 'resource' ->> 'title', e.slot ->> 'title', e.slot ->> 'key') AS title,
       COALESCE(v.payload -> 'resource' ->> 'description', v.payload ->> 'description', t.payload -> 'variantDescriptions' ->> (e.slot ->> 'key')) AS description,
       COALESCE(NULLIF(e.slot -> 'attributes', 'null'::jsonb), '{}'::jsonb) AS attributes,
       COALESCE(CAST(e.slot ->> 'isDefault' AS BOOLEAN), FALSE) AS is_default,
       NULLIF(COALESCE(v.payload -> 'resource' -> 'metadata', e.slot -> 'metadata'), 'null'::jsonb) AS metadata,
       COALESCE(v.content_hash, m.model_hash) AS model_hash,
       -- Its model; the default variant's for one that has none of its own (as a received release may
       -- say). A release retained before variants had rows names its models by revision (m).
       COALESCE(v.payload -> 'resource' -> 'templateModel', d.payload -> 'resource' -> 'templateModel', m.model) AS template_model,
       e.position,
       COALESCE(v.created_at, t.created_at) AS created_at, COALESCE(v.updated_at, t.updated_at) AS updated_at,
       COALESCE(v.created_by, t.created_by) AS created_by, COALESCE(v.updated_by, t.updated_by) AS updated_by,
       COALESCE(v.revision, t.revision) AS revision
FROM (
    SELECT r.tenant_key, r.catalog_id, r.version_id, r.version, r.resource_id, c.payload,
           r.created_at, r.updated_at, r.created_by, r.updated_by, r.revision
    FROM catalog_version_resources('template') r
    CROSS JOIN LATERAL (
        SELECT c.payload FROM resource_contents c WHERE c.tenant_key = r.tenant_key AND c.content_hash = r.content_hash OFFSET 0
    ) c
) t
CROSS JOIN LATERAL jsonb_array_elements(COALESCE(t.payload -> 'resource' -> 'variants', '[]'::jsonb)) WITH ORDINALITY AS e(slot, position)
-- Each variant is a row of its own, naming its template's row: its content and revision.
LEFT JOIN LATERAL (
    SELECT p.content_hash, c.payload, p.created_at, p.updated_at, p.created_by, p.updated_by, p.revision
    FROM release_resources p
    JOIN resource_contents c ON c.tenant_key = p.tenant_key AND c.content_hash = p.content_hash
    WHERE p.tenant_key = t.tenant_key AND p.parent_id = t.resource_id AND p.resource_key = e.slot ->> 'key'
      AND p.catalog_id = t.catalog_id AND p.resource_type = 'variant'
      AND p.version_id IS NOT DISTINCT FROM t.version_id AND p.version IS NOT DISTINCT FROM t.version
    OFFSET 0
) v ON TRUE
LEFT JOIN LATERAL (
    SELECT c.payload
    FROM jsonb_array_elements(t.payload -> 'resource' -> 'variants') x(slot)
    JOIN release_resources p ON p.tenant_key = t.tenant_key AND p.parent_id = t.resource_id AND p.resource_key = x.slot ->> 'key'
      AND p.catalog_id = t.catalog_id AND p.resource_type = 'variant'
      AND p.version_id IS NOT DISTINCT FROM t.version_id AND p.version IS NOT DISTINCT FROM t.version
    JOIN resource_contents c ON c.tenant_key = p.tenant_key AND c.content_hash = p.content_hash
    WHERE CAST(x.slot ->> 'isDefault' AS BOOLEAN) AND v.content_hash IS NOT NULL AND v.payload -> 'resource' -> 'templateModel' IS NULL
    OFFSET 0
) d ON TRUE
LEFT JOIN LATERAL (
    SELECT x.model_hash, (SELECT c.payload FROM resource_contents c WHERE c.tenant_key = t.tenant_key AND c.content_hash = x.model_hash) AS model
    FROM (SELECT COALESCE(e.slot -> 'templateModel' ->> 'revisionDigest', t.payload -> 'resource' -> 'templateModel' ->> 'revisionDigest') AS model_hash) x
    WHERE v.content_hash IS NULL
    OFFSET 0
) m ON TRUE;
