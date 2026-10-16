-- A catalog gets an opaque identity, `catalog_id`, and is named by (registry, namespace, key)
-- (contract ADR 0003). A key alone is not unique in a tenant: two publishers may each have a
-- catalog called `letters`. Live tables reference a catalog by `catalog_id`, as resources already
-- reference each other by `resource_id`, so a catalog's name lives in one row and changing its
-- namespace touches nothing else. History (generated documents, the deployment log) keeps the name
-- the catalog had: it records what was generated, and names, rather than points at, its catalog.

-- 1. The catalog's own identity -----------------------------------------------------------------

ALTER TABLE catalogs ADD COLUMN catalog_id UUID;
UPDATE catalogs SET catalog_id = epistola_uuidv7();
ALTER TABLE catalogs ALTER COLUMN catalog_id SET NOT NULL;
-- Opaque and never named by a caller, so the database assigns it.
ALTER TABLE catalogs ALTER COLUMN catalog_id SET DEFAULT epistola_uuidv7();
ALTER TABLE catalogs ADD CONSTRAINT uq_catalogs_catalog_id UNIQUE (tenant_key, catalog_id);

-- 2. Live tables gain catalog_id, filled from their catalog_key ---------------------------------

DO $$
DECLARE
    t TEXT;
    unmatched BIGINT;
