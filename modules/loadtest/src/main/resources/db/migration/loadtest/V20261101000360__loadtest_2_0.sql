-- SPDX-FileCopyrightText: Epistola Nederland B.V.
--
-- SPDX-License-Identifier: AGPL-3.0-only

-- The 2.0 upgrade of load tests (docs/migrations.md, "The 2.0 chain"). It runs after core step 3,
-- which creates JSON storage, and before core step 14, which drops the 1.x template tables this module
-- referenced.

-- 1. A load test renders a catalog version: the one its environment serves, or the catalog's latest
-- when it names no environment, so a new run carries neither a template version nor, necessarily, an
-- environment. A 1.x run keeps what it recorded; only one naming both stays invalid.
ALTER TABLE load_test_runs DROP CONSTRAINT chk_load_test_version_or_environment;
ALTER TABLE load_test_runs ADD CONSTRAINT chk_load_test_version_or_environment CHECK (
    version_key IS NULL OR environment_key IS NULL
);

-- 2. A run is of a template, whichever version holds it: it references the template's identity in
-- catalog_resources; deleting the identity still deletes its runs. A 1.x run keeps the number of the
-- template version it rendered, without a reference to a version that is gone (core step 14), and the
-- variant_key it ran, which a trigger (3) follows.
ALTER TABLE load_test_runs
    DROP CONSTRAINT load_test_runs_version_fkey,
    DROP CONSTRAINT load_test_runs_variant_fkey,
    DROP CONSTRAINT load_test_runs_template_fkey,
    ADD CONSTRAINT load_test_runs_template_fkey
        FOREIGN KEY (tenant_key, template_resource_id)
        REFERENCES catalog_resources (tenant_key, resource_id) ON DELETE CASCADE;

-- 3. A run of a variant goes when the variant does: when a template's row stops holding it and no
-- other row of that template, draft or release, holds it any more.
CREATE FUNCTION load_test_runs_follow_variant() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    DELETE FROM load_test_runs x
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

CREATE TRIGGER trg_release_resources_load_test_runs_follow_variant
    AFTER UPDATE OF content_hash OR DELETE ON release_resources
    FOR EACH ROW WHEN (OLD.resource_type = 'template')
    EXECUTE FUNCTION load_test_runs_follow_variant();

-- 4. A run follows its template identity (2): its deletion finds the runs by an index.
CREATE INDEX idx_load_test_runs_template ON load_test_runs (tenant_key, template_resource_id);
