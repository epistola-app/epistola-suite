-- SPDX-FileCopyrightText: Epistola Nederland B.V.
--
-- SPDX-License-Identifier: AGPL-3.0-only

-- Several drafts of a catalog at once (contract ADR 0002, step 4b, slice c). Every content row already
-- carries its version_id and every statement names its version, so the transitional pieces of the
-- earlier steps go, and content is keyed per version:
--
-- 1. a catalog may have any number of drafts; a subscribed catalog still has one installed row;
-- 2. the triggers that filled version_id for a writer that named none go: every writer names one;
-- 3. what referenced a content row by (tenant_key, resource_id) references the resource's identity in
--    catalog_resources, which every version of the resource shares;
-- 4. content rows are keyed (tenant_key, version_id, resource_id), their children per version too, and
--    the address is unique per version only;
-- 5. ready marks and dependency declarations are keyed per version;
-- 6. an identity lives while any version holds a row of its resource;
-- 7. copy_catalog_version_content() copies a version's content into another, for a new draft;
-- 8. catalog_current_versions answers the version a transaction targets (epistola.content_version).
--
-- No row is dropped: every existing catalog still has the one version its content is in.

-- 1 -----------------------------------------------------------------------------------------------
DROP INDEX uq_catalog_versions_one_per_catalog;
CREATE UNIQUE INDEX uq_catalog_versions_one_installed ON catalog_versions (tenant_key, catalog_id) WHERE kind = 'installed';

-- 2 -----------------------------------------------------------------------------------------------
DO $$
DECLARE
    t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['themes', 'document_templates', 'variant_attribute_definitions', 'code_lists', 'assets',
                             'fonts', 'stencils', 'template_variants', 'code_list_entries', 'font_variants', 'catalog_dependencies']
    LOOP
        EXECUTE format('DROP TRIGGER %I ON %I', 'trg_' || t || '_content_version', t);
    END LOOP;
END $$;
DROP TRIGGER trg_resource_ready_marks_content_version ON resource_ready_marks;
DROP FUNCTION content_version_fill();
DROP FUNCTION content_version_from_parent();
DROP FUNCTION ready_mark_version_fill();
DROP FUNCTION content_version_of_catalog(TEXT, UUID);
-- content_version_move_children() stays: a resource moved to another catalog takes its children along.

-- 3 -----------------------------------------------------------------------------------------------
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

-- Children reference their parent within their version (fk_*_version_parent); the references to the
-- parent across versions go.
ALTER TABLE template_variants DROP CONSTRAINT template_variants_template_fkey;
ALTER TABLE code_list_entries DROP CONSTRAINT code_list_entries_code_list_fkey;
ALTER TABLE font_variants DROP CONSTRAINT font_variants_font_fkey;
-- Recreated below, once their target is the new primary key.
ALTER TABLE template_variants DROP CONSTRAINT fk_template_variants_version_parent;
ALTER TABLE code_list_entries DROP CONSTRAINT fk_code_list_entries_version_parent;
ALTER TABLE font_variants DROP CONSTRAINT fk_font_variants_version_parent;

-- 4 -----------------------------------------------------------------------------------------------
DO $$
DECLARE
    t TEXT;
    address TEXT;
BEGIN
    FOR t, address IN VALUES
        ('themes', 'uq_themes_address'), ('document_templates', 'uq_document_templates_address'),
        ('variant_attribute_definitions', 'uq_variant_attributes_address'), ('code_lists', 'uq_code_lists_address'),
        ('assets', 'uq_assets_address'), ('fonts', 'uq_fonts_address'), ('stencils', 'uq_stencils_address')
    LOOP
        EXECUTE format('ALTER TABLE %I DROP CONSTRAINT %I', t, t || '_pkey');
        -- The primary key takes over the per-version unique of slice (a), over the same columns.
        EXECUTE format('ALTER TABLE %I DROP CONSTRAINT %I', t, 'uq_' || t || '_version_resource');
        EXECUTE format('ALTER TABLE %I ADD CONSTRAINT %I PRIMARY KEY (tenant_key, version_id, resource_id)', t, t || '_pkey');
        -- The address is unique per version (uq_*_version_address), no longer per catalog.
        EXECUTE format('ALTER TABLE %I DROP CONSTRAINT %I', t, address);
        -- Every version's row of one resource: what the identity trigger and the identity references look up.
        EXECUTE format('CREATE INDEX %I ON %I (tenant_key, resource_id)', 'idx_' || t || '_resource', t);
    END LOOP;
END $$;

ALTER TABLE template_variants
    DROP CONSTRAINT template_variants_pkey,
    DROP CONSTRAINT uq_template_variants_version_key,
    ADD CONSTRAINT template_variants_pkey PRIMARY KEY (tenant_key, version_id, template_resource_id, id),
    ADD CONSTRAINT fk_template_variants_version_parent FOREIGN KEY (tenant_key, version_id, template_resource_id)
        REFERENCES document_templates (tenant_key, version_id, resource_id) ON DELETE CASCADE DEFERRABLE INITIALLY DEFERRED;
