-- The 2.0 upgrade, step 2 of 16 (docs/migrations.md, "The 2.0 chain"): catalog versions.
--
-- A catalog version is one concept with one id from the moment it exists (contract ADR 0002, ADR
-- 0036): a draft, which people edit, or a release, its row frozen. A catalog has at most one next
-- draft, the one everyday work goes into and a caller that names no version means. What a version
-- depends on, and the releases a draft or a release merged, are rows of their own.
--
-- The 1.x releases are not carried over: a 1.x release is a number, a fingerprint and notes, and kept
-- no content, so nothing could render, export or deploy it (ADR 0035). Step 4 opens a draft for every
-- catalog on the number of its last one.

-- A catalog version's id (contract `CatalogVersionId`): opaque, assigned when a version is started as
-- a draft and kept when it is released, so it addresses a release as its number does. Eight
-- characters from an alphabet without look-alikes (no 0/o, 1/l/i), which people can read and type.
CREATE FUNCTION epistola_version_key() RETURNS VARCHAR
    LANGUAGE sql
AS $$
SELECT string_agg(substr('abcdefghjkmnpqrstuvwxyz23456789', 1 + floor(random() * 31)::int, 1), '')
FROM generate_series(1, 8);
$$;

CREATE TABLE catalog_versions (
    tenant_key                 TENANT_KEY   NOT NULL,
    version_id                 UUID         NOT NULL DEFAULT epistola_uuidv7(),
    version_key                VARCHAR(36)  NOT NULL DEFAULT epistola_version_key(),
    catalog_id                 UUID         NOT NULL,
    state                      VARCHAR(16)  NOT NULL,
    version                    VARCHAR(128),
    name                       VARCHAR(100),
    base_version               VARCHAR(128),
    base_version_id            UUID,
    base_fingerprint           VARCHAR(80),
    base_fingerprints          JSONB,
    created_from               VARCHAR(128),
    created_at                 TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    default_theme_resource_id  UUID,
    presets                    JSONB        NOT NULL DEFAULT '[]'::jsonb,
    revision                   BIGINT       NOT NULL DEFAULT 1,
    -- The release record: set when a draft is released, frozen from then on.
    fingerprint                VARCHAR(80),
    content_hash               VARCHAR(80),
    notes                      TEXT,
    manifest_snapshot          JSONB,
    rendering_defaults_version INTEGER,
    released_at                TIMESTAMPTZ,
    released_by                UUID,
    released_by_installation   UUID,
    -- How the release came to be in this tenant (provenance, descriptive only).
    released_via               VARCHAR(16),
    released_from              TEXT,
    -- A release sorts as SemVer says, a patch release labelled with a namespace and a sequence
    -- number (1.3.1-gemeente-x.1, ADR 0034) above its core's predecessor and below the next core.
    version_major              INTEGER GENERATED ALWAYS AS ((substring(version, '^(\d+)\.\d+\.\d+(?:-[a-z][a-z0-9-]*\.[1-9][0-9]*)?$'))::integer) STORED,
    version_minor              INTEGER GENERATED ALWAYS AS ((substring(version, '^\d+\.(\d+)\.\d+(?:-[a-z][a-z0-9-]*\.[1-9][0-9]*)?$'))::integer) STORED,
    version_patch              INTEGER GENERATED ALWAYS AS ((substring(version, '^\d+\.\d+\.(\d+)(?:-[a-z][a-z0-9-]*\.[1-9][0-9]*)?$'))::integer) STORED,
    version_label              TEXT GENERATED ALWAYS AS (substring(version, '^\d+\.\d+\.\d+-([a-z][a-z0-9-]*)\.[1-9][0-9]*$')) STORED,
    version_label_number       INTEGER GENERATED ALWAYS AS ((substring(version, '^\d+\.\d+\.\d+-[a-z][a-z0-9-]*\.([1-9][0-9]*)$'))::integer) STORED,
    PRIMARY KEY (tenant_key, version_id),
    FOREIGN KEY (tenant_key, catalog_id) REFERENCES catalogs (tenant_key, catalog_id) ON DELETE CASCADE,
    CONSTRAINT uq_catalog_versions_catalog UNIQUE (tenant_key, version_id, catalog_id),
    CONSTRAINT uq_catalog_versions_key UNIQUE (tenant_key, catalog_id, version_key),
    CONSTRAINT uq_catalog_versions_number UNIQUE (tenant_key, catalog_id, version),
    CONSTRAINT chk_catalog_versions_state CHECK (state IN ('draft', 'released')),
    -- A released version has its number and record; a draft has none of them.
    CONSTRAINT chk_catalog_versions_released CHECK (
        (state = 'released') = (version IS NOT NULL AND fingerprint IS NOT NULL AND content_hash IS NOT NULL AND released_at IS NOT NULL)),
    CONSTRAINT chk_catalog_versions_draft_unnumbered CHECK (state = 'released' OR version IS NULL),
    -- The version this one was made from, by its row: lineage, not ownership, so it survives the
    -- base being deleted (base_version and base_fingerprint keep what it was).
    CONSTRAINT fk_catalog_versions_base FOREIGN KEY (tenant_key, base_version_id)
        REFERENCES catalog_versions (tenant_key, version_id) ON DELETE SET NULL (base_version_id),
    CONSTRAINT fk_catalog_versions_default_theme FOREIGN KEY (tenant_key, default_theme_resource_id)
        REFERENCES catalog_resources (tenant_key, resource_id)
);

