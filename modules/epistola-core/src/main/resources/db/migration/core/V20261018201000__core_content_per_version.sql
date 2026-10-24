-- Content belongs to a catalog version (contract ADR 0002): every content row carries the version_id
-- of the catalog_versions row it is part of, so two drafts of one catalog can each hold their own copy
-- of a resource under the same identity (resource_id). Every catalog has one version here (the next
-- version, or the installed release), so every row is filled with it. Then:
--
-- 1. what referenced a content row by (tenant_key, resource_id) references the resource's identity in
--    catalog_resources, which every version of the resource shares;
-- 2. content rows are keyed (tenant_key, version_id, resource_id), their children per version too,
--    and the address is unique per version;
-- 3. a resource moved to another catalog takes its children along;
-- 4. an identity lives while any version, or a release, holds a row of its resource.

-- 1 -----------------------------------------------------------------------------------------------
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
    END LOOP;
END $$;

-- A reference to another resource names what it is, in every version: the version a reader is in
-- resolves it (versionAt / catalog_current_versions), as rendering resolves a dependency's.
ALTER TABLE document_templates
    DROP CONSTRAINT document_templates_theme_fkey,
    ADD CONSTRAINT document_templates_theme_fkey FOREIGN KEY (tenant_key, theme_resource_id)
        REFERENCES catalog_resources (tenant_key, resource_id) ON DELETE SET NULL (theme_resource_id);
ALTER TABLE variant_attribute_definitions
    DROP CONSTRAINT attr_code_list_fk,
    ADD CONSTRAINT attr_code_list_fk FOREIGN KEY (tenant_key, code_list_resource_id)
        REFERENCES catalog_resources (tenant_key, resource_id) ON DELETE RESTRICT;
ALTER TABLE font_variants
    DROP CONSTRAINT font_variants_asset_fkey,
    ADD CONSTRAINT font_variants_asset_fkey FOREIGN KEY (tenant_key, asset_resource_id)
        REFERENCES catalog_resources (tenant_key, resource_id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE tenants
    DROP CONSTRAINT fk_tenants_default_theme,
    ADD CONSTRAINT fk_tenants_default_theme FOREIGN KEY (id, default_theme_resource_id)
        REFERENCES catalog_resources (tenant_key, resource_id);
ALTER TABLE contract_versions
    DROP CONSTRAINT contract_versions_template_fkey,
    ADD CONSTRAINT contract_versions_template_fkey FOREIGN KEY (tenant_key, template_resource_id)
        REFERENCES catalog_resources (tenant_key, resource_id) ON DELETE CASCADE;
ALTER TABLE stencil_versions
    DROP CONSTRAINT fk_stencil_versions_parent,
    ADD CONSTRAINT fk_stencil_versions_parent FOREIGN KEY (tenant_key, stencil_resource_id)
        REFERENCES catalog_resources (tenant_key, resource_id) ON DELETE CASCADE;

-- A variant is a row of one version: no table holds a variant across versions, so a reference to one
-- cannot be kept. These keep the template-level reference to its identity instead, and their recorded
-- variant_key; deleting the template (its last version) still deletes them, and deleting the variant's
-- last row does too (trg_template_variants_history below).
ALTER TABLE template_versions
    DROP CONSTRAINT template_versions_variant_fkey,
    ADD CONSTRAINT template_versions_template_fkey FOREIGN KEY (tenant_key, template_resource_id)
        REFERENCES catalog_resources (tenant_key, resource_id) ON DELETE CASCADE;
ALTER TABLE environment_activations
    DROP CONSTRAINT environment_activations_variant_fkey,
    ADD CONSTRAINT environment_activations_template_fkey FOREIGN KEY (tenant_key, template_resource_id)
        REFERENCES catalog_resources (tenant_key, resource_id) ON DELETE CASCADE;

-- What hung off a variant goes with the variant's last row, as those references cascaded.
CREATE FUNCTION variant_history_follow_variant() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM template_variants
                   WHERE tenant_key = OLD.tenant_key AND template_resource_id = OLD.template_resource_id AND id = OLD.id) THEN
        DELETE FROM environment_activations
        WHERE tenant_key = OLD.tenant_key AND template_resource_id = OLD.template_resource_id AND variant_key = OLD.id;
        DELETE FROM template_versions
        WHERE tenant_key = OLD.tenant_key AND template_resource_id = OLD.template_resource_id AND variant_key = OLD.id;
    END IF;
    RETURN NULL;
