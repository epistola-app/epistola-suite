-- SPDX-FileCopyrightText: Epistola Nederland B.V.
--
-- SPDX-License-Identifier: AGPL-3.0-only

-- Content belongs to a catalog version (contract ADR 0002, step 4b): every content row carries the
-- version_id of the catalog_versions row it is part of, so that two drafts of one catalog can each
-- hold their own copy of a resource under the same identity (resource_id).
--
-- This is the first, behaviour-preserving step. Every catalog still has exactly one content version
-- (uq_catalog_versions_one_per_catalog), every row is backfilled with it, and the existing keys stay:
-- (tenant_key, resource_id) is still unique, so every reference keeps working. What is added:
--
-- - version_id on each content table, with a (tenant_key, version_id, catalog_id) reference to
--   catalog_versions that keeps it in agreement with catalog_id and deletes a version's content with
--   it; a child row (a variant, a code-list entry, a font face) carries its parent's, and moves with it;
-- - the keys the content will have once several versions hold it: an address unique per version,
--   and (tenant_key, version_id, resource_id);
-- - content_version_fill(), a transitional trigger that sets version_id when a writer does not name
--   one, from the catalog's only content version (or the parent row's), and moves it when a resource
--   moves to another catalog. It refuses a catalog with several versions, so nothing guesses.
--
-- Next, every writer and reader names its version; then the old keys, the trigger and the
-- one-per-catalog rule go, and several drafts can be open.

CREATE FUNCTION content_version_of_catalog(tenant TEXT, catalog UUID) RETURNS UUID
    LANGUAGE plpgsql
AS $$
DECLARE
    found_version UUID;
    versions INT;
BEGIN
    SELECT count(*), min(version_id::text)::uuid INTO versions, found_version
    FROM catalog_versions WHERE tenant_key = tenant AND catalog_id = catalog;
    IF versions = 0 THEN
        RAISE EXCEPTION 'catalog % of tenant % has no content version', catalog, tenant;
    ELSIF versions > 1 THEN
        RAISE EXCEPTION 'catalog % of tenant % has % content versions; name the version', catalog, tenant, versions;
    END IF;
    RETURN found_version;
END;
$$;

-- Catalog-scoped content: fills version_id from the catalog, and follows a move to another catalog.
CREATE FUNCTION content_version_fill() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.version_id IS NULL THEN
            NEW.version_id := content_version_of_catalog(NEW.tenant_key, NEW.catalog_id);
        END IF;
    ELSIF NEW.catalog_id IS DISTINCT FROM OLD.catalog_id AND NEW.version_id IS NOT DISTINCT FROM OLD.version_id THEN
        NEW.version_id := content_version_of_catalog(NEW.tenant_key, NEW.catalog_id);
    END IF;
    RETURN NEW;
END;
$$;

DO $$
DECLARE
    t TEXT;
    key_column TEXT;
