-- ADR 0033, step 7b: templates are stored as JSON (core V20261025170800 drops template_variants and
-- with it the trigger that let a load_test_runs row follow its variant). A variant is now an entry of its
-- template's content, so a row follows it through release_resources: when a template's row stops
-- holding a variant (its content changes, or the row goes) and no other row of that template, draft
-- or release, holds it any more, its rows go, as the dropped reference cascaded.
CREATE OR REPLACE FUNCTION load_test_runs_follow_variant() RETURNS trigger
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
