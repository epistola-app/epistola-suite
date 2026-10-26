-- ADR 0036, step (e): the catalog's current release is a query over its released rows, not a pointer.
--
-- `catalogs.released_version`, `released_fingerprint` and `released_at` named the highest release cut
-- here, kept by hand by releasing, deleting a release and restoring a snapshot, with no foreign key:
-- they could drift. `catalog_current_releases` gives the same answer from the rows themselves, so it
-- cannot. A received release is the catalog's installed one, not its current release cut here.

CREATE VIEW catalog_current_releases AS
SELECT DISTINCT ON (v.tenant_key, v.catalog_id)
       v.tenant_key, v.catalog_id, v.version_id, v.version, v.fingerprint, v.released_at
FROM catalog_versions v
WHERE v.state = 'released' AND v.origin = 'authored'
ORDER BY v.tenant_key, v.catalog_id,
         v.version_major DESC NULLS LAST, v.version_minor DESC NULLS LAST, v.version_patch DESC NULLS LAST,
         (v.version_label IS NULL) DESC, v.version_label COLLATE "C" DESC NULLS LAST,
         v.version_label_number DESC NULLS LAST, v.released_at DESC;

COMMENT ON VIEW catalog_current_releases IS
    'Per catalog, its highest release cut here (ADR 0036): what catalogs.released_version and its companions pointed at.';

-- Which releases were cut here, said by what recorded them rather than guessed from the installation
-- that cut them (step (a) compared `released_by_installation`, which a release received without one,
-- or cut before installations had an id, leaves unknown): installing notes where it came from, and
-- the old pointer names the current release cut here.
UPDATE catalog_versions v
SET origin = CASE
        WHEN EXISTS (SELECT 1 FROM catalogs c WHERE c.tenant_key = v.tenant_key AND c.catalog_id = v.catalog_id AND c.released_version = v.version)
            THEN 'authored'
        WHEN v.notes LIKE 'Installed from %' THEN 'installed'
        ELSE 'authored'
    END
WHERE v.state = 'released';

ALTER TABLE catalogs DROP COLUMN released_version, DROP COLUMN released_fingerprint, DROP COLUMN released_at;

-- "Next" is `catalogs.next_version_id` only ------------------------------------------------------
--
-- Since step (a) the pointer was kept in step with `catalog_versions.is_next` by a trigger. The
-- pointer is now the one place: the flag, its trigger and its one-per-catalog index go, and the two
-- checks that read the flag become one deferred check across both tables: the next version is a draft
-- of its catalog, and a draft without a name is its catalog's next (every other draft is named).

-- A subscribed catalog's next version follows its installed release, through the pointer.
CREATE OR REPLACE FUNCTION catalog_installed_version() RETURNS TRIGGER AS $$
DECLARE
    created UUID;
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.next_version_id IS NULL THEN
            INSERT INTO catalog_versions (tenant_key, catalog_id, state, base_version)
            VALUES (NEW.tenant_key, NEW.catalog_id, 'draft', NEW.installed_release_version)
            RETURNING version_id INTO created;
            UPDATE catalogs SET next_version_id = created
            WHERE tenant_key = NEW.tenant_key AND catalog_id = NEW.catalog_id;
        END IF;
    ELSE
        UPDATE catalog_versions
        SET base_version = NEW.installed_release_version, created_at = NOW(), base_fingerprints = NULL
        WHERE tenant_key = NEW.tenant_key AND version_id = NEW.next_version_id;
        DELETE FROM catalog_draft_merges m
        WHERE m.tenant_key = NEW.tenant_key AND m.version_id = NEW.next_version_id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- The version a caller means: the targeted release, else the targeted draft, else the catalog's next.
CREATE OR REPLACE VIEW catalog_current_versions AS
SELECT DISTINCT ON (tenant_key, catalog_id) tenant_key, catalog_id, version_id
FROM catalog_versions v
WHERE CASE
          WHEN catalog_id = NULLIF(current_setting('epistola.release_catalog', true), '')::uuid
              THEN state = 'released' AND version = current_setting('epistola.release_version', true)
          ELSE version_id::text = current_setting('epistola.content_version', true)
              OR version_id = (SELECT c.next_version_id FROM catalogs c WHERE c.tenant_key = v.tenant_key AND c.catalog_id = v.catalog_id)
      END
ORDER BY tenant_key, catalog_id, ((version_id::text = current_setting('epistola.content_version', true)) IS TRUE) DESC;

DROP TRIGGER trg_catalog_versions_next_pointer ON catalog_versions;
DROP FUNCTION catalog_next_version_pointer();
DROP INDEX uq_catalog_versions_one_next;
ALTER TABLE catalog_versions DROP CONSTRAINT chk_catalog_versions_long_term_named;
ALTER TABLE catalog_versions DROP CONSTRAINT chk_catalog_versions_next_is_draft;
ALTER TABLE catalog_versions DROP COLUMN is_next;

-- One check, at commit, so a release and the next version that continues on it can move the pointer
-- in two statements.
CREATE FUNCTION catalog_next_version_check() RETURNS TRIGGER AS $$
BEGIN
    -- Checked against the state at commit, not the row as it was at the event: one transaction may
    -- move the pointer more than once (a release, a hand-over).
    IF TG_TABLE_NAME = 'catalogs' THEN
        IF EXISTS (
            SELECT 1 FROM catalogs c
            WHERE c.tenant_key = NEW.tenant_key AND c.catalog_id = NEW.catalog_id AND c.next_version_id IS NOT NULL
              AND NOT EXISTS (SELECT 1 FROM catalog_versions v
                              WHERE v.tenant_key = c.tenant_key AND v.version_id = c.next_version_id
                                AND v.catalog_id = c.catalog_id AND v.state = 'draft')
        ) THEN
            RAISE EXCEPTION 'The next version of catalog % is not one of its drafts', NEW.catalog_id
                USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    IF EXISTS (
        SELECT 1 FROM catalog_versions v
        WHERE v.tenant_key = NEW.tenant_key AND v.catalog_id = NEW.catalog_id
          AND v.state = 'draft' AND v.name IS NULL
          AND v.version_id IS DISTINCT FROM (SELECT c.next_version_id FROM catalogs c
                                             WHERE c.tenant_key = v.tenant_key AND c.catalog_id = v.catalog_id)
    ) THEN
        RAISE EXCEPTION 'A draft of catalog % without a name is not its next version', NEW.catalog_id
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER trg_catalog_versions_next_check
    AFTER INSERT OR UPDATE OF state, name ON catalog_versions
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION catalog_next_version_check();
CREATE CONSTRAINT TRIGGER trg_catalogs_next_check
    AFTER INSERT OR UPDATE OF next_version_id ON catalogs
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION catalog_next_version_check();
