-- A document, a generation request and a collected result name the catalog version they were
-- rendered from (contract `versionId`), a release as well as a draft: every catalog version has an
-- id, and a release keeps the one it had as a draft. Before, only a draft's was stored (draft_id),
-- so a document rendered from a release named none. Set when a request is accepted, and copied from
-- it to its document and its result.
ALTER TABLE document_generation_requests ADD COLUMN catalog_version_id TEXT;
ALTER TABLE documents ADD COLUMN catalog_version_id TEXT;
ALTER TABLE generation_results ADD COLUMN catalog_version_id TEXT;

COMMENT ON COLUMN document_generation_requests.catalog_version_id IS 'Id (version_key) of the catalog version rendered: the draft''s (draft_id), or the release''s; null only for requests from before 2.0.';
COMMENT ON COLUMN documents.catalog_version_id IS 'Id (version_key) of the catalog version rendered, copied from its request; null only for documents from before 2.0.';
COMMENT ON COLUMN generation_results.catalog_version_id IS 'Id (version_key) of the catalog version rendered, copied from its request.';

-- What is already there: a draft's from draft_id, a release's from the release it names.
UPDATE document_generation_requests q
SET catalog_version_id = COALESCE(q.draft_id, (
    SELECT r.version_key FROM catalog_releases r
    JOIN catalogs c ON c.tenant_key = r.tenant_key AND c.catalog_id = r.catalog_id
    WHERE c.tenant_key = q.tenant_key AND c.catalog_key = q.catalog_key AND r.version = q.release_version))
WHERE q.draft_id IS NOT NULL OR q.release_version IS NOT NULL;

UPDATE documents d
SET catalog_version_id = COALESCE(d.draft_id, (
    SELECT r.version_key FROM catalog_releases r
    JOIN catalogs c ON c.tenant_key = r.tenant_key AND c.catalog_id = r.catalog_id
    WHERE c.tenant_key = d.tenant_key AND c.catalog_key = d.catalog_key AND r.version = d.release_version))
WHERE d.draft_id IS NOT NULL OR d.release_version IS NOT NULL;

UPDATE generation_results g
SET catalog_version_id = COALESCE(g.draft_id, (
    SELECT r.version_key FROM catalog_releases r
    JOIN catalogs c ON c.tenant_key = r.tenant_key AND c.catalog_id = r.catalog_id
    WHERE c.tenant_key = g.tenant_key AND c.catalog_key = g.catalog_key AND r.version = g.release_version))
WHERE g.draft_id IS NOT NULL OR g.release_version IS NOT NULL;
