-- ADR 0036, step (b): release_resources is keyed by the version row only.
--
-- A release's resources named it by its number (`version`), a draft's by its row (`version_id`). Since
-- step (a) every release is a row of catalog_versions, so its resources name that row too, and the
-- number goes from this table. Reads of a release go through the same read place as a draft's:
-- catalog_current_versions resolves the release a transaction targets to its row, so
-- catalog_version_resources has one branch.

-- 1. Whether a row of release_resources is a draft's: what the bookkeeping triggers fire for. A
--    release's rows are frozen with it; nobody edits them, moves them, or touches the draft for them.
CREATE FUNCTION release_resource_is_draft(p_tenant TEXT, p_version_id UUID)
    RETURNS BOOLEAN
    LANGUAGE sql
    STABLE
AS $$
SELECT EXISTS (SELECT 1 FROM catalog_versions v WHERE v.tenant_key = p_tenant AND v.version_id = p_version_id AND v.state = 'draft')
$$;

-- A delete fires them unless the row is a release's: a draft's rows cascade after their version row
-- is gone, and a release's rows are deleted while it is still there (DeleteCatalogRelease).
CREATE FUNCTION release_resource_is_released(p_tenant TEXT, p_version_id UUID)
    RETURNS BOOLEAN
    LANGUAGE sql
    STABLE
AS $$
SELECT EXISTS (SELECT 1 FROM catalog_versions v WHERE v.tenant_key = p_tenant AND v.version_id = p_version_id AND v.state = 'released')
$$;

-- 2. A release's rows name its row (the triggers stay off: this moves nothing, it renames a key).
ALTER TABLE release_resources DROP CONSTRAINT chk_release_resources_release_or_draft;
ALTER TABLE release_resources DISABLE TRIGGER USER;
UPDATE release_resources r SET version_id = v.version_id
FROM catalog_versions v
WHERE r.version IS NOT NULL
  AND v.tenant_key = r.tenant_key AND v.catalog_id = r.catalog_id AND v.version = r.version AND v.state = 'released';
ALTER TABLE release_resources ENABLE TRIGGER USER;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM release_resources WHERE version_id IS NULL) THEN
        RAISE EXCEPTION 'release_resources rows name a release that has no row in catalog_versions';
    END IF;
END;
$$;

-- 3. What read the number goes first, then the number.
DROP VIEW current_template_variants;
DROP VIEW release_wire_resources;

ALTER TABLE release_resources
    DROP CONSTRAINT fk_release_resources_release,
    DROP CONSTRAINT chk_release_resources_released_fingerprint;
DROP INDEX uq_release_resources_release;
DROP INDEX uq_release_resources_release_variant;
DROP INDEX uq_release_resources_draft;
DROP INDEX uq_release_resources_draft_variant;
DROP INDEX idx_release_resources_draft_version;
ALTER TABLE release_resources ALTER COLUMN version_id SET NOT NULL;
DROP TRIGGER trg_release_resources_template_moved ON release_resources;
ALTER TABLE release_resources DROP COLUMN version;

-- One row per version, type and key for a wire resource, and per version, template and key for a
-- variant; keyed by catalog first, as a catalog's reads filter (V20261024110723).
CREATE UNIQUE INDEX uq_release_resources_entry ON release_resources (tenant_key, catalog_id, version_id, resource_type, resource_key)
    WHERE parent_id IS NULL;
-- A variant per version, template and key; leading with the version, it is also how a version's rows
-- are found by their version alone (a copy, a cascade). One index, so the planner, without statistics,
-- never prefers scanning a version's rows to finding one template's variants (ThemePlanStabilityTest).
CREATE UNIQUE INDEX uq_release_resources_variant ON release_resources (tenant_key, version_id, parent_id, resource_key);