END;
$$;

CREATE TRIGGER trg_template_variants_history AFTER DELETE ON template_variants
    FOR EACH ROW EXECUTE FUNCTION variant_history_follow_variant();

-- Children reference their parent within their version (fk_*_version_parent, below); the references
-- to the parent across versions go.
ALTER TABLE template_variants DROP CONSTRAINT template_variants_template_fkey;
ALTER TABLE code_list_entries DROP CONSTRAINT code_list_entries_code_list_fkey;
ALTER TABLE font_variants DROP CONSTRAINT font_variants_font_fkey;

-- 2 -----------------------------------------------------------------------------------------------
DO $$
DECLARE
    t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['themes', 'document_templates', 'variant_attribute_definitions', 'code_lists', 'assets', 'fonts', 'stencils']
    LOOP
        EXECUTE format('ALTER TABLE %I DROP CONSTRAINT %I', t, t || '_pkey');
        EXECUTE format('ALTER TABLE %I ADD CONSTRAINT %I PRIMARY KEY (tenant_key, version_id, resource_id)', t, t || '_pkey');
        -- Every version's row of one resource: what the identity trigger and the identity references look up.
        EXECUTE format('CREATE INDEX %I ON %I (tenant_key, resource_id)', 'idx_' || t || '_resource', t);
    END LOOP;
END $$;

-- A child row (a variant, a code-list entry, a font face) is part of its parent's version.
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
    END LOOP;
END $$;

ALTER TABLE template_variants
    DROP CONSTRAINT template_variants_pkey,
    ADD CONSTRAINT template_variants_pkey PRIMARY KEY (tenant_key, version_id, template_resource_id, id),
    ADD CONSTRAINT fk_template_variants_version_parent FOREIGN KEY (tenant_key, version_id, template_resource_id)
        REFERENCES document_templates (tenant_key, version_id, resource_id) ON DELETE CASCADE DEFERRABLE INITIALLY DEFERRED;
-- One default variant per template in each version.
DROP INDEX idx_one_default_variant_per_template;
CREATE UNIQUE INDEX idx_one_default_variant_per_template ON template_variants (tenant_key, version_id, template_resource_id) WHERE is_default;
ALTER TABLE code_list_entries
    DROP CONSTRAINT code_list_entries_pkey,
    ADD CONSTRAINT code_list_entries_pkey PRIMARY KEY (tenant_key, version_id, code_list_resource_id, code),
    ADD CONSTRAINT fk_code_list_entries_version_parent FOREIGN KEY (tenant_key, version_id, code_list_resource_id)
        REFERENCES code_lists (tenant_key, version_id, resource_id) ON DELETE CASCADE DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE font_variants
    DROP CONSTRAINT font_variants_pkey,
    ADD CONSTRAINT font_variants_pkey PRIMARY KEY (tenant_key, version_id, font_resource_id, weight, italic),
    ADD CONSTRAINT fk_font_variants_version_parent FOREIGN KEY (tenant_key, version_id, font_resource_id)
        REFERENCES fonts (tenant_key, version_id, resource_id) ON DELETE CASCADE DEFERRABLE INITIALLY DEFERRED;

-- 3 -----------------------------------------------------------------------------------------------
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
        EXECUTE format(
            -- Any update that changes the parent's version: a move to another catalog sets both.
            'CREATE TRIGGER %I AFTER UPDATE ON %I FOR EACH ROW WHEN (NEW.version_id IS DISTINCT FROM OLD.version_id) EXECUTE FUNCTION content_version_move_children(%L, %L)',
            'trg_' || parent || '_move_' || t, parent, t, parent_column);
    END LOOP;
END $$;

-- 4 -----------------------------------------------------------------------------------------------
-- One identity per resource, shared by every version that holds a row of it:
-- - a row inserted into another version at the same address adopts the identity there (as before);
-- - deleting a row deletes the identity only when no other version still holds a row of it;
-- - moving or renaming a resource (relocation) is refused while another version holds it, since the
--   identity carries one address.
CREATE OR REPLACE FUNCTION sync_catalog_resource_identity() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    resource_type_value TEXT := TG_ARGV[0];
    key_column TEXT := TG_ARGV[1];
    resource_key_value TEXT;
    existing_resource_id UUID;
    held_elsewhere BOOLEAN;
