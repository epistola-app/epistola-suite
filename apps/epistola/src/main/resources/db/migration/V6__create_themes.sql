-- Theme system for reusable styling across templates
-- Themes define document-level styles and named block style presets that can be shared

CREATE TABLE themes (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    document_styles JSONB NOT NULL DEFAULT '{}'::jsonb,
    page_settings JSONB,
    block_style_presets JSONB,  -- Named style presets for blocks (like CSS classes)
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    last_modified TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_themes_tenant_name UNIQUE (tenant_id, name)
);

CREATE INDEX idx_themes_tenant_id ON themes(tenant_id);
CREATE INDEX idx_themes_last_modified ON themes(last_modified DESC);