CREATE INDEX idx_catalog_versions_release_order
    ON catalog_versions (tenant_key, catalog_id, version_major DESC, version_minor DESC, version_patch DESC,
                         (version_label IS NULL) DESC, version_label COLLATE "C" DESC, version_label_number DESC)
    WHERE state = 'released';
CREATE INDEX idx_catalog_versions_released_at ON catalog_versions (tenant_key, catalog_id, released_at DESC) WHERE state = 'released';
CREATE INDEX idx_catalog_versions_release_content_hash ON catalog_versions (tenant_key, catalog_id, content_hash) WHERE state = 'released';

COMMENT ON TABLE catalog_versions IS
    'Every version of a catalog (ADR 0036): its drafts, whose content is in release_resources, and its releases, each the row its draft was, frozen.';
COMMENT ON COLUMN catalog_versions.version_key IS
    'The version''s id (contract CatalogVersionId), given when it is started as a draft and kept when it is released.';
COMMENT ON COLUMN catalog_versions.state IS 'draft or released (ADR 0036): releasing a draft freezes its row.';
COMMENT ON COLUMN catalog_versions.version IS 'The release number; NULL for a draft, which is numbered when it is released.';
COMMENT ON COLUMN catalog_versions.name IS
    'What people call a draft that is long-term work (not the next version), e.g. "Redesign 2027". NULL for the next version, which is always "Next version".';
COMMENT ON COLUMN catalog_versions.base_version IS 'The release this version was made from, by number: what its changes are measured against.';
COMMENT ON COLUMN catalog_versions.base_version_id IS 'The version this one was made from, while it exists; base_version and base_fingerprint name it by number and hash.';
COMMENT ON COLUMN catalog_versions.base_fingerprints IS
    'The per-resource fingerprints of the draft as a received release put it in place; NULL compares it with its base release instead (ADR 0032).';
COMMENT ON COLUMN catalog_versions.created_from IS
    'The version_key of the draft this draft was copied from, unreleased changes included; NULL when it was started from a release, its base, or from nothing.';
COMMENT ON COLUMN catalog_versions.default_theme_resource_id IS
    'The theme a template that names none renders with (ADR 0007); NULL renders with the rendering defaults alone.';
COMMENT ON COLUMN catalog_versions.presets IS
    'The block style presets this version declares: [{key, name, description}] (ADR 0007). Its themes style them.';
COMMENT ON COLUMN catalog_versions.revision IS
    'Raised by one when the version''s name, default theme, presets or dependency declarations change (bump_catalog_version_revision); a write naming the revision it read is refused when it differs.';
COMMENT ON COLUMN catalog_versions.content_hash IS
    'A release''s content hash (its V6 fingerprint, ADR 0034): what identifies it, whatever its number or route.';
COMMENT ON COLUMN catalog_versions.rendering_defaults_version IS
    'The rendering defaults a release was cut against (RenderingDefaults.version), so an engine upgrade does not change what it renders.';
