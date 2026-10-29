-- The 2.0 upgrade, step 15 of 16 (docs/migrations.md, "The 2.0 chain"): the read model.
--
-- The functions and views every reader of a catalog version reads through, in their final form: which
-- version a caller means (catalog_current_versions), the rows of one resource type of it
-- (catalog_version_resources), and per type its resources as their JSON content (current_*). And the
-- functions step 16's triggers keep the storage tables consistent with.

-- 1. Trigger functions ------------------------------------------------------------------------------

-- Only a change counts. Bookkeeping does not: when it was saved and by whom, and the columns a
-- trigger passes as arguments (a variant's is_default, which making another variant the default
-- clears without touching this one's content). An update that changes nothing else keeps the
-- revision, so saving unchanged content never makes someone else's open copy stale. A write that
-- changed rows hanging off this one (a code list's entries) raises it itself, by setting
-- revision = revision + 1, which is kept.
CREATE FUNCTION bump_content_revision() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    ignored TEXT[] := ARRAY['revision', 'updated_at', 'updated_by'] || TG_ARGV;
BEGIN
    IF NEW.revision = OLD.revision + 1 THEN
        RETURN NEW;
    END IF;
    IF (to_jsonb(NEW) - ignored) IS DISTINCT FROM (to_jsonb(OLD) - ignored) THEN
        NEW.revision := OLD.revision + 1;
    ELSE
        NEW.revision := OLD.revision;
    END IF;
    RETURN NEW;
END;
$$;

COMMENT ON FUNCTION bump_content_revision() IS 'BEFORE UPDATE trigger function for editable content tables: raises revision by one when anything but bookkeeping (revision, updated_at, updated_by, and the columns given as trigger arguments) changed, so a write that names the revision it read can tell whether the row changed since.';

CREATE FUNCTION bump_catalog_version_revision() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.revision = OLD.revision + 1 THEN
        RETURN NEW;
    END IF;
    IF (NEW.name, NEW.default_theme_resource_id, NEW.presets) IS DISTINCT FROM (OLD.name, OLD.default_theme_resource_id, OLD.presets) THEN
        NEW.revision := OLD.revision + 1;
    ELSE
        NEW.revision := OLD.revision;
    END IF;
    RETURN NEW;
END;
$$;

COMMENT ON FUNCTION bump_catalog_version_revision() IS 'BEFORE UPDATE trigger function for catalog_versions: raises revision by one when the name, default theme or presets changed; a raise by one the update made itself (a dependency change) is kept.';

CREATE FUNCTION bump_catalog_version_revision_for_dependency() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    changed RECORD;
BEGIN
    changed := CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
    UPDATE catalog_versions SET revision = revision + 1
    WHERE tenant_key = changed.tenant_key AND version_id = changed.version_id;
    IF TG_OP = 'UPDATE' AND OLD.version_id IS DISTINCT FROM NEW.version_id THEN
        UPDATE catalog_versions SET revision = revision + 1
        WHERE tenant_key = OLD.tenant_key AND version_id = OLD.version_id;
    END IF;
    RETURN NULL;
END;
$$;

COMMENT ON FUNCTION bump_catalog_version_revision_for_dependency() IS 'AFTER trigger function for catalog_dependencies: a declaration added, changed or removed raises the revision of the catalog version that declares it.';

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

COMMENT ON FUNCTION touch_catalog_content_on_delete() IS 'AFTER DELETE trigger function for catalog-scoped resource tables: bumps catalogs.content_updated_at so the drift signal sees a deletion. Requires tenant_key and catalog_id on the row.';

-- 5. A document or a generation request that names its template by address finds its identity in
--    the registry, which holds every template's, whichever version holds it.
CREATE OR REPLACE FUNCTION fill_generation_template_identity() RETURNS TRIGGER AS $$
BEGIN
    IF NEW.template_resource_id IS NULL THEN
        SELECT r.resource_id INTO NEW.template_resource_id
        FROM catalog_resources r
        JOIN catalogs c ON c.tenant_key = r.tenant_key AND c.catalog_id = r.catalog_id
        WHERE r.tenant_key = NEW.tenant_key
          AND r.resource_type = 'template'
          AND c.registry = NEW.registry
          AND c.namespace = NEW.namespace
          AND c.catalog_key = NEW.catalog_key
          AND r.resource_key = NEW.template_key;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'no template % in catalog %/%/% for tenant %',
                NEW.template_key, NEW.registry, NEW.namespace, NEW.catalog_key, NEW.tenant_key;
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- At most one draft of a catalog per environment. A partial index cannot see the row's state, so a
-- trigger checks it when a deployment is written. Releasing a deployed draft only removes a draft.
CREATE FUNCTION epistola_one_deployed_draft() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM catalog_versions v
               WHERE v.tenant_key = NEW.tenant_key AND v.version_id = NEW.target_version_id AND v.state = 'draft')
       AND EXISTS (SELECT 1 FROM environment_catalog_deployments d
                   JOIN catalog_versions o ON o.tenant_key = d.tenant_key AND o.version_id = d.target_version_id
                   WHERE d.tenant_key = NEW.tenant_key AND d.environment_key = NEW.environment_key
                     AND d.catalog_id = NEW.catalog_id AND d.id <> NEW.id AND o.state = 'draft') THEN
        RAISE EXCEPTION 'environment % already serves a draft of this catalog', NEW.environment_key
            USING ERRCODE = 'unique_violation';
    END IF;
    RETURN NEW;
END;
$$;

-- A catalog opens its next version on no release; receiving one moves it there.
CREATE FUNCTION catalog_open_next_version() RETURNS TRIGGER AS $$
DECLARE
    created UUID;
BEGIN
    IF NEW.next_version_id IS NULL THEN
        INSERT INTO catalog_versions (tenant_key, catalog_id, state)
        VALUES (NEW.tenant_key, NEW.catalog_id, 'draft')
        RETURNING version_id INTO created;
        UPDATE catalogs SET next_version_id = created
        WHERE tenant_key = NEW.tenant_key AND catalog_id = NEW.catalog_id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

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

-- Starting a draft copies the version's declarations, as before.
CREATE FUNCTION copy_catalog_version_content(p_tenant TEXT, p_from UUID, p_to UUID) RETURNS VOID LANGUAGE plpgsql AS $$
DECLARE
    t TEXT;
    columns TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['release_resources', 'resource_ready_marks', 'code_list_refreshes', 'catalog_version_dependencies']
    LOOP
        SELECT string_agg(quote_ident(column_name), ', ' ORDER BY ordinal_position) INTO columns
        FROM information_schema.columns
        WHERE table_schema = current_schema() AND table_name = t AND is_generated = 'NEVER' AND column_name <> 'version_id';
        EXECUTE format('INSERT INTO %I (%s, version_id) SELECT %s, $2 FROM %I WHERE tenant_key = $1 AND version_id = $3', t, columns, columns, t)
            USING p_tenant, p_to, p_from;
    END LOOP;
END;
$$;

COMMENT ON FUNCTION copy_catalog_version_content(p_tenant text, p_from uuid, p_to uuid) IS 'Copies the content of one catalog version into another of the same catalog, keeping resource identities.';

-- 2. Which version a caller means, and what it holds -------------------------------------------------

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

-- The version a caller means: the targeted release, else the targeted draft, else the catalog's next.
CREATE VIEW catalog_current_versions AS
SELECT DISTINCT ON (tenant_key, catalog_id) tenant_key, catalog_id, version_id
FROM catalog_versions v
WHERE CASE
          WHEN catalog_id = NULLIF(current_setting('epistola.release_catalog', true), '')::uuid
              THEN state = 'released' AND version = current_setting('epistola.release_version', true)
          ELSE version_id::text = current_setting('epistola.content_version', true)
              OR version_id = (SELECT c.next_version_id FROM catalogs c WHERE c.tenant_key = v.tenant_key AND c.catalog_id = v.catalog_id)
      END
ORDER BY tenant_key, catalog_id, ((version_id::text = current_setting('epistola.content_version', true)) IS TRUE) DESC;

COMMENT ON VIEW catalog_current_versions IS 'Per catalog, the version a caller means: the one its transaction targets (epistola.content_version), else its next version.';

CREATE VIEW catalog_current_releases AS
SELECT DISTINCT ON (v.tenant_key, v.catalog_id)
       v.tenant_key, v.catalog_id, v.version_id, v.version, v.fingerprint, v.released_at
FROM catalog_versions v
WHERE v.state = 'released'
ORDER BY v.tenant_key, v.catalog_id,
         v.version_major DESC NULLS LAST, v.version_minor DESC NULLS LAST, v.version_patch DESC NULLS LAST,
         (v.version_label IS NULL) DESC, v.version_label COLLATE "C" DESC NULLS LAST,
         v.version_label_number DESC NULLS LAST, v.released_at DESC;

COMMENT ON VIEW catalog_current_releases IS 'Per catalog, its highest release (ADR 0036), released here or received: a release is a release however it arrived.';

-- 5. A variant and its template are of one version: its row.
CREATE FUNCTION release_resource_variant_parent() RETURNS trigger LANGUAGE plpgsql AS $$
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

CREATE FUNCTION release_resource_variants_deleted() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    DELETE FROM release_resources v
    WHERE v.tenant_key = OLD.tenant_key AND v.catalog_id = OLD.catalog_id AND v.parent_id = OLD.resource_id
      AND v.version_id = OLD.version_id;
    RETURN NULL;
END;
$$;

CREATE FUNCTION release_resource_variants_moved() RETURNS trigger LANGUAGE plpgsql AS $$
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

-- A resource moved or renamed in one draft while other drafts of its catalog hold it too (ADR 0042):
-- the identity goes with the move, and the copies left behind, which stay at the old address, are
-- given an identity of their own there, with what each draft keeps of it (its ready marks, its code
-- list refreshes, its default theme). A release is frozen at the address and identity it had.
CREATE FUNCTION split_draft_resource_identity(p_tenant TEXT, p_resource UUID, p_version UUID, p_catalog UUID, p_key TEXT)
    RETURNS UUID LANGUAGE plpgsql AS $$
DECLARE
    left_behind UUID := epistola_uuidv7();
    registered catalog_resources%ROWTYPE;
    others UUID[];
BEGIN
    SELECT array_agg(DISTINCT version_id) INTO others FROM release_resources
    WHERE tenant_key = p_tenant AND resource_id = p_resource AND parent_id IS NULL AND version_id <> p_version
      AND release_resource_is_draft(tenant_key, version_id);
    SELECT * INTO registered FROM catalog_resources WHERE tenant_key = p_tenant AND resource_id = p_resource;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'catalog resource % has no identity row', p_resource;
    END IF;

    -- The identity leaves the old address first, so the copies left behind can be registered there.
    UPDATE catalog_resources SET catalog_id = p_catalog, resource_key = p_key
    WHERE tenant_key = p_tenant AND resource_id = p_resource;
    INSERT INTO catalog_resources (tenant_key, resource_id, resource_type, catalog_id, resource_key)
    VALUES (p_tenant, left_behind, registered.resource_type, registered.catalog_id, registered.resource_key);

    -- Re-keying a row is refused by sync_draft_resource_identity unless it is this split's.
    PERFORM set_config('epistola.identity_split', left_behind::text, TRUE);
    UPDATE release_resources SET resource_id = left_behind
    WHERE tenant_key = p_tenant AND resource_id = p_resource AND parent_id IS NULL AND version_id = ANY (others);
    UPDATE release_resources SET parent_id = left_behind, resource_id = variant_resource_id(left_behind, resource_key)
    WHERE tenant_key = p_tenant AND parent_id = p_resource AND version_id = ANY (others);
    PERFORM set_config('epistola.identity_split', '', TRUE);

    UPDATE resource_ready_marks SET resource_id = left_behind
    WHERE tenant_key = p_tenant AND resource_id = p_resource AND version_id = ANY (others);
    UPDATE code_list_refreshes SET resource_id = left_behind
    WHERE tenant_key = p_tenant AND resource_id = p_resource AND version_id = ANY (others);
    UPDATE catalog_versions SET default_theme_resource_id = left_behind
    WHERE tenant_key = p_tenant AND default_theme_resource_id = p_resource AND version_id = ANY (others);
    RETURN left_behind;
END;
$$;

COMMENT ON FUNCTION split_draft_resource_identity(TEXT, UUID, UUID, UUID, TEXT) IS
    'Moves identity p_resource to p_catalog/p_key with the row of draft p_version, and gives the rows other drafts hold at the old address an identity of their own there (ADR 0042); returns it.';

-- A draft row's identity: registered per address, carried along by a move or rename (split from the
-- copies other drafts keep, split_draft_resource_identity), and taken along by the last row holding it.
CREATE FUNCTION sync_draft_resource_identity() RETURNS trigger LANGUAGE plpgsql AS $$

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
        IF current_setting('epistola.identity_split', TRUE) IS DISTINCT FROM NEW.resource_id::text THEN
            RAISE EXCEPTION 'catalog resource_id is immutable';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.catalog_id IS DISTINCT FROM OLD.catalog_id OR NEW.resource_key IS DISTINCT FROM OLD.resource_key THEN
        SELECT EXISTS (SELECT 1 FROM release_resources WHERE tenant_key = OLD.tenant_key AND resource_id = OLD.resource_id
                       AND parent_id IS NULL AND version_id <> OLD.version_id AND release_resource_is_draft(tenant_key, version_id))
            INTO held_elsewhere;
        IF held_elsewhere THEN
            PERFORM split_draft_resource_identity(OLD.tenant_key, OLD.resource_id, OLD.version_id, NEW.catalog_id, NEW.resource_key);
            RETURN NEW;
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

