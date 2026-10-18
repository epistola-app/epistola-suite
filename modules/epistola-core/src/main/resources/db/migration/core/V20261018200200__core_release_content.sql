-- Retain the content behind a release, not only its digest.
--
-- A release records that it happened and what it fingerprinted to, but not the
-- content behind them, so an earlier release cannot be reproduced, exported or rendered from -- it
-- is a fingerprint and a promise. Content that does not move when the working copy does is what
-- retained releases, self-contained artifacts and deploying a release to an environment all rest on.
--
-- Content-addressed and deduplicated: re-releasing a catalog whose one changed template is the only
-- edit writes one payload and reuses the rest. Scoped by tenant, so identical content is stored once
-- within a tenant and never shared across them.

-- Extend by inserting a row, not by widening a CHECK.
--
-- Deliberately not `catalog_resource_types`, which holds the catalog wire's own tokens so that a
-- registry address is the triple an export uses. A revision kind is a storage concept, and it
-- includes parts of a resource that the wire never names on their own.
CREATE TABLE resource_revision_kinds (
    kind VARCHAR(20) PRIMARY KEY
);

COMMENT ON TABLE resource_revision_kinds IS
    'What a stored revision is: a catalog wire resource type, or a part of one that is stored separately.';

INSERT INTO resource_revision_kinds (kind) VALUES
    ('codeList'), ('font'), ('attribute'), ('theme'), ('stencil'), ('image'), ('template'),
    -- A document model: the node and slot graph that says what a document looks like. Stored on
    -- its own so that editing one variant does not rewrite every other variant of the same template
    -- (ADR 0026 section 3a) -- a bundled template is 20-70 KB of JSON.
    --
    -- Named after the field it is lifted out of, `TemplateResource.templateModel`, so the reference
    -- left behind in the payload and the row it points at carry the same name.
    --
    -- Worth knowing what that name does not say. A model belongs to a template VERSION, which
    -- belongs to a variant, which belongs to a template: `template_versions.template_model` is a
    -- column in a variant-keyed table, and the wire hoists the default variant's model up to the
    -- template. And a row here has no owner at all -- two variants with the same model share one,
    -- as does one variant across releases.
    ('templateModel');

CREATE TABLE resource_revisions (
    tenant_key TENANT_KEY  NOT NULL,
    digest     CHAR(64)    NOT NULL,
    kind       VARCHAR(20) NOT NULL,
    payload    JSONB       NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_resource_revisions PRIMARY KEY (tenant_key, digest),
    CONSTRAINT fk_resource_revisions_tenant FOREIGN KEY (tenant_key)
        REFERENCES tenants (id) ON DELETE CASCADE,
    CONSTRAINT fk_resource_revisions_kind FOREIGN KEY (kind)
        REFERENCES resource_revision_kinds (kind)
);

COMMENT ON TABLE resource_revisions IS
    'Immutable content of one released resource, or one part of one, deduplicated by digest within a tenant. Written at release; never updated.';
COMMENT ON COLUMN resource_revisions.digest IS
    'Lowercase hex SHA-256 of the canonical payload. Internal storage identity -- NOT the catalog fingerprint, which the contract computes over the wire form and which appears on the wire (ADR 0026 section 5).';
COMMENT ON COLUMN resource_revisions.payload IS
    'The resource in its protocol form. A template payload carries a digest reference in place of each variant''s model, so the parent digest still covers the whole.';

-- Which binary files each revision needs.
--
-- A revision's payload describes an image or a font face but never carries its bytes: those live
-- once in `asset_content`, shared by everything that refers to them. This table is the link between
-- the two -- one row per binary a revision needs.
--
-- Two different SHA-256 hashes meet here, both 64 hex characters, which is exactly why they are
-- worth naming apart:
--
--   digest        identifies the REVISION -- the hash of its canonical JSON payload.
--   content_hash  identifies the FILE     -- the hash of the bytes, as `asset_content` keys them.
--
-- So a row reads: revision <digest> needs the file <content_hash>. They are never equal and neither
-- is derived from the other.
--
-- That link is also what stops the file being collected. `ContentReaper` deletes `asset_content`
-- rows that no live `assets` row points at, so releasing a catalog and then deleting the image from
-- the working copy would take bytes a release still needs. These rows are the second holder, and
-- the foreign key below makes that a fact of the schema rather than a rule someone has to remember.
--
-- `scope` is part of the key because it is part of `asset_content`'s: a sensitive asset's bytes are
-- stored per tenant and a shared one's under 'global', so a hash alone does not resolve to a file.
-- No media type: `asset_content.content_type` already holds it, and a second copy is a second thing
-- to keep in step.
CREATE TABLE revision_binaries (
    tenant_key   TENANT_KEY NOT NULL,
    digest       CHAR(64)   NOT NULL,
    scope        TEXT       NOT NULL,
    content_hash TEXT       NOT NULL,
    CONSTRAINT pk_revision_binaries PRIMARY KEY (tenant_key, digest, scope, content_hash),
    CONSTRAINT fk_revision_binaries_revision FOREIGN KEY (tenant_key, digest)
        REFERENCES resource_revisions (tenant_key, digest) ON DELETE CASCADE,
    CONSTRAINT fk_revision_binaries_content FOREIGN KEY (scope, content_hash)
        REFERENCES asset_content (scope, content_hash)
);

