-- Theme system for reusable styling across templates
-- Themes define document-level styles and named block style presets that can be shared

CREATE TABLE themes (
    id VARCHAR(20) PRIMARY KEY CHECK (id ~ '^[a-z][a-z0-9]*(-[a-z0-9]+)*$'),
    tenant_id VARCHAR(63) NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
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

-- Foreign key from tenants.default_theme_id to themes.id
-- Added here because themes table must exist first
ALTER TABLE tenants ADD CONSTRAINT fk_tenants_default_theme
    FOREIGN KEY (default_theme_id) REFERENCES themes(id);
CREATE INDEX idx_tenants_default_theme_id ON tenants(default_theme_id)
    WHERE default_theme_id IS NOT NULL;