COMMENT ON FUNCTION sync_draft_resource_identity() IS 'Keeps catalog_resources for the rows of a draft in release_resources (ADR 0033): an identity registered per address, carried along by a move or rename (the copies other drafts keep given one of their own, ADR 0042), and taken along by the last row holding it.';

-- An alias resolves through what the version read declares: a draft's declarations or a release's
-- pins are both its row's (one branch, ADR 0036 section 7).
CREATE FUNCTION resolve_catalog_alias(p_tenant TEXT, p_catalog UUID, p_version_id UUID, p_version TEXT, p_alias TEXT)
    RETURNS UUID
    LANGUAGE sql
    STABLE
AS $$
SELECT CASE
    WHEN p_alias IS NULL OR p_alias = (SELECT c.catalog_key FROM catalogs c WHERE c.tenant_key = p_tenant AND c.catalog_id = p_catalog)
        THEN p_catalog
    ELSE (SELECT epistola_catalog_id(p_tenant, d.namespace, d.dependency_key) FROM catalog_version_dependencies d
          WHERE d.tenant_key = p_tenant AND d.alias = p_alias
            AND d.version_id = COALESCE(p_version_id, (SELECT v.version_id FROM catalog_versions v
                                                       WHERE v.tenant_key = p_tenant AND v.catalog_id = p_catalog
                                                         AND v.version = p_version AND v.state = 'released')))
