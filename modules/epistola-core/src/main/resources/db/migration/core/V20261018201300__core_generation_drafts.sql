-- What a generation request, a document and a collected result rendered: a release, or the draft an
-- environment follows (an override), live. Requests from before 2.0 named a template version or an
-- environment instead; every request names one of these, and never both a release and a draft.

-- No foreign key: a document outlives the draft it came from. draft_id is the draft's public id
-- (contract CatalogVersionId), which its release keeps, so it still names the version after the draft
-- is released. The label is the draft's name when it was rendered ("Next version" for the next
-- version), so the document still says where it came from.
ALTER TABLE document_generation_requests
    ADD COLUMN draft_id VARCHAR(36),
    ADD COLUMN draft_label VARCHAR(100);

ALTER TABLE document_generation_requests
    ADD CONSTRAINT chk_requests_render_source CHECK (
        version_key IS NOT NULL OR environment_key IS NOT NULL OR release_version IS NOT NULL OR draft_id IS NOT NULL
    ),
    ADD CONSTRAINT chk_requests_release_or_draft CHECK (release_version IS NULL OR draft_id IS NULL);

COMMENT ON COLUMN document_generation_requests.draft_id IS
    'The draft this request renders, live, bound when it was accepted through an environment override. NULL when it renders a release.';
COMMENT ON COLUMN document_generation_requests.draft_label IS 'The draft''s name when the request was accepted';

ALTER TABLE documents
    ADD COLUMN draft_id VARCHAR(36),
    ADD COLUMN draft_label VARCHAR(100);

ALTER TABLE documents
    ADD CONSTRAINT chk_documents_render_source CHECK (version_key IS NOT NULL OR release_version IS NOT NULL OR draft_id IS NOT NULL);

COMMENT ON COLUMN documents.draft_id IS 'The draft this document was rendered from, live, through an environment override. NULL when rendered from a release.';
COMMENT ON COLUMN documents.draft_label IS 'The draft''s name when the document was rendered';

-- The collect feed says the same of each result.
ALTER TABLE generation_results
    ADD COLUMN draft_id VARCHAR(36),
    ADD COLUMN draft_label VARCHAR(100);

COMMENT ON COLUMN generation_results.draft_id IS 'The draft rendered, live, through an environment that follows it. NULL when rendered from a release.';
COMMENT ON COLUMN generation_results.draft_label IS 'The draft''s name when it was rendered';

-- How often a generation request was taken back from a node that stopped while rendering it (crashed,
-- or stopped responding). A render that brings its node down would otherwise be handed to the next
-- node, and the next, forever; after a few recoveries it is failed with the reason instead.
ALTER TABLE document_generation_requests ADD COLUMN recoveries INT NOT NULL DEFAULT 0;

COMMENT ON COLUMN document_generation_requests.recoveries IS
    'Times the request was taken back from a node that stopped while rendering it (stale-job recovery); at the limit it fails instead of rendering again';
