-- ADR 0033, step 7b: a template's variants are rows of their own in release_resources, each with
-- its own content, revision and bookkeeping. Saving one writes that variant's content and bumps that
-- variant's revision; neither the template's row nor its other variants change.
--
-- A variant is not a catalog resource (contract: a member of its TemplateResource, with no address
-- or fingerprint of its own). So what a row may be (storage types) is decoupled from the wire's
-- resource types: a variant row is of storage type `variant`, names its template's row
-- (`parent_id`, the template's identity in the same draft or release) and is registered nowhere.
-- Readers of release entries (a manifest, counts, fingerprints) read `release_wire_resources`, which holds
-- wire resources only (ReleaseResourceSqlTest).

-- 1. The storage types: every wire type, and the parts stored as rows of their own.
CREATE TABLE release_resource_types (
    resource_type VARCHAR(20) PRIMARY KEY,
    -- The wire type a row of this type is; NULL for a part of one (a template's variant).
    wire_type     VARCHAR(20) REFERENCES catalog_resource_types (resource_type),
    CONSTRAINT chk_release_resource_types_wire CHECK (wire_type IS NULL OR wire_type = resource_type)
);

COMMENT ON TABLE release_resource_types IS
    'What a release_resources row may be: a catalog wire resource type (wire_type set), or a part of one stored as a row of its own (a template''s variant). catalog_resource_types stays the wire list.';

INSERT INTO release_resource_types (resource_type, wire_type) SELECT resource_type, resource_type FROM catalog_resource_types;
INSERT INTO release_resource_types (resource_type, wire_type) VALUES ('variant', NULL);

-- A wire type added later is a storage type too.
CREATE FUNCTION add_release_resource_type() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    INSERT INTO release_resource_types (resource_type, wire_type) VALUES (NEW.resource_type, NEW.resource_type)
    ON CONFLICT (resource_type) DO NOTHING;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_catalog_resource_types_storage AFTER INSERT ON catalog_resource_types
    FOR EACH ROW EXECUTE FUNCTION add_release_resource_type();

ALTER TABLE release_resources DROP CONSTRAINT fk_release_resources_type;
ALTER TABLE release_resources ADD CONSTRAINT fk_release_resources_type
    FOREIGN KEY (resource_type) REFERENCES release_resource_types (resource_type);

INSERT INTO resource_content_kinds (kind) VALUES ('variant');

-- 2. A variant row names its template's row; nothing else names a parent.
ALTER TABLE release_resources ADD COLUMN parent_id UUID;
ALTER TABLE release_resources ADD CONSTRAINT chk_release_resources_variant_parent
    CHECK ((resource_type = 'variant') = (parent_id IS NOT NULL));
-- A variant has no fingerprint of its own: its template's release row holds the assembled one.
ALTER TABLE release_resources DROP CONSTRAINT chk_release_resources_released_fingerprint;
ALTER TABLE release_resources ADD CONSTRAINT chk_release_resources_released_fingerprint
    CHECK (version IS NULL OR parent_id IS NOT NULL OR fingerprint IS NOT NULL);

COMMENT ON COLUMN release_resources.parent_id IS
    'For a variant row: the identity of its template, whose row in the same draft or release it belongs to. NULL for a wire resource.';

-- Unique per version, type and key for a wire resource, and per version, template and key for a
-- variant: a key such as `default` is in every template. A wire resource's index is the one its
-- lookups use, as before (ThemePlanStabilityTest); they read wire rows (`parent_id IS NULL`, as
-- release_wire_resources and catalog_version_resources say), so the index says so too.
DROP INDEX uq_release_resources_release;
DROP INDEX uq_release_resources_draft;
CREATE UNIQUE INDEX uq_release_resources_release ON release_resources (tenant_key, catalog_id, version, resource_type, resource_key)
    WHERE version IS NOT NULL AND parent_id IS NULL;
CREATE UNIQUE INDEX uq_release_resources_draft ON release_resources (tenant_key, catalog_id, version_id, resource_type, resource_key)
    WHERE version_id IS NOT NULL AND parent_id IS NULL;
CREATE UNIQUE INDEX uq_release_resources_release_variant ON release_resources (tenant_key, parent_id, version, resource_key)
    WHERE version IS NOT NULL AND parent_id IS NOT NULL;
CREATE UNIQUE INDEX uq_release_resources_draft_variant ON release_resources (tenant_key, parent_id, version_id, resource_key)
    WHERE version_id IS NOT NULL AND parent_id IS NOT NULL;
-- A template's variants, in every version.
CREATE INDEX idx_release_resources_parent ON release_resources (tenant_key, parent_id, resource_key)
    WHERE parent_id IS NOT NULL;

-- The rows of one wire type of the version a caller means (V20261024110723), without the variant
-- rows, which are no wire type's.
CREATE OR REPLACE FUNCTION catalog_version_resources(p_type TEXT)
    RETURNS TABLE (
        tenant_key    tenant_key,
        catalog_id    UUID,
        version_id    UUID,
        version       VARCHAR(128),
        resource_type VARCHAR(20),
        resource_key  TEXT,
        resource_id   UUID,
        content_hash  CHAR(64),
        fingerprint   VARCHAR(80),
        name          VARCHAR(255),
        description   TEXT,
        revision      BIGINT,
        created_at    TIMESTAMPTZ,
        updated_at    TIMESTAMPTZ,
        created_by    UUID,
        updated_by    UUID
    )
    LANGUAGE sql
    STABLE
AS $$
SELECT cv.tenant_key, cv.catalog_id, cv.version_id, r.version, r.resource_type, r.resource_key, r.resource_id,
       r.content_hash, r.fingerprint, r.name, r.description, r.revision, r.created_at, r.updated_at, r.created_by, r.updated_by
FROM catalog_current_versions cv
CROSS JOIN LATERAL (
    SELECT * FROM release_resources d
    WHERE d.tenant_key = cv.tenant_key AND d.catalog_id = cv.catalog_id AND d.version_id = cv.version_id AND d.resource_type = p_type
      AND d.parent_id IS NULL
    OFFSET 0
) r
WHERE cv.catalog_id IS DISTINCT FROM NULLIF(current_setting('epistola.release_catalog', TRUE), '')::uuid
UNION ALL
SELECT r.tenant_key, r.catalog_id, r.version_id, r.version, r.resource_type, r.resource_key, r.resource_id,
       r.content_hash, r.fingerprint, r.name, r.description, r.revision, r.created_at, r.updated_at, r.created_by, r.updated_by
FROM release_resources r
WHERE r.version IS NOT NULL
  AND r.resource_type = p_type
  AND r.parent_id IS NULL
  AND r.catalog_id = NULLIF(current_setting('epistola.release_catalog', TRUE), '')::uuid
  AND r.version = current_setting('epistola.release_version', TRUE)
$$;

-- A variant's identity: derived from its template's and its key, so it is the same in every draft
-- and release, and needs no registry.
CREATE FUNCTION variant_resource_id(p_parent UUID, p_key TEXT) RETURNS UUID
    LANGUAGE sql
    IMMUTABLE
AS $$
SELECT CAST(md5(CAST(p_parent AS TEXT) || '/' || p_key) AS UUID)
$$;

COMMENT ON FUNCTION variant_resource_id(UUID, TEXT) IS
    'The resource_id of the variant row [p_key] of the template [p_parent]: the same in every draft and release.';

-- 3. The identity registry (catalog_resources) registers wire resources only.
DROP TRIGGER trg_release_resources_draft_identity ON release_resources;
DROP TRIGGER trg_release_resources_draft_delete ON release_resources;
CREATE TRIGGER trg_release_resources_draft_identity
    BEFORE INSERT OR UPDATE OF resource_id, catalog_id, resource_key ON release_resources
    FOR EACH ROW WHEN (NEW.version_id IS NOT NULL AND NEW.parent_id IS NULL) EXECUTE FUNCTION sync_draft_resource_identity();
CREATE TRIGGER trg_release_resources_draft_delete
    AFTER DELETE ON release_resources
    FOR EACH ROW WHEN (OLD.version_id IS NOT NULL AND OLD.parent_id IS NULL) EXECUTE FUNCTION sync_draft_resource_identity();

-- 4. A variant row belongs to its template's row in the same draft or release, checked at commit
--    (a template and its variants are written in one transaction).
CREATE FUNCTION release_resource_variant_parent() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    -- Checked at commit against what is there then: a row that moved on since this event (a later
    -- update, a move with its template) is checked by its own event.
    IF EXISTS (
        SELECT 1 FROM release_resources v
        WHERE v.tenant_key = NEW.tenant_key AND v.catalog_id = NEW.catalog_id AND v.parent_id = NEW.parent_id
          AND v.resource_type = 'variant' AND v.resource_key = NEW.resource_key
          AND v.version_id IS NOT DISTINCT FROM NEW.version_id AND v.version IS NOT DISTINCT FROM NEW.version
    ) AND NOT EXISTS (
        SELECT 1 FROM release_resources r
        WHERE r.tenant_key = NEW.tenant_key AND r.catalog_id = NEW.catalog_id AND r.resource_id = NEW.parent_id
          AND r.resource_type = 'template'
          AND r.version_id IS NOT DISTINCT FROM NEW.version_id AND r.version IS NOT DISTINCT FROM NEW.version
    ) THEN
        RAISE EXCEPTION 'variant % of template % has no template row in its version', NEW.resource_key, NEW.parent_id
            USING ERRCODE = 'foreign_key_violation';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER trg_release_resources_variant_parent AFTER INSERT OR UPDATE ON release_resources
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW WHEN (NEW.parent_id IS NOT NULL) EXECUTE FUNCTION release_resource_variant_parent();

-- A template row that moves leaves no variant behind: relocation moves them itself (ADR 0014), and
-- this refuses at commit the move that forgot them.
CREATE FUNCTION release_resource_variants_moved() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM release_resources v
        WHERE v.tenant_key = OLD.tenant_key AND v.catalog_id = OLD.catalog_id AND v.parent_id = OLD.resource_id
          AND v.version_id IS NOT DISTINCT FROM OLD.version_id AND v.version IS NOT DISTINCT FROM OLD.version
    ) AND NOT EXISTS (
        SELECT 1 FROM release_resources r
        WHERE r.tenant_key = OLD.tenant_key AND r.catalog_id = OLD.catalog_id AND r.resource_id = OLD.resource_id
          AND r.resource_type = 'template'
          AND r.version_id IS NOT DISTINCT FROM OLD.version_id AND r.version IS NOT DISTINCT FROM OLD.version
    ) THEN
        RAISE EXCEPTION 'template % moved without its variants', OLD.resource_id
            USING ERRCODE = 'foreign_key_violation';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER trg_release_resources_template_moved AFTER UPDATE OF catalog_id, version_id, version ON release_resources
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW WHEN (OLD.resource_type = 'template') EXECUTE FUNCTION release_resource_variants_moved();

-- Deleting a template's row deletes its variants in that draft or release.
CREATE FUNCTION release_resource_variants_deleted() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    DELETE FROM release_resources v
    WHERE v.tenant_key = OLD.tenant_key AND v.catalog_id = OLD.catalog_id AND v.parent_id = OLD.resource_id
      AND v.version_id IS NOT DISTINCT FROM OLD.version_id AND v.version IS NOT DISTINCT FROM OLD.version;
    RETURN NULL;
END;
$$;

CREATE TRIGGER trg_release_resources_template_deleted AFTER DELETE ON release_resources
    FOR EACH ROW WHEN (OLD.resource_type = 'template') EXECUTE FUNCTION release_resource_variants_deleted();

-- 5. The entries of a release or a draft: its wire resources, without the rows that are parts of one.
--    What lists, counts or fingerprints entries reads this, not the table.
CREATE VIEW release_wire_resources AS
SELECT tenant_key, catalog_id, version_id, version, resource_type, resource_key, resource_id, content_hash,
       fingerprint, name, description, revision, created_at, updated_at, created_by, updated_by
FROM release_resources
WHERE parent_id IS NULL;

COMMENT ON VIEW release_wire_resources IS
    'The wire resources of every draft and release (release_resources without the variant rows): what a manifest, a count or a fingerprint reads.';