-- 4. The bookkeeping triggers fire for a draft's rows only: an insert or update of one, a delete of
--    any row but a release's.
DROP TRIGGER trg_release_resources_draft_touched ON release_resources;
DROP TRIGGER trg_release_resources_draft_updated_at ON release_resources;
DROP TRIGGER trg_release_resources_draft_revision ON release_resources;
DROP TRIGGER trg_release_resources_draft_identity ON release_resources;
DROP TRIGGER trg_release_resources_draft_delete ON release_resources;
CREATE TRIGGER trg_release_resources_draft_touched AFTER DELETE ON release_resources
    FOR EACH ROW WHEN (NOT release_resource_is_released(OLD.tenant_key, OLD.version_id)) EXECUTE FUNCTION touch_catalog_content_on_delete();
CREATE TRIGGER trg_release_resources_draft_updated_at BEFORE UPDATE ON release_resources
    FOR EACH ROW WHEN (release_resource_is_draft(OLD.tenant_key, OLD.version_id)) EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_release_resources_draft_revision BEFORE UPDATE ON release_resources
    FOR EACH ROW WHEN (release_resource_is_draft(OLD.tenant_key, OLD.version_id)) EXECUTE FUNCTION bump_content_revision();
CREATE TRIGGER trg_release_resources_draft_identity BEFORE INSERT OR UPDATE OF resource_id, catalog_id, resource_key ON release_resources
    FOR EACH ROW WHEN (NEW.parent_id IS NULL AND release_resource_is_draft(NEW.tenant_key, NEW.version_id)) EXECUTE FUNCTION sync_draft_resource_identity();
CREATE TRIGGER trg_release_resources_draft_delete AFTER DELETE ON release_resources
    FOR EACH ROW WHEN (OLD.parent_id IS NULL AND NOT release_resource_is_released(OLD.tenant_key, OLD.version_id)) EXECUTE FUNCTION sync_draft_resource_identity();

-- A resource moves or is renamed only while no other draft of its catalog holds it (a release does
-- not hold it back: a release is frozen at the address it had).
CREATE OR REPLACE FUNCTION sync_draft_resource_identity() RETURNS trigger LANGUAGE plpgsql AS $$

DECLARE
    existing_resource_id UUID;
    held_elsewhere BOOLEAN;
BEGIN
    IF TG_OP = 'DELETE' THEN
        SELECT EXISTS (SELECT 1 FROM release_resources WHERE tenant_key = OLD.tenant_key AND resource_id = OLD.resource_id)
            INTO held_elsewhere;
        IF NOT held_elsewhere THEN
            DELETE FROM catalog_resources WHERE tenant_key = OLD.tenant_key AND resource_id = OLD.resource_id;
        END IF;
        RETURN OLD;
    END IF;

    IF TG_OP = 'INSERT' THEN
        SELECT resource_id INTO existing_resource_id
        FROM catalog_resources
        WHERE tenant_key = NEW.tenant_key AND resource_type = NEW.resource_type
          AND catalog_id = NEW.catalog_id AND resource_key = NEW.resource_key;
        IF NEW.resource_id IS NULL THEN
            NEW.resource_id := COALESCE(existing_resource_id, epistola_uuidv7());
        ELSIF existing_resource_id IS NOT NULL AND existing_resource_id <> NEW.resource_id THEN
            RAISE EXCEPTION 'catalog resource % is already registered at %/% under identity %',
                NEW.resource_id, NEW.catalog_id, NEW.resource_key, existing_resource_id;
        END IF;
        INSERT INTO catalog_resources (tenant_key, resource_id, resource_type, catalog_id, resource_key)
        VALUES (NEW.tenant_key, NEW.resource_id, NEW.resource_type, NEW.catalog_id, NEW.resource_key)
        ON CONFLICT (tenant_key, resource_id) DO UPDATE
        SET catalog_id = EXCLUDED.catalog_id, resource_key = EXCLUDED.resource_key
        WHERE catalog_resources.resource_type = EXCLUDED.resource_type
          AND catalog_resources.catalog_id = EXCLUDED.catalog_id
          AND catalog_resources.resource_key = EXCLUDED.resource_key;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'catalog resource % is registered with another type or at another address than %/%',
                NEW.resource_id, NEW.catalog_id, NEW.resource_key;
        END IF;
        RETURN NEW;
    END IF;

    IF NEW.resource_id <> OLD.resource_id THEN
        RAISE EXCEPTION 'catalog resource_id is immutable';
    END IF;
    IF NEW.catalog_id IS DISTINCT FROM OLD.catalog_id OR NEW.resource_key IS DISTINCT FROM OLD.resource_key THEN
        SELECT EXISTS (SELECT 1 FROM release_resources WHERE tenant_key = OLD.tenant_key AND resource_id = OLD.resource_id
                       AND version_id <> OLD.version_id AND release_resource_is_draft(tenant_key, version_id))
            INTO held_elsewhere;
        IF held_elsewhere THEN
            RAISE EXCEPTION 'catalog resource % is held by another version of its catalog; it cannot move or be renamed', OLD.resource_id;
        END IF;
        UPDATE catalog_resources SET catalog_id = NEW.catalog_id, resource_key = NEW.resource_key
        WHERE tenant_key = OLD.tenant_key AND resource_id = OLD.resource_id;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'catalog resource % has no identity row', OLD.resource_id;
        END IF;
    END IF;
    RETURN NEW;
