-- Stable, tenant-local identity for movable catalog resources.
-- Public APIs and catalog exchange continue to use (type, catalog_key, resource_key);
-- resource_id exists only to keep relational identity stable when that address changes.
CREATE TABLE catalog_resources (
    tenant_key TENANT_KEY NOT NULL,
    resource_id UUID NOT NULL,
    resource_type VARCHAR(20) NOT NULL,
    catalog_key CATALOG_KEY NOT NULL,
    resource_key TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (tenant_key, resource_id),
    UNIQUE (tenant_key, resource_id, resource_type),
    UNIQUE (tenant_key, resource_type, catalog_key, resource_key),
    FOREIGN KEY (tenant_key, catalog_key) REFERENCES catalogs(tenant_key, id) ON DELETE CASCADE,
    CHECK (resource_type IN ('asset', 'codeList', 'font', 'attribute', 'theme', 'stencil', 'template'))
);

COMMENT ON TABLE catalog_resources IS
    'Stable tenant-local identities and current public addresses for top-level catalog resources; not resource content or a persisted reference graph.';
COMMENT ON COLUMN catalog_resources.resource_id IS
    'Internal immutable identity. Never serialized into public URLs or catalog exchange data.';
COMMENT ON COLUMN catalog_resources.resource_type IS
    'Catalog wire resource type; forms the typed public address with catalog_key and resource_key.';

ALTER TABLE assets ADD COLUMN resource_id UUID DEFAULT gen_random_uuid();
ALTER TABLE code_lists ADD COLUMN resource_id UUID DEFAULT gen_random_uuid();
ALTER TABLE fonts ADD COLUMN resource_id UUID DEFAULT gen_random_uuid();
ALTER TABLE variant_attribute_definitions ADD COLUMN resource_id UUID DEFAULT gen_random_uuid();
ALTER TABLE themes ADD COLUMN resource_id UUID DEFAULT gen_random_uuid();
ALTER TABLE stencils ADD COLUMN resource_id UUID DEFAULT gen_random_uuid();
ALTER TABLE document_templates ADD COLUMN resource_id UUID DEFAULT gen_random_uuid();

INSERT INTO catalog_resources (tenant_key, resource_id, resource_type, catalog_key, resource_key)
SELECT tenant_key, resource_id, 'asset', catalog_key, id::text FROM assets
UNION ALL
SELECT tenant_key, resource_id, 'codeList', catalog_key, slug::text FROM code_lists
UNION ALL
SELECT tenant_key, resource_id, 'font', catalog_key, slug::text FROM fonts
UNION ALL
SELECT tenant_key, resource_id, 'attribute', catalog_key, id::text FROM variant_attribute_definitions
UNION ALL
SELECT tenant_key, resource_id, 'theme', catalog_key, id::text FROM themes
UNION ALL
SELECT tenant_key, resource_id, 'stencil', catalog_key, id::text FROM stencils
UNION ALL
SELECT tenant_key, resource_id, 'template', catalog_key, id::text FROM document_templates;

ALTER TABLE assets ALTER COLUMN resource_id SET NOT NULL;
ALTER TABLE code_lists ALTER COLUMN resource_id SET NOT NULL;
ALTER TABLE fonts ALTER COLUMN resource_id SET NOT NULL;
ALTER TABLE variant_attribute_definitions ALTER COLUMN resource_id SET NOT NULL;
ALTER TABLE themes ALTER COLUMN resource_id SET NOT NULL;
ALTER TABLE stencils ALTER COLUMN resource_id SET NOT NULL;
ALTER TABLE document_templates ALTER COLUMN resource_id SET NOT NULL;

ALTER TABLE assets
    ADD CONSTRAINT uq_assets_resource_id UNIQUE (tenant_key, resource_id),
    ADD CONSTRAINT fk_assets_resource_identity FOREIGN KEY (tenant_key, resource_id) REFERENCES catalog_resources(tenant_key, resource_id);
ALTER TABLE code_lists
    ADD CONSTRAINT uq_code_lists_resource_id UNIQUE (tenant_key, resource_id),
    ADD CONSTRAINT fk_code_lists_resource_identity FOREIGN KEY (tenant_key, resource_id) REFERENCES catalog_resources(tenant_key, resource_id);
ALTER TABLE fonts
    ADD CONSTRAINT uq_fonts_resource_id UNIQUE (tenant_key, resource_id),
    ADD CONSTRAINT fk_fonts_resource_identity FOREIGN KEY (tenant_key, resource_id) REFERENCES catalog_resources(tenant_key, resource_id);
ALTER TABLE variant_attribute_definitions
    ADD CONSTRAINT uq_variant_attributes_resource_id UNIQUE (tenant_key, resource_id),
    ADD CONSTRAINT fk_variant_attributes_resource_identity FOREIGN KEY (tenant_key, resource_id) REFERENCES catalog_resources(tenant_key, resource_id);
ALTER TABLE themes
    ADD CONSTRAINT uq_themes_resource_id UNIQUE (tenant_key, resource_id),
    ADD CONSTRAINT fk_themes_resource_identity FOREIGN KEY (tenant_key, resource_id) REFERENCES catalog_resources(tenant_key, resource_id);
