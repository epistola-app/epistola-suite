-- The 2.0 upgrade, step 3 of 16 (docs/migrations.md, "The 2.0 chain"): JSON storage (ADR 0033).
--
-- Every catalog resource is stored as JSON, one shape for every type: its content, by content hash,
-- in `resource_contents`, and per version a row in `release_resources` naming it. A draft and a
-- release are read alike. A template's variants are rows of their own, each with its own content.
-- What a content refers to, and the files it needs, are extracted when it is stored. Steps 5 to 9
-- convert the 1.x content into these tables; their triggers come in step 16, once it is converted.

-- 1. What a stored content is -------------------------------------------------------------------
-- Extended by inserting a row. Not `catalog_resource_types`, which holds the catalog wire's own
-- tokens: a content kind is a storage concept, and includes parts of a resource the wire never names
-- on their own (a template's variant; `templateModel`, a model stored apart, which a release read
-- before the variant rows may still name).
CREATE TABLE resource_content_kinds (
    kind VARCHAR(20) PRIMARY KEY
);

INSERT INTO resource_content_kinds (kind) VALUES
    ('codeList'), ('font'), ('attribute'), ('theme'), ('stencil'), ('image'), ('template'), ('templateModel'), ('variant');

COMMENT ON TABLE resource_content_kinds IS
    'What a stored content is: a catalog wire resource type, or a part of one that is stored separately.';

-- 2. A resource's JSON by its content hash, once per tenant ---------------------------------------
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
    CONSTRAINT fk_resource_contents_tenant FOREIGN KEY (tenant_key) REFERENCES tenants (id) ON DELETE CASCADE,
    CONSTRAINT fk_resource_contents_kind FOREIGN KEY (kind) REFERENCES resource_content_kinds (kind)
);

COMMENT ON TABLE resource_contents IS
    'Immutable content of one resource, or one part of one, deduplicated by content hash within a tenant. Never updated; collected once nothing points at it (ADR 0033).';
COMMENT ON COLUMN resource_contents.content_hash IS
    'Lowercase hex SHA-256 of the canonical payload. Internal storage identity -- NOT the catalog fingerprint, which the contract computes over the wire form and which appears on the wire (ADR 0026 section 5).';
COMMENT ON COLUMN resource_contents.payload IS
    'The resource in its protocol form, {"resource": ...}; a template''s variants are contents of their own.';
COMMENT ON COLUMN resource_contents.stored_at IS
    'When the content was last stored, first or again. The collector takes only content stored longer ago than its grace period.';

-- 3. The files a content needs ------------------------------------------------------------------
-- A content's payload describes an image or a font face but never carries its bytes: those live once
-- per installation in `asset_content`, deduplicated by their hash. 1.x kept a sensitive image's bytes
-- apart, per tenant (`scope`); 2.0 has no sensitive flag (ADR 0033, step 6), so a tenant's copy joins
-- the shared store and the scope goes.
INSERT INTO asset_content (scope, content_hash, content, content_type, size_bytes, created_at)
SELECT DISTINCT ON (content_hash) 'global', content_hash, content, content_type, size_bytes, created_at
FROM asset_content
WHERE scope <> 'global'
ORDER BY content_hash, created_at
ON CONFLICT DO NOTHING;
DELETE FROM asset_content WHERE scope <> 'global';
ALTER TABLE asset_content DROP CONSTRAINT asset_content_pkey;
ALTER TABLE asset_content DROP COLUMN scope;
-- Every unprefixed SHA-256 is CHAR(64), as the storage tables hold them, so a join on a file's hash
-- compares like types and keeps its index.
ALTER TABLE asset_content ALTER COLUMN content_hash TYPE CHAR(64);
ALTER TABLE asset_content ADD CONSTRAINT asset_content_pkey PRIMARY KEY (content_hash);

COMMENT ON TABLE asset_content IS
    'The bytes of images and font faces, once per installation, by their SHA-256 (content_hash). What a stored content needs is linked through resource_binaries.';

