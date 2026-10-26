-- ADR 0036, step (a): one row per catalog version, draft or released.
--
-- A catalog version is one concept with one id from the moment it exists (contract ADR 0002: "The
-- draft becomes released at that version ... Its releaseId and its name stay"). catalog_versions
-- held the drafts and catalog_releases the releases; from here catalog_versions holds both, and
-- releasing a draft freezes its row instead of writing a release beside it.
--
-- This step adds the release to catalog_versions and keeps catalog_releases as a mirror of the
-- released rows, so that every reader keyed by (catalog_id, version) keeps working until the later
-- steps re-key them (ADR 0036, build order (b) to (e)); the mirror and catalog_releases go then.

-- 1. A version's state: `kind` was always 'draft' (ADR 0032); it is now `state`, draft or released.
ALTER TABLE catalog_versions RENAME COLUMN kind TO state;
ALTER TABLE catalog_versions
    DROP CONSTRAINT chk_catalog_versions_kind,
    DROP CONSTRAINT chk_catalog_versions_numbered,
    DROP CONSTRAINT chk_catalog_versions_next_is_draft,
    DROP CONSTRAINT chk_catalog_versions_long_term_named;

-- 2. The release record, on the row.
ALTER TABLE catalog_versions
    ADD COLUMN origin                     VARCHAR(16) NOT NULL DEFAULT 'authored',
    ADD COLUMN base_version_id            UUID,
    ADD COLUMN base_fingerprint           VARCHAR(80),
    ADD COLUMN fingerprint                VARCHAR(80),
    ADD COLUMN content_hash               VARCHAR(80),
    ADD COLUMN notes                      TEXT,
    ADD COLUMN manifest_snapshot          JSONB,
    ADD COLUMN rendering_defaults_version INTEGER,
    ADD COLUMN released_at                TIMESTAMPTZ,
    ADD COLUMN released_by                UUID,
    ADD COLUMN released_by_installation   UUID;

ALTER TABLE catalog_versions
    ADD CONSTRAINT chk_catalog_versions_state CHECK (state IN ('draft', 'released')),
    ADD CONSTRAINT chk_catalog_versions_origin CHECK (origin IN ('authored', 'installed')),
    -- A released version has its number and record; a draft has none of them.
    ADD CONSTRAINT chk_catalog_versions_released CHECK (
        (state = 'released') = (version IS NOT NULL AND fingerprint IS NOT NULL AND content_hash IS NOT NULL AND released_at IS NOT NULL)),
    ADD CONSTRAINT chk_catalog_versions_draft_unnumbered CHECK (state = 'released' OR version IS NULL),
    -- An installed version is a release: a subscribed catalog's next draft is authored here.
    ADD CONSTRAINT chk_catalog_versions_installed_is_released CHECK (origin <> 'installed' OR state = 'released'),
    ADD CONSTRAINT chk_catalog_versions_next_is_draft CHECK (NOT is_next OR state = 'draft'),
    ADD CONSTRAINT chk_catalog_versions_long_term_named CHECK (state <> 'draft' OR is_next OR name IS NOT NULL),
    -- The version this one was made from, by its row: lineage, not ownership, so it survives the
    -- base being deleted (base_version and base_fingerprint keep what it was).
    ADD CONSTRAINT fk_catalog_versions_base FOREIGN KEY (tenant_key, base_version_id)
        REFERENCES catalog_versions (tenant_key, version_id) ON DELETE SET NULL (base_version_id);

COMMENT ON COLUMN catalog_versions.state IS 'draft or released (ADR 0036): releasing a draft freezes its row.';
COMMENT ON COLUMN catalog_versions.origin IS 'authored here, or installed: received from an archive, a registry or the Exchange.';
COMMENT ON COLUMN catalog_versions.base_version_id IS 'The version this one was made from, while it exists; base_version and base_fingerprint name it by number and hash.';

-- 3. Every release becomes a released row, keeping its id and number. A release whose installation
-- is not this one was received (RecordInstalledRelease names the publisher's).
INSERT INTO catalog_versions (tenant_key, catalog_id, version_key, state, origin, version, base_version, base_fingerprint,
                              fingerprint, content_hash, notes, manifest_snapshot, rendering_defaults_version,
                              released_at, released_by, released_by_installation, created_at)
SELECT r.tenant_key, r.catalog_id, r.version_key, 'released',
       CASE WHEN r.released_by_installation IS NOT DISTINCT FROM (
                SELECT CAST(m.value ->> 'id' AS UUID) FROM app_metadata m WHERE m.key = 'installation')
            THEN 'authored' ELSE 'installed' END,
       r.version, r.base_version, r.base_fingerprint, r.fingerprint, r.content_hash, r.notes, r.manifest_snapshot,
       r.rendering_defaults_version, r.released_at, r.released_by, r.released_by_installation, r.released_at
FROM catalog_releases r;

UPDATE catalog_versions v SET base_version_id = b.version_id
FROM catalog_versions b
WHERE b.tenant_key = v.tenant_key AND b.catalog_id = v.catalog_id AND b.version = v.base_version AND b.state = 'released'
  AND v.base_version IS NOT NULL;

-- 4. "Next" is a property of the catalog: at most one draft, possibly none.
ALTER TABLE catalogs ADD COLUMN next_version_id UUID;
ALTER TABLE catalogs ADD CONSTRAINT fk_catalogs_next_version FOREIGN KEY (tenant_key, next_version_id)
    REFERENCES catalog_versions (tenant_key, version_id) ON DELETE SET NULL (next_version_id);
COMMENT ON COLUMN catalogs.next_version_id IS 'The catalog''s next draft (ADR 0036); kept from catalog_versions.is_next until that goes.';

UPDATE catalogs c SET next_version_id = v.version_id
FROM catalog_versions v
WHERE v.tenant_key = c.tenant_key AND v.catalog_id = c.catalog_id AND v.is_next;

CREATE FUNCTION catalog_next_version_pointer() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    changed RECORD;
BEGIN
    changed := CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
    UPDATE catalogs c
    SET next_version_id = (SELECT v.version_id FROM catalog_versions v
                           WHERE v.tenant_key = c.tenant_key AND v.catalog_id = c.catalog_id AND v.is_next)
    WHERE c.tenant_key = changed.tenant_key AND c.catalog_id = changed.catalog_id
      AND c.next_version_id IS DISTINCT FROM (SELECT v.version_id FROM catalog_versions v
                                              WHERE v.tenant_key = c.tenant_key AND v.catalog_id = c.catalog_id AND v.is_next);
    RETURN NULL;
END;
$$;

CREATE TRIGGER trg_catalog_versions_next_pointer
    AFTER INSERT OR DELETE OR UPDATE OF is_next ON catalog_versions
    FOR EACH ROW EXECUTE FUNCTION catalog_next_version_pointer();

-- 5. catalog_releases mirrors the released rows until the readers keyed by number are re-keyed. A
-- release recorded for a draft that is being released (the same id) is that draft's row, which the
-- release path freezes itself; any other release (installed, restored) becomes a released row here.
CREATE FUNCTION catalog_release_mirror() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        DELETE FROM catalog_versions
        WHERE tenant_key = OLD.tenant_key AND catalog_id = OLD.catalog_id AND version = OLD.version AND state = 'released';
        RETURN OLD;
    ELSIF TG_OP = 'UPDATE' THEN
        UPDATE catalog_versions
        SET version = NEW.version, fingerprint = NEW.fingerprint, content_hash = NEW.content_hash, notes = NEW.notes,
            manifest_snapshot = NEW.manifest_snapshot, rendering_defaults_version = NEW.rendering_defaults_version,
            released_at = NEW.released_at, released_by = NEW.released_by, released_by_installation = NEW.released_by_installation,
            base_version = NEW.base_version, base_fingerprint = NEW.base_fingerprint, version_key = NEW.version_key
        WHERE tenant_key = OLD.tenant_key AND catalog_id = OLD.catalog_id AND version = OLD.version AND state = 'released';
        RETURN NEW;
    END IF;
    IF EXISTS (SELECT 1 FROM catalog_versions v
               WHERE v.tenant_key = NEW.tenant_key AND v.catalog_id = NEW.catalog_id AND v.version_key = NEW.version_key) THEN
        RETURN NEW;
    END IF;
    INSERT INTO catalog_versions (tenant_key, catalog_id, version_key, state, origin, version, base_version, base_version_id,
                                  base_fingerprint, fingerprint, content_hash, notes, manifest_snapshot,
                                  rendering_defaults_version, released_at, released_by, released_by_installation, created_at)
    VALUES (NEW.tenant_key, NEW.catalog_id, NEW.version_key, 'released',
            CASE WHEN NEW.released_by_installation IS NOT DISTINCT FROM (
                     SELECT CAST(m.value ->> 'id' AS UUID) FROM app_metadata m WHERE m.key = 'installation')
                 THEN 'authored' ELSE 'installed' END,
            NEW.version, NEW.base_version,
            (SELECT b.version_id FROM catalog_versions b
             WHERE b.tenant_key = NEW.tenant_key AND b.catalog_id = NEW.catalog_id AND b.version = NEW.base_version AND b.state = 'released'),
            NEW.base_fingerprint, NEW.fingerprint, NEW.content_hash, NEW.notes, NEW.manifest_snapshot,
            NEW.rendering_defaults_version, NEW.released_at, NEW.released_by, NEW.released_by_installation, NEW.released_at);
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_catalog_releases_mirror
    AFTER INSERT OR UPDATE OR DELETE ON catalog_releases
    FOR EACH ROW EXECUTE FUNCTION catalog_release_mirror();

-- 6. A subscribed catalog's next draft is opened by the catalog's trigger, now in the state column.
CREATE OR REPLACE FUNCTION catalog_installed_version() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        INSERT INTO catalog_versions (tenant_key, catalog_id, state, is_next, base_version)
        SELECT NEW.tenant_key, NEW.catalog_id, 'draft', TRUE, NEW.installed_release_version
        WHERE NOT EXISTS (
            SELECT 1 FROM catalog_versions v
            WHERE v.tenant_key = NEW.tenant_key AND v.catalog_id = NEW.catalog_id AND v.is_next
        );
    ELSE
        UPDATE catalog_versions
        SET base_version = NEW.installed_release_version, created_at = NOW(), base_fingerprints = NULL
        WHERE tenant_key = NEW.tenant_key AND catalog_id = NEW.catalog_id AND is_next;
        DELETE FROM catalog_draft_merges m
        USING catalog_versions v
        WHERE v.tenant_key = NEW.tenant_key AND v.catalog_id = NEW.catalog_id AND v.is_next
          AND m.tenant_key = v.tenant_key AND m.version_id = v.version_id;
    END IF;
    RETURN NEW;
END;
$$;