END;

$$;

-- 5. A variant and its template are of one version: its row.
CREATE OR REPLACE FUNCTION release_resource_variant_parent() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM release_resources v
        WHERE v.tenant_key = NEW.tenant_key AND v.catalog_id = NEW.catalog_id AND v.parent_id = NEW.parent_id
          AND v.resource_type = 'variant' AND v.resource_key = NEW.resource_key AND v.version_id = NEW.version_id
    ) AND NOT EXISTS (
        SELECT 1 FROM release_resources r
        WHERE r.tenant_key = NEW.tenant_key AND r.catalog_id = NEW.catalog_id AND r.resource_id = NEW.parent_id
          AND r.resource_type = 'template' AND r.version_id = NEW.version_id
    ) THEN
        RAISE EXCEPTION 'variant % of template % has no template row in its version', NEW.resource_key, NEW.parent_id
            USING ERRCODE = 'foreign_key_violation';
    END IF;
    RETURN NULL;
END;
$$;

CREATE OR REPLACE FUNCTION release_resource_variants_moved() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM release_resources v
        WHERE v.tenant_key = OLD.tenant_key AND v.catalog_id = OLD.catalog_id AND v.parent_id = OLD.resource_id
          AND v.version_id = OLD.version_id
    ) AND NOT EXISTS (
        SELECT 1 FROM release_resources r
        WHERE r.tenant_key = OLD.tenant_key AND r.catalog_id = OLD.catalog_id AND r.resource_id = OLD.resource_id
          AND r.resource_type = 'template' AND r.version_id = OLD.version_id
    ) THEN
        RAISE EXCEPTION 'template % moved without its variants', OLD.resource_id
            USING ERRCODE = 'foreign_key_violation';
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER trg_release_resources_template_moved AFTER UPDATE OF catalog_id, version_id ON release_resources
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW WHEN (OLD.resource_type = 'template') EXECUTE FUNCTION release_resource_variants_moved();

CREATE OR REPLACE FUNCTION release_resource_variants_deleted() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    DELETE FROM release_resources v
    WHERE v.tenant_key = OLD.tenant_key AND v.catalog_id = OLD.catalog_id AND v.parent_id = OLD.resource_id
      AND v.version_id = OLD.version_id;
    RETURN NULL;
END;
$$;