-- content_hash identifies the CONTENT, the hash of its canonical JSON; binary_hash the FILE, the hash
-- of its bytes. A row reads: content <content_hash> needs the file <binary_hash>. The link is also
-- what keeps the file from being collected, a fact of the schema by the foreign key.
CREATE TABLE resource_binaries (
    tenant_key   TENANT_KEY NOT NULL,
    content_hash CHAR(64)   NOT NULL,
    binary_hash  CHAR(64)   NOT NULL,
    CONSTRAINT pk_resource_binaries PRIMARY KEY (tenant_key, content_hash, binary_hash),
    CONSTRAINT fk_resource_binaries_content FOREIGN KEY (tenant_key, content_hash)
        REFERENCES resource_contents (tenant_key, content_hash) ON DELETE CASCADE,
    CONSTRAINT fk_resource_binaries_binary FOREIGN KEY (binary_hash) REFERENCES asset_content (content_hash)
);

-- Serves the sweep, which asks whether anything still holds a given file.
CREATE INDEX idx_resource_binaries_binary ON resource_binaries (binary_hash);
-- Serves reading a resource's binaries when they are shown.
CREATE INDEX idx_resource_binaries_tenant_binary ON resource_binaries (tenant_key, binary_hash);

COMMENT ON TABLE resource_binaries IS
    'Links a content to the binary files it needs: content_hash identifies the content, binary_hash the file in asset_content. Also the retention root -- bytes reachable from a content are never collected, and the foreign key enforces it.';

-- 4. What a content refers to -------------------------------------------------------------------
-- Content is immutable, so what it refers to is extracted once, when it is stored, and shared by
-- every version that holds it: the alias of the catalog it names (NULL for its own), the type and
-- key. Never a foreign key: content may refer to what a version does not hold.
CREATE TABLE resource_references (
    tenant_key    TENANT_KEY  NOT NULL,
    content_hash  CHAR(64)    NOT NULL,
    alias         VARCHAR(50),
    resource_type VARCHAR(20) NOT NULL,
    resource_key  TEXT        NOT NULL,
    CONSTRAINT fk_resource_references_content FOREIGN KEY (tenant_key, content_hash)
        REFERENCES resource_contents (tenant_key, content_hash) ON DELETE CASCADE,
    CONSTRAINT fk_resource_references_type FOREIGN KEY (resource_type) REFERENCES catalog_resource_types (resource_type)
);

-- One row per distinct reference of a content; the alias of the own catalog is NULL, compared as ''.
CREATE UNIQUE INDEX uq_resource_references ON resource_references
    (tenant_key, content_hash, COALESCE(alias, ''), resource_type, resource_key);
-- What refers to a resource: usages and the graph.
CREATE INDEX idx_resource_references_target ON resource_references (tenant_key, resource_type, resource_key);

COMMENT ON TABLE resource_references IS
    'What a content refers to: the alias of the catalog it names (NULL for its own catalog), the resource type and key. Extracted when the content is stored; collected with it.';

