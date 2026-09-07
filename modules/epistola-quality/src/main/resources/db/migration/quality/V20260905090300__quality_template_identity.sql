-- backup-restore-compatibility: backward=false forward=false
-- reason: Replaces the template address on both quality tables with the template's identity.
-- A backup taken either side carries a different column set for two backed-up tables.

-- Findings and ignores name the template, not where it lives
--
-- Both tables carried the template's address purely to let the database collect their rows when
-- the template goes. V20260905090200 releases those foreign keys along with every other one into
-- the hierarchy and re-keys the template on its identity; this rebuilds quality's two on that
-- identity, which is the same cleanup with nothing to drift.
--
-- The ignore's scope stays the URN. It is the wire value the disposition feed is defined in terms
-- of, and it must also be able to name a future non-template subject, which an identity column
-- cannot. `OnResourceRelocatedRepointFindings` rewrites both tables' URNs when a template moves,
-- which is what keeps a finding and its ignore joined across a relocation.

-- 1. Add ------------------------------------------------------------------------------------------
ALTER TABLE quality_findings ADD COLUMN template_resource_id UUID;
ALTER TABLE quality_finding_ignores ADD COLUMN template_resource_id UUID;

-- 2. Backfill, asserting every row found its template ------------------------------------------------
UPDATE quality_findings findings
SET template_resource_id = templates.resource_id
FROM document_templates templates
WHERE templates.tenant_key = findings.tenant_key
  AND templates.catalog_key = findings.catalog_key
  AND templates.id = findings.template_key;

-- An ignore's address columns are nullable: a non-template scope leaves them unset.
UPDATE quality_finding_ignores ignores
SET template_resource_id = templates.resource_id
FROM document_templates templates
WHERE templates.tenant_key = ignores.tenant_key
  AND templates.catalog_key = ignores.catalog_key
  AND templates.id = ignores.template_key;

DO $$
DECLARE unmatched BIGINT;
BEGIN
    SELECT count(*) INTO unmatched FROM quality_findings WHERE template_resource_id IS NULL;
    IF unmatched > 0 THEN
        RAISE EXCEPTION '% quality findings reference a template that does not exist', unmatched;
    END IF;

    SELECT count(*) INTO unmatched FROM quality_finding_ignores
    WHERE template_key IS NOT NULL AND template_resource_id IS NULL;
    IF unmatched > 0 THEN
        RAISE EXCEPTION '% quality ignores reference a template that does not exist', unmatched;
    END IF;
END $$;

ALTER TABLE quality_findings ALTER COLUMN template_resource_id SET NOT NULL;

-- 3. Reference by identity, and drop the address copies ------------------------------------------------
ALTER TABLE quality_findings
    DROP COLUMN catalog_key,
    DROP COLUMN template_key,
    ADD CONSTRAINT quality_findings_template_fkey
        FOREIGN KEY (tenant_key, template_resource_id)
        REFERENCES document_templates(tenant_key, resource_id) ON DELETE CASCADE,
    ADD CONSTRAINT quality_findings_variant_fkey
        FOREIGN KEY (tenant_key, template_resource_id, variant_key)
        REFERENCES template_variants(tenant_key, template_resource_id, id) ON DELETE CASCADE;

ALTER TABLE quality_finding_ignores
    DROP COLUMN catalog_key,
    DROP COLUMN template_key,
    ADD CONSTRAINT quality_finding_ignores_template_fkey
        FOREIGN KEY (tenant_key, template_resource_id)
        REFERENCES document_templates(tenant_key, resource_id) ON DELETE CASCADE;

-- The index went with its columns; the editor panel and the report read through the identity now.
CREATE INDEX idx_quality_findings_template
    ON quality_findings(tenant_key, template_resource_id, variant_key);

COMMENT ON COLUMN quality_findings.template_resource_id IS
    'Identity of the template this finding is about. Replaces its address, so a relocation leaves the finding untouched.';
COMMENT ON COLUMN quality_finding_ignores.template_resource_id IS
    'Identity of the template this ignore hangs off, so the database can collect it when the template goes. NULL for a non-template scope. Not read: ignore_scope_urn is the scope.';
