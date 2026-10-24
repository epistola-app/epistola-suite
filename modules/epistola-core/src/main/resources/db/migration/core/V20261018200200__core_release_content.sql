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
-- registry address is the triple an export uses. A content kind is a storage concept, and it
-- includes parts of a resource that the wire never names on their own.
CREATE TABLE resource_content_kinds (
    kind VARCHAR(20) PRIMARY KEY
);

COMMENT ON TABLE resource_content_kinds IS
    'What a stored content is: a catalog wire resource type, or a part of one that is stored separately.';

INSERT INTO resource_content_kinds (kind) VALUES
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

-- A resource's JSON by its content hash, once per tenant (ADR 0033: `resource_contents`).
CREATE TABLE resource_contents (
    tenant_key   TENANT_KEY  NOT NULL,
    content_hash CHAR(64)    NOT NULL,
    kind         VARCHAR(20) NOT NULL,
    payload      JSONB       NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    -- When the content was last stored, a first time or again: the collector's grace period counts
    -- from here, so content a save is about to point at again is never taken from under it.
    stored_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_resource_contents PRIMARY KEY (tenant_key, content_hash),
    CONSTRAINT fk_resource_contents_tenant FOREIGN KEY (tenant_key)
        REFERENCES tenants (id) ON DELETE CASCADE,
    CONSTRAINT fk_resource_contents_kind FOREIGN KEY (kind)
        REFERENCES resource_content_kinds (kind)
);

COMMENT ON TABLE resource_contents IS
    'Immutable content of one resource, or one part of one, deduplicated by content hash within a tenant. Never updated; collected once nothing points at it (ADR 0033).';
COMMENT ON COLUMN resource_contents.content_hash IS
    'Lowercase hex SHA-256 of the canonical payload. Internal storage identity -- NOT the catalog fingerprint, which the contract computes over the wire form and which appears on the wire (ADR 0026 section 5).';
COMMENT ON COLUMN resource_contents.payload IS
    'The resource in its protocol form. A template payload carries a content hash reference in place of each variant''s model, so the parent hash still covers the whole.';
COMMENT ON COLUMN resource_contents.stored_at IS
    'When the content was last stored, first or again. The collector takes only content stored longer ago than its grace period.';

-- Which binary files each content needs.
--
-- A content's payload describes an image or a font face but never carries its bytes: those live
-- once in `asset_content`, shared by everything that refers to them. This table is the link between
-- the two -- one row per binary a content needs.
--
-- Two different SHA-256 hashes meet here, both 64 hex characters, which is exactly why they are
-- named apart:
--
--   content_hash  identifies the CONTENT -- the hash of its canonical JSON payload.
--   binary_hash   identifies the FILE    -- the hash of the bytes, as `asset_content` keys them.
--
-- So a row reads: content <content_hash> needs the file <binary_hash>. They are never equal and
-- neither is derived from the other.
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
CREATE TABLE resource_binaries (
    tenant_key   TENANT_KEY NOT NULL,
    content_hash CHAR(64)   NOT NULL,
    scope        TEXT       NOT NULL,
    binary_hash  TEXT       NOT NULL,
    CONSTRAINT pk_resource_binaries PRIMARY KEY (tenant_key, content_hash, scope, binary_hash),
    CONSTRAINT fk_resource_binaries_content FOREIGN KEY (tenant_key, content_hash)
        REFERENCES resource_contents (tenant_key, content_hash) ON DELETE CASCADE,
    CONSTRAINT fk_resource_binaries_binary FOREIGN KEY (scope, binary_hash)
        REFERENCES asset_content (scope, content_hash)
);

COMMENT ON TABLE resource_binaries IS
    'Links a content to the binary files it needs: content_hash identifies the content, binary_hash the file in asset_content. Also the retention root -- bytes reachable from a content are never collected, and the foreign key enforces it.';

-- Serves the sweep, which asks whether anything still holds a given blob.
CREATE INDEX idx_resource_binaries_binary ON resource_binaries (scope, binary_hash);
-- Serves reading a resource's binaries when they are shown (ADR 0033).
CREATE INDEX idx_resource_binaries_tenant_binary ON resource_binaries (tenant_key, binary_hash);

-- A release is its resources, each at the content that holds it (ADR 0033: `release_resources`).
--
-- `catalog_releases` records that a release happened and what it fingerprinted to, and
-- `resource_contents` (earlier in this migration) holds the content. This is what connects the two,
-- so a release can be read back rather than only compared against -- which is what makes an export
-- of an earlier release, and a publication of one, something other than a rebuild from today's
-- working copy.
--
-- Each row carries both digests, for the reason ADR 0026 section 5 gives: `content_hash` says
-- where the content is stored, `fingerprint` is the contract's canonical digest over the wire
-- form, and neither is derivable from the other.
CREATE TABLE release_resources (
    tenant_key    TENANT_KEY   NOT NULL,
    version       VARCHAR(50)  NOT NULL,
    resource_type VARCHAR(20)  NOT NULL,
    resource_key  TEXT         NOT NULL,
    resource_id   UUID         NOT NULL,
    content_hash  CHAR(64)     NOT NULL,
    fingerprint   VARCHAR(80)  NOT NULL,
    name          VARCHAR(255) NOT NULL,
    description   TEXT,
    catalog_id    UUID         NOT NULL,
    CONSTRAINT pk_release_resources PRIMARY KEY (tenant_key, catalog_id, version, resource_type, resource_key),
    CONSTRAINT fk_release_resources_release FOREIGN KEY (tenant_key, catalog_id, version)
        REFERENCES catalog_releases (tenant_key, catalog_id, version) ON DELETE CASCADE,
    -- Deferred, not cascading. Content must never be deleted while a release still names it, so
    -- this refuses -- but deleting a tenant removes both, and Postgres cascades in an order that
    -- reaches the contents first. Checking at commit lets the whole cascade land while keeping the
    -- refusal for anything that would leave a release naming content that is gone. It is also what
    -- keeps the collector safe: content a row points at cannot be deleted (ADR 0033).
    CONSTRAINT fk_release_resources_content FOREIGN KEY (tenant_key, content_hash)
        REFERENCES resource_contents (tenant_key, content_hash) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT fk_release_resources_type FOREIGN KEY (resource_type)
        REFERENCES catalog_resource_types (resource_type)
);

