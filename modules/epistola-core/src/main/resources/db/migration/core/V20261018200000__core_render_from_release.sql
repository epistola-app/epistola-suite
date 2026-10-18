-- 2.0 generates documents from catalog releases (contract 2.0), never from template versions.
--
-- A release keeps the content it was cut with (resource_revisions, release_entries: see the release
-- content migration) and says so, and it records the rendering defaults it was cut against, so an
-- engine upgrade does not silently change what an existing release renders.

ALTER TABLE catalog_releases
    ADD COLUMN content_retained BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN catalog_releases.content_retained IS
    'Whether this release stored the content it contained, and so can be rebuilt and exported as released. FALSE for releases cut before this migration, which retained nothing. Not the same as having no release_entries: a catalog with no resources retains an empty release.';

ALTER TABLE catalog_releases
    ADD COLUMN rendering_defaults_version INT;

COMMENT ON COLUMN catalog_releases.rendering_defaults_version IS
    'The rendering defaults the release was cut against (RenderingDefaults.version). NULL for releases cut before this column, which render with the current defaults.';

-- Generation history names the release it rendered.
--
-- A request is accepted against a release: the one it names, or the one its environment uses.
-- version_key stays, for history recorded before 2.0. Which of the two a request or document names is
-- checked once its last form exists (the generation migration at the end of 2.0).
ALTER TABLE document_generation_requests
    ADD COLUMN release_version VARCHAR(50);

COMMENT ON COLUMN document_generation_requests.release_version IS
    'The catalog release this request renders, bound when it was accepted. NULL for requests accepted before 2.0.';

ALTER TABLE document_generation_requests
    DROP CONSTRAINT chk_requests_version_or_environment;

ALTER TABLE documents
    ADD COLUMN release_version VARCHAR(50);

ALTER TABLE documents
    ALTER COLUMN version_key DROP NOT NULL;

COMMENT ON COLUMN documents.release_version IS
    'The catalog release this document was rendered from. NULL for documents generated before 2.0, which name a version_key instead.';
COMMENT ON COLUMN documents.version_key IS
    'The template version this document was rendered from, for documents generated before 2.0. NULL from 2.0 on, where release_version says what was rendered.';

-- A collected generation result names the catalog release it rendered.
--
-- From contract 2.0 a request renders a catalog release, never a template version, and the collect
-- feed reports `releaseVersion` in place of `versionId`. version_id stays for results emitted
-- before the change; release_version is null for those.
ALTER TABLE generation_results ADD COLUMN release_version VARCHAR(50);

COMMENT ON COLUMN generation_results.release_version IS
    'The catalog release the request rendered; null for results of requests accepted before 2.0.';