BEGIN
    FOREACH t IN ARRAY ARRAY[
        'catalog_resources', 'themes', 'document_templates', 'variant_attribute_definitions',
        'assets', 'stencils', 'code_lists', 'fonts', 'catalog_releases', 'release_entries',
        'catalog_release_publications', 'environment_catalog_deployments', 'catalog_upstream_checks'
    ] LOOP
        EXECUTE format('ALTER TABLE %I ADD COLUMN catalog_id UUID', t);
        EXECUTE format(
            'UPDATE %I r SET catalog_id = c.catalog_id FROM catalogs c
              WHERE c.tenant_key = r.tenant_key AND c.id = r.catalog_key', t);
        EXECUTE format('SELECT count(*) FROM %I WHERE catalog_id IS NULL', t) INTO unmatched;
        IF unmatched > 0 THEN
            RAISE EXCEPTION '% row(s) of % name a catalog that does not exist', unmatched, t;
        END IF;
        EXECUTE format('ALTER TABLE %I ALTER COLUMN catalog_id SET NOT NULL', t);
    END LOOP;
END $$;

-- 3. History gains the catalog's full name ------------------------------------------------------

ALTER TABLE documents ADD COLUMN registry TEXT, ADD COLUMN namespace TEXT;
ALTER TABLE document_generation_requests ADD COLUMN registry TEXT, ADD COLUMN namespace TEXT;
ALTER TABLE environment_deployment_log ADD COLUMN registry TEXT, ADD COLUMN namespace TEXT;

-- A row whose catalog no longer exists keeps its key and is named on the default registry in its
-- tenant's namespace, which is what a catalog of that tenant was named on the move to 2.0.
UPDATE documents d SET registry = COALESCE(c.registry, 'exchange.epistola.app'),
                       namespace = COALESCE(c.namespace, CASE WHEN length(d.tenant_key) < 3 THEN rpad(d.tenant_key, 3, '0') ELSE d.tenant_key END)
FROM (SELECT DISTINCT tenant_key, catalog_key FROM documents) k
LEFT JOIN catalogs c ON c.tenant_key = k.tenant_key AND c.id = k.catalog_key
WHERE d.tenant_key = k.tenant_key AND d.catalog_key = k.catalog_key;

UPDATE document_generation_requests d SET registry = COALESCE(c.registry, 'exchange.epistola.app'),
                       namespace = COALESCE(c.namespace, CASE WHEN length(d.tenant_key) < 3 THEN rpad(d.tenant_key, 3, '0') ELSE d.tenant_key END)
FROM (SELECT DISTINCT tenant_key, catalog_key FROM document_generation_requests) k
LEFT JOIN catalogs c ON c.tenant_key = k.tenant_key AND c.id = k.catalog_key
WHERE d.tenant_key = k.tenant_key AND d.catalog_key = k.catalog_key;

UPDATE environment_deployment_log l SET registry = COALESCE(c.registry, 'exchange.epistola.app'),
                       namespace = COALESCE(c.namespace, CASE WHEN length(l.tenant_key) < 3 THEN rpad(l.tenant_key, 3, '0') ELSE l.tenant_key END)
FROM (SELECT DISTINCT tenant_key, catalog_key FROM environment_deployment_log) k
LEFT JOIN catalogs c ON c.tenant_key = k.tenant_key AND c.id = k.catalog_key
WHERE l.tenant_key = k.tenant_key AND l.catalog_key = k.catalog_key;

ALTER TABLE documents ALTER COLUMN registry SET NOT NULL, ALTER COLUMN namespace SET NOT NULL;
ALTER TABLE document_generation_requests ALTER COLUMN registry SET NOT NULL, ALTER COLUMN namespace SET NOT NULL;
ALTER TABLE environment_deployment_log ALTER COLUMN registry SET NOT NULL, ALTER COLUMN namespace SET NOT NULL;

-- 4. Drop every constraint and index built on catalog_key ---------------------------------------

ALTER TABLE environment_catalog_deployments DROP CONSTRAINT fk_environment_catalog_deployments_release;
ALTER TABLE release_entries DROP CONSTRAINT fk_release_entries_release;
ALTER TABLE catalog_release_publications DROP CONSTRAINT fk_catalog_release_publications_release;
ALTER TABLE catalog_release_publications DROP CONSTRAINT uq_catalog_release_publications_release;

ALTER TABLE catalog_releases DROP CONSTRAINT catalog_releases_tenant_key_catalog_key_fkey;
ALTER TABLE themes DROP CONSTRAINT themes_tenant_key_catalog_key_fkey, DROP CONSTRAINT uq_themes_address;
ALTER TABLE document_templates DROP CONSTRAINT document_templates_tenant_key_catalog_key_fkey, DROP CONSTRAINT uq_document_templates_address;
ALTER TABLE variant_attribute_definitions DROP CONSTRAINT variant_attribute_definitions_tenant_key_catalog_key_fkey, DROP CONSTRAINT uq_variant_attributes_address;
ALTER TABLE assets DROP CONSTRAINT assets_tenant_key_catalog_key_fkey, DROP CONSTRAINT uq_assets_address;
ALTER TABLE stencils DROP CONSTRAINT stencils_tenant_key_catalog_key_fkey, DROP CONSTRAINT uq_stencils_address;
ALTER TABLE code_lists DROP CONSTRAINT code_lists_tenant_key_catalog_key_fkey, DROP CONSTRAINT uq_code_lists_address;
ALTER TABLE fonts DROP CONSTRAINT fonts_tenant_key_catalog_key_fkey, DROP CONSTRAINT uq_fonts_address;
ALTER TABLE catalog_resources DROP CONSTRAINT fk_catalog_resources_catalog, DROP CONSTRAINT uq_catalog_resources_address;
ALTER TABLE catalog_upstream_checks DROP CONSTRAINT catalog_upstream_checks_tenant_key_catalog_key_fkey, DROP CONSTRAINT catalog_upstream_checks_pkey;
ALTER TABLE release_entries DROP CONSTRAINT pk_release_entries;
ALTER TABLE environment_catalog_deployments DROP CONSTRAINT pk_environment_catalog_deployments;
DROP INDEX idx_environment_catalog_deployments_release;
DROP INDEX idx_catalog_releases_lookup;
DROP INDEX idx_catalog_releases_version_order;
ALTER TABLE catalog_releases DROP CONSTRAINT catalog_releases_pkey;

-- 5. The catalog is keyed by its id and named by registry, namespace and key --------------------

ALTER TABLE catalogs DROP CONSTRAINT catalogs_pkey;
ALTER TABLE catalogs DROP CONSTRAINT uq_catalogs_catalog_id;
ALTER TABLE catalogs RENAME COLUMN id TO catalog_key;
ALTER TABLE catalogs ADD CONSTRAINT catalogs_pkey PRIMARY KEY (tenant_key, catalog_id);
ALTER TABLE catalogs ADD CONSTRAINT uq_catalogs_name UNIQUE (tenant_key, registry, namespace, catalog_key);
-- Until every caller names a catalog by registry, namespace and key, code still looks catalogs up by
-- key alone; this keeps that lookup unambiguous. Dropped once nothing looks a catalog up by key.
ALTER TABLE catalogs ADD CONSTRAINT uq_catalogs_key_until_named UNIQUE (tenant_key, catalog_key);

COMMENT ON COLUMN catalogs.catalog_id IS
    'Opaque identity of the catalog. Never shown and never a name: a catalog is named by (registry, namespace, catalog_key).';
COMMENT ON COLUMN catalogs.catalog_key IS
    'The catalog''s key within its namespace (contract ADR 0003). Unique only together with registry and namespace.';

-- The identity triggers fire on UPDATE OF their address columns, which pins catalog_key; they are
-- recreated on catalog_id below.
DROP TRIGGER trg_assets_resource_identity ON assets;
DROP TRIGGER trg_code_lists_resource_identity ON code_lists;
DROP TRIGGER trg_fonts_resource_identity ON fonts;
DROP TRIGGER trg_variant_attributes_resource_identity ON variant_attribute_definitions;
DROP TRIGGER trg_themes_resource_identity ON themes;
DROP TRIGGER trg_stencils_resource_identity ON stencils;
DROP TRIGGER trg_document_templates_resource_identity ON document_templates;

-- 6. Live tables reference the catalog by id ---------------------------------------------------

ALTER TABLE catalog_resources DROP COLUMN catalog_key,
    ADD CONSTRAINT fk_catalog_resources_catalog FOREIGN KEY (tenant_key, catalog_id)
        REFERENCES catalogs(tenant_key, catalog_id) ON DELETE CASCADE DEFERRABLE,
    ADD CONSTRAINT uq_catalog_resources_address UNIQUE (tenant_key, resource_type, catalog_id, resource_key);

ALTER TABLE themes DROP COLUMN catalog_key,
    ADD CONSTRAINT fk_themes_catalog FOREIGN KEY (tenant_key, catalog_id) REFERENCES catalogs(tenant_key, catalog_id) ON DELETE CASCADE,
    ADD CONSTRAINT uq_themes_address UNIQUE (tenant_key, catalog_id, id);
ALTER TABLE document_templates DROP COLUMN catalog_key,
    ADD CONSTRAINT fk_document_templates_catalog FOREIGN KEY (tenant_key, catalog_id) REFERENCES catalogs(tenant_key, catalog_id) ON DELETE CASCADE,
    ADD CONSTRAINT uq_document_templates_address UNIQUE (tenant_key, catalog_id, id);
ALTER TABLE variant_attribute_definitions DROP COLUMN catalog_key,
    ADD CONSTRAINT fk_variant_attributes_catalog FOREIGN KEY (tenant_key, catalog_id) REFERENCES catalogs(tenant_key, catalog_id) ON DELETE CASCADE,
    ADD CONSTRAINT uq_variant_attributes_address UNIQUE (tenant_key, catalog_id, id);
ALTER TABLE assets DROP COLUMN catalog_key,
    ADD CONSTRAINT fk_assets_catalog FOREIGN KEY (tenant_key, catalog_id) REFERENCES catalogs(tenant_key, catalog_id) ON DELETE CASCADE,
    ADD CONSTRAINT uq_assets_address UNIQUE (tenant_key, catalog_id, id);
ALTER TABLE stencils DROP COLUMN catalog_key,
    ADD CONSTRAINT fk_stencils_catalog FOREIGN KEY (tenant_key, catalog_id) REFERENCES catalogs(tenant_key, catalog_id) ON DELETE CASCADE,
    ADD CONSTRAINT uq_stencils_address UNIQUE (tenant_key, catalog_id, id);
ALTER TABLE code_lists DROP COLUMN catalog_key,
    ADD CONSTRAINT fk_code_lists_catalog FOREIGN KEY (tenant_key, catalog_id) REFERENCES catalogs(tenant_key, catalog_id) ON DELETE CASCADE,
    ADD CONSTRAINT uq_code_lists_address UNIQUE (tenant_key, catalog_id, slug);
ALTER TABLE fonts DROP COLUMN catalog_key,
    ADD CONSTRAINT fk_fonts_catalog FOREIGN KEY (tenant_key, catalog_id) REFERENCES catalogs(tenant_key, catalog_id) ON DELETE CASCADE,
    ADD CONSTRAINT uq_fonts_address UNIQUE (tenant_key, catalog_id, slug);

ALTER TABLE catalog_releases DROP COLUMN catalog_key,
    ADD CONSTRAINT catalog_releases_pkey PRIMARY KEY (tenant_key, catalog_id, version),
    ADD CONSTRAINT fk_catalog_releases_catalog FOREIGN KEY (tenant_key, catalog_id) REFERENCES catalogs(tenant_key, catalog_id) ON DELETE CASCADE;
CREATE INDEX idx_catalog_releases_lookup ON catalog_releases (tenant_key, catalog_id, released_at DESC);
CREATE INDEX idx_catalog_releases_version_order
    ON catalog_releases (tenant_key, catalog_id, version_major DESC, version_minor DESC, version_patch DESC);

ALTER TABLE release_entries DROP COLUMN catalog_key,
    ADD CONSTRAINT pk_release_entries PRIMARY KEY (tenant_key, catalog_id, version, resource_type, resource_key),
    ADD CONSTRAINT fk_release_entries_release FOREIGN KEY (tenant_key, catalog_id, version)
        REFERENCES catalog_releases(tenant_key, catalog_id, version) ON DELETE CASCADE;

ALTER TABLE catalog_release_publications DROP COLUMN catalog_key,
    ADD CONSTRAINT uq_catalog_release_publications_release UNIQUE (tenant_key, catalog_id, version),
    ADD CONSTRAINT fk_catalog_release_publications_release FOREIGN KEY (tenant_key, catalog_id, version)
        REFERENCES catalog_releases(tenant_key, catalog_id, version) ON DELETE CASCADE;

ALTER TABLE environment_catalog_deployments DROP COLUMN catalog_key,
    ADD CONSTRAINT pk_environment_catalog_deployments PRIMARY KEY (tenant_key, environment_key, catalog_id),
    ADD CONSTRAINT fk_environment_catalog_deployments_release FOREIGN KEY (tenant_key, catalog_id, version)
        REFERENCES catalog_releases(tenant_key, catalog_id, version) DEFERRABLE INITIALLY DEFERRED;
CREATE INDEX idx_environment_catalog_deployments_release ON environment_catalog_deployments (tenant_key, catalog_id, version);

ALTER TABLE catalog_upstream_checks DROP COLUMN catalog_key,
    ADD CONSTRAINT catalog_upstream_checks_pkey PRIMARY KEY (tenant_key, catalog_id),
    ADD CONSTRAINT fk_catalog_upstream_checks_catalog FOREIGN KEY (tenant_key, catalog_id) REFERENCES catalogs(tenant_key, catalog_id) ON DELETE CASCADE;

-- 7. Functions follow --------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION sync_catalog_resource_identity() RETURNS TRIGGER AS $$
DECLARE
    resource_type_value TEXT := TG_ARGV[0];
    key_column TEXT := TG_ARGV[1];
    resource_key_value TEXT;
    existing_resource_id UUID;
BEGIN
    IF TG_OP = 'DELETE' THEN
        DELETE FROM catalog_resources
        WHERE tenant_key = OLD.tenant_key AND resource_id = OLD.resource_id;
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
            -- Nothing supplied: adopt the identity already registered at this address (imports use
            -- INSERT .. ON CONFLICT DO UPDATE, which runs this BEFORE INSERT trigger too).
            NEW.resource_id := COALESCE(existing_resource_id, epistola_uuidv7());
        ELSIF existing_resource_id IS NOT NULL AND existing_resource_id <> NEW.resource_id THEN
            -- A caller that names an identity means it; never silently prefer the incumbent.
            RAISE EXCEPTION 'catalog resource % is already registered at %/% under identity %',
                NEW.resource_id, NEW.catalog_id, resource_key_value, existing_resource_id;
        END IF;

        INSERT INTO catalog_resources (tenant_key, resource_id, resource_type, catalog_id, resource_key)
        VALUES (NEW.tenant_key, NEW.resource_id, resource_type_value, NEW.catalog_id, resource_key_value)
        ON CONFLICT (tenant_key, resource_id) DO UPDATE
        SET catalog_id = EXCLUDED.catalog_id,
            resource_key = EXCLUDED.resource_key
        WHERE catalog_resources.resource_type = EXCLUDED.resource_type;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'catalog resource_id type does not match its domain row';
        END IF;
    ELSE
        IF NEW.resource_id <> OLD.resource_id THEN
            RAISE EXCEPTION 'catalog resource_id is immutable';
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
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION touch_catalog_content_on_delete() RETURNS trigger
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

CREATE OR REPLACE FUNCTION touch_catalog_content_on_variant_delete() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    UPDATE catalogs c
    SET content_updated_at = now()
    FROM document_templates t
    WHERE t.tenant_key = OLD.tenant_key AND t.resource_id = OLD.template_resource_id
      AND c.tenant_key = t.tenant_key AND c.catalog_id = t.catalog_id;
    RETURN OLD;
END;
$$;

-- A generated document names its template's catalog by (registry, namespace, catalog_key).
CREATE OR REPLACE FUNCTION fill_generation_template_identity() RETURNS TRIGGER AS $$
BEGIN
    IF NEW.template_resource_id IS NULL THEN
        SELECT t.resource_id INTO NEW.template_resource_id
        FROM document_templates t
        JOIN catalogs c ON c.tenant_key = t.tenant_key AND c.catalog_id = t.catalog_id
        WHERE t.tenant_key = NEW.tenant_key
          AND c.registry = NEW.registry
          AND c.namespace = NEW.namespace
          AND c.catalog_key = NEW.catalog_key
          AND t.id = NEW.template_key;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'no template % in catalog %/%/% for tenant %',
                NEW.template_key, NEW.registry, NEW.namespace, NEW.catalog_key, NEW.tenant_key;
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- The identity triggers again, on the new address columns.
CREATE TRIGGER trg_assets_resource_identity
    BEFORE INSERT OR UPDATE OF resource_id, catalog_id, id ON assets
    FOR EACH ROW EXECUTE FUNCTION sync_catalog_resource_identity('image', 'id');
CREATE TRIGGER trg_code_lists_resource_identity
    BEFORE INSERT OR UPDATE OF resource_id, catalog_id, slug ON code_lists
    FOR EACH ROW EXECUTE FUNCTION sync_catalog_resource_identity('codeList', 'slug');
CREATE TRIGGER trg_fonts_resource_identity
    BEFORE INSERT OR UPDATE OF resource_id, catalog_id, slug ON fonts
    FOR EACH ROW EXECUTE FUNCTION sync_catalog_resource_identity('font', 'slug');
CREATE TRIGGER trg_variant_attributes_resource_identity
    BEFORE INSERT OR UPDATE OF resource_id, catalog_id, id ON variant_attribute_definitions
    FOR EACH ROW EXECUTE FUNCTION sync_catalog_resource_identity('attribute', 'id');
CREATE TRIGGER trg_themes_resource_identity
    BEFORE INSERT OR UPDATE OF resource_id, catalog_id, id ON themes
    FOR EACH ROW EXECUTE FUNCTION sync_catalog_resource_identity('theme', 'id');
CREATE TRIGGER trg_stencils_resource_identity
    BEFORE INSERT OR UPDATE OF resource_id, catalog_id, id ON stencils
    FOR EACH ROW EXECUTE FUNCTION sync_catalog_resource_identity('stencil', 'id');
CREATE TRIGGER trg_document_templates_resource_identity
    BEFORE INSERT OR UPDATE OF resource_id, catalog_id, id ON document_templates
    FOR EACH ROW EXECUTE FUNCTION sync_catalog_resource_identity('template', 'id');