-- One default variant per template in each version.
DROP INDEX idx_one_default_variant_per_template;
CREATE UNIQUE INDEX idx_one_default_variant_per_template ON template_variants (tenant_key, version_id, template_resource_id) WHERE is_default;
ALTER TABLE code_list_entries
    DROP CONSTRAINT code_list_entries_pkey,
    DROP CONSTRAINT uq_code_list_entries_version_code,
    ADD CONSTRAINT code_list_entries_pkey PRIMARY KEY (tenant_key, version_id, code_list_resource_id, code),
    ADD CONSTRAINT fk_code_list_entries_version_parent FOREIGN KEY (tenant_key, version_id, code_list_resource_id)
        REFERENCES code_lists (tenant_key, version_id, resource_id) ON DELETE CASCADE DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE font_variants
    DROP CONSTRAINT font_variants_pkey,
    DROP CONSTRAINT uq_font_variants_version_face,
    ADD CONSTRAINT font_variants_pkey PRIMARY KEY (tenant_key, version_id, font_resource_id, weight, italic),
    ADD CONSTRAINT fk_font_variants_version_parent FOREIGN KEY (tenant_key, version_id, font_resource_id)
        REFERENCES fonts (tenant_key, version_id, resource_id) ON DELETE CASCADE DEFERRABLE INITIALLY DEFERRED;

-- 5 -----------------------------------------------------------------------------------------------
ALTER TABLE resource_ready_marks
    DROP CONSTRAINT resource_ready_marks_pkey,
    ADD CONSTRAINT resource_ready_marks_pkey PRIMARY KEY (tenant_key, version_id, resource_id, part);
ALTER TABLE catalog_dependencies
    DROP CONSTRAINT catalog_dependencies_pkey,
    ADD CONSTRAINT catalog_dependencies_pkey PRIMARY KEY (tenant_key, version_id, registry, namespace, dependency_key);

-- 6 -----------------------------------------------------------------------------------------------
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

-- 7 -----------------------------------------------------------------------------------------------
-- Copies every content row of version from_version into to_version, of the same catalog (the
-- (tenant_key, version_id, catalog_id) references refuse another), keeping each resource_id: the copy
-- is the same resources. Ready marks and dependency declarations come along. Asset binaries are
-- content-addressed, so the copied rows share them. Columns are read from the catalog, so a column
-- added to a content table later is copied without changing this.
CREATE FUNCTION copy_catalog_version_content(p_tenant TEXT, p_from UUID, p_to UUID) RETURNS VOID
    LANGUAGE plpgsql
AS $$
DECLARE
    t TEXT;
    columns TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['themes', 'assets', 'code_lists', 'code_list_entries', 'variant_attribute_definitions', 'fonts',
                             'font_variants', 'stencils', 'document_templates', 'template_variants', 'resource_ready_marks',
                             'catalog_dependencies']
    LOOP
        SELECT string_agg(quote_ident(column_name), ', ' ORDER BY ordinal_position) INTO columns
        FROM information_schema.columns
        WHERE table_schema = current_schema() AND table_name = t AND is_generated = 'NEVER' AND column_name <> 'version_id';
        EXECUTE format('INSERT INTO %I (%s, version_id) SELECT %s, $2 FROM %I WHERE tenant_key = $1 AND version_id = $3', t, columns, columns, t)
            USING p_tenant, p_to, p_from;
    END LOOP;
END;
$$;

COMMENT ON FUNCTION copy_catalog_version_content(TEXT, UUID, UUID) IS
    'Copies the content of one catalog version into another of the same catalog, keeping resource identities.';

-- 8 -----------------------------------------------------------------------------------------------
-- The version a caller means, per catalog: the version its transaction targets (the setting
-- epistola.content_version, which the mediator sets transaction-locally for a message that names a
-- version), else a subscribed catalog's installed release, else the highest open draft. The targeted
-- version only wins for its own catalog; every other catalog answers its current version.
CREATE OR REPLACE VIEW catalog_current_versions AS
SELECT DISTINCT ON (v.tenant_key, v.catalog_id) v.tenant_key, v.catalog_id, v.version_id
FROM catalog_versions v
ORDER BY v.tenant_key, v.catalog_id,
         (v.version_id::text = current_setting('epistola.content_version', TRUE)) IS TRUE DESC,
         (v.kind = 'installed') DESC,
         v.version_major DESC NULLS LAST, v.version_minor DESC NULLS LAST, v.version_patch DESC NULLS LAST,
         v.version DESC;

COMMENT ON VIEW catalog_current_versions IS
    'Per catalog, the version a caller means: the one its transaction targets (epistola.content_version), else a subscribed catalog''s installed release, else the authored catalog''s highest open draft.';
COMMENT ON TABLE catalog_versions IS
    'The versions of a catalog whose content is in the content tables: every open draft of an authored catalog, and the installed release of a subscribed one.';