COMMENT ON TABLE release_resources IS
    'What a release contains: one row per resource, naming the content that holds it. Immutable, like the release itself.';
COMMENT ON COLUMN release_resources.resource_key IS
    'The address the resource had inside this release. A later relocation changes the working copy, never a release.';
COMMENT ON COLUMN release_resources.resource_id IS
    'Identity at release time, so a moved resource can still be traced to the releases that carried it.';
COMMENT ON COLUMN release_resources.content_hash IS
    'Internal storage identity of the content (resource_contents). Not the fingerprint -- see the next column and ADR 0026 section 5.';
COMMENT ON COLUMN release_resources.fingerprint IS
    'The contract''s per-resource canonical digest over the wire form, the same family as catalog_releases.fingerprint and catalogs.installed_resource_fingerprints. What the working copy is compared against to say a resource changed.';
COMMENT ON COLUMN release_resources.name IS
    'Name and description as released. The manifest is canonicalised over these, so rebuilding the archive needs them, and a resource dropped since can still be named.';

-- "Which releases carried this resource", and the lookup a relocation's provenance needs.
CREATE INDEX idx_release_resources_resource ON release_resources (tenant_key, resource_id);
-- What points at a content: the collector's question, and what the foreign key checks (ADR 0033).
CREATE INDEX idx_release_resources_content ON release_resources (tenant_key, content_hash);

-- A release records its base: the released version its draft started from (contract ADR 0002),
-- which what it changed is measured against. Null when it had none (a catalog's first release, or
-- a release installed from elsewhere, whose publisher's base is not in the archive) and for every
-- release cut before this column: the draft's base is gone once it is released, so it cannot be
-- backfilled, and readers fall back to the release below it.
ALTER TABLE catalog_releases ADD COLUMN base_version VARCHAR(50);

COMMENT ON COLUMN catalog_releases.base_version IS
    'The released version this release''s draft started from; null when it had none or was released before it was recorded.';

-- A catalog version's id (contract `CatalogVersionId`): opaque, assigned when a version is started as
-- a draft and kept when it is released, so it addresses a release as its number does. Eight
-- characters from an alphabet without look-alikes (no 0/o, 1/l/i), which people can read and type;
-- never a dot, so it cannot be mistaken for a release number. 31^8 is about 8.5e11, and an id only
-- has to be unique within its catalog, which the unique constraints check.
CREATE FUNCTION epistola_version_key() RETURNS VARCHAR(36)
    LANGUAGE sql
    VOLATILE
AS $$
SELECT string_agg(substr('abcdefghjkmnpqrstuvwxyz23456789', 1 + floor(random() * 31)::int, 1), '')
FROM generate_series(1, 8);
$$;

-- Every release gets an id: a release cut from a draft takes the draft's (set when it is released);
-- one installed, imported or recorded before this column gets a new one.
ALTER TABLE catalog_releases ADD COLUMN version_key VARCHAR(36) NOT NULL DEFAULT epistola_version_key();
ALTER TABLE catalog_releases ADD CONSTRAINT uq_catalog_releases_version_key UNIQUE (tenant_key, catalog_id, version_key);

COMMENT ON COLUMN catalog_releases.version_key IS
    'The release''s id (contract CatalogVersionId): the id of the draft it was released from, or a new one for a release installed from elsewhere. Addresses the release as its number does.';

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
-- release dialog (which diffs per-resource digests against `release_resources`) correctly reports a
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

-- What each release was cut with: every declaration, and the release of the dependency it pinned,
-- with that release''s fingerprint. A release renders with exactly these releases. A release
-- imported from an archive of wire v7 or earlier never recorded one, so both stay null together.
CREATE TABLE catalog_release_dependencies (
    tenant_key          TENANT_KEY   NOT NULL,
    catalog_id          UUID         NOT NULL,
    version             VARCHAR(50)  NOT NULL,
    registry            TEXT         NOT NULL,
    namespace           TEXT         NOT NULL,
    dependency_key      VARCHAR(50)  NOT NULL,
    position            INT          NOT NULL,
    release_version     VARCHAR(100),
    release_fingerprint VARCHAR(80),
    PRIMARY KEY (tenant_key, catalog_id, version, registry, namespace, dependency_key),
    CONSTRAINT fk_catalog_release_dependencies_release
        FOREIGN KEY (tenant_key, catalog_id, version) REFERENCES catalog_releases (tenant_key, catalog_id, version) ON DELETE CASCADE,
    CONSTRAINT chk_catalog_release_dependencies_pinned CHECK ((release_version IS NULL) = (release_fingerprint IS NULL))
);

COMMENT ON TABLE catalog_release_dependencies IS
    'The dependency declarations a release was cut with, each with the release it pinned (releaseVersion + releaseFingerprint).';