BEGIN
    IF TG_OP = 'DELETE' THEN
        EXECUTE format('SELECT EXISTS (SELECT 1 FROM %I WHERE tenant_key = $1 AND resource_id = $2)', TG_TABLE_NAME)
            INTO held_elsewhere USING OLD.tenant_key, OLD.resource_id;
        -- A release holds it too: releasing a draft that a higher draft does not follow discards the
        -- draft, and what only it held lives on in that release. Keeping the identity keeps the
        -- resource's history and findings with it, and a resource created again at its address adopts it.
        IF NOT held_elsewhere THEN
            SELECT EXISTS (SELECT 1 FROM release_resources WHERE tenant_key = OLD.tenant_key AND resource_id = OLD.resource_id)
                INTO held_elsewhere;
        END IF;
        IF NOT held_elsewhere THEN
            DELETE FROM catalog_resources
            WHERE tenant_key = OLD.tenant_key AND resource_id = OLD.resource_id;
        END IF;
        RETURN OLD;
    END IF;

    resource_key_value := to_jsonb(NEW) ->> key_column;

    IF TG_OP = 'INSERT' THEN
        SELECT resource_id INTO existing_resource_id
        FROM catalog_resources
        WHERE tenant_key = NEW.tenant_key
          AND resource_type = resource_type_value
          AND catalog_id = NEW.catalog_id
          AND resource_key = resource_key_value;

        IF NEW.resource_id IS NULL THEN
            -- Nothing supplied: adopt the identity already registered at this address, which another
            -- version of the catalog may hold (imports use INSERT .. ON CONFLICT DO UPDATE, which runs
            -- this BEFORE INSERT trigger too).
            NEW.resource_id := COALESCE(existing_resource_id, epistola_uuidv7());
        ELSIF existing_resource_id IS NOT NULL AND existing_resource_id <> NEW.resource_id THEN
            -- A caller that names an identity means it; never silently prefer the incumbent.
            RAISE EXCEPTION 'catalog resource % is already registered at %/% under identity %',
                NEW.resource_id, NEW.catalog_id, resource_key_value, existing_resource_id;
        END IF;

        -- An insert registers an identity, or joins the one registered at its address; it never moves
        -- one. Under concurrency the address it adopted may have moved since its snapshot (a
        -- relocation committing meanwhile): the conflict check sees the committed row, and refuses.
        INSERT INTO catalog_resources (tenant_key, resource_id, resource_type, catalog_id, resource_key)
        VALUES (NEW.tenant_key, NEW.resource_id, resource_type_value, NEW.catalog_id, resource_key_value)
        ON CONFLICT (tenant_key, resource_id) DO UPDATE
        SET catalog_id = EXCLUDED.catalog_id,
            resource_key = EXCLUDED.resource_key
        WHERE catalog_resources.resource_type = EXCLUDED.resource_type
          AND catalog_resources.catalog_id = EXCLUDED.catalog_id
          AND catalog_resources.resource_key = EXCLUDED.resource_key;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'catalog resource % is registered with another type or at another address than %/%',
                NEW.resource_id, NEW.catalog_id, resource_key_value;
        END IF;
    ELSE
        IF NEW.resource_id <> OLD.resource_id THEN
            RAISE EXCEPTION 'catalog resource_id is immutable';
        END IF;
        IF NEW.catalog_id IS DISTINCT FROM OLD.catalog_id OR resource_key_value IS DISTINCT FROM (to_jsonb(OLD) ->> key_column) THEN
            EXECUTE format('SELECT EXISTS (SELECT 1 FROM %I WHERE tenant_key = $1 AND resource_id = $2 AND version_id <> $3)', TG_TABLE_NAME)
                INTO held_elsewhere USING OLD.tenant_key, OLD.resource_id, OLD.version_id;
            IF held_elsewhere THEN
                RAISE EXCEPTION 'catalog resource % is held by another version of its catalog; it cannot move or be renamed', OLD.resource_id;
            END IF;
        END IF;
        UPDATE catalog_resources
        SET catalog_id = NEW.catalog_id,
            resource_key = resource_key_value
        WHERE tenant_key = OLD.tenant_key AND resource_id = OLD.resource_id;
        -- A missing registry row would let the domain row move while the registry keeps the old
        -- address, silently; every public address lookup reads the registry.
        IF NOT FOUND THEN
            RAISE EXCEPTION 'catalog resource % has no identity row', OLD.resource_id;
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

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