ALTER TABLE stencils
    ADD CONSTRAINT uq_stencils_resource_id UNIQUE (tenant_key, resource_id),
    ADD CONSTRAINT fk_stencils_resource_identity FOREIGN KEY (tenant_key, resource_id) REFERENCES catalog_resources(tenant_key, resource_id);
ALTER TABLE document_templates
    ADD CONSTRAINT uq_document_templates_resource_id UNIQUE (tenant_key, resource_id),
    ADD CONSTRAINT fk_document_templates_resource_identity FOREIGN KEY (tenant_key, resource_id) REFERENCES catalog_resources(tenant_key, resource_id);

CREATE FUNCTION sync_catalog_resource_identity() RETURNS TRIGGER AS $$
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
        -- Domain imports use INSERT .. ON CONFLICT DO UPDATE. PostgreSQL still runs this BEFORE
        -- INSERT trigger for that path, so retain the identity already registered at the public
        -- address instead of attempting to register the row's fresh column default.
        SELECT resource_id INTO existing_resource_id
        FROM catalog_resources
        WHERE tenant_key = NEW.tenant_key
          AND resource_type = resource_type_value
          AND catalog_key = NEW.catalog_key
          AND resource_key = resource_key_value;
        NEW.resource_id := COALESCE(existing_resource_id, NEW.resource_id, gen_random_uuid());

        INSERT INTO catalog_resources (tenant_key, resource_id, resource_type, catalog_key, resource_key)
        VALUES (NEW.tenant_key, NEW.resource_id, resource_type_value, NEW.catalog_key, resource_key_value)
        ON CONFLICT (tenant_key, resource_id) DO UPDATE
        SET catalog_key = EXCLUDED.catalog_key,
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
        SET catalog_key = NEW.catalog_key,
            resource_key = resource_key_value
        WHERE tenant_key = OLD.tenant_key AND resource_id = OLD.resource_id;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_assets_resource_identity
    BEFORE INSERT OR UPDATE OF resource_id, catalog_key, id ON assets
    FOR EACH ROW EXECUTE FUNCTION sync_catalog_resource_identity('asset', 'id');
CREATE TRIGGER trg_assets_delete_resource_identity
    AFTER DELETE ON assets
    FOR EACH ROW EXECUTE FUNCTION sync_catalog_resource_identity('asset', 'id');

CREATE TRIGGER trg_code_lists_resource_identity
    BEFORE INSERT OR UPDATE OF resource_id, catalog_key, slug ON code_lists
    FOR EACH ROW EXECUTE FUNCTION sync_catalog_resource_identity('codeList', 'slug');
CREATE TRIGGER trg_code_lists_delete_resource_identity
    AFTER DELETE ON code_lists
    FOR EACH ROW EXECUTE FUNCTION sync_catalog_resource_identity('codeList', 'slug');

CREATE TRIGGER trg_fonts_resource_identity
    BEFORE INSERT OR UPDATE OF resource_id, catalog_key, slug ON fonts
    FOR EACH ROW EXECUTE FUNCTION sync_catalog_resource_identity('font', 'slug');
CREATE TRIGGER trg_fonts_delete_resource_identity
    AFTER DELETE ON fonts
    FOR EACH ROW EXECUTE FUNCTION sync_catalog_resource_identity('font', 'slug');

CREATE TRIGGER trg_variant_attributes_resource_identity
    BEFORE INSERT OR UPDATE OF resource_id, catalog_key, id ON variant_attribute_definitions
    FOR EACH ROW EXECUTE FUNCTION sync_catalog_resource_identity('attribute', 'id');
CREATE TRIGGER trg_variant_attributes_delete_resource_identity
    AFTER DELETE ON variant_attribute_definitions
    FOR EACH ROW EXECUTE FUNCTION sync_catalog_resource_identity('attribute', 'id');

CREATE TRIGGER trg_themes_resource_identity
    BEFORE INSERT OR UPDATE OF resource_id, catalog_key, id ON themes
    FOR EACH ROW EXECUTE FUNCTION sync_catalog_resource_identity('theme', 'id');
CREATE TRIGGER trg_themes_delete_resource_identity
    AFTER DELETE ON themes
    FOR EACH ROW EXECUTE FUNCTION sync_catalog_resource_identity('theme', 'id');

CREATE TRIGGER trg_stencils_resource_identity
    BEFORE INSERT OR UPDATE OF resource_id, catalog_key, id ON stencils
    FOR EACH ROW EXECUTE FUNCTION sync_catalog_resource_identity('stencil', 'id');
CREATE TRIGGER trg_stencils_delete_resource_identity
    AFTER DELETE ON stencils
    FOR EACH ROW EXECUTE FUNCTION sync_catalog_resource_identity('stencil', 'id');

CREATE TRIGGER trg_document_templates_resource_identity
    BEFORE INSERT OR UPDATE OF resource_id, catalog_key, id ON document_templates
    FOR EACH ROW EXECUTE FUNCTION sync_catalog_resource_identity('template', 'id');
CREATE TRIGGER trg_document_templates_delete_resource_identity
    AFTER DELETE ON document_templates
    FOR EACH ROW EXECUTE FUNCTION sync_catalog_resource_identity('template', 'id');
