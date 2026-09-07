-- backup-restore-compatibility: backward=false forward=false
-- reason: Replaces the template address on load_test_runs with the template's identity. A backup
-- taken either side carries a different column set for the table.

-- Load-test runs name the template, not where it lives
--
-- V20260905090400 releases every foreign key into the template hierarchy and re-keys the template
-- on its identity. This rebuilds the three this table declared, on that identity. ON DELETE is
-- preserved exactly: a run dies with the template, variant or version it exercised.
--
-- The address columns go with them: a run is a record of load against a template, and reading
-- where that template lives is the template's own row to answer.

-- 1. Add ------------------------------------------------------------------------------------------
ALTER TABLE load_test_runs ADD COLUMN template_resource_id UUID;

-- 2. Backfill, asserting every run found its template -------------------------------------------------
UPDATE load_test_runs runs
SET template_resource_id = templates.resource_id
FROM document_templates templates
WHERE templates.tenant_key = runs.tenant_key
  AND templates.catalog_key = runs.catalog_key
  AND templates.id = runs.template_key;

DO $$
DECLARE unmatched BIGINT;
BEGIN
    SELECT count(*) INTO unmatched FROM load_test_runs WHERE template_resource_id IS NULL;
    IF unmatched > 0 THEN
        RAISE EXCEPTION '% load test runs reference a template that does not exist', unmatched;
    END IF;
END $$;

ALTER TABLE load_test_runs ALTER COLUMN template_resource_id SET NOT NULL;

-- 3. Reference by identity, and drop the address copies -----------------------------------------------
ALTER TABLE load_test_runs
    DROP COLUMN catalog_key,
    DROP COLUMN template_key,
    ADD CONSTRAINT load_test_runs_template_fkey
        FOREIGN KEY (tenant_key, template_resource_id)
        REFERENCES document_templates(tenant_key, resource_id) ON DELETE CASCADE,
    ADD CONSTRAINT load_test_runs_variant_fkey
        FOREIGN KEY (tenant_key, template_resource_id, variant_key)
        REFERENCES template_variants(tenant_key, template_resource_id, id) ON DELETE CASCADE,
    ADD CONSTRAINT load_test_runs_version_fkey
        FOREIGN KEY (tenant_key, template_resource_id, variant_key, version_key)
        REFERENCES template_versions(tenant_key, template_resource_id, variant_key, id) ON DELETE CASCADE;

COMMENT ON COLUMN load_test_runs.template_resource_id IS
    'Identity of the template this run exercised. Replaces its address, so a relocation leaves the run untouched.';
