-- Document templates table with tenant isolation
-- IDs are client-provided slugs for human-readable, URL-safe identifiers
-- Composite PK (tenant_id, id) allows different tenants to reuse the same template slug
CREATE TABLE document_templates (
    id VARCHAR(50) NOT NULL CHECK (id ~ '^[a-z][a-z0-9]*(-[a-z0-9]+)*$'),
    tenant_id VARCHAR(63) NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    schema JSONB,
    data_model JSONB,
    data_examples JSONB DEFAULT '[]'::jsonb,
    theme_id VARCHAR(20),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    last_modified TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    created_by UUID REFERENCES users(id),
    last_modified_by UUID REFERENCES users(id),
    PRIMARY KEY (tenant_id, id),
    FOREIGN KEY (tenant_id, theme_id) REFERENCES themes(tenant_id, id) ON DELETE SET NULL
);

CREATE INDEX idx_document_templates_last_modified ON document_templates(last_modified DESC);
CREATE INDEX idx_document_templates_theme_id ON document_templates(theme_id) WHERE theme_id IS NOT NULL;

COMMENT ON COLUMN document_templates.theme_id IS 'Default theme for this template. Variants can override via TemplateModel.themeId.';
COMMENT ON COLUMN document_templates.created_by IS 'User who created this template';
COMMENT ON COLUMN document_templates.last_modified_by IS 'User who last modified this template';
