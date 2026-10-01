-- backup-restore-compatibility: backward=true forward=true
-- reason: Adds a trigger function and triggers. No column, no data, nothing a backup carries either
-- way; a restore into either schema replays inserts, and a trigger that fires only on DELETE does
-- not touch them.
-- SPDX-FileCopyrightText: Epistola Nederland B.V.
--
-- SPDX-License-Identifier: AGPL-3.0-only

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
    WHERE tenant_key = OLD.tenant_key AND id = OLD.catalog_key;
    RETURN OLD;
END;
$$;

COMMENT ON FUNCTION touch_catalog_content_on_delete() IS
    'AFTER DELETE trigger function for catalog-scoped resource tables: bumps catalogs.content_updated_at so the drift signal sees a deletion. Requires tenant_key and catalog_key on the row.';

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
      AND c.tenant_key = t.tenant_key AND c.id = t.catalog_key;
    RETURN OLD;
END;
$$;

COMMENT ON FUNCTION touch_catalog_content_on_variant_delete() IS
    'AFTER DELETE trigger function for template_variants, which reaches its catalog through the template it belongs to.';

CREATE TRIGGER trg_template_variants_catalog_touched
    AFTER DELETE ON template_variants
    FOR EACH ROW EXECUTE FUNCTION touch_catalog_content_on_variant_delete();
