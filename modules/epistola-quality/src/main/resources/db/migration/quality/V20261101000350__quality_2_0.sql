-- SPDX-FileCopyrightText: Epistola Nederland B.V.
--
-- SPDX-License-Identifier: AGPL-3.0-only

-- The 2.0 upgrade of quality findings (docs/migrations.md, "The 2.0 chain"). It runs after core step 3,
-- which creates JSON storage, and before core step 14, which drops the 1.x template tables this module
-- referenced.

-- 1. A finding is about a template, whichever version holds it. Several versions of a catalog each
-- hold their own row of a template, so a finding references the template's identity in
-- catalog_resources; deleting the identity (the last version holding the template) still deletes its
-- findings and ignores. The variant-level reference cannot stay: no table holds a variant's identity
-- across versions. A finding keeps its variant_key, and a trigger (3) deletes it with the variant.
ALTER TABLE quality_findings
    DROP CONSTRAINT quality_findings_variant_fkey,
    DROP CONSTRAINT quality_findings_template_fkey,
    ADD CONSTRAINT quality_findings_template_fkey
        FOREIGN KEY (tenant_key, template_resource_id)
        REFERENCES catalog_resources (tenant_key, resource_id) ON DELETE CASCADE;

ALTER TABLE quality_finding_ignores
    DROP CONSTRAINT quality_finding_ignores_template_fkey,
    ADD CONSTRAINT quality_finding_ignores_template_fkey
        FOREIGN KEY (tenant_key, template_resource_id)
        REFERENCES catalog_resources (tenant_key, resource_id) ON DELETE CASCADE;

-- 2. A template's URN names its catalog by address (ADR 0034, section 9): `tenant/namespace/key/...`,
-- since two catalogs of a tenant may share a key. A 1.x finding and ignore name the catalog by key
-- alone (`tenant/key/...`); each gets its template's namespace (core step 1), so an ignore keeps
-- matching its findings.
UPDATE quality_findings f
SET subject_urn = regexp_replace(f.subject_urn, '^(urn:epistola:[a-z-]+:[^/]+/)', '\1' || c.namespace || '/'),
    ignore_scope_urn = regexp_replace(f.ignore_scope_urn, '^(urn:epistola:[a-z-]+:[^/]+/)', '\1' || c.namespace || '/')
FROM catalog_resources r
JOIN catalogs c ON c.tenant_key = r.tenant_key AND c.catalog_id = r.catalog_id
WHERE r.tenant_key = f.tenant_key AND r.resource_id = f.template_resource_id;

UPDATE quality_finding_ignores i
SET ignore_scope_urn = regexp_replace(i.ignore_scope_urn, '^(urn:epistola:[a-z-]+:[^/]+/)', '\1' || c.namespace || '/')
FROM catalog_resources r
JOIN catalogs c ON c.tenant_key = r.tenant_key AND c.catalog_id = r.catalog_id
WHERE r.tenant_key = i.tenant_key AND r.resource_id = i.template_resource_id;

-- 3. A variant is an entry of its template's content, so a finding follows it through
-- release_resources: when a template's row stops holding a variant (its content changes, or the row
-- goes) and no other row of that template, draft or release, holds it any more, its findings go, as
-- the dropped reference cascaded.
CREATE FUNCTION quality_findings_follow_variant() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    DELETE FROM quality_findings x
    WHERE x.tenant_key = OLD.tenant_key AND x.template_resource_id = OLD.resource_id
      AND x.variant_key IN (
          SELECT e ->> 'key' FROM resource_contents c, jsonb_array_elements(c.payload -> 'resource' -> 'variants') e
          WHERE c.tenant_key = OLD.tenant_key AND c.content_hash = OLD.content_hash
      )
      AND NOT EXISTS (
          SELECT 1 FROM release_resources r
          JOIN resource_contents c ON c.tenant_key = r.tenant_key AND c.content_hash = r.content_hash
          CROSS JOIN LATERAL jsonb_array_elements(c.payload -> 'resource' -> 'variants') e
          WHERE r.tenant_key = OLD.tenant_key AND r.resource_id = OLD.resource_id AND r.resource_type = 'template'
            AND e ->> 'key' = x.variant_key
      );
    RETURN NULL;
END;
$$;

CREATE TRIGGER trg_release_resources_quality_findings_follow_variant
    AFTER UPDATE OF content_hash OR DELETE ON release_resources
    FOR EACH ROW WHEN (OLD.resource_type = 'template')
    EXECUTE FUNCTION quality_findings_follow_variant();

-- 4. An ignore follows its template identity (1): its deletion finds the ignores by an index.
CREATE INDEX idx_quality_finding_ignores_template ON quality_finding_ignores (tenant_key, template_resource_id);
