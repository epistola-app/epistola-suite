-- Domain type for document template identifiers (slugs)
CREATE DOMAIN TEMPLATE_ID AS VARCHAR(50)
    CHECK (VALUE ~ '^[a-z][a-z0-9]*(-[a-z0-9]+)*$');

-- Document templates table with tenant isolation
-- IDs are client-provided slugs for human-readable, URL-safe identifiers
-- Composite PK (tenant_id, id) allows different tenants to reuse the same template slug
CREATE TABLE document_templates (
    id TEMPLATE_ID NOT NULL,
    tenant_id TENANT_ID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    schema JSONB,
    data_model JSONB,
    data_examples JSONB DEFAULT '[]'::jsonb,
    theme_id THEME_ID,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    last_modified TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    created_by UUID REFERENCES users(id),
    last_modified_by UUID REFERENCES users(id),
    PRIMARY KEY (tenant_id, id),
    FOREIGN KEY (tenant_id, theme_id) REFERENCES themes(tenant_id, id) ON DELETE SET NULL
);

CREATE INDEX idx_document_templates_last_modified ON document_templates(last_modified DESC);
CREATE INDEX idx_document_templates_theme_id ON document_templates(theme_id) WHERE theme_id IS NOT NULL;

COMMENT ON TABLE document_templates IS 'Document template definitions. Each template has a data contract (schema), optional sample data, and one or more variants.';
COMMENT ON COLUMN document_templates.id IS 'URL-safe slug identifier unique within the tenant';
COMMENT ON COLUMN document_templates.tenant_id IS 'Owning tenant';
COMMENT ON COLUMN document_templates.name IS 'Human-readable display name';
COMMENT ON COLUMN document_templates.schema IS 'JSON Schema defining the data contract for document generation. NULL means no validation.';
COMMENT ON COLUMN document_templates.data_model IS 'JSON Schema describing the expected input structure (informational, not enforced)';
COMMENT ON COLUMN document_templates.data_examples IS 'Sample data objects conforming to the schema, used for preview and testing';
COMMENT ON COLUMN document_templates.theme_id IS 'Default theme for this template. Variants can override via TemplateDocument.themeRef. NULL falls back to tenant default theme.';
COMMENT ON COLUMN document_templates.created_at IS 'When the template was created';
COMMENT ON COLUMN document_templates.last_modified IS 'When the template was last updated';
COMMENT ON COLUMN document_templates.created_by IS 'User who created this template';
COMMENT ON COLUMN document_templates.last_modified_by IS 'User who last modified this template';
