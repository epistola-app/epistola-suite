-- SPDX-FileCopyrightText: Epistola Nederland B.V.
--
-- SPDX-License-Identifier: AGPL-3.0-only

-- A template's variants name the attributes they select by (ADR 0039): those are references, indexed
-- in resource_references as a template content is stored (TemplateResourceType.references). Content
-- stored before is indexed here, by the same rule: a key `catalog.attribute` names that catalog by
-- its alias, a bare key no alias. Keys are slugs and hold no dot, so the split is unambiguous.
-- Additive only: it writes index rows and changes no content.
INSERT INTO resource_references (tenant_key, content_hash, alias, resource_type, resource_key)
SELECT DISTINCT c.tenant_key,
       c.content_hash,
       CASE WHEN position('.' IN a.name) > 0 THEN split_part(a.name, '.', 1) END,
       'attribute',
       CASE WHEN position('.' IN a.name) > 0 THEN split_part(a.name, '.', 2) ELSE a.name END
FROM resource_contents c
-- A template stored in the envelope, or one retained before it was, whose resource is the content.
CROSS JOIN LATERAL (
    SELECT CASE WHEN jsonb_typeof(c.payload -> 'resource') = 'object' THEN c.payload -> 'resource' -> 'variants' ELSE c.payload -> 'variants' END AS variants
) AS t
CROSS JOIN LATERAL jsonb_array_elements(CASE WHEN jsonb_typeof(t.variants) = 'array' THEN t.variants ELSE '[]'::jsonb END) AS v(slot)
CROSS JOIN LATERAL jsonb_object_keys(
    CASE WHEN jsonb_typeof(v.slot -> 'attributes') = 'object' THEN v.slot -> 'attributes' ELSE '{}'::jsonb END
) AS a(name)
WHERE c.kind = 'template' AND jsonb_typeof(v.slot) = 'object'
ON CONFLICT (tenant_key, content_hash, COALESCE(alias, ''), resource_type, resource_key) DO NOTHING;