-- 6. One read place, keyed once (ADR 0036, section 7): the version a caller means is a row, a
--    targeted release's included; the release's number comes from that row.
CREATE OR REPLACE VIEW catalog_current_versions AS
SELECT DISTINCT ON (tenant_key, catalog_id) tenant_key, catalog_id, version_id
FROM catalog_versions v
WHERE CASE
    WHEN v.catalog_id = NULLIF(current_setting('epistola.release_catalog', TRUE), '')::uuid
        THEN v.state = 'released' AND v.version = current_setting('epistola.release_version', TRUE)
    ELSE v.version_id::text = current_setting('epistola.content_version', TRUE) OR v.is_next
END
ORDER BY tenant_key, catalog_id, ((version_id::text = current_setting('epistola.content_version', TRUE)) IS TRUE) DESC;

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
SELECT cv.tenant_key, cv.catalog_id, cv.version_id, vr.version, r.resource_type, r.resource_key, r.resource_id,
       r.content_hash, r.fingerprint, r.name, r.description, r.revision, r.created_at, r.updated_at, r.created_by, r.updated_by
FROM catalog_current_versions cv
JOIN catalog_versions vr ON vr.tenant_key = cv.tenant_key AND vr.version_id = cv.version_id
CROSS JOIN LATERAL (
    SELECT * FROM release_resources d
    WHERE d.tenant_key = cv.tenant_key AND d.catalog_id = cv.catalog_id AND d.version_id = cv.version_id AND d.resource_type = p_type
      AND d.parent_id IS NULL
    OFFSET 0
) r
$$;

-- 7. An alias resolves through a release's pins when it is read as a release (it has a number), else
--    through the draft's declarations.
CREATE OR REPLACE FUNCTION resolve_catalog_alias(p_tenant TEXT, p_catalog UUID, p_version_id UUID, p_version TEXT, p_alias TEXT)
    RETURNS UUID
    LANGUAGE sql
    STABLE
AS $$
SELECT CASE
    WHEN p_alias IS NULL OR p_alias = (SELECT c.catalog_key FROM catalogs c WHERE c.tenant_key = p_tenant AND c.catalog_id = p_catalog)
        THEN p_catalog
    ELSE COALESCE(
        (SELECT epistola_catalog_id(p_tenant, d.namespace, d.dependency_key) FROM catalog_dependencies d
          WHERE p_version IS NULL AND d.tenant_key = p_tenant AND d.version_id = p_version_id AND d.alias = p_alias),
        (SELECT epistola_catalog_id(p_tenant, d.namespace, d.dependency_key) FROM catalog_release_dependencies d
          WHERE p_version IS NOT NULL AND d.tenant_key = p_tenant AND d.catalog_id = p_catalog AND d.version = p_version AND d.alias = p_alias))
END
$$;

-- 8. The views that read the number, again: the number from the version's row.
CREATE VIEW release_wire_resources AS
SELECT r.tenant_key, r.catalog_id, r.version_id, v.version, r.resource_type, r.resource_key, r.resource_id, r.content_hash,
       r.fingerprint, r.name, r.description, r.revision, r.created_at, r.updated_at, r.created_by, r.updated_by
FROM release_resources r
JOIN catalog_versions v ON v.tenant_key = r.tenant_key AND v.version_id = r.version_id
WHERE r.parent_id IS NULL;

COMMENT ON VIEW release_wire_resources IS
    'The wire resources of every draft and release (release_resources without the variant rows), with a release''s number: what a manifest, a count or a fingerprint reads.';

