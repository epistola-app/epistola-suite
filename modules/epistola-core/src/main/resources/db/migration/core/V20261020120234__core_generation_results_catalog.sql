-- A collected result names the catalog of its template in full (contract `GenerationResult`:
-- registry, namespace and catalogKey are required), as documents and generation requests already
-- store it. Taken from the request when the result is emitted.
ALTER TABLE generation_results ADD COLUMN registry TEXT;
ALTER TABLE generation_results ADD COLUMN namespace TEXT;
ALTER TABLE generation_results ADD COLUMN catalog_key TEXT;

COMMENT ON COLUMN generation_results.registry IS 'Registry of the catalog the result''s template was rendered from, copied from its request.';
COMMENT ON COLUMN generation_results.namespace IS 'Namespace of that catalog, copied from its request.';
COMMENT ON COLUMN generation_results.catalog_key IS 'Key of that catalog, copied from its request.';

-- Results already emitted: from their request while it is kept, else from their document.
UPDATE generation_results r
SET registry = q.registry, namespace = q.namespace, catalog_key = q.catalog_key
FROM document_generation_requests q
WHERE q.id = r.request_id AND r.catalog_key IS NULL;

UPDATE generation_results r
SET registry = d.registry, namespace = d.namespace, catalog_key = d.catalog_key
FROM documents d
WHERE d.id = r.document_id AND d.tenant_key = r.tenant_key AND r.catalog_key IS NULL;
