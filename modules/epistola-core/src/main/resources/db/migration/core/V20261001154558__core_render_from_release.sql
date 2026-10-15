-- SPDX-FileCopyrightText: Epistola Nederland B.V.
--
-- SPDX-License-Identifier: AGPL-3.0-only

-- Render from a release, and record what a release renders with.
--
-- Generation stops reading template versions and reads a catalog release instead (the 2.0 release
-- model, docs/catalog-release-model-v2.md). A release already holds its own resources' content
-- (resource_revisions, release_entries). What it did not record is which rendering defaults it was
-- cut against, so an engine upgrade does not silently change what an existing release renders.
-- Template versions recorded this per version; a release records it once.

ALTER TABLE catalog_releases
    ADD COLUMN rendering_defaults_version INT;

COMMENT ON COLUMN catalog_releases.rendering_defaults_version IS
    'The rendering defaults the release was cut against (RenderingDefaults.version). NULL for releases cut before this column, which render with the current defaults.';

-- Generation history names the release it rendered.
--
-- A request is now accepted against a release: the one it names, or the latest of its catalog.
-- version_key stays, for history recorded before 2.0 and as the reading of requests still queued
-- from then.
ALTER TABLE document_generation_requests
    ADD COLUMN release_version VARCHAR(50);

COMMENT ON COLUMN document_generation_requests.release_version IS
    'The catalog release this request renders, bound when it was accepted. NULL for requests accepted before 2.0.';

ALTER TABLE document_generation_requests
    DROP CONSTRAINT chk_requests_version_or_environment,
    ADD CONSTRAINT chk_requests_render_source CHECK (
        version_key IS NOT NULL OR environment_key IS NOT NULL OR release_version IS NOT NULL
    );

ALTER TABLE documents
    ADD COLUMN release_version VARCHAR(50);

ALTER TABLE documents
    ALTER COLUMN version_key DROP NOT NULL;

ALTER TABLE documents
    ADD CONSTRAINT chk_documents_render_source CHECK (version_key IS NOT NULL OR release_version IS NOT NULL);

COMMENT ON COLUMN documents.release_version IS
    'The catalog release this document was rendered from. NULL for documents generated before 2.0, which name a version_key instead.';
COMMENT ON COLUMN documents.version_key IS
    'The template version this document was rendered from, for documents generated before 2.0. NULL from 2.0 on, where release_version says what was rendered.';
