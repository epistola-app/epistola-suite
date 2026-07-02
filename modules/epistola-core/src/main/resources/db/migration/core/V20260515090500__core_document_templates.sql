-- Domain type for document template identifiers (slugs)
CREATE DOMAIN TEMPLATE_KEY AS VARCHAR(50)
    CHECK (VALUE ~ '^[a-z][a-z0-9]*(-[a-z0-9]+)*$');

-- Document templates table with tenant and catalog isolation
-- IDs are client-provided slugs for human-readable, URL-safe identifiers
-- Composite PK (tenant_key, catalog_key, id) allows different catalogs to have the same template slug
--
-- The data contract (schema, data_model, data_examples) is versioned separately
-- in contract_versions; pdfa_enabled toggles PDF/A output.
CREATE TABLE document_templates (
    id TEMPLATE_KEY NOT NULL,
    tenant_key TENANT_KEY NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    catalog_key CATALOG_KEY NOT NULL DEFAULT 'default',
    name VARCHAR(255) NOT NULL,
    theme_key THEME_KEY,
    theme_catalog_key CATALOG_KEY,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by UUID REFERENCES users(id) ON DELETE SET NULL,
    updated_by UUID REFERENCES users(id) ON DELETE SET NULL,
    pdfa_enabled BOOLEAN NOT NULL DEFAULT true,
    PRIMARY KEY (tenant_key, catalog_key, id),
    FOREIGN KEY (tenant_key, catalog_key) REFERENCES catalogs(tenant_key, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_key, theme_catalog_key, theme_key) REFERENCES themes(tenant_key, catalog_key, id) ON DELETE SET NULL (theme_catalog_key, theme_key)
);

CREATE INDEX idx_document_templates_updated_at ON document_templates(updated_at DESC);
CREATE INDEX idx_document_templates_theme_key ON document_templates(theme_key) WHERE theme_key IS NOT NULL;

COMMENT ON TABLE document_templates IS 'Document template definitions. Each template has a data contract (schema), optional sample data, and one or more variants.';
COMMENT ON COLUMN document_templates.id IS 'URL-safe slug identifier unique within the catalog';
COMMENT ON COLUMN document_templates.tenant_key IS 'Owning tenant';
COMMENT ON COLUMN document_templates.catalog_key IS 'Catalog this template belongs to';
COMMENT ON COLUMN document_templates.name IS 'Human-readable display name';
COMMENT ON COLUMN document_templates.theme_key IS 'Default theme for this template. Variants can override via TemplateDocument.themeRef. NULL falls back to tenant default theme.';
COMMENT ON COLUMN document_templates.created_at IS 'When the template was created';
COMMENT ON COLUMN document_templates.updated_at IS 'When the template was last updated';
COMMENT ON COLUMN document_templates.created_by IS 'User who created this template (NULL if the user was deleted)';
COMMENT ON COLUMN document_templates.pdfa_enabled IS 'Whether PDF/A-2b archival output is enabled for this template. Defaults to true for new templates.';
COMMENT ON COLUMN document_templates.updated_by IS 'User who last modified this template (NULL if the user was deleted)';

-- updated_at is DB-enforced by the shared set_updated_at() trigger function.
CREATE TRIGGER trg_document_templates_updated_at
    BEFORE UPDATE ON document_templates
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
