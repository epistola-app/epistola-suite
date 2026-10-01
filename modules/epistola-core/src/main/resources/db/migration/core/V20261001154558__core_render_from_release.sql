-- backup-restore-compatibility: backward=true forward=false
-- reason: Adds a table and nullable columns. A backup taken before this migration restores with no
-- release dependencies, no rendering-defaults version on its releases and no release on its
-- generation history, which is the state every release and document cut before it is in. Forward is
-- not safe: a newer backup carries a table an older schema cannot classify.
-- SPDX-FileCopyrightText: Epistola Nederland B.V.
--
-- SPDX-License-Identifier: AGPL-3.0-only

-- Render from a release, and record what a release renders with.
--
-- Generation stops reading template versions and reads a catalog release instead (the 2.0 release
-- model, docs/catalog-release-model-v2.md). A release already holds its own resources' content
-- (resource_revisions, release_entries). Two things it did not record, both needed to render it the
-- same way every time:
--
--   1. Which release of ANOTHER catalog it uses. A template may use a theme, font or image from a
--      different catalog. Releasing records, per catalog it references, the release that was latest
--      at that moment, and rendering reads that one -- never the other catalog's working copy and
--      never whatever is latest now. A lockfile without a declaration.
--   2. Which rendering defaults it was cut against, so an engine upgrade does not silently change
--      what an existing release renders. Template versions recorded this per version; a release
--      records it once.

CREATE TABLE release_dependencies (
    tenant_key             TENANT_KEY  NOT NULL,
    catalog_key            CATALOG_KEY NOT NULL,
    version                VARCHAR(50) NOT NULL,
    dependency_catalog_key CATALOG_KEY NOT NULL,
    dependency_version     VARCHAR(50) NOT NULL,
    -- TRUE when the release's own resources reference the catalog; FALSE when it is reached only
    -- through another dependency. The record holds the whole closure, flattened, so rendering never
    -- has to work out a catalog's release; the flag keeps the tree drawable.
    direct                 BOOLEAN     NOT NULL DEFAULT TRUE,
    CONSTRAINT pk_release_dependencies PRIMARY KEY (tenant_key, catalog_key, version, dependency_catalog_key),
    CONSTRAINT fk_release_dependencies_release FOREIGN KEY (tenant_key, catalog_key, version)
        REFERENCES catalog_releases (tenant_key, catalog_key, version) ON DELETE CASCADE,
    -- Refuses deleting a release another release renders with. Deferred, for the same reason as
    -- release_entries' revision key: deleting a tenant removes both sides, and Postgres may reach the
    -- dependency's release first. Checking at commit keeps the refusal without breaking that cascade.
    CONSTRAINT fk_release_dependencies_dependency FOREIGN KEY (tenant_key, dependency_catalog_key, dependency_version)
        REFERENCES catalog_releases (tenant_key, catalog_key, version) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT chk_release_dependencies_not_self CHECK (dependency_catalog_key <> catalog_key)
);

COMMENT ON TABLE release_dependencies IS
    'For each release, the release of every other catalog it renders with, directly or through another dependency: one release per catalog, the highest any dependency asked for. Immutable, like the release.';

-- "Which releases render with this one" -- the guard on deleting or forgetting a release.
CREATE INDEX idx_release_dependencies_dependency
    ON release_dependencies (tenant_key, dependency_catalog_key, dependency_version);

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