COMMENT ON COLUMN catalog_versions.released_at IS 'When the version became a release in this tenant: released here, or received.';
COMMENT ON COLUMN catalog_versions.released_by IS
    'users.id of the user who released it here or installed it: the system user for the system (a bundled catalog, an upgrade), NULL when not known (a 1.x release). No FK, as for the other audit columns.';
COMMENT ON COLUMN catalog_versions.released_via IS
    'How the release came to be in this tenant: released (here), upload, api, registry, exchange, source (a URL source), bundled or upgrade-1x. Descriptive only: nothing decides by it, a release is a release however it arrived.';
COMMENT ON COLUMN catalog_versions.released_from IS
    'Where it came from, in the terms of released_via: the source URL, the registry reference or the Exchange address; NULL when there is no such place (released here, an upload).';
COMMENT ON COLUMN catalog_versions.released_by_installation IS
    'The installation that cut the release (app_metadata installation id): this one, or the publisher''s for a received release.';
COMMENT ON COLUMN catalog_versions.version_label IS
    'The namespace a patch release is labelled with (1.3.1-gemeente-x.1: gemeente-x), null for a release without a label.';
COMMENT ON COLUMN catalog_versions.version_label_number IS
    'The sequence number of a patch release (1.3.1-gemeente-x.1: 1), null for a release without a label.';

-- "Next" is a property of the catalog: at most one draft.
ALTER TABLE catalogs ADD COLUMN next_version_id UUID;
ALTER TABLE catalogs ADD CONSTRAINT fk_catalogs_next_version FOREIGN KEY (tenant_key, next_version_id)
    REFERENCES catalog_versions (tenant_key, version_id) ON DELETE SET NULL (next_version_id);
COMMENT ON COLUMN catalogs.next_version_id IS
    'The catalog''s next draft (ADR 0036): the draft everyday work goes into, and the one a caller that names no version means.';

-- A publication publishes a release: its row. A 1.x publication published a 1.x release, which is
-- not carried over, so it goes with it.
DELETE FROM catalog_release_publications;
ALTER TABLE catalog_release_publications ADD CONSTRAINT fk_catalog_release_publications_version
    FOREIGN KEY (tenant_key, catalog_id, version) REFERENCES catalog_versions (tenant_key, catalog_id, version) ON DELETE CASCADE;

-- What a version depends on (ADR 0036): a draft's declarations and a release's pins. A dependency is
-- named by registry, namespace and key, as the contract names it (it may be a catalog this
-- installation does not have), under the alias the version's content names it by (contract ADR 0003),
-- pinned by number and hash, and, when the version depended on is here, by its row.
CREATE TABLE catalog_version_dependencies (
    tenant_key          TENANT_KEY   NOT NULL,
    catalog_id          UUID         NOT NULL,
    version_id          UUID         NOT NULL,
    registry            TEXT         NOT NULL,
    namespace           TEXT         NOT NULL,
    dependency_key      VARCHAR(50)  NOT NULL,
    alias               VARCHAR(50)  NOT NULL,
    position            INTEGER      NOT NULL,
    release_version     VARCHAR(128),
    release_fingerprint VARCHAR(80),
    target_version_id   UUID,
    PRIMARY KEY (tenant_key, version_id, registry, namespace, dependency_key),
    CONSTRAINT uq_catalog_version_dependencies_alias UNIQUE (tenant_key, version_id, alias),
    CONSTRAINT chk_catalog_version_dependencies_pinned CHECK ((release_version IS NULL) = (release_fingerprint IS NULL)),
    CONSTRAINT fk_catalog_version_dependencies_catalog FOREIGN KEY (tenant_key, catalog_id)
        REFERENCES catalogs (tenant_key, catalog_id) ON DELETE CASCADE,
    CONSTRAINT fk_catalog_version_dependencies_version FOREIGN KEY (tenant_key, version_id, catalog_id)
        REFERENCES catalog_versions (tenant_key, version_id, catalog_id) ON DELETE CASCADE,
    -- The version depended on is lineage of the declaration, not its owner.
    CONSTRAINT fk_catalog_version_dependencies_target FOREIGN KEY (tenant_key, target_version_id)
        REFERENCES catalog_versions (tenant_key, version_id) ON DELETE SET NULL (target_version_id)
);