BEGIN
    FOR t, key_column IN VALUES
        ('themes', 'id'), ('document_templates', 'id'), ('variant_attribute_definitions', 'id'),
        ('code_lists', 'slug'), ('assets', 'id'), ('fonts', 'slug'), ('stencils', 'id')
    LOOP
        EXECUTE format('ALTER TABLE %I ADD COLUMN version_id UUID', t);
        EXECUTE format(
            'UPDATE %I r SET version_id = v.version_id FROM catalog_versions v
              WHERE v.tenant_key = r.tenant_key AND v.catalog_id = r.catalog_id', t);
        EXECUTE format('ALTER TABLE %I ALTER COLUMN version_id SET NOT NULL', t);
        EXECUTE format(
            'ALTER TABLE %I ADD CONSTRAINT %I FOREIGN KEY (tenant_key, version_id, catalog_id)
               REFERENCES catalog_versions (tenant_key, version_id, catalog_id) ON DELETE CASCADE', t, 'fk_' || t || '_version');
        EXECUTE format('ALTER TABLE %I ADD CONSTRAINT %I UNIQUE (tenant_key, version_id, %I)', t, 'uq_' || t || '_version_address', key_column);
        EXECUTE format('ALTER TABLE %I ADD CONSTRAINT %I UNIQUE (tenant_key, version_id, resource_id)', t, 'uq_' || t || '_version_resource');
        EXECUTE format(
            'CREATE TRIGGER %I BEFORE INSERT OR UPDATE OF catalog_id ON %I FOR EACH ROW EXECUTE FUNCTION content_version_fill()',
            'trg_' || t || '_content_version', t);
    END LOOP;
END $$;

-- Children: a row is part of its parent's version.
CREATE FUNCTION content_version_from_parent() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    parent_table TEXT := TG_ARGV[0];
    parent_column TEXT := TG_ARGV[1];
    parent_id UUID := (to_jsonb(NEW) ->> parent_column)::uuid;
BEGIN
    IF NEW.version_id IS NULL OR (TG_OP = 'UPDATE' AND (to_jsonb(OLD) ->> parent_column) IS DISTINCT FROM (to_jsonb(NEW) ->> parent_column)) THEN
        EXECUTE format('SELECT version_id FROM %I WHERE tenant_key = $1 AND resource_id = $2', parent_table)
            INTO NEW.version_id USING NEW.tenant_key, parent_id;
    END IF;
    RETURN NEW;
END;
$$;

-- A parent that moves to another version (a resource moved to another catalog) takes its children
-- along in the same transaction. Not ON UPDATE CASCADE (ADR 0014): a child row is part of its
-- parent's content, not a copy of its address, so it is moved with it explicitly; the child's
-- reference to its parent is checked at commit, by when both have moved.
CREATE FUNCTION content_version_move_children() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    child_table TEXT := TG_ARGV[0];
    parent_column TEXT := TG_ARGV[1];
BEGIN
    IF NEW.version_id IS DISTINCT FROM OLD.version_id THEN
        EXECUTE format('UPDATE %I SET version_id = $1 WHERE tenant_key = $2 AND %I = $3 AND version_id = $4', child_table, parent_column)
            USING NEW.version_id, NEW.tenant_key, NEW.resource_id, OLD.version_id;
    END IF;
    RETURN NULL;
END;
$$;

DO $$
DECLARE
    t TEXT;
    parent TEXT;
    parent_column TEXT;
BEGIN
    FOR t, parent, parent_column IN VALUES
        ('template_variants', 'document_templates', 'template_resource_id'),
        ('code_list_entries', 'code_lists', 'code_list_resource_id'),
        ('font_variants', 'fonts', 'font_resource_id')
    LOOP
        EXECUTE format('ALTER TABLE %I ADD COLUMN version_id UUID', t);
        EXECUTE format(
            'UPDATE %I c SET version_id = p.version_id FROM %I p
              WHERE p.tenant_key = c.tenant_key AND p.resource_id = c.%I', t, parent, parent_column);
        EXECUTE format('ALTER TABLE %I ALTER COLUMN version_id SET NOT NULL', t);
        EXECUTE format(
            'ALTER TABLE %I ADD CONSTRAINT %I FOREIGN KEY (tenant_key, version_id, %I)
               REFERENCES %I (tenant_key, version_id, resource_id) ON DELETE CASCADE DEFERRABLE INITIALLY DEFERRED', t, 'fk_' || t || '_version_parent', parent_column, parent);
        EXECUTE format(
            'CREATE TRIGGER %I BEFORE INSERT OR UPDATE OF %I ON %I FOR EACH ROW EXECUTE FUNCTION content_version_from_parent(%L, %L)',
            'trg_' || t || '_content_version', parent_column, t, parent, parent_column);
        EXECUTE format(
            -- Not UPDATE OF version_id: a move sets catalog_id, and version_id changes in the BEFORE trigger.
            'CREATE TRIGGER %I AFTER UPDATE ON %I FOR EACH ROW WHEN (NEW.version_id IS DISTINCT FROM OLD.version_id) EXECUTE FUNCTION content_version_move_children(%L, %L)',
            'trg_' || parent || '_move_' || t, parent, t, parent_column);
    END LOOP;
END $$;

ALTER TABLE template_variants ADD CONSTRAINT uq_template_variants_version_key UNIQUE (tenant_key, version_id, template_resource_id, id);
ALTER TABLE code_list_entries ADD CONSTRAINT uq_code_list_entries_version_code UNIQUE (tenant_key, version_id, code_list_resource_id, code);
ALTER TABLE font_variants ADD CONSTRAINT uq_font_variants_version_face UNIQUE (tenant_key, version_id, font_resource_id, weight, italic);

-- State kept per version: what was marked ready, and what each draft declares.
ALTER TABLE resource_ready_marks ADD COLUMN version_id UUID;
UPDATE resource_ready_marks m SET version_id = v.version_id
FROM catalog_resources r JOIN catalog_versions v ON v.tenant_key = r.tenant_key AND v.catalog_id = r.catalog_id
WHERE r.tenant_key = m.tenant_key AND r.resource_id = m.resource_id;
-- A mark whose resource's catalog has no version (none should) cannot be kept: it marks nothing.
DELETE FROM resource_ready_marks WHERE version_id IS NULL;
ALTER TABLE resource_ready_marks ALTER COLUMN version_id SET NOT NULL;
ALTER TABLE resource_ready_marks ADD CONSTRAINT fk_resource_ready_marks_version
    FOREIGN KEY (tenant_key, version_id) REFERENCES catalog_versions (tenant_key, version_id) ON DELETE CASCADE;

CREATE FUNCTION ready_mark_version_fill() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.version_id IS NULL THEN
        NEW.version_id := content_version_of_catalog(
            NEW.tenant_key,
            (SELECT catalog_id FROM catalog_resources WHERE tenant_key = NEW.tenant_key AND resource_id = NEW.resource_id));
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_resource_ready_marks_content_version BEFORE INSERT ON resource_ready_marks
    FOR EACH ROW EXECUTE FUNCTION ready_mark_version_fill();

ALTER TABLE catalog_dependencies ADD COLUMN version_id UUID;
UPDATE catalog_dependencies d SET version_id = v.version_id
FROM catalog_versions v WHERE v.tenant_key = d.tenant_key AND v.catalog_id = d.catalog_id;
ALTER TABLE catalog_dependencies ALTER COLUMN version_id SET NOT NULL;
ALTER TABLE catalog_dependencies ADD CONSTRAINT fk_catalog_dependencies_version
    FOREIGN KEY (tenant_key, version_id, catalog_id) REFERENCES catalog_versions (tenant_key, version_id, catalog_id) ON DELETE CASCADE;
CREATE TRIGGER trg_catalog_dependencies_content_version BEFORE INSERT ON catalog_dependencies
    FOR EACH ROW EXECUTE FUNCTION content_version_fill();

COMMENT ON COLUMN themes.version_id IS 'The catalog version (catalog_versions) this row is part of.';
COMMENT ON COLUMN document_templates.version_id IS 'The catalog version (catalog_versions) this row is part of.';
COMMENT ON COLUMN template_variants.version_id IS 'The catalog version of the template this variant belongs to.';
COMMENT ON COLUMN variant_attribute_definitions.version_id IS 'The catalog version (catalog_versions) this row is part of.';
COMMENT ON COLUMN code_lists.version_id IS 'The catalog version (catalog_versions) this row is part of.';
COMMENT ON COLUMN code_list_entries.version_id IS 'The catalog version of the code list this entry belongs to.';
COMMENT ON COLUMN assets.version_id IS 'The catalog version (catalog_versions) this row is part of.';
COMMENT ON COLUMN fonts.version_id IS 'The catalog version (catalog_versions) this row is part of.';
COMMENT ON COLUMN font_variants.version_id IS 'The catalog version of the font this face belongs to.';
COMMENT ON COLUMN stencils.version_id IS 'The catalog version (catalog_versions) this row is part of.';
COMMENT ON COLUMN resource_ready_marks.version_id IS 'The catalog version in which the resource part was marked ready.';
COMMENT ON COLUMN catalog_dependencies.version_id IS 'The catalog version that declares the dependency.';
