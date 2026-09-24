-- backup-restore-compatibility: backward=false forward=false
-- reason: Moves a release's per-resource record from a JSONB column on catalog_releases into rows.
-- A backup taken either side records it in a place the other does not read.
-- SPDX-FileCopyrightText: Epistola Nederland B.V.
--
-- SPDX-License-Identifier: AGPL-3.0-only

-- A release is its resources, each at the revision that holds its content.
--
-- `catalog_releases.resource_fingerprints` (V20260923154857) recorded which resources a release
-- contained and at which content digest, and said in its own comment that it was the interim form
-- of this table. It could say what a release contained but not point at it: the content behind
-- those digests arrived with `resource_revisions` (V20260923162028), and this is what connects the
-- two, so a release can be read back rather than only compared against.
--
-- The column is dropped rather than left beside its replacement. It holds derived data, it has
-- never been part of a released version -- both migrations are unreleased work on the same
-- integration branch -- and two records of one fact is how they come to disagree.
CREATE TABLE release_entries (
    tenant_key      TENANT_KEY   NOT NULL,
    catalog_key     CATALOG_KEY  NOT NULL,
    version         VARCHAR(50)  NOT NULL,
    resource_type   VARCHAR(20)  NOT NULL,
    resource_key    TEXT         NOT NULL,
    resource_id     UUID         NOT NULL,
    revision_digest CHAR(64)     NOT NULL,
    fingerprint     CHAR(64)     NOT NULL,
    name            VARCHAR(255) NOT NULL,
    description     TEXT,
    CONSTRAINT pk_release_entries PRIMARY KEY (tenant_key, catalog_key, version, resource_type, resource_key),
    CONSTRAINT fk_release_entries_release FOREIGN KEY (tenant_key, catalog_key, version)
        REFERENCES catalog_releases (tenant_key, catalog_key, version) ON DELETE CASCADE,
    -- Deferred, not cascading. A revision must never be deleted while a release still names it,
    -- so this refuses -- but deleting a tenant removes both, and Postgres cascades in an order that
    -- reaches the revisions first. Checking at commit lets the whole cascade land while keeping the
    -- refusal for anything that would leave a release naming content that is gone.
    CONSTRAINT fk_release_entries_revision FOREIGN KEY (tenant_key, revision_digest)
        REFERENCES resource_revisions (tenant_key, digest) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT fk_release_entries_type FOREIGN KEY (resource_type)
        REFERENCES catalog_resource_types (resource_type)
);

COMMENT ON TABLE release_entries IS
    'What a release contains: one row per resource, naming the revision that holds its content. Immutable, like the release itself.';
COMMENT ON COLUMN release_entries.resource_key IS
    'The address the resource had inside this release. A later relocation changes the working copy, never a release.';
COMMENT ON COLUMN release_entries.resource_id IS
    'Identity at release time, so a moved resource can still be traced to the releases that carried it.';
COMMENT ON COLUMN release_entries.revision_digest IS
    'Internal storage identity of the content (resource_revisions). Not the fingerprint -- see the next column and ADR 0026 section 5.';
COMMENT ON COLUMN release_entries.fingerprint IS
    'The contract''s per-resource canonical digest over the wire form, the same family as catalog_releases.fingerprint and catalogs.installed_resource_fingerprints. What the working copy is compared against to say a resource changed.';
COMMENT ON COLUMN release_entries.name IS
    'Name and description as released. The manifest is canonicalised over these, so rebuilding the archive needs them, and a resource dropped since can still be named.';

-- "Which releases carried this resource", and the lookup a relocation's provenance needs.
CREATE INDEX idx_release_entries_resource ON release_entries (tenant_key, resource_id);

ALTER TABLE catalog_releases DROP COLUMN resource_fingerprints;