END
$$;

COMMENT ON FUNCTION resolve_catalog_alias(p_tenant text, p_catalog uuid, p_version_id uuid, p_version text, p_alias text) IS 'The catalog an alias in content means, from a catalog''s draft or release, by its declarations (ADR 0033, ADR 0034 section 9); NULL when none declares it. The one place aliases are resolved.';

-- The theme a release's manifest names as its default, by identity; NULL when it names none or no
-- theme of that catalog is registered here.
CREATE FUNCTION catalog_release_default_theme(p_tenant TEXT, p_catalog UUID, p_version TEXT, p_manifest JSONB)
    RETURNS UUID
    LANGUAGE sql
    STABLE
AS $$
SELECT r.resource_id FROM catalog_resources r
WHERE p_manifest #>> '{catalog,defaultTheme,key}' IS NOT NULL
  AND r.tenant_key = p_tenant AND r.resource_type = 'theme'
  AND r.catalog_id = resolve_catalog_alias(p_tenant, p_catalog, NULL, p_version, p_manifest #>> '{catalog,defaultTheme,catalogKey}')
  AND r.resource_key = p_manifest #>> '{catalog,defaultTheme,key}'
$$;

COMMENT ON FUNCTION catalog_release_default_theme(p_tenant text, p_catalog uuid, p_version text, p_manifest jsonb) IS 'A release''s default theme by identity, from its manifest (ADR 0036): its alias resolved through the release''s declarations; NULL when none is named or none is registered.';

-- The presets a release's manifest declares, in the form a draft holds them (by key).
CREATE FUNCTION catalog_release_presets(p_manifest JSONB)
    RETURNS JSONB
    LANGUAGE sql
    IMMUTABLE
AS $$
SELECT COALESCE(
    (SELECT jsonb_agg(jsonb_build_object('key', p ->> 'key', 'name', p ->> 'name', 'description', p -> 'description') ORDER BY p ->> 'key')
     FROM jsonb_array_elements(CASE WHEN jsonb_typeof(p_manifest #> '{catalog,presets}') = 'array' THEN p_manifest #> '{catalog,presets}' ELSE '[]'::jsonb END) p),
    '[]'::jsonb)
$$;

COMMENT ON FUNCTION catalog_release_presets(p_manifest jsonb) IS 'A release''s declared presets, from its manifest, as a draft holds them (ADR 0036).';

CREATE FUNCTION catalog_version_resources(p_type TEXT)
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

COMMENT ON FUNCTION catalog_version_resources(p_type text) IS 'Per catalog, the resource rows of one type of the version a caller means: the release its transaction targets (epistola.release_catalog, epistola.release_version), else the draft catalog_current_versions names.';

-- 5. What the resources of one type of the versions a caller means refer to, resolved: each row of
--    catalog_version_resources(type) with the references its content holds, and the catalog each
--    names. "Used by" and "in use?" read this.
CREATE FUNCTION catalog_version_references(p_type TEXT)
    RETURNS TABLE (
        tenant_key      tenant_key,
        catalog_id      UUID,
        version_id      UUID,
        version         VARCHAR(128),
        resource_key    TEXT,
        resource_id     UUID,
        ref_alias       VARCHAR(50),
        ref_type        VARCHAR(20),
        ref_key         TEXT,
        ref_catalog_id  UUID
    )
    LANGUAGE sql
    STABLE
AS $$
SELECT r.tenant_key, r.catalog_id, r.version_id, r.version, r.resource_key, r.resource_id,
       x.alias, x.resource_type, x.resource_key,
       resolve_catalog_alias(r.tenant_key, r.catalog_id, r.version_id, r.version, x.alias)
FROM catalog_version_resources(p_type) r
CROSS JOIN LATERAL (
    SELECT * FROM resource_references x WHERE x.tenant_key = r.tenant_key AND x.content_hash = r.content_hash OFFSET 0
) x
$$;

COMMENT ON FUNCTION catalog_version_references(p_type text) IS 'The references of one resource type''s rows of the versions a caller means, each with the catalog it names (resolve_catalog_alias).';

-- 8. The views that read the number, again: the number from the version's row.
CREATE VIEW release_wire_resources AS
SELECT r.tenant_key, r.catalog_id, r.version_id, v.version, r.resource_type, r.resource_key, r.resource_id, r.content_hash,
       r.fingerprint, r.name, r.description, r.revision, r.created_at, r.updated_at, r.created_by, r.updated_by
FROM release_resources r
JOIN catalog_versions v ON v.tenant_key = r.tenant_key AND v.version_id = r.version_id
WHERE r.parent_id IS NULL;

COMMENT ON VIEW release_wire_resources IS 'The wire resources of every draft and release (release_resources without the variant rows), with a release''s number: what a manifest, a count or a fingerprint reads.';

-- 4. Every digest comes from a draft row now: a template's per variant (its variant row), the rest
--    the row's content hash.
CREATE VIEW resource_content_digests AS
SELECT r.tenant_key, r.resource_id, CAST(p.resource_key AS TEXT) AS part,
       template_variant_digest(r.content_hash, p.content_hash) AS digest,
       r.version_id
FROM release_resources r
JOIN release_resources p ON p.tenant_key = r.tenant_key AND p.catalog_id = r.catalog_id AND p.version_id = r.version_id
 AND p.resource_type = 'variant' AND p.parent_id = r.resource_id
WHERE r.version_id IS NOT NULL AND r.resource_type = 'template'
UNION ALL
-- A resource type stored as JSON (ADR 0033): its draft row's content hash is its digest.
SELECT r.tenant_key, r.resource_id, '',
       CAST(r.content_hash AS TEXT),
       r.version_id
FROM release_resources r
WHERE r.version_id IS NOT NULL AND r.resource_type NOT IN ('template', 'variant');

COMMENT ON VIEW resource_content_digests IS 'The stored content of every catalog resource as one digest per part (a template per variant) and catalog version; what a ready mark records.';

-- 5. The themes of a draft or a release, from their rows alone.
CREATE VIEW current_themes AS
SELECT u.*,
       -- The released form is the content's `resource` (ADR 0033: one shape for every type).
       u.payload -> 'resource' ->> 'description' AS description,
       COALESCE(u.payload -> 'resource' -> 'documentStyles', '{}'::jsonb) AS document_styles,
       NULLIF(u.payload -> 'resource' -> 'pageSettings', 'null'::jsonb) AS page_settings,
       NULLIF(u.payload -> 'resource' -> 'blockStylePresets', 'null'::jsonb) AS block_style_presets,
       CAST(u.payload -> 'resource' ->> 'spacingUnit' AS NUMERIC(6, 2)) AS spacing_unit
FROM (
    -- The content by its key, row by row, rather than a join: a join's order is the planner's to
    -- choose, and a plan made before the tables have statistics put every stored content of the
    -- tenant outermost (ThemePlanStabilityTest).
    SELECT r.tenant_key, r.catalog_id, r.version_id, r.version, r.resource_id, r.resource_key AS id, CAST(r.name AS TEXT) AS name,
           (SELECT c.payload FROM resource_contents c WHERE c.tenant_key = r.tenant_key AND c.content_hash = r.content_hash) AS payload,
           r.created_at, r.updated_at, r.created_by, r.updated_by, r.revision
    FROM catalog_version_resources('theme') r
) u;

COMMENT ON VIEW current_themes IS 'The themes of the version a caller means, each as its JSON content (payload, the wire form): of the targeted release, else of the draft catalog_current_versions names.';

-- 4. The code lists of a draft or a release, from their rows alone. `resource` is the released form.
--    Columns derived from the content are for the statements that filter or order on them.
CREATE VIEW current_code_lists AS
SELECT u.*,
       -- Its name and description from its row: reading them from the content would read the whole
       -- content, every entry of it.
       u.name AS display_name,
       u.row_description AS description,
       COALESCE(u.resource -> 'entries', '[]'::jsonb) AS entries,
       COALESCE(u.payload -> 'source' ->> 'type', 'INLINE') AS source_type,
       u.payload -> 'source' ->> 'url' AS source_url,
       COALESCE(u.payload -> 'source' ->> 'auth', 'NONE') AS auth_type,
       CAST(u.payload -> 'source' ->> 'credential' AS UUID) AS credential_id
FROM (
    SELECT p.*, p.payload -> 'resource' AS resource
    FROM (
        -- The content by its key, row by row, rather than a join (ThemePlanStabilityTest).
        SELECT r.tenant_key, r.catalog_id, r.version_id, r.version, r.resource_id, r.resource_key AS id, CAST(r.name AS TEXT) AS name,
               (SELECT c.payload FROM resource_contents c WHERE c.tenant_key = r.tenant_key AND c.content_hash = r.content_hash) AS payload,
               r.description AS row_description, r.created_at, r.updated_at, r.created_by, r.updated_by, r.revision
        FROM catalog_version_resources('codeList') r
    ) p
) u;

COMMENT ON VIEW current_code_lists IS 'The code lists of the version a caller means, each as its JSON content (payload: resource, the wire form, and source beside it).';

-- 6. The attributes of a draft or a release, from their rows alone.
CREATE VIEW current_attributes AS
SELECT u.*,
       u.name AS display_name,
       COALESCE(u.payload -> 'resource' -> 'allowedValues', '[]'::jsonb) AS allowed_values,
       u.payload -> 'resource' -> 'codeListBinding' ->> 'catalogKey' AS code_list_alias,
       u.payload -> 'resource' -> 'codeListBinding' ->> 'key' AS code_list_key,
       CASE WHEN u.payload -> 'resource' -> 'codeListBinding' ->> 'key' IS NOT NULL
            THEN resolve_catalog_alias(u.tenant_key, u.catalog_id, u.version_id, u.version, u.payload -> 'resource' -> 'codeListBinding' ->> 'catalogKey')
       END AS code_list_catalog_id
FROM (
    -- The content by its key, row by row, rather than a join (ThemePlanStabilityTest).
    SELECT r.tenant_key, r.catalog_id, r.version_id, r.version, r.resource_id, r.resource_key AS id, CAST(r.name AS TEXT) AS name,
           (SELECT c.payload FROM resource_contents c WHERE c.tenant_key = r.tenant_key AND c.content_hash = r.content_hash) AS payload,
           r.created_at, r.updated_at, r.created_by, r.updated_by, r.revision
    FROM catalog_version_resources('attribute') r
) u;

COMMENT ON VIEW current_attributes IS 'The variant attributes of the version a caller means, each as its JSON content, with its code list binding resolved (resolve_catalog_alias).';

-- 5. The fonts of a draft or a release, from their rows alone.
CREATE VIEW current_fonts AS
SELECT u.*,
       u.payload -> 'resource' ->> 'kind' AS kind,
       COALESCE(u.payload -> 'resource' -> 'variants', '[]'::jsonb) AS variants
FROM (
    -- The content by its key, row by row: a fenced LATERAL (ThemePlanStabilityTest).
    SELECT r.tenant_key, r.catalog_id, r.version_id, r.version, r.resource_id, r.resource_key AS slug, CAST(r.name AS TEXT) AS name,
           c.payload, r.created_at, r.updated_at, r.created_by, r.updated_by, r.revision
    FROM catalog_version_resources('font') r
    CROSS JOIN LATERAL (
        SELECT c.payload FROM resource_contents c WHERE c.tenant_key = r.tenant_key AND c.content_hash = r.content_hash OFFSET 0
    ) c
) u;

COMMENT ON VIEW current_fonts IS 'The fonts of the version a caller means, each as its JSON content (payload: resource, the wire form); its faces'' bytes are its resource_binaries.';

-- 4. The images of a draft or a release, from their rows alone; its size is its bytes'.
CREATE VIEW current_images AS
SELECT u.*,
       u.payload -> 'resource' ->> 'mediaType' AS media_type,
       CAST(u.payload -> 'resource' ->> 'width' AS INTEGER) AS width,
       CAST(u.payload -> 'resource' ->> 'height' AS INTEGER) AS height,
       u.payload -> 'resource' ->> 'contentHash' AS content_hash,
       (SELECT ac.size_bytes FROM asset_content ac WHERE ac.content_hash = CAST(u.payload -> 'resource' ->> 'contentHash' AS CHAR(64))) AS size_bytes
FROM (
    -- The content by its key, row by row: a fenced LATERAL (ThemePlanStabilityTest).
    SELECT r.tenant_key, r.catalog_id, r.version_id, r.version, r.resource_id, r.resource_key AS id, CAST(r.name AS TEXT) AS name,
           c.payload, r.created_at, r.updated_at, r.created_by, r.updated_by, r.revision
    FROM catalog_version_resources('image') r
    CROSS JOIN LATERAL (
        SELECT c.payload FROM resource_contents c WHERE c.tenant_key = r.tenant_key AND c.content_hash = r.content_hash OFFSET 0
    ) c
) u;

COMMENT ON VIEW current_images IS 'The images of the version a caller means, each as its JSON content (payload: resource, the wire form); its bytes are its resource_binaries.';

-- 4. The stencils of a draft or a release, from their rows alone. Columns derived from the content
--    are what the stencil reads map and filter on (tags in SQL over the JSON, ADR 0033).
CREATE VIEW current_stencils AS
SELECT u.*,
       -- The content's, not the row's: a release row carries its catalog index's summary, which
       -- an archive may word differently from the stencil it holds.
       u.payload -> 'resource' ->> 'description' AS description,
       COALESCE(u.payload -> 'resource' -> 'tags', '[]'::jsonb) AS tags,
       u.payload -> 'resource' -> 'content' AS content,
       NULLIF(u.payload -> 'resource' -> 'parameterSchema', 'null'::jsonb) AS parameter_schema,
       COALESCE(CAST(u.payload -> 'resource' ->> 'readsTemplateData' AS BOOLEAN), FALSE) AS reads_template_data
FROM (
    -- The content by its key, row by row: a fenced LATERAL, so the planner can neither reorder the
    -- join (ThemePlanStabilityTest) nor repeat the lookup for every column read from it.
    SELECT r.tenant_key, r.catalog_id, r.version_id, r.version, r.resource_id, r.resource_key AS id, CAST(r.name AS TEXT) AS name,
           c.payload, r.created_at, r.updated_at, r.created_by, r.updated_by, r.revision
    FROM catalog_version_resources('stencil') r
    CROSS JOIN LATERAL (
        SELECT c.payload FROM resource_contents c WHERE c.tenant_key = r.tenant_key AND c.content_hash = r.content_hash OFFSET 0
    ) c
) u;

COMMENT ON VIEW current_stencils IS 'The stencils of the version a caller means, each as its JSON content (payload: resource, the wire form).';

CREATE VIEW current_templates AS
SELECT u.*,
       u.payload -> 'resource' ->> 'description' AS description,
       COALESCE(CAST(u.payload -> 'resource' ->> 'pdfaEnabled' AS BOOLEAN), TRUE) AS pdfa_enabled,
       NULLIF(u.payload -> 'resource' -> 'dataModel', 'null'::jsonb) AS data_model,
       -- An example's id is its key; one a received release left without a key reads by its name.
       COALESCE((SELECT jsonb_agg(e.example || jsonb_build_object('id', COALESCE(e.example ->> 'key', e.example ->> 'name')) ORDER BY e.n)
                 FROM jsonb_array_elements(NULLIF(u.payload -> 'resource' -> 'dataExamples', 'null'::jsonb)) WITH ORDINALITY AS e(example, n)), '[]'::jsonb) AS data_examples,
       u.payload -> 'resource' ->> 'themeKey' AS theme_key,
       theme.catalog_id AS theme_catalog_id,
       (SELECT cr.resource_id FROM catalog_resources cr
        WHERE cr.tenant_key = u.tenant_key AND cr.catalog_id = theme.catalog_id AND cr.resource_type = 'theme'
          AND cr.resource_key = u.payload -> 'resource' ->> 'themeKey') AS theme_resource_id,
       CAST(jsonb_array_length(COALESCE(u.payload -> 'resource' -> 'variants', '[]'::jsonb)) AS BIGINT) AS variant_count
FROM (
    -- The content by its key, row by row: a fenced LATERAL (ThemePlanStabilityTest).
    SELECT r.tenant_key, r.catalog_id, r.version_id, r.version, r.resource_id, r.resource_key AS id, CAST(r.name AS TEXT) AS name,
           r.content_hash, c.payload, r.created_at, r.updated_at, r.created_by, r.updated_by, r.revision
    FROM catalog_version_resources('template') r
    CROSS JOIN LATERAL (
        SELECT c.payload FROM resource_contents c WHERE c.tenant_key = r.tenant_key AND c.content_hash = r.content_hash OFFSET 0
    ) c
) u
CROSS JOIN LATERAL (
    SELECT CASE WHEN u.payload -> 'resource' ->> 'themeKey' IS NOT NULL
                THEN resolve_catalog_alias(u.tenant_key, u.catalog_id, u.version_id, u.version, u.payload -> 'resource' ->> 'themeCatalogKey')
           END AS catalog_id
) theme;

COMMENT ON VIEW current_templates IS 'The templates of the version a caller means, each as its JSON content (payload: resource, the wire form without its variants, which are rows of their own).';

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

COMMENT ON VIEW current_template_variants IS 'The variants of the templates of the version a caller means, each its own row (storage type variant) with its own content and revision.';