-- 5. What a version holds -----------------------------------------------------------------------
-- What a row may be: a catalog wire resource type, or a part of one stored as a row of its own (a
-- template's variant, which is no catalog resource: no address, no fingerprint of its own).
CREATE TABLE release_resource_types (
    resource_type VARCHAR(20) PRIMARY KEY,
    -- The wire type a row of this type is; NULL for a part of one.
    wire_type     VARCHAR(20) REFERENCES catalog_resource_types (resource_type),
    CONSTRAINT chk_release_resource_types_wire CHECK (wire_type IS NULL OR wire_type = resource_type)
);

INSERT INTO release_resource_types (resource_type, wire_type) SELECT resource_type, resource_type FROM catalog_resource_types;
INSERT INTO release_resource_types (resource_type, wire_type) VALUES ('variant', NULL);

COMMENT ON TABLE release_resource_types IS
    'What a release_resources row may be: a catalog wire resource type (wire_type set), or a part of one stored as a row of its own (a template''s variant). catalog_resource_types stays the wire list.';

-- One row per resource of a version, draft or released, naming the content that holds it. A draft's
-- row is edited (its revision is its optimistic lock); a released row is frozen with its version.
CREATE TABLE release_resources (
    tenant_key    TENANT_KEY   NOT NULL,
    catalog_id    UUID         NOT NULL,
    version_id    UUID         NOT NULL,
    resource_type VARCHAR(20)  NOT NULL,
    resource_key  TEXT         NOT NULL,
    resource_id   UUID         NOT NULL,
    parent_id     UUID,
    content_hash  CHAR(64)     NOT NULL,
    fingerprint   VARCHAR(80),
    name          VARCHAR(255) NOT NULL,
    description   TEXT,
    revision      BIGINT       NOT NULL DEFAULT 1,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by    UUID REFERENCES users (id) ON DELETE SET NULL,
    updated_by    UUID REFERENCES users (id) ON DELETE SET NULL,
    -- A row is of its version's catalog, and goes with the version.
    CONSTRAINT fk_release_resources_draft FOREIGN KEY (tenant_key, version_id, catalog_id)
        REFERENCES catalog_versions (tenant_key, version_id, catalog_id) ON DELETE CASCADE,
    -- Deferred, not cascading: content is never deleted while a row names it, and deleting a tenant,
    -- which removes both in an order Postgres chooses, is not refused by it (ADR 0033).
    CONSTRAINT fk_release_resources_content FOREIGN KEY (tenant_key, content_hash)
        REFERENCES resource_contents (tenant_key, content_hash) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT fk_release_resources_type FOREIGN KEY (resource_type) REFERENCES release_resource_types (resource_type),
    -- A variant row names its template's row; nothing else names a parent.
    CONSTRAINT chk_release_resources_variant_parent CHECK ((resource_type = 'variant') = (parent_id IS NOT NULL))
);

-- Unique per version, type and key for a wire resource; per version, template and key for a variant
-- (a key such as `default` is in every template). Keyed by catalog first: what a catalog's reads filter on.
CREATE UNIQUE INDEX uq_release_resources_entry ON release_resources (tenant_key, catalog_id, version_id, resource_type, resource_key)
    WHERE parent_id IS NULL;
CREATE UNIQUE INDEX uq_release_resources_variant ON release_resources (tenant_key, version_id, parent_id, resource_key);
-- A template's variants, in every version.
CREATE INDEX idx_release_resources_parent ON release_resources (tenant_key, parent_id, resource_key) WHERE parent_id IS NOT NULL;
-- Which versions carried a resource.
CREATE INDEX idx_release_resources_resource ON release_resources (tenant_key, resource_id);
-- What points at a content: the collector's question, and what the foreign key checks.
CREATE INDEX idx_release_resources_content ON release_resources (tenant_key, content_hash);

COMMENT ON TABLE release_resources IS
    'What a catalog version contains: one row per resource, naming the content that holds it. A released version''s rows are frozen with it.';
COMMENT ON COLUMN release_resources.resource_key IS
    'The address the resource has inside this version. A later relocation changes a draft, never a release.';
COMMENT ON COLUMN release_resources.resource_id IS
    'The resource''s identity (catalog_resources), so a moved resource can still be traced to the versions that carried it. A variant''s is derived from its template''s (variant_resource_id).';
COMMENT ON COLUMN release_resources.content_hash IS
    'Internal storage identity of the content (resource_contents). Not the fingerprint -- see the next column and ADR 0026 section 5.';
COMMENT ON COLUMN release_resources.fingerprint IS
    'The contract''s per-resource canonical digest over the wire form, recorded when the version is released; NULL for a draft row and a variant row.';
COMMENT ON COLUMN release_resources.name IS
    'Name and description as held. The manifest is canonicalised over these, so rebuilding the archive needs them, and a resource dropped since can still be named.';
COMMENT ON COLUMN release_resources.version_id IS 'The catalog version (catalog_versions) this row is of.';
COMMENT ON COLUMN release_resources.revision IS 'A draft row''s optimistic lock, raised by every change (Revisions); kept as it was by a released row.';
COMMENT ON COLUMN release_resources.parent_id IS
    'For a variant row: the identity of its template, whose row in the same version it belongs to. NULL for a wire resource.';

-- A variant's identity: derived from its template's and its key, so it is the same in every version
-- and needs no registry.
CREATE FUNCTION variant_resource_id(p_parent UUID, p_key TEXT) RETURNS UUID
    LANGUAGE sql
    IMMUTABLE
AS $$
SELECT CAST(md5(CAST(p_parent AS TEXT) || '/' || p_key) AS UUID)
$$;

COMMENT ON FUNCTION variant_resource_id(UUID, TEXT) IS
    'The resource_id of the variant row [p_key] of the template [p_parent]: the same in every draft and release.';

-- 6. What is ready ------------------------------------------------------------------------------
-- A part of a resource marked ready in a version, with the digest of its content then: it stays ready
-- while its digest does. A template is ready per variant (part = the variant's key), the others whole.
CREATE TABLE resource_ready_marks (
    tenant_key  TENANT_KEY  NOT NULL,
    version_id  UUID        NOT NULL,
    resource_id UUID        NOT NULL,
    part        TEXT        NOT NULL,
    digest      CHAR(64)    NOT NULL,
    marked_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    marked_by   UUID,
    PRIMARY KEY (tenant_key, version_id, resource_id, part),
    FOREIGN KEY (tenant_key, resource_id) REFERENCES catalog_resources (tenant_key, resource_id) ON DELETE CASCADE,
    CONSTRAINT fk_resource_ready_marks_version FOREIGN KEY (tenant_key, version_id)
        REFERENCES catalog_versions (tenant_key, version_id) ON DELETE CASCADE
);

COMMENT ON TABLE resource_ready_marks IS
    'Ready marks: the content digest (resource_content_digests) a part had when it was marked ready.';
COMMENT ON COLUMN resource_ready_marks.version_id IS 'The catalog version in which the resource part was marked ready.';

-- A variant's digest, for its ready mark: of its template's content and its own. A variant is ready
-- apart from the others, and stops being ready when it, or what its template says of every variant,
-- changes.
CREATE FUNCTION template_variant_digest(p_template_hash TEXT, p_variant_hash TEXT) RETURNS TEXT
    LANGUAGE sql
    IMMUTABLE
AS $$
SELECT encode(sha256(convert_to(p_template_hash || ':' || p_variant_hash, 'UTF8')), 'hex')
$$;

COMMENT ON FUNCTION template_variant_digest(TEXT, TEXT) IS
    'The digest a ready mark records for one variant of a template stored as JSON (ADR 0033): of its template''s content hash and its own.';

-- 7. What only this suite keeps beside content ----------------------------------------------------
-- A credential a code list's source authenticates with: its content names it by id, so the secret is
-- never part of what is hashed, shared between versions or exported. Encrypted at rest.
CREATE TABLE resource_credentials (
    tenant_key    TENANT_KEY  NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    credential_id UUID        NOT NULL,
    credential    TEXT        NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (tenant_key, credential_id)
);

COMMENT ON TABLE resource_credentials IS
    'Secrets a resource''s source authenticates with (ADR 0033), named by id from its content (source.credential); encrypted at rest.';

-- When a draft's code list was last refreshed from its source, and what went wrong: the state of the
-- draft's copy, not content and not versioned.
CREATE TABLE code_list_refreshes (
    tenant_key         TENANT_KEY  NOT NULL,
    version_id         UUID        NOT NULL,
    resource_id        UUID        NOT NULL,
    last_refreshed_at  TIMESTAMPTZ,
    last_refresh_error TEXT,
    PRIMARY KEY (tenant_key, version_id, resource_id),
    FOREIGN KEY (tenant_key, version_id) REFERENCES catalog_versions (tenant_key, version_id) ON DELETE CASCADE
);

COMMENT ON TABLE code_list_refreshes IS
    'A draft code list''s last refresh from its source (ADR 0033): operational state of the draft, not content.';