-- What depends on a version: the guard on discarding a draft another version names.
CREATE INDEX idx_catalog_version_dependencies_target ON catalog_version_dependencies (tenant_key, target_version_id)
    WHERE target_version_id IS NOT NULL;

COMMENT ON TABLE catalog_version_dependencies IS
    'What each catalog version depends on (ADR 0036): a draft''s declarations and a release''s pins, by the version that declares them.';
COMMENT ON COLUMN catalog_version_dependencies.version_id IS 'The catalog version that declares the dependency.';
COMMENT ON COLUMN catalog_version_dependencies.alias IS
    'What the declaring version''s content names the dependency by (wire v8 catalogDependencies[].alias, contract ADR 0003). Unique per version; this installation declares every dependency under its key.';
COMMENT ON COLUMN catalog_version_dependencies.release_version IS
    'The release the declaration pins; null for a declaration the 1.x upgrade made, which pins none until the version is released.';
COMMENT ON COLUMN catalog_version_dependencies.target_version_id IS
    'The version depended on, when it is here: a draft a declaration names, or the release it pins.';

-- The releases merged into a draft (ADR 0032), one row per release: the draft is not behind them.
CREATE TABLE catalog_draft_merges (
    tenant_key        TENANT_KEY   NOT NULL,
    version_id        UUID         NOT NULL,
    release_version   VARCHAR(128) NOT NULL,
    merge_base        VARCHAR(128),
    resolutions       JSONB        NOT NULL DEFAULT '{}',
    held_fingerprints JSONB,
    merged_by         UUID REFERENCES users (id) ON DELETE SET NULL,
    merged_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    PRIMARY KEY (tenant_key, version_id, release_version),
    FOREIGN KEY (tenant_key, version_id) REFERENCES catalog_versions (tenant_key, version_id) ON DELETE CASCADE
);

CREATE INDEX idx_catalog_draft_merges_merged_by ON catalog_draft_merges (merged_by);

COMMENT ON TABLE catalog_draft_merges IS
    'The releases merged into a draft (ADR 0032), one row per release: the draft is not behind them.';
COMMENT ON COLUMN catalog_draft_merges.merge_base IS 'The release the merge compared against (the closest both descend from); NULL when none.';
COMMENT ON COLUMN catalog_draft_merges.resolutions IS 'How each conflict was resolved: {"type/key": "OURS" | "THEIRS"}.';
COMMENT ON COLUMN catalog_draft_merges.held_fingerprints IS
    'What the draft held of the release once merged, a fingerprint per entry (type/key): the base a later merge compares the draft with. NULL when not recorded.';
COMMENT ON COLUMN catalog_draft_merges.merged_by IS 'User who merged it (NULL if unknown, or the user was deleted)';

-- The releases merged into a release's draft before it was released (ADR 0032): its merge parents,
-- beside its base, so a fix merged before a release does not look missing after it.
CREATE TABLE catalog_release_merges (
    tenant_key         TENANT_KEY   NOT NULL,
    version_id         UUID         NOT NULL,
    merged_version     VARCHAR(128) NOT NULL,
    merged_fingerprint VARCHAR(80),
    PRIMARY KEY (tenant_key, version_id, merged_version),
    CONSTRAINT fk_catalog_release_merges_version FOREIGN KEY (tenant_key, version_id)
        REFERENCES catalog_versions (tenant_key, version_id) ON DELETE CASCADE
);

COMMENT ON TABLE catalog_release_merges IS
    'The releases merged into a release''s draft before it was released (ADR 0032): its merge parents, beside its base.';
COMMENT ON COLUMN catalog_release_merges.version_id IS 'The release whose merge parent this is: its version row (ADR 0036).';
COMMENT ON COLUMN catalog_release_merges.merged_version IS
    'A release of the same catalog merged into its draft. Not a foreign key: it may be deleted later, or not be installed here.';
COMMENT ON COLUMN catalog_release_merges.merged_fingerprint IS
    'The qualified catalog fingerprint of merged_version, as recorded or received; NULL when not known.';
