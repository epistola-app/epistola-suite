-- Backfill missing contract versions for templates that were imported without a data model or data examples.
-- Every template must have at least one contract version.
INSERT INTO contract_versions (id, tenant_key, catalog_key, template_key, data_examples, status, published_at, created_at)
SELECT 1, dt.tenant_key, dt.catalog_key, dt.id, '[]'::jsonb, 'published', NOW(), NOW()
FROM document_templates dt
WHERE NOT EXISTS (
    SELECT 1 FROM contract_versions cv
    WHERE cv.tenant_key = dt.tenant_key
      AND cv.catalog_key = dt.catalog_key
      AND cv.template_key = dt.id
);