-- A template's variants, read from its version's rows (V20261026090540, keyed once).
CREATE VIEW current_template_variants AS
SELECT t.tenant_key, t.catalog_id, t.version_id, t.version, t.resource_id AS template_resource_id,
       e.slot ->> 'key' AS id,
       COALESCE(v.payload -> 'resource' ->> 'title', e.slot ->> 'title', e.slot ->> 'key') AS title,
       COALESCE(v.payload -> 'resource' ->> 'description', v.payload ->> 'description', t.payload -> 'variantDescriptions' ->> (e.slot ->> 'key')) AS description,
       COALESCE(NULLIF(e.slot -> 'attributes', 'null'::jsonb), '{}'::jsonb) AS attributes,
       COALESCE(CAST(e.slot ->> 'isDefault' AS BOOLEAN), FALSE) AS is_default,
       NULLIF(COALESCE(v.payload -> 'resource' -> 'metadata', e.slot -> 'metadata'), 'null'::jsonb) AS metadata,
       COALESCE(v.content_hash, m.model_hash) AS model_hash,
       -- Its model; the default variant's for one that has none of its own (as a received release may
       -- say). A release retained before variants had rows names its models by revision (m).
       COALESCE(v.payload -> 'resource' -> 'templateModel', d.payload -> 'resource' -> 'templateModel', m.model) AS template_model,
       e.position,
       COALESCE(v.created_at, t.created_at) AS created_at, COALESCE(v.updated_at, t.updated_at) AS updated_at,
       COALESCE(v.created_by, t.created_by) AS created_by, COALESCE(v.updated_by, t.updated_by) AS updated_by,
       COALESCE(v.revision, t.revision) AS revision
FROM (
    SELECT r.tenant_key, r.catalog_id, r.version_id, r.version, r.resource_id, c.payload,
           r.created_at, r.updated_at, r.created_by, r.updated_by, r.revision
    FROM catalog_version_resources('template') r
    CROSS JOIN LATERAL (
        SELECT c.payload FROM resource_contents c WHERE c.tenant_key = r.tenant_key AND c.content_hash = r.content_hash OFFSET 0
    ) c
) t
CROSS JOIN LATERAL jsonb_array_elements(COALESCE(t.payload -> 'resource' -> 'variants', '[]'::jsonb)) WITH ORDINALITY AS e(slot, position)
-- Each variant is a row of its own, naming its template's row: its content and revision.
LEFT JOIN LATERAL (
    SELECT p.content_hash, c.payload, p.created_at, p.updated_at, p.created_by, p.updated_by, p.revision
    FROM release_resources p
    JOIN resource_contents c ON c.tenant_key = p.tenant_key AND c.content_hash = p.content_hash
    WHERE p.tenant_key = t.tenant_key AND p.parent_id = t.resource_id AND p.resource_key = e.slot ->> 'key'
      AND p.catalog_id = t.catalog_id AND p.resource_type = 'variant'
      AND p.version_id = t.version_id
    OFFSET 0
) v ON TRUE
LEFT JOIN LATERAL (
    SELECT c.payload
    FROM jsonb_array_elements(t.payload -> 'resource' -> 'variants') x(slot)
    JOIN release_resources p ON p.tenant_key = t.tenant_key AND p.parent_id = t.resource_id AND p.resource_key = x.slot ->> 'key'
      AND p.catalog_id = t.catalog_id AND p.resource_type = 'variant'
      AND p.version_id = t.version_id
    JOIN resource_contents c ON c.tenant_key = p.tenant_key AND c.content_hash = p.content_hash
    WHERE CAST(x.slot ->> 'isDefault' AS BOOLEAN) AND v.content_hash IS NOT NULL AND v.payload -> 'resource' -> 'templateModel' IS NULL
    OFFSET 0
) d ON TRUE
LEFT JOIN LATERAL (
    SELECT x.model_hash, (SELECT c.payload FROM resource_contents c WHERE c.tenant_key = t.tenant_key AND c.content_hash = x.model_hash) AS model
    FROM (SELECT COALESCE(e.slot -> 'templateModel' ->> 'revisionDigest', t.payload -> 'resource' -> 'templateModel' ->> 'revisionDigest') AS model_hash) x
    WHERE v.content_hash IS NULL
    OFFSET 0
) m ON TRUE;

COMMENT ON VIEW current_template_variants IS
    'The variants of the templates of the version a caller means, each its own row (storage type variant) with its own content and revision.';
