-- The 2.0 upgrade, step 11 of 16 (docs/migrations.md, "The 2.0 chain"): the 1.x version history.
--
-- 1.x kept versions per template variant, data contract and stencil, and activated template versions
-- per environment. 2.0 keeps none of that: each working copy and what each last published are drafts
-- now (steps 7 to 9). What is kept of the rest is a summary, one record in app_metadata
-- (`upgrade-1x-history`): every version's number, status and dates, and what each environment had
-- activated, so documents and load-test runs that name a 1.x version number can still be traced.
-- Content of older versions is not kept; step 14 drops the tables.

INSERT INTO app_metadata (key, value)
SELECT 'upgrade-1x-history', jsonb_build_object(
    'description', 'The template, data contract and stencil versions of 1.x and what each environment had activated, as numbers and dates; their content is not kept.',
    'templateVersions', COALESCE((
        SELECT jsonb_agg(jsonb_build_object(
                   'tenant', tv.tenant_key, 'catalog', c.catalog_key, 'template', r.resource_key, 'variant', tv.variant_key,
                   'version', tv.id, 'status', tv.status, 'createdAt', tv.created_at, 'publishedAt', tv.published_at, 'archivedAt', tv.archived_at)
                   ORDER BY tv.tenant_key, c.catalog_key, r.resource_key, tv.variant_key, tv.id)
        FROM template_versions tv
        JOIN catalog_resources r ON r.tenant_key = tv.tenant_key AND r.resource_id = tv.template_resource_id
        JOIN catalogs c ON c.tenant_key = r.tenant_key AND c.catalog_id = r.catalog_id), '[]'::jsonb),
    'contractVersions', COALESCE((
        SELECT jsonb_agg(jsonb_build_object(
                   'tenant', cv.tenant_key, 'catalog', c.catalog_key, 'template', r.resource_key,
                   'version', cv.id, 'status', cv.status, 'createdAt', cv.created_at, 'publishedAt', cv.published_at)
                   ORDER BY cv.tenant_key, c.catalog_key, r.resource_key, cv.id)
        FROM contract_versions cv
        JOIN catalog_resources r ON r.tenant_key = cv.tenant_key AND r.resource_id = cv.template_resource_id
        JOIN catalogs c ON c.tenant_key = r.tenant_key AND c.catalog_id = r.catalog_id), '[]'::jsonb),
    'stencilVersions', COALESCE((
        SELECT jsonb_agg(jsonb_build_object(
                   'tenant', sv.tenant_key, 'catalog', c.catalog_key, 'stencil', r.resource_key,
                   'version', sv.id, 'status', sv.status, 'createdAt', sv.created_at, 'publishedAt', sv.published_at, 'archivedAt', sv.archived_at)
                   ORDER BY sv.tenant_key, c.catalog_key, r.resource_key, sv.id)
        FROM stencil_versions sv
        JOIN catalog_resources r ON r.tenant_key = sv.tenant_key AND r.resource_id = sv.stencil_resource_id
        JOIN catalogs c ON c.tenant_key = r.tenant_key AND c.catalog_id = r.catalog_id), '[]'::jsonb),
    'activations', COALESCE((
        SELECT jsonb_agg(jsonb_build_object(
                   'tenant', a.tenant_key, 'environment', a.environment_key, 'catalog', c.catalog_key, 'template', r.resource_key,
                   'variant', a.variant_key, 'version', a.version_key, 'activatedAt', a.activated_at)
                   ORDER BY a.tenant_key, a.environment_key, c.catalog_key, r.resource_key, a.variant_key)
        FROM environment_activations a
        JOIN catalog_resources r ON r.tenant_key = a.tenant_key AND r.resource_id = a.template_resource_id
        JOIN catalogs c ON c.tenant_key = r.tenant_key AND c.catalog_id = r.catalog_id), '[]'::jsonb))
WHERE EXISTS (SELECT 1 FROM template_versions) OR EXISTS (SELECT 1 FROM contract_versions)
   OR EXISTS (SELECT 1 FROM stencil_versions) OR EXISTS (SELECT 1 FROM environment_activations);
