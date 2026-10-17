-- SPDX-FileCopyrightText: Epistola Nederland B.V.
--
-- SPDX-License-Identifier: AGPL-3.0-only

-- Several drafts of a catalog each hold their own row of a template (contract ADR 0002, step 4b), so
-- a template's (tenant_key, resource_id) no longer names one document_templates row. A load-test run
-- is of the template, whichever draft holds it, so it references the template's identity in
-- catalog_resources instead, which the core migration that follows needs before it re-keys the
-- content tables. Deleting the identity still deletes its runs.
--
-- The variant-level reference cannot stay: a variant is a row of one version, and no table holds a
-- variant's identity across versions. A run keeps the variant_key it ran, and a trigger below deletes
-- it with the variant's last row, as the reference's cascade did.
ALTER TABLE load_test_runs
    DROP CONSTRAINT load_test_runs_variant_fkey,
    DROP CONSTRAINT load_test_runs_template_fkey,
    ADD CONSTRAINT load_test_runs_template_fkey
        FOREIGN KEY (tenant_key, template_resource_id)
        REFERENCES catalog_resources (tenant_key, resource_id) ON DELETE CASCADE;

-- A load-test run of a variant goes when the variant does, as the dropped reference cascaded: when the
-- last version holding that variant deletes it.
CREATE FUNCTION load_test_runs_follow_variant() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM template_variants
                   WHERE tenant_key = OLD.tenant_key AND template_resource_id = OLD.template_resource_id AND id = OLD.id) THEN
        DELETE FROM load_test_runs
        WHERE tenant_key = OLD.tenant_key AND template_resource_id = OLD.template_resource_id AND variant_key = OLD.id;
    END IF;
    RETURN NULL;
END;
$$;

CREATE TRIGGER trg_template_variants_load_test_runs AFTER DELETE ON template_variants
    FOR EACH ROW EXECUTE FUNCTION load_test_runs_follow_variant();