COMMENT ON TABLE revision_binaries IS
    'Links a revision to the binary files it needs: digest identifies the revision, content_hash the file in asset_content. Also the retention root -- bytes reachable from a revision are never collected, and the foreign key enforces it.';

-- Serves the sweep, which asks whether anything still holds a given blob.
CREATE INDEX idx_revision_binaries_content ON revision_binaries (scope, content_hash);

-- A release is its resources, each at the revision that holds its content.
--
-- `catalog_releases` records that a release happened and what it fingerprinted to, and
-- `resource_revisions` (earlier in this migration) holds the content. This is what connects the two, so a
-- release can be read back rather than only compared against -- which is what makes an export of
-- an earlier release, and a publication of one, something other than a rebuild from today's
-- working copy.
--
-- Each entry carries both digests, for the reason ADR 0026 section 5 gives: `revision_digest` says
-- where the content is stored, `fingerprint` is the contract's canonical digest over the wire
-- form, and neither is derivable from the other.
CREATE TABLE release_entries (
    tenant_key      TENANT_KEY   NOT NULL,
    version         VARCHAR(50)  NOT NULL,
    resource_type   VARCHAR(20)  NOT NULL,
    resource_key    TEXT         NOT NULL,
    resource_id     UUID         NOT NULL,
    revision_digest CHAR(64)     NOT NULL,
    fingerprint     VARCHAR(80)  NOT NULL,
    name            VARCHAR(255) NOT NULL,
    description     TEXT,
    catalog_id      UUID         NOT NULL,
    CONSTRAINT pk_release_entries PRIMARY KEY (tenant_key, catalog_id, version, resource_type, resource_key),
    CONSTRAINT fk_release_entries_release FOREIGN KEY (tenant_key, catalog_id, version)
        REFERENCES catalog_releases (tenant_key, catalog_id, version) ON DELETE CASCADE,
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

-- A release records its base: the released version its draft started from (contract ADR 0002),
-- which what it changed is measured against. Null when it had none (a catalog's first release, or
-- a release installed from elsewhere, whose publisher's base is not in the archive) and for every
-- release cut before this column: the draft's base is gone once it is released, so it cannot be
-- backfilled, and readers fall back to the release below it.
ALTER TABLE catalog_releases ADD COLUMN base_version VARCHAR(50);

COMMENT ON COLUMN catalog_releases.base_version IS
    'The released version this release''s draft started from; null when it had none or was released before it was recorded.';

-- Let the content sweep find an asset by the bytes it points at.
--
-- `ContentReaper` asks, for each candidate blob, whether any live `assets` row still points at it.
-- `assets` was indexed only by `(tenant_key, created_at)` and `(tenant_key, name)`, so that question
-- had no index to answer it. It matters most in the case the sweep actually runs: `created_at <
-- cutoff` narrows the candidates first, and a small candidate set is exactly when the planner picks
-- a nested loop -- turning each probe into a sequential scan of every asset in the installation.
--
-- Deliberately NOT prefixed with `tenant_key`, unlike every other index on this table. The sweep
-- has no tenant to filter by and cannot have one: a blob in the 'global' scope is held by ANY
-- tenant's asset, so the question is installation-wide. Put `tenant_key` in front and the leading
-- column is unconstrained, the index stops being usable, and the scan comes back. The two
-- tenant-scoped readers -- `ResolveAssetKeysByContentHash` and the backup's blob dump -- are served
-- by this one too, since a content hash is far more selective than a tenant.
--
-- Partial, because the join can only match a row that has a hash. `content_hash` is still nullable
-- pending the backfill in #742, and those rows can never satisfy the predicate.
CREATE INDEX idx_assets_content_hash ON assets (content_hash) WHERE content_hash IS NOT NULL;

COMMENT ON INDEX idx_assets_content_hash IS
    'Serves the content reaper''s reachability check: which assets still point at a given blob.';

-- Let the cheap drift signal see a deletion.
--
-- `CATALOG_PENDING_CHANGES` asks whether any resource changed after the last release:
-- `last_activity > GREATEST(released_at, imported_at)`, where `last_activity` is a MAX over the
-- resources' own `updated_at` / `published_at` / `created_at`. Deleting a resource moves none of
-- them -- the row simply goes, so the MAX can even move *backwards*. The result is that a catalog
-- whose only change since its last release was a deletion reports no unreleased changes, while the
-- release dialog (which diffs per-resource digests against `release_entries`) correctly reports a
-- resource being dropped. Two answers to one question, and the reader is told there is nothing to
-- review right before a release removes something.
--
-- `catalogs.content_updated_at` is already the first term of that MAX and is already bumped by
-- register, metadata edits and import. This makes deletion bump it too, which is all the signal
-- needs: it stays conservative in one direction only -- it may over-warn on an edit-then-revert,
-- and never under-warns.
--
-- A trigger rather than eight commands. The paths that delete catalog content are the seven delete
-- commands plus every cascade, and a command added later that forgets to bump would reintroduce
-- exactly the silence this fixes. The database is where "a row of catalog content went away" is
-- knowable without being remembered.
CREATE FUNCTION touch_catalog_content_on_delete() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    -- Matches nothing when the catalog itself is being deleted, because the parent row is gone
    -- before its children cascade. That is correct: there is no working copy left to describe.
    UPDATE catalogs
    SET content_updated_at = now()
    WHERE tenant_key = OLD.tenant_key AND catalog_id = OLD.catalog_id;
    RETURN OLD;
END;
$$;

COMMENT ON FUNCTION touch_catalog_content_on_delete() IS
    'AFTER DELETE trigger function for catalog-scoped resource tables: bumps catalogs.content_updated_at so the drift signal sees a deletion. Requires tenant_key and catalog_id on the row.';

CREATE TRIGGER trg_document_templates_catalog_touched
    AFTER DELETE ON document_templates
    FOR EACH ROW EXECUTE FUNCTION touch_catalog_content_on_delete();

CREATE TRIGGER trg_themes_catalog_touched
    AFTER DELETE ON themes
    FOR EACH ROW EXECUTE FUNCTION touch_catalog_content_on_delete();

CREATE TRIGGER trg_stencils_catalog_touched
    AFTER DELETE ON stencils
    FOR EACH ROW EXECUTE FUNCTION touch_catalog_content_on_delete();

CREATE TRIGGER trg_code_lists_catalog_touched
    AFTER DELETE ON code_lists
    FOR EACH ROW EXECUTE FUNCTION touch_catalog_content_on_delete();

CREATE TRIGGER trg_fonts_catalog_touched
    AFTER DELETE ON fonts
    FOR EACH ROW EXECUTE FUNCTION touch_catalog_content_on_delete();

CREATE TRIGGER trg_assets_catalog_touched
    AFTER DELETE ON assets
    FOR EACH ROW EXECUTE FUNCTION touch_catalog_content_on_delete();

CREATE TRIGGER trg_variant_attribute_definitions_catalog_touched
    AFTER DELETE ON variant_attribute_definitions
    FOR EACH ROW EXECUTE FUNCTION touch_catalog_content_on_delete();

-- A variant is content too: dropping one changes what the catalog would release, and the variant
-- row carries the catalog through its template rather than directly -- hence its own statement.
CREATE FUNCTION touch_catalog_content_on_variant_delete() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    UPDATE catalogs c
    SET content_updated_at = now()
    FROM document_templates t
    WHERE t.tenant_key = OLD.tenant_key AND t.resource_id = OLD.template_resource_id
      AND c.tenant_key = t.tenant_key AND c.catalog_id = t.catalog_id;
    RETURN OLD;
END;
$$;

COMMENT ON FUNCTION touch_catalog_content_on_variant_delete() IS
    'AFTER DELETE trigger function for template_variants, which reaches its catalog through the template it belongs to.';

CREATE TRIGGER trg_template_variants_catalog_touched
    AFTER DELETE ON template_variants
    FOR EACH ROW EXECUTE FUNCTION touch_catalog_content_on_variant_delete();

-- What each release was cut with: every declaration, and the release of the dependency it resolved
-- to with that release''s fingerprint. A release renders with exactly these releases. A release
-- imported from an archive of wire v7 or earlier never recorded one, so both stay null together.
CREATE TABLE catalog_release_dependencies (
    tenant_key          TENANT_KEY   NOT NULL,
    catalog_id          UUID         NOT NULL,
    version             VARCHAR(50)  NOT NULL,
    registry            TEXT         NOT NULL,
    namespace           TEXT         NOT NULL,
    dependency_key      VARCHAR(50)  NOT NULL,
    version_selector    VARCHAR(100) NOT NULL,
    position            INT          NOT NULL,
    release_version     VARCHAR(100),
    release_fingerprint VARCHAR(80),
    PRIMARY KEY (tenant_key, catalog_id, version, registry, namespace, dependency_key),
    CONSTRAINT fk_catalog_release_dependencies_release
        FOREIGN KEY (tenant_key, catalog_id, version) REFERENCES catalog_releases (tenant_key, catalog_id, version) ON DELETE CASCADE,
    CONSTRAINT chk_catalog_release_dependencies_pinned CHECK ((release_version IS NULL) = (release_fingerprint IS NULL))
);

COMMENT ON TABLE catalog_release_dependencies IS
    'The dependency declarations a release was cut with, each pinned to the release it resolved to (releaseVersion + releaseFingerprint).';
