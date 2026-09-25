-- backup-restore-compatibility: backward=true forward=false
-- reason: Backward is safe, and is the case that matters -- restoring a backup taken before an
-- upgrade. Nothing this migration adds has been part of a released version, so no backup in
-- anyone's hands carries any of it. An older backup restores with no release entries and
-- `content_retained` false on every row, which is the same state as a release cut before this
-- migration: the content was never retained, the release says so, and an export of it refuses
-- rather than substituting the working copy. `validateColumns` still runs as the backstop -- the
-- flag relaxes the stamp, never a structural check. Forward is not safe: a newer backup carries
-- tables a schema without this migration cannot classify.
-- SPDX-FileCopyrightText: Epistola Nederland B.V.
--
-- SPDX-License-Identifier: AGPL-3.0-only

-- A release is its resources, each at the revision that holds its content.
--
-- `catalog_releases` records that a release happened and what it fingerprinted to, and
-- `resource_revisions` (V20260923162028) holds the content. This is what connects the two, so a
-- release can be read back rather than only compared against -- which is what makes an export of
-- an earlier release, and a publication of one, something other than a rebuild from today's
-- working copy.
--
-- Each entry carries both digests, for the reason ADR 0026 section 5 gives: `revision_digest` says
-- where the content is stored, `fingerprint` is the contract's canonical digest over the wire
-- form, and neither is derivable from the other.
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

-- Say whether a release kept its content, instead of inferring it from having no entries.
--
-- Those are two different things wearing one answer: a catalog with no resources can be released,
-- and its release legitimately contains nothing. Asked whether such a release kept its content, the
-- inference says no -- so it cannot be exported as released, and publishing it to Exchange is
-- refused with a message telling the author to release changes they have not made.
--
-- FALSE for every existing row, which is correct: a release cut before this migration retained
-- nothing and cannot be rebuilt.
ALTER TABLE catalog_releases
    ADD COLUMN content_retained BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN catalog_releases.content_retained IS
    'Whether this release stored the content it contained, and so can be rebuilt and exported as released. FALSE for releases cut before this migration, which retained nothing. Not the same as having no release_entries: a catalog with no resources retains an empty release.';
